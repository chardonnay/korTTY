package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;

import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Turns token counts into money using the optional per-million-token prices of an
 * {@link AiProfile}. Profiles without a price cost nothing as far as korTTY knows; a local
 * profile (embedded model or an endpoint on the loopback interface) is labelled as such so the
 * UI can say "local · 0 €" instead of leaving the cost blank.
 */
public final class AiCostCalculator {

    public static final String DEFAULT_CURRENCY = "EUR";

    private static final double ONE_MILLION = 1_000_000.0;

    private AiCostCalculator() {
    }

    /** True when at least one of the two prices is configured and positive. */
    public static boolean hasPrice(AiProfile profile) {
        return profile != null
            && (positive(profile.getPricePerMillionPromptTokens())
                || positive(profile.getPricePerMillionCompletionTokens()));
    }

    /** True for profiles that run on this machine and therefore incur no per-token charges. */
    public static boolean isLocal(AiProfile profile) {
        if (profile == null) {
            return false;
        }
        AiConnectionMode mode = profile.getConnectionMode();
        if (mode != null && mode.isEmbedded()) {
            return true;
        }
        return mode == AiConnectionMode.HTTP_API && LocalLmModelResolver.isLoopbackHttpUrl(profile.getApiUrl());
    }

    /** The profile's price currency, EUR when none is set. */
    public static String currency(AiProfile profile) {
        String currency = profile != null ? profile.getPriceCurrency() : null;
        return currency == null || currency.isBlank() ? DEFAULT_CURRENCY : currency.trim().toUpperCase(Locale.ROOT);
    }

    /** Cost of one usage record; 0 when the profile has no price. */
    public static double cost(AiProfile profile, AiTokenUsage usage) {
        if (usage == null) {
            return 0.0;
        }
        return cost(profile, usage.promptTokens(), usage.completionTokens());
    }

    /** Cost of the given prompt/completion token counts; 0 when the profile has no price. */
    public static double cost(AiProfile profile, long promptTokens, long completionTokens) {
        if (profile == null) {
            return 0.0;
        }
        double promptPrice = nonNegative(profile.getPricePerMillionPromptTokens());
        double completionPrice = nonNegative(profile.getPricePerMillionCompletionTokens());
        return (Math.max(0L, promptTokens) * promptPrice + Math.max(0L, completionTokens) * completionPrice)
            / ONE_MILLION;
    }

    /**
     * Formats an amount for display: two decimals, and {@code < 0.01} for a positive amount that
     * would otherwise round to zero, so a cheap call never looks free.
     */
    public static String format(double amount, String currencyCode, Locale locale) {
        Locale effectiveLocale = locale != null ? locale : Locale.getDefault();
        NumberFormat format = NumberFormat.getCurrencyInstance(effectiveLocale);
        try {
            format.setCurrency(Currency.getInstance(
                currencyCode == null || currencyCode.isBlank() ? DEFAULT_CURRENCY : currencyCode.trim()));
        } catch (IllegalArgumentException ignored) {
            // an unknown code keeps the locale's currency symbol
        }
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        if (amount > 0.0 && amount < 0.01) {
            return "< " + format.format(0.01);
        }
        return format.format(Math.max(0.0, amount));
    }

    /**
     * Parses a price the user typed: blank → null (no price), comma or dot as decimal separator,
     * negative or unparsable → null.
     */
    public static Double parsePrice(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String normalized = text.trim().replace(" ", "").replace("\u00a0", "");
        if (normalized.indexOf(',') >= 0 && normalized.indexOf('.') >= 0) {
            // "1.234,56" or "1,234.56": the last separator is the decimal one
            if (normalized.lastIndexOf(',') > normalized.lastIndexOf('.')) {
                normalized = normalized.replace(".", "").replace(',', '.');
            } else {
                normalized = normalized.replace(",", "");
            }
        } else {
            normalized = normalized.replace(',', '.');
        }
        try {
            double value = Double.parseDouble(normalized);
            return value >= 0.0 && !Double.isNaN(value) && !Double.isInfinite(value) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Formats a stored price for an input field; null → empty. */
    public static String formatPrice(Double price, Locale locale) {
        if (price == null) {
            return "";
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale != null ? locale : Locale.getDefault());
        format.setGroupingUsed(false);
        format.setMinimumFractionDigits(0);
        format.setMaximumFractionDigits(6);
        return format.format(price);
    }

    private static boolean positive(Double value) {
        return value != null && value > 0.0 && !value.isNaN() && !value.isInfinite();
    }

    private static double nonNegative(Double value) {
        return positive(value) ? value : 0.0;
    }
}
