package de.kortty.ui;

import java.time.DateTimeException;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/**
 * The texts the command-timestamp gutter draws and shows on hover, in the UI language.
 *
 * <p>The short day-month above each mark comes from a translator-controlled pattern
 * ({@value #SHORT_DATE_PATTERN_KEY}) rather than the JDK's locale data, because CLDR changes its
 * short forms between JDK releases and the gutter is only a few characters wide. A pattern that is
 * missing or unusable falls back to {@link FormatStyle#SHORT}. The hover popup uses the locale's
 * {@link FormatStyle#FULL} date, which names the weekday and month in that language.
 *
 * <p>Toolkit-free so the formats can be checked against every bundle without a JavaFX runtime.
 */
public record TimestampGutterFormats(
        Locale locale,
        DateTimeFormatter shortDate,
        DateTimeFormatter popupDate,
        String elapsedTemplate,
        String secondsTemplate,
        String minutesTemplate,
        String hoursTemplate) {

    public static final String SHORT_DATE_PATTERN_KEY = "terminal.timestamps.dateShortPattern";
    public static final String ELAPSED_KEY = "terminal.timestamps.elapsed";
    public static final String SECONDS_KEY = "terminal.timestamps.duration.seconds";
    public static final String MINUTES_KEY = "terminal.timestamps.duration.minutes";
    public static final String HOURS_KEY = "terminal.timestamps.duration.hours";

    private static final String DEFAULT_ELAPSED = "Elapsed: {0}";
    private static final String DEFAULT_SECONDS = "{0} sec";
    private static final String DEFAULT_MINUTES = "{0} min {1} sec";
    private static final String DEFAULT_HOURS = "{0} h {1} min {2} sec";
    private static final LocalDateTime SAMPLE = LocalDateTime.of(2026, 10, 2, 17, 20, 3);

    public TimestampGutterFormats {
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(shortDate, "shortDate");
        Objects.requireNonNull(popupDate, "popupDate");
        Objects.requireNonNull(elapsedTemplate, "elapsedTemplate");
        Objects.requireNonNull(secondsTemplate, "secondsTemplate");
        Objects.requireNonNull(minutesTemplate, "minutesTemplate");
        Objects.requireNonNull(hoursTemplate, "hoursTemplate");
    }

    /**
     * Builds the formats for {@code locale}, reading the translated texts through {@code lookup}
     * (for example {@code I18n::get}). A lookup that returns null, a blank value or the key itself
     * counts as missing and falls back to the built-in default.
     */
    public static TimestampGutterFormats forLocale(Locale locale, Function<String, String> lookup) {
        Locale effective = locale != null ? locale : Locale.getDefault();
        Function<String, String> safeLookup = lookup != null ? lookup : key -> null;
        return new TimestampGutterFormats(
                effective,
                shortDateFormatter(effective, translated(safeLookup, SHORT_DATE_PATTERN_KEY, null)),
                popupDateFormatter(effective),
                translated(safeLookup, ELAPSED_KEY, DEFAULT_ELAPSED),
                translated(safeLookup, SECONDS_KEY, DEFAULT_SECONDS),
                translated(safeLookup, MINUTES_KEY, DEFAULT_MINUTES),
                translated(safeLookup, HOURS_KEY, DEFAULT_HOURS));
    }

    /**
     * The day-month formatter drawn above each mark: {@code pattern} if it parses and can format
     * a date-time, otherwise the locale's {@link FormatStyle#SHORT} date.
     */
    public static DateTimeFormatter shortDateFormatter(Locale locale, String pattern) {
        Locale effective = locale != null ? locale : Locale.getDefault();
        if (pattern != null && !pattern.isBlank()) {
            try {
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern.strip(), effective);
                // A pattern can parse and still fail on a LocalDateTime (a zone letter, say).
                formatter.format(SAMPLE);
                return formatter;
            } catch (IllegalArgumentException | DateTimeException e) {
                // Fall through to the locale's own short date.
            }
        }
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(effective);
    }

    /** The full date shown in the hover popup, with weekday and month names in that language. */
    public static DateTimeFormatter popupDateFormatter(Locale locale) {
        return DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL)
                .withLocale(locale != null ? locale : Locale.getDefault());
    }

    /**
     * The compact elapsed time drawn next to the date ({@code +12s}, {@code +1:05},
     * {@code +1:02:03}); language-neutral on purpose, the gutter has no room for words.
     */
    public static String compactDuration(Duration duration) {
        long s = Math.max(0, duration.getSeconds());
        if (s < 60) {
            return "+" + s + "s";
        }
        if (s < 3600) {
            return "+" + (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
        }
        return "+" + (s / 3600) + ":" + String.format(Locale.ROOT, "%02d", (s % 3600) / 60)
                + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** The verbose elapsed time for the popup, e.g. {@code 1 min 5 sec}. */
    public String verboseDuration(Duration duration) {
        long s = Math.max(0, duration.getSeconds());
        if (s < 60) {
            return fill(secondsTemplate, s);
        }
        if (s < 3600) {
            return fill(minutesTemplate, s / 60, s % 60);
        }
        return fill(hoursTemplate, s / 3600, (s % 3600) / 60, s % 60);
    }

    /** The popup's elapsed line, e.g. {@code Elapsed: 1 min 5 sec}. */
    public String elapsed(Duration duration) {
        return fill(elapsedTemplate, verboseDuration(duration));
    }

    private static String translated(Function<String, String> lookup, String key, String fallback) {
        String value;
        try {
            value = lookup.apply(key);
        } catch (RuntimeException e) {
            value = null;
        }
        if (value == null || value.isBlank() || value.equals(key)) {
            return fallback;
        }
        return value;
    }

    // Same substitution as LanguageManager.getString(key, args): plain replace, no MessageFormat.
    private static String fill(String template, Object... args) {
        String result = template;
        for (int i = 0; i < args.length; i++) {
            result = result.replace("{" + i + "}", String.valueOf(args[i]));
        }
        return result;
    }
}
