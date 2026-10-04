package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.CommandStatus;
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
 *
 * <p>A row with the status of a command the shell marked (OSC 133) shows that command's runtime in
 * place of the gap since the previous mark, and its popup the runtime and the exit status.
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
    void theRuntimeReplacesTheTimeSinceThePreviousMarkWhenAStatusIsPresent() {
        Duration sincePrevious = Duration.ofSeconds(90);
        assertWithMessage("without shell integration the row shows the gap since the previous mark")
            .that(TimestampGutterFormats.topLine("10/02", sincePrevious, null)).isEqualTo("10/02 +1:30");
        assertWithMessage("a finished command shows its own runtime, without the + of a gap")
            .that(TimestampGutterFormats.topLine("10/02", sincePrevious, finished(0, Duration.ofSeconds(12))))
            .isEqualTo("10/02 12s");
        assertThat(TimestampGutterFormats.topLine("10/02", null, finished(1, Duration.ofMillis(40))))
            .isEqualTo("10/02 <1s");
        assertWithMessage("a running command has no runtime yet, so the gap stays")
            .that(TimestampGutterFormats.topLine("10/02", sincePrevious, running())).isEqualTo("10/02 +1:30");
        assertWithMessage("the first output line of a running command has no timestamp and no text")
            .that(TimestampGutterFormats.topLine(null, null, running())).isEmpty();
        assertThat(TimestampGutterFormats.topLine(null, null, finished(0, Duration.ofSeconds(3)))).isEqualTo("3s");
        assertThat(TimestampGutterFormats.topLine("10/02", Duration.ofSeconds(-5), null)).isEqualTo("10/02");
    }

    @Test
    void theCompactRuntimeIsLanguageNeutralAndHasNoSign() {
        assertThat(TimestampGutterFormats.compactRuntime(Duration.ZERO)).isEqualTo("<1s");
        assertThat(TimestampGutterFormats.compactRuntime(Duration.ofMillis(999))).isEqualTo("<1s");
        assertThat(TimestampGutterFormats.compactRuntime(Duration.ofSeconds(1))).isEqualTo("1s");
        assertThat(TimestampGutterFormats.compactRuntime(Duration.ofSeconds(65))).isEqualTo("1:05");
        assertThat(TimestampGutterFormats.compactRuntime(Duration.ofSeconds(3723))).isEqualTo("1:02:03");
        assertThat(TimestampGutterFormats.compactRuntime(Duration.ofSeconds(-3))).isEqualTo("<1s");
    }

    @Test
    void thePopupNamesTheRuntimeOrHowLongTheCommandHasBeenRunning() throws Exception {
        TimestampGutterFormats english = formatsFor("messages.properties");
        assertThat(english.durationLine(Duration.ofSeconds(90), finished(0, Duration.ofSeconds(65)), 0L))
            .isEqualTo("Runtime: 1 min 5 sec");
        assertThat(english.durationLine(null, finished(0, Duration.ofMillis(350)), 0L)).isEqualTo("Runtime: 350 ms");
        CommandStatus running = new CommandStatus(CommandStatus.Kind.RUNNING, null, 1_000_000_000L, 0L);
        assertThat(english.durationLine(null, running, 66_000_000_000L)).isEqualTo("Running for 1 min 5 sec");
        assertWithMessage("without a status the popup keeps the elapsed time")
            .that(english.durationLine(Duration.ofSeconds(65), null, 0L)).isEqualTo("Elapsed: 1 min 5 sec");
        assertThat(english.durationLine(null, null, 0L)).isNull();
        assertThat(english.durationLine(Duration.ofSeconds(-1), null, 0L)).isNull();

        TimestampGutterFormats german = formatsFor("messages_de.properties");
        assertThat(german.durationLine(null, finished(0, Duration.ofSeconds(65)), 0L)).isEqualTo("Laufzeit: 1 Min. 5 Sek.");
        assertThat(german.exitStatusLine(finished(2, Duration.ZERO))).isEqualTo("Exit-Status: 2");
    }

    @Test
    void thePopupShowsTheExitStatusOnlyWhenTheShellReportedOne() throws Exception {
        TimestampGutterFormats english = formatsFor("messages.properties");
        assertThat(english.exitStatusLine(finished(0, Duration.ZERO))).isEqualTo("Exit status: 0");
        assertThat(english.exitStatusLine(finished(130, Duration.ZERO))).isEqualTo("Exit status: 130");
        assertThat(english.exitStatusLine(new CommandStatus(CommandStatus.Kind.NO_STATUS, null, 1L, 2L))).isNull();
        assertThat(english.exitStatusLine(running())).isNull();
        assertThat(english.exitStatusLine(null)).isNull();
    }

    @Test
    void everyBundleFillsTheRuntimeAndExitStatusTexts() throws Exception {
        CommandStatus failed = finished(127, Duration.ofSeconds(3723));
        for (String bundle : BUNDLES.keySet()) {
            TimestampGutterFormats formats = formatsFor(bundle);
            for (String text : new String[] {
                formats.durationLine(null, failed, 0L),
                formats.durationLine(null, new CommandStatus(CommandStatus.Kind.RUNNING, null, 0L, 0L), 3_723_000_000_000L),
                formats.exitStatusLine(failed),
                formats.verboseRuntime(Duration.ofMillis(250))}) {
                assertWithMessage(bundle + " left a placeholder unfilled: " + text).that(text).doesNotContain("{");
            }
            assertWithMessage(bundle).that(formats.exitStatusLine(failed)).contains("127");
            assertWithMessage(bundle).that(formats.durationLine(null, failed, 0L)).contains("2");
            assertWithMessage(bundle).that(formats.verboseRuntime(Duration.ofMillis(250))).contains("250");
        }
    }

    @Test
    void aMissingTranslationOfTheStatusTextsFallsBackToEnglish() {
        TimestampGutterFormats formats = TimestampGutterFormats.forLocale(Locale.GERMAN, key -> key);
        assertThat(formats.durationLine(null, finished(1, Duration.ofSeconds(2)), 0L)).isEqualTo("Runtime: 2 sec");
        assertThat(formats.exitStatusLine(finished(1, Duration.ZERO))).isEqualTo("Exit status: 1");
        assertThat(formats.verboseRuntime(Duration.ofMillis(5))).isEqualTo("5 ms");
    }

    @Test
    void theFormatsRememberTheLocaleTheyWereBuiltFor() {
        assertThat(TimestampGutterFormats.forLocale(Locale.ITALIAN, key -> null).locale()).isEqualTo(Locale.ITALIAN);
    }

    private static CommandStatus finished(int exitStatus, Duration runtime) {
        CommandStatus.Kind kind = exitStatus == 0 ? CommandStatus.Kind.SUCCEEDED : CommandStatus.Kind.FAILED;
        return new CommandStatus(kind, exitStatus, 1_000L, 1_000L + runtime.toNanos());
    }

    private static CommandStatus running() {
        return new CommandStatus(CommandStatus.Kind.RUNNING, null, 1_000L, 0L);
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
