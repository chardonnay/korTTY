package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.stats.SkillStatsReport;

import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.format.FormatStyle;
import java.util.Locale;

/**
 * The text of the skill statistics: periods, sizes, growth and dates, without any control. Numbers, percentages and dates
 * follow the application language, not the machine's locale, so the table, the hints and the axis labels agree.
 */
final class AiSkillStatisticsFormat {

    /** Shown for a value that is not known, such as a growth without a snapshot to grow from. */
    static final String NONE = "—";

    private AiSkillStatisticsFormat() {
    }

    /** The application language's locale, which every format of this class uses unless a locale is given. */
    static Locale locale() {
        try {
            return LanguageManager.getInstance().getCurrentLocale();
        } catch (RuntimeException unavailable) {
            return Locale.getDefault();
        }
    }

    /** A period key as an axis label: a short date, a month and year, or the year, unique within the range. */
    static String periodLabel(String key) {
        return periodLabel(key, locale());
    }

    static String periodLabel(String key, Locale locale) {
        if (key == null) {
            return "";
        }
        try {
            return switch (key.length()) {
                case 10 -> DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(locale).format(LocalDate.parse(key));
                case 7 -> DateTimeFormatter.ofPattern("LLL yyyy", locale).format(YearMonth.parse(key).atDay(1));
                default -> key;
            };
        } catch (DateTimeParseException e) {
            return key;
        }
    }

    static String date(LocalDate date) {
        return date(date, locale());
    }

    static String date(LocalDate date, Locale locale) {
        return date != null ? DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(date) : NONE;
    }

    static String number(long value) {
        return number(value, locale());
    }

    static String number(long value, Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(value);
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

    /** A signed percentage with one decimal place, in the decimal separator of the application language. */
    static String percent(Double value) {
        return percent(value, locale());
    }

    static String percent(Double value, Locale locale) {
        if (value == null || value.isInfinite() || value.isNaN()) {
            return NONE;
        }
        NumberFormat format = NumberFormat.getNumberInstance(locale);
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return (value > 0 ? "+" : "") + format.format(value) + " %";
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
