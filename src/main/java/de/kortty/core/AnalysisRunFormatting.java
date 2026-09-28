package de.kortty.core;

import de.kortty.ui.I18n;

import java.text.NumberFormat;
import java.util.Locale;

/**
 * Text formatting shared by every surface that reports what a Full-code-analysis apply run cost:
 * the progress window while it runs, its copyable summary afterwards, and the exported reports.
 *
 * <p>Token usage is rendered exactly as the provider reported it and never guessed — a run against
 * a backend that reports nothing says so rather than showing an estimate that looks like a
 * fact.</p>
 */
public final class AnalysisRunFormatting {

    private AnalysisRunFormatting() {
    }

    /** {@code mm:ss}, growing to {@code h:mm:ss} only once the run actually passed an hour. */
    public static String formatDuration(long seconds) {
        long safe = Math.max(0L, seconds);
        long hours = safe / 3_600L;
        long minutes = (safe % 3_600L) / 60L;
        long remaining = safe % 60L;
        return hours > 0
            ? String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, remaining)
            : String.format(Locale.ROOT, "%02d:%02d", minutes, remaining);
    }

    /** {@link #tokenSummary(AiTokenUsage, Locale)} grouped in the user's default formatting locale. */
    public static String tokenSummary(AiTokenUsage usage) {
        return tokenSummary(usage, Locale.getDefault(Locale.Category.FORMAT));
    }

    /**
     * "Tokens: 1,204 prompt / 388 completion / 1,592 total", or the honest "not reported". The
     * numbers are grouped for {@code locale}, so a report exported for another language does not
     * carry the machine's separators.
     */
    public static String tokenSummary(AiTokenUsage usage, Locale locale) {
        if (usage == null) {
            return I18n.get("snippets.ai.analysis.progress.tokens",
                I18n.get("snippets.ai.analysis.progress.tokensUnavailable"));
        }
        NumberFormat format = NumberFormat.getIntegerInstance(
            locale != null ? locale : Locale.getDefault(Locale.Category.FORMAT));
        return I18n.get("snippets.ai.analysis.progress.summary.tokens",
            format.format(usage.promptTokens()),
            format.format(usage.completionTokens()),
            format.format(usage.totalTokens()));
    }
}
