package de.kortty.ui;

import de.kortty.stats.SkillStatsReport;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** The text of the skill statistics: periods, sizes, growth and dates, without any control. */
final class AiSkillStatisticsFormat {

    /** Shown for a value that is not known, such as a growth without a snapshot to grow from. */
    static final String NONE = "—";

    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd.MM.yy");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MM.yyyy");
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    private AiSkillStatisticsFormat() {
    }

    /** A period key as an axis label: {@code dd.MM.yy}, {@code MM.yyyy} or the year, unique within the range. */
    static String periodLabel(String key) {
        if (key == null) {
            return "";
        }
        try {
            return switch (key.length()) {
                case 10 -> DAY_LABEL.format(LocalDate.parse(key));
                case 7 -> MONTH_LABEL.format(java.time.YearMonth.parse(key).atDay(1));
                default -> key;
            };
        } catch (java.time.format.DateTimeParseException e) {
            return key;
        }
    }

    static String date(LocalDate date) {
        return date != null ? DATE.format(date) : NONE;
    }

    static String number(long value) {
        return NumberFormat.getIntegerInstance().format(value);
    }

    /** A token count, or {@link #NONE} for an unknown one: a null, or the negative infinity used for sorting. */
    static String tokens(Double value) {
        return value == null || value.isInfinite() || value.isNaN() ? NONE : number(Math.round(value));
    }

    /** A signed change per month, rounded to whole tokens; {@link #NONE} when unknown. */
    static String perMonth(Double value) {
        if (value == null || value.isInfinite() || value.isNaN()) {
            return NONE;
        }
        long rounded = Math.round(value);
        return (rounded > 0 ? "+" : "") + number(rounded);
    }

    /** A signed percentage with one decimal place, using a dot; {@link #NONE} when unknown. */
    static String percent(Double value) {
        if (value == null || value.isInfinite() || value.isNaN()) {
            return NONE;
        }
        return (value > 0 ? "+" : "") + String.format(java.util.Locale.ROOT, "%.1f", value) + " %";
    }

    /** The sort key of an optional number: unknown values sort below every real one. */
    static Double sortKey(Double value) {
        return value == null ? Double.NEGATIVE_INFINITY : value;
    }

    static String kindKey(SkillStatsReport.HintKind kind) {
        return switch (kind) {
            case UNUSED_90_DAYS -> "settings.aiSkills.stats.hint.unused";
            case GREW_TWICE_IN_30_DAYS -> "settings.aiSkills.stats.hint.grew";
            case OVER_1000_TOKENS -> "settings.aiSkills.stats.hint.large";
        };
    }
}
