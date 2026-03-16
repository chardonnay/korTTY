package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenLimitUnit;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Computes and updates AI token quota usage.
 */
public final class AiTokenUsageManager {

    private static final int DEFAULT_RESET_DAYS = 30;
    private static final int DEFAULT_YELLOW_PERCENT = 75;
    private static final int DEFAULT_RED_PERCENT = 90;

    private AiTokenUsageManager() {
    }

    /**
     * Refreshes derived token-usage fields on the provided {@link AiProfile}.
     * This method synchronizes on {@code profile} while reading or updating
     * {@code usedPromptTokens}, {@code usedCompletionTokens}, {@code usedTotalTokens},
     * {@code tokenResetAnchorDate}, and {@code tokenUsageCycleStartDate}; callers that
     * prefer isolated reads can use a defensive copy pattern like {@code TerminalView.getConfiguredAiProfiles()}.
     */
    public static AiTokenUsageSnapshot refreshUsage(AiProfile profile) {
        return refreshUsage(profile, LocalDate.now());
    }

    static AiTokenUsageSnapshot refreshUsage(AiProfile profile, LocalDate today) {
        if (profile == null) {
            return new AiTokenUsageSnapshot(0, 0, 0, 0, Long.MAX_VALUE, today, today.plusDays(DEFAULT_RESET_DAYS), AiTokenWarningLevel.NONE, true);
        }

        LocalDate safeToday = today != null ? today : LocalDate.now();
        synchronized (profile) {
            int resetDays = getResetDays(profile);
            LocalDate anchor = parseDate(profile.getTokenResetAnchorDate(), safeToday);
            LocalDate cycleStart = calculateCycleStart(anchor, resetDays, safeToday);
            LocalDate storedCycleStart = parseDate(profile.getTokenUsageCycleStartDate(), cycleStart);

            if (!storedCycleStart.equals(cycleStart)) {
                profile.setUsedPromptTokens(0L);
                profile.setUsedCompletionTokens(0L);
                profile.setUsedTotalTokens(0L);
                profile.setTokenUsageCycleStartDate(cycleStart.toString());
            } else if (profile.getTokenUsageCycleStartDate() == null || profile.getTokenUsageCycleStartDate().isBlank()) {
                profile.setTokenUsageCycleStartDate(cycleStart.toString());
            }
            if (profile.getTokenResetAnchorDate() == null || profile.getTokenResetAnchorDate().isBlank()) {
                profile.setTokenResetAnchorDate(anchor.toString());
            }

            long usedPrompt = positive(profile.getUsedPromptTokens());
            long usedCompletion = positive(profile.getUsedCompletionTokens());
            long usedTotal = Math.max(positive(profile.getUsedTotalTokens()), usedPrompt + usedCompletion);
            profile.setUsedTotalTokens(usedTotal);

            long maxTokens = resolveMaxTokens(profile);
            boolean unlimited = maxTokens <= 0;
            long remaining = unlimited ? Long.MAX_VALUE : Math.max(0L, maxTokens - usedTotal);
            LocalDate nextReset = cycleStart.plusDays(resetDays);
            AiTokenWarningLevel warningLevel = determineWarningLevel(profile, usedTotal, maxTokens);

            return new AiTokenUsageSnapshot(
                usedPrompt,
                usedCompletion,
                usedTotal,
                maxTokens,
                remaining,
                cycleStart,
                nextReset,
                warningLevel,
                unlimited);
        }
    }

    public static AiTokenUsageSnapshot recordUsage(AiProfile profile, AiTokenUsage usage) {
        if (profile == null || usage == null) {
            return refreshUsage(profile);
        }
        LocalDate today = LocalDate.now();
        synchronized (profile) {
            AiTokenUsageSnapshot snapshot = refreshUsage(profile, today);
            long prompt = snapshot.usedPromptTokens() + Math.max(0L, usage.promptTokens());
            long completion = snapshot.usedCompletionTokens() + Math.max(0L, usage.completionTokens());
            long total = snapshot.usedTotalTokens() + Math.max(0L, usage.totalTokens());
            profile.setUsedPromptTokens(prompt);
            profile.setUsedCompletionTokens(completion);
            profile.setUsedTotalTokens(total);
            return refreshUsage(profile, today);
        }
    }

    public static long resolveMaxTokens(AiProfile profile) {
        if (profile == null || profile.getTokenLimitAmount() == null || profile.getTokenLimitAmount() <= 0) {
            return 0L;
        }
        AiTokenLimitUnit unit = profile.getTokenLimitUnit() != null ? profile.getTokenLimitUnit() : AiTokenLimitUnit.THOUSANDS;
        return Math.max(0L, profile.getTokenLimitAmount()) * unit.getMultiplier();
    }

    public static long remainingAfter(AiProfile profile, long additionalTokens) {
        AiTokenUsageSnapshot snapshot = refreshUsage(profile);
        if (snapshot.unlimited()) {
            return Long.MAX_VALUE;
        }
        return Math.max(0L, snapshot.maxTokens() - snapshot.usedTotalTokens() - Math.max(0L, additionalTokens));
    }

    public static AiTokenWarningLevel determineProjectedWarningLevel(AiProfile profile, long additionalTokens) {
        AiTokenUsageSnapshot snapshot = refreshUsage(profile);
        return determineWarningLevel(profile, snapshot.usedTotalTokens() + Math.max(0L, additionalTokens), snapshot.maxTokens());
    }

    public static String formatCompact(long tokens) {
        if (tokens == Long.MAX_VALUE) {
            return "unlimited";
        }
        if (tokens >= 1_000_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fB", tokens / 1_000_000_000.0);
        }
        if (tokens >= 1_000_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fM", tokens / 1_000_000.0);
        }
        if (tokens >= 1_000L) {
            return String.format(java.util.Locale.ROOT, "%.1fk", tokens / 1_000.0);
        }
        return Long.toString(tokens);
    }

    private static AiTokenWarningLevel determineWarningLevel(AiProfile profile, long usedTotal, long maxTokens) {
        if (profile == null || maxTokens <= 0) {
            return AiTokenWarningLevel.NONE;
        }
        double usagePercent = (usedTotal * 100.0) / maxTokens;
        int yellow = normalizePercent(profile.getTokenWarningYellowPercent(), DEFAULT_YELLOW_PERCENT);
        int red = normalizePercent(profile.getTokenWarningRedPercent(), DEFAULT_RED_PERCENT);
        if (red < yellow) {
            red = yellow;
        }
        if (usagePercent >= red) {
            return AiTokenWarningLevel.RED;
        }
        if (usagePercent >= yellow) {
            return AiTokenWarningLevel.YELLOW;
        }
        return AiTokenWarningLevel.NONE;
    }

    private static int getResetDays(AiProfile profile) {
        int value = profile != null && profile.getTokenResetPeriodDays() != null ? profile.getTokenResetPeriodDays() : DEFAULT_RESET_DAYS;
        return Math.max(1, value);
    }

    private static int normalizePercent(Integer value, int defaultValue) {
        int safeValue = value != null ? value : defaultValue;
        return Math.max(0, Math.min(100, safeValue));
    }

    private static long positive(Long value) {
        return value != null ? Math.max(0L, value) : 0L;
    }

    private static LocalDate parseDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static LocalDate calculateCycleStart(LocalDate anchor, int resetDays, LocalDate today) {
        if (anchor == null) {
            return today;
        }
        if (!today.isAfter(anchor)) {
            return anchor;
        }
        long daysBetween = ChronoUnit.DAYS.between(anchor, today);
        long cycles = daysBetween / resetDays;
        return anchor.plusDays(cycles * resetDays);
    }
}
