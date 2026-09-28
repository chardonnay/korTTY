package de.kortty.core;

import java.util.Locale;

/**
 * The severity of a Full-code-analysis finding, normalised from the free-text label the AI
 * returns. Declaration order is severity order (most severe first), which is the order the
 * analysis window and the exports sort findings in.
 *
 * <p>{@link #fromLabel} accepts exactly the spellings the export and the analysis window have
 * always accepted ({@code critical}/{@code crit}, {@code high}, {@code medium}/{@code moderate}/
 * {@code med}, {@code low}); anything else is {@link #INFO}, so an unexpected label never hides
 * a finding — it merely sorts last.</p>
 */
public enum AnalysisSeverity {
    CRITICAL("#991B1B"),
    HIGH("#C0392B"),
    MEDIUM("#E67E22"),
    LOW("#B7950B"),
    INFO("#7F8C8D");

    private static final String I18N_PREFIX = "snippets.ai.analysis.report.severity.";

    private final String printColorHex;

    AnalysisSeverity(String printColorHex) {
        this.printColorHex = printColorHex;
    }

    /** Maps the AI's severity text; blank, null and unknown labels are {@link #INFO}. */
    public static AnalysisSeverity fromLabel(String label) {
        String value = label != null ? label.trim().toLowerCase(Locale.ROOT) : "";
        return switch (value) {
            case "critical", "crit" -> CRITICAL;
            case "high" -> HIGH;
            case "medium", "moderate", "med" -> MEDIUM;
            case "low" -> LOW;
            default -> INFO;
        };
    }

    /** Sort key: 0 for {@link #CRITICAL} up to 4 for {@link #INFO}. */
    public int rank() {
        return ordinal();
    }

    /** The message key of the localized label, e.g. {@code snippets.ai.analysis.report.severity.high}. */
    public String i18nKey() {
        return I18N_PREFIX + name().toLowerCase(Locale.ROOT);
    }

    /**
     * The pill colour on a white page. Critical and high are deliberately distinct — the older
     * export painted both the same red.
     */
    public String printColorHex() {
        return printColorHex;
    }

    /** The CSS class the HTML export and the analysis window use, e.g. {@code sev-high}. */
    public String cssClass() {
        return "sev-" + name().toLowerCase(Locale.ROOT);
    }
}
