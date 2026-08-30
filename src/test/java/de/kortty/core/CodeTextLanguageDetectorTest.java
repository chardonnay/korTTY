package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The detector decides whether korTTY may keep a script's prose language silently or has to ask.
 * Both mistakes are costly in opposite ways — a wrong silent answer rewrites someone's comments
 * into a language they did not choose, and asking about an obvious English script is noise — so
 * these cases pin the middle ground rather than only the happy path.
 */
class CodeTextLanguageDetectorTest {

    private static final String ENGLISH_BASH = """
        #!/bin/bash
        # Collects the server statistics and writes them to the report file.
        # The script must be run as root, otherwise the counters are not readable.
        set -euo pipefail

        if [ "$EUID" -ne 0 ]; then
          echo "This script has to be started with root privileges"
          exit 1
        fi

        # Check that the output directory exists before we write anything into it
        for host in "${HOSTS[@]}"; do
          echo "Collecting values from the host $host"
        done
        """;

    private static final String GERMAN_BASH = """
        #!/bin/bash
        # Sammelt die Serverstatistiken und schreibt sie in die Reportdatei.
        # Das Skript muss als root laufen, sonst sind die Zähler nicht lesbar.
        set -euo pipefail

        if [ "$EUID" -ne 0 ]; then
          echo "Dieses Skript muss mit root Rechten gestartet werden"
          exit 1
        fi

        # Prüft ob das Ausgabeverzeichnis vorhanden ist bevor etwas geschrieben wird
        for host in "${HOSTS[@]}"; do
          echo "Sammle die Werte von dem Host $host"
        done
        """;

    @Test
    void recognizesAnEnglishScript() {
        CodeTextLanguageDetector.Detection detection =
            CodeTextLanguageDetector.detect(ENGLISH_BASH, "bash");

        assertThat(detection.languageCode()).isEqualTo("en");
        assertThat(detection.confidence()).isEqualTo(CodeTextLanguageDetector.Confidence.CONFIDENT);
        assertThat(detection.isUsable()).isTrue();
    }

    @Test
    void recognizesAGermanScript() {
        CodeTextLanguageDetector.Detection detection =
            CodeTextLanguageDetector.detect(GERMAN_BASH, "bash");

        assertThat(detection.languageCode()).isEqualTo("de");
        assertThat(detection.confidence()).isEqualTo(CodeTextLanguageDetector.Confidence.CONFIDENT);
    }

    /** The case that motivated the whole change: German UI, English script, nothing to translate. */
    @Test
    void anEnglishScriptStaysEnglishRegardlessOfTheInterfaceLanguage() {
        CodeTextLanguageDetector.Detection detection =
            CodeTextLanguageDetector.detect(ENGLISH_BASH, "perl");

        assertThat(detection.isUsable()).isTrue();
        assertThat(detection.languageCode()).isEqualTo("en");
    }

    @Test
    void aScriptWithoutProseIsUnknownRatherThanGuessed() {
        String bare = """
            #!/bin/bash
            set -euo pipefail
            cd /opt/app && ./run --force
            exit $?
            """;

        CodeTextLanguageDetector.Detection detection = CodeTextLanguageDetector.detect(bare, "bash");

        assertThat(detection.confidence()).isEqualTo(CodeTextLanguageDetector.Confidence.UNKNOWN);
        assertThat(detection.isUsable()).isFalse();
    }

    @Test
    void anEmptyOrNullScriptIsUnknown() {
        for (String source : new String[]{null, "", "   \n\n"}) {
            assertThat(CodeTextLanguageDetector.detect(source, "bash").confidence())
                .isEqualTo(CodeTextLanguageDetector.Confidence.UNKNOWN);
        }
    }

    /** A script commented half in one language and half in another must reach the user as a question. */
    @Test
    void aMixedLanguageScriptIsAmbiguous() {
        String mixed = """
            #!/bin/bash
            # Collects the server statistics and writes them into the report file
            # The script must be run as root otherwise the counters are not readable
            # Sammelt die Werte und schreibt sie in eine Datei wenn das Verzeichnis
            # vorhanden ist und der Benutzer die Rechte dafuer hat sonst bricht es ab
            echo "done"
            """;

        CodeTextLanguageDetector.Detection detection = CodeTextLanguageDetector.detect(mixed, "bash");

        assertWithMessage("a half-and-half script must not be decided silently")
            .that(detection.isUsable()).isFalse();
        assertThat(detection.confidence()).isEqualTo(CodeTextLanguageDetector.Confidence.AMBIGUOUS);
    }

    /** A language korTTY does not speak scores nowhere and must not be forced onto a neighbour. */
    @Test
    void aLanguageKorttyDoesNotSpeakIsNotGuessedAsANeighbour() {
        String polish = """
            #!/bin/bash
            # Skrypt zbiera statystyki serwera oraz zapisuje wyniki
            # Uruchomienie wymaga uprawnien administratora inaczej licznik
            echo "gotowe"
            """;

        CodeTextLanguageDetector.Detection detection = CodeTextLanguageDetector.detect(polish, "bash");

        assertThat(detection.isUsable()).isFalse();
    }

    @Test
    void readsCommentsInCStyleAndMarkupLanguagesToo() {
        String java = """
            // Collects the server statistics and writes them into the report file.
            /* The method must not be called before the configuration has been loaded,
               otherwise the values are read from an empty map and the result is wrong. */
            class Report { }
            """;
        assertThat(CodeTextLanguageDetector.detect(java, "java").languageCode()).isEqualTo("en");

        String xml = """
            <!-- Die Konfiguration wird beim Start geladen und darf nicht leer sein,
                 sonst werden die Werte nicht gefunden und der Dienst startet nicht. -->
            <config/>
            """;
        assertThat(CodeTextLanguageDetector.detect(xml, "xml").languageCode()).isEqualTo("de");
    }

    /** Paths, flags and format tokens are not messages and must not be counted as prose. */
    @Test
    void ignoresStringLiteralsThatAreNotSentences() {
        String source = """
            #!/bin/bash
            SRC="/var/log/app"
            FLAG="--dry-run"
            FMT="%s:%d"
            echo "$SRC"
            """;

        assertThat(CodeTextLanguageDetector.detect(source, "bash").confidence())
            .isEqualTo(CodeTextLanguageDetector.Confidence.UNKNOWN);
    }

    @Test
    void reportsHowMuchProseItJudged() {
        assertThat(CodeTextLanguageDetector.detect(ENGLISH_BASH, "bash").wordCount())
            .isAtLeast(CodeTextLanguageDetector.MIN_WORDS);
        assertThat(CodeTextLanguageDetector.detect("#!/bin/bash\nexit 0\n", "bash").wordCount())
            .isLessThan(CodeTextLanguageDetector.MIN_WORDS);
    }
}
