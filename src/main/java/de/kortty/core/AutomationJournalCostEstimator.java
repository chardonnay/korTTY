package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.AutomationJournalAiMode;
import de.kortty.model.AutomationJournalConfig;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;

import java.util.List;

/**
 * Estimates what the AI summaries of an automation source's session journals will cost, for the
 * warning shown when they are switched on. Uses the source's own history when it has any —
 * the average tokens of its kept runs — and otherwise a deliberately high upper bound derived
 * from the journal's line and token limits.
 */
public final class AutomationJournalCostEstimator {

    /** Prompt overhead of one summary call besides the terminal lines (system prompt, JSON). */
    static final int PROMPT_OVERHEAD_TOKENS = 1_500;
    /** Rough tokens per captured terminal line, deliberately generous. */
    static final int TOKENS_PER_LINE = 25;
    /** Completion tokens of one summary answer. */
    static final int ANSWER_TOKENS = 400;
    /** Generous tokens of one screenshot description (image input plus a short answer). */
    static final int VISION_TOKENS_PER_SCREENSHOT = 1_600;

    /**
     * @param tokensPerRun     estimated journal AI tokens of one run (all targets)
     * @param fromHistory      true when {@code tokensPerRun} is the average of earlier runs
     * @param runsPerDay       scheduled runs per day, null when the source has no schedule
     * @param tokensPerDay     {@code tokensPerRun × runsPerDay}, null without a schedule
     * @param tokensPerMonth   30 days of {@code tokensPerDay}, null without a schedule
     * @param costPerRun       money per run, NaN when the profile has no price
     * @param costPerMonth     money per 30 days, NaN without a price or a schedule
     * @param currency         currency of the two costs
     * @param local            the profile runs locally (no token costs)
     * @param remainingQuota   tokens left in the profile's current quota period, Long.MAX_VALUE = unlimited
     */
    public record Estimate(long tokensPerRun, boolean fromHistory, Double runsPerDay, Long tokensPerDay,
                           Long tokensPerMonth, double costPerRun, double costPerMonth, String currency,
                           boolean local, long remainingQuota) {
    }

    private AutomationJournalCostEstimator() {
    }

    public static Estimate estimate(
            List<SessionJournalMeta> journals,
            SessionJournalSourceKind kind,
            String sourceId,
            AutomationJournalConfig config,
            int targets,
            Double runsPerDay,
            AiProfile profile,
            GlobalSettings settings) {
        return estimate(journals, kind, sourceId, config, targets, runsPerDay, profile, settings, 0);
    }

    /**
     * @param screenshotsPerTarget screenshots a run may take per server (virtual terminal), each of
     *                             which the AI describes; only used for the upper bound
     */
    public static Estimate estimate(
            List<SessionJournalMeta> journals,
            SessionJournalSourceKind kind,
            String sourceId,
            AutomationJournalConfig config,
            int targets,
            Double runsPerDay,
            AiProfile profile,
            GlobalSettings settings,
            int screenshotsPerTarget) {
        long perRun = 0;
        boolean fromHistory = false;
        if (config != null && config.getAiMode() != AutomationJournalAiMode.OFF) {
            long average = averageTokensPerRun(journals, kind, sourceId);
            if (average > 0) {
                perRun = average;
                fromHistory = true;
            } else {
                perRun = (upperBoundPerTarget(settings)
                    + (long) Math.max(0, screenshotsPerTarget) * VISION_TOKENS_PER_SCREENSHOT) * Math.max(1, targets);
            }
        }
        Long perDay = runsPerDay != null ? Math.round(perRun * runsPerDay) : null;
        Long perMonth = perDay != null ? perDay * 30 : null;
        double costPerRun = Double.NaN;
        double costPerMonth = Double.NaN;
        if (AiCostCalculator.hasPrice(profile)) {
            // split like a typical summary call: mostly prompt, a small answer
            long completion = Math.min(perRun, (long) ANSWER_TOKENS * Math.max(1, targets) * 2L);
            costPerRun = AiCostCalculator.cost(profile, perRun - completion, completion);
            if (runsPerDay != null) {
                costPerMonth = costPerRun * runsPerDay * 30;
            }
        }
        long remaining = profile != null ? AiTokenUsageManager.remainingAfter(profile, 0) : Long.MAX_VALUE;
        return new Estimate(perRun, fromHistory, runsPerDay, perDay, perMonth, costPerRun, costPerMonth,
            AiCostCalculator.currency(profile), AiCostCalculator.isLocal(profile), remaining);
    }

    /** Average journal AI tokens per kept run of the source; 0 without history. */
    static long averageTokensPerRun(List<SessionJournalMeta> journals, SessionJournalSourceKind kind, String sourceId) {
        if (journals == null || journals.isEmpty()) {
            return 0;
        }
        List<AutomationJournalRetention.RunGroup> groups = AutomationJournalRetention.groupsOf(journals, kind, sourceId);
        long total = 0;
        int runs = 0;
        for (AutomationJournalRetention.RunGroup group : groups) {
            long tokens = group.journals().stream().mapToLong(SessionJournalMeta::getAiTotalTokens).sum();
            if (tokens > 0) {
                total += tokens;
                runs++;
            }
        }
        return runs > 0 ? total / runs : 0;
    }

    /**
     * Upper bound of one target's journal: one window summary filled to the configured limit plus
     * the closing session summary.
     */
    static long upperBoundPerTarget(GlobalSettings settings) {
        int maxLines = settings != null ? settings.getSessionJournalAiMaxLines() : 100;
        int tokenBudget = settings != null ? settings.getSessionJournalAiTokenBudget() : 130_000;
        long window = maxLines > 0
            ? Math.min((long) tokenBudget * 3 / 4, (long) maxLines * TOKENS_PER_LINE + PROMPT_OVERHEAD_TOKENS)
            : (long) tokenBudget * 3 / 4;
        long sessionSummary = PROMPT_OVERHEAD_TOKENS + ANSWER_TOKENS;
        return window + ANSWER_TOKENS + sessionSummary;
    }
}
