package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import org.testng.annotations.Test;

/**
 * The dates and durations the command-timestamp gutter shows follow the UI language.
 *
 * <p>The short day-month is a translator-controlled pattern, so a typo in one bundle would only
 * show up in that language. Every bundle's pattern is therefore checked here: it has to parse,
 * leave the year out and fit the narrow gutter.
 */
class TimestampGutterFormatsTest {

    private static final Map<String, Locale> BUNDLES = new LinkedHashMap<>();

    static {
        BUNDLES.put("messages.properties", Locale.ENGLISH);
        BUNDLES.put("messages_de.properties", Locale.GERMAN);
        BUNDLES.put("messages_it.properties", Locale.ITALIAN);
        BUNDLES.put("messages_es.properties", Locale.forLanguageTag("es"));
        BUNDLES.put("messages_pt.properties", Locale.forLanguageTag("pt"));
        BUNDLES.put("messages_fr.properties", Locale.FRENCH);
        BUNDLES.put("messages_hr.properties", Locale.forLanguageTag("hr"));
        BUNDLES.put("messages_nl.properties", Locale.forLanguageTag("nl"));
    }

    private static final LocalDateTime OCT_2 = LocalDateTime.of(2026, 10, 2, 17, 20, 3);

    @Test
    void everyBundlesShortDatePatternParsesLeavesTheYearOutAndFitsTheGutter() throws Exception {
        for (Map.Entry<String, Locale> bundle : BUNDLES.entrySet()) {
            String pattern = load(bundle.getKey()).getProperty(TimestampGutterFormats.SHORT_DATE_PATTERN_KEY);
            assertWithMessage(bundle.getKey() + " is missing the short date pattern").that(pattern).isNotNull();

            // Throws on an unknown pattern letter, which is the point.
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(pattern.strip(), bundle.getValue());
            String rendered = formatter.format(OCT_2);

            assertWithMessage(bundle.getKey() + " renders the year: " + pattern)
                    .that(rendered).isEqualTo(formatter.format(OCT_2.plusYears(5)));
            assertWithMessage(bundle.getKey() + " renders " + rendered + ", too wide for the gutter")
                    .that(rendered.length()).isAtMost(6);
            assertWithMessage(bundle.getKey() + " must show the day")
                    .that(rendered).contains("02");
            assertWithMessage(bundle.getKey() + " must show the month")
                    .that(rendered).contains("10");
        }
    }

    @Test
    void germanAndEnglishOrderDayAndMonthTheirOwnWay() throws Exception {
        assertThat(formatsFor("messages_de.properties").shortDate().format(OCT_2)).isEqualTo("02.10.");
        assertThat(formatsFor("messages.properties").shortDate().format(OCT_2)).isEqualTo("10/02");
    }

    @Test
    void thePopupDateNamesTheWeekdayInTheUiLanguage() {
        assertWithMessage("precondition: the sample date is a Friday")
                .that(OCT_2.getDayOfWeek()).isEqualTo(DayOfWeek.FRIDAY);
        assertThat(TimestampGutterFormats.popupDateFormatter(Locale.ITALIAN).format(OCT_2)).contains("venerdì");
        assertThat(TimestampGutterFormats.popupDateFormatter(Locale.ENGLISH).format(OCT_2)).contains("Friday");
        assertThat(TimestampGutterFormats.popupDateFormatter(Locale.GERMAN).format(OCT_2)).contains("Freitag");
        assertWithMessage("the popup no longer follows the JVM locale, it follows the language passed in")
                .that(TimestampGutterFormats.popupDateFormatter(Locale.FRENCH).format(OCT_2))
                .contains("octobre");
    }

    @Test
    void anUnusablePatternFallsBackToTheLocalesShortDate() {
        DateTimeFormatter expected = DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.GERMAN);
        String fallback = expected.format(OCT_2);

        // LanguageManager echoes the key for a missing translation.
        assertThat(TimestampGutterFormats.shortDateFormatter(Locale.GERMAN, TimestampGutterFormats.SHORT_DATE_PATTERN_KEY)
                .format(OCT_2)).isEqualTo(fallback);
        assertThat(TimestampGutterFormats.shortDateFormatter(Locale.GERMAN, "").format(OCT_2)).isEqualTo(fallback);
        assertThat(TimestampGutterFormats.shortDateFormatter(Locale.GERMAN, null).format(OCT_2)).isEqualTo(fallback);
        assertWithMessage("a pattern that parses but cannot format a local date-time must not reach render()")
                .that(TimestampGutterFormats.shortDateFormatter(Locale.GERMAN, "dd.MM. z").format(OCT_2))
                .isEqualTo(fallback);
    }

    @Test
    void aMissingTranslationFallsBackToTheEnglishTexts() {
        TimestampGutterFormats formats = TimestampGutterFormats.forLocale(Locale.GERMAN, key -> key);

        assertThat(formats.elapsed(Duration.ofSeconds(65))).isEqualTo("Elapsed: 1 min 5 sec");
        assertThat(formats.shortDate().format(OCT_2))
                .isEqualTo(DateTimeFormatter.ofLocalizedDate(FormatStyle.SHORT).withLocale(Locale.GERMAN).format(OCT_2));
    }

    @Test
    void theVerboseDurationUsesTheTranslatedUnits() throws Exception {
        TimestampGutterFormats english = formatsFor("messages.properties");
        assertThat(english.verboseDuration(Duration.ofSeconds(12))).isEqualTo("12 sec");
        assertThat(english.verboseDuration(Duration.ofSeconds(65))).isEqualTo("1 min 5 sec");
        assertThat(english.verboseDuration(Duration.ofSeconds(3723))).isEqualTo("1 h 2 min 3 sec");
        assertThat(english.elapsed(Duration.ofSeconds(65))).isEqualTo("Elapsed: 1 min 5 sec");

        TimestampGutterFormats german = formatsFor("messages_de.properties");
        assertThat(german.elapsed(Duration.ofSeconds(65))).isEqualTo("Verstrichen: 1 Min. 5 Sek.");

        for (String bundle : BUNDLES.keySet()) {
            String text = formatsFor(bundle).elapsed(Duration.ofSeconds(3723));
            assertWithMessage(bundle + " left a placeholder unfilled: " + text).that(text).doesNotContain("{");
            assertWithMessage(bundle + " dropped a number: " + text).that(text).contains("1");
            assertWithMessage(bundle + " dropped a number: " + text).that(text).contains("2");
            assertWithMessage(bundle + " dropped a number: " + text).that(text).contains("3");
        }
    }

    @Test
    void theCompactDurationStaysLanguageNeutral() {
        assertThat(TimestampGutterFormats.compactDuration(Duration.ofSeconds(12))).isEqualTo("+12s");
        assertThat(TimestampGutterFormats.compactDuration(Duration.ofSeconds(65))).isEqualTo("+1:05");
        assertThat(TimestampGutterFormats.compactDuration(Duration.ofSeconds(3723))).isEqualTo("+1:02:03");
    }

    @Test
    void theFormatsRememberTheLocaleTheyWereBuiltFor() {
        assertThat(TimestampGutterFormats.forLocale(Locale.ITALIAN, key -> null).locale()).isEqualTo(Locale.ITALIAN);
    }

    private TimestampGutterFormats formatsFor(String bundle) throws Exception {
        Properties properties = load(bundle);
        return TimestampGutterFormats.forLocale(BUNDLES.get(bundle), properties::getProperty);
    }

    private Properties load(String fileName) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(in).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
