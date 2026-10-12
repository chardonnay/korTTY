package de.kortty.core;

import de.kortty.model.TerminalLogConfig;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class TerminalLogNamingParseTest {

    private static final LocalDateTime STAMP = LocalDateTime.of(2026, 8, 4, 14, 30, 12);

    /**
     * Names the retention sweep and the Logfile Manager must agree on. The parity test below holds
     * the two readers of the same file name to one answer, so every shape the logger can produce,
     * and every near miss a user could plausibly keep in the folder, belongs here.
     */
    private static final List<String> PARITY_CORPUS = List.of(
        "2026-08-04-14-30-12-web01_1.log",
        "2026-08-04-14-30-12-web01_1.txt",
        "2026-08-04-14-30-12-web01_1.xml",
        "2026-08-04-14-30-12-web01_1.json",
        "2026-08-04-14-30-12-web01_1.log.gz",
        "2026-08-04-14-30-12-web01_1.txt.gz",
        "2026-08-04-14-30-12-web01_1.xml.gz",
        "2026-08-04-14-30-12-web01_1.json.gz",
        "2026-08-04-14-30-12-web01_1.log.zst",
        "2026-08-04-14-30-12-web01_1.log.bz2",
        "2026-08-04-14-30-12-web01_1.p2.log.gz",
        "2026-08-04-14-30-12-web01_12.p10.json.gz",
        "2026-08-04-14-30-12-web01_999.log.gz",
        "2026-08-04-14-30-12-web_2_1.log.gz",
        "2026-08-04-14-30-12-web.log_3.log.gz",
        "2026-08-04-14-30-12-a_b-c_5.log.gz",
        "2026-08-04-99-30-12-web01_1.log.gz",
        "2026-02-30-10-00-00-web01_1.log.gz",
        "2026-04-31-10-00-00-web01_1.log.gz",
        "2026-13-01-10-00-00-web01_1.log.gz",
        "2026-08-32-10-00-00-web01_1.log.gz",
        "2026-8-04-14-30-12-web01_1.log.gz",
        "2026-08-04-14-30-12-web01.log.gz",
        "2026-08-04-14-30-12-web01_1.log.gz.gz",
        "2026-08-04-14-30-12-web01_1.LOG.gz",
        "2026-08-04-14-30-12-web01_1.log.GZ",
        "2026-08-04-14-30-12-web01_1.log.zst.gz",
        "2026-08-04-14-30-12-web01_1.log.bz2",
        "2026-08-04-14-30-12-web01_1.p2.log",
        "2026-08-04-14-30-12-web01_1.p.log.gz",
        "2026-08-04-14-30-12-_1.log.gz",
        "2026-08-04-14-30-12-web01_1.log.gz.d",
        "2026-08-04-14-30-12-web01_1.log.gz~",
        " 2026-08-04-14-30-12-web01_1.log.gz",
        "2026-08-04-14-30-12-web01_1.bin.gz",
        "backup.tar.gz",
        "important.gz",
        "notes.txt",
        "2026-08-04-14-30-12-web01_1.log.gz"
    );

    @Test
    void readsAPlainLogNameIntoItsParts() {
        TerminalLogNaming.ParsedName parsed = TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.log");

        assertThat(parsed).isEqualTo(new TerminalLogNaming.ParsedName(STAMP, "web01", 1, 1,
            TerminalLogConfig.LogFormat.PLAIN_TEXT, TerminalLogNaming.Compression.NONE));
    }

    @Test
    void mapsEachExtensionToItsFormat() {
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.log").format())
            .isEqualTo(TerminalLogConfig.LogFormat.PLAIN_TEXT);
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.txt").format())
            .isEqualTo(TerminalLogConfig.LogFormat.PLAIN_TEXT);
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.xml").format())
            .isEqualTo(TerminalLogConfig.LogFormat.XML);
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.json").format())
            .isEqualTo(TerminalLogConfig.LogFormat.JSON);
    }

    @Test
    void mapsEachCompressionSuffixToItsCompression() {
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.log.gz").compression())
            .isEqualTo(TerminalLogNaming.Compression.GZIP);
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.log.zst").compression())
            .isEqualTo(TerminalLogNaming.Compression.ZSTD);
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.log.bz2").compression())
            .isEqualTo(TerminalLogNaming.Compression.BZIP2);
    }

    @Test
    void readsACompressedPartWithItsPartNumberAndFormat() {
        TerminalLogNaming.ParsedName parsed = TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.p2.json.gz");

        assertThat(parsed).isEqualTo(new TerminalLogNaming.ParsedName(STAMP, "web01", 1, 2,
            TerminalLogConfig.LogFormat.JSON, TerminalLogNaming.Compression.GZIP));
    }

    @Test
    void readsSequenceNumbersOfOneToThreeDigits() {
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_12.log").sequence()).isEqualTo(12);
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_999.log.gz").sequence()).isEqualTo(999);
    }

    @Test
    void keepsSlugsWithUnderscoresDotsAndDashesWhole() {
        // The greedy slug takes everything up to the last _<digits>, so an underscore followed by
        // digits inside a server name is part of the slug, not the sequence.
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web_2_1.log").slug()).isEqualTo("web_2");
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web_2_1.log").sequence()).isEqualTo(1);
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web.log_3.log.gz").slug()).isEqualTo("web.log");
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web.log_3.log.gz").sequence()).isEqualTo(3);
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-a_b-c_5.log.gz").slug()).isEqualTo("a_b-c");
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-a_b-c_5.log.gz").sequence()).isEqualTo(5);
    }

    @Test
    void readsTheStampFromTheName() {
        assertThat(TerminalLogNaming.parse("2026-08-04-14-30-12-web01_1.log").startedAt()).isEqualTo(STAMP);
    }

    @Test
    void rejectsEveryNameKorTTYDidNotWrite() {
        List<String> foreign = List.of(
            "backup.tar.gz",
            "notes.txt",
            "2026-08-04-14-30-12-web01.log",
            "2026-08-04-14-30-12-web01_1.log.gz.gz",
            "2026-08-04-14-30-12-web01_1.LOG",
            "2026-08-04-14-30-12-web01_1.bin"
        );
        for (String name : foreign) {
            assertWithMessage(name).that(TerminalLogNaming.parse(name)).isNull();
        }
        assertThat(TerminalLogNaming.parse(null)).isNull();
    }

    @Test
    void resolvesAnImpossibleDayToTheLastDayOfTheMonth() {
        assertThat(TerminalLogNaming.parse("2026-02-30-10-00-00-web01_1.log").startedAt().toLocalDate())
            .isEqualTo(LocalDate.of(2026, 2, 28));
        assertThat(TerminalLogNaming.parse("2026-04-31-10-00-00-web01_1.log").startedAt().toLocalDate())
            .isEqualTo(LocalDate.of(2026, 4, 30));
    }

    @Test
    void rejectsAMonthOrDayThatCannotExist() {
        assertThat(TerminalLogNaming.parse("2026-13-01-10-00-00-web01_1.log")).isNull();
        assertThat(TerminalLogNaming.parse("2026-08-32-10-00-00-web01_1.log")).isNull();
    }

    @Test
    void readsAnImpossibleHourAsMidnightInsteadOfRejectingTheFile() {
        // The retention sweep looks only at the date, so it would still delete this archive; the
        // listing has to show it too rather than hide a file the sweep will remove.
        TerminalLogNaming.ParsedName parsed = TerminalLogNaming.parse("2026-08-04-99-30-12-web01_1.log.gz");

        assertThat(parsed).isNotNull();
        assertThat(parsed.startedAt()).isEqualTo(LocalDateTime.of(2026, 8, 4, 0, 0));
    }

    @Test
    void agreesWithTheRetentionSweepOnWhichArchivesAreKorTTYs() {
        for (String name : PARITY_CORPUS) {
            TerminalLogNaming.ParsedName parsed = TerminalLogNaming.parse(name);
            boolean listedAsArchive = parsed != null && parsed.compression() == TerminalLogNaming.Compression.GZIP;
            boolean sweptAsArchive = TerminalLogNaming.archiveDate(name) != null;
            assertWithMessage("archive verdict for %s", name).that(listedAsArchive).isEqualTo(sweptAsArchive);
        }
    }

    @Test
    void parsesEveryArchiveTheSweepWouldDelete() {
        for (String name : PARITY_CORPUS) {
            if (TerminalLogNaming.archiveDate(name) != null) {
                assertWithMessage("parse of %s", name).that(TerminalLogNaming.parse(name)).isNotNull();
            }
        }
    }

    @Test
    void mapsCompressionSuffixesByName() {
        assertThat(TerminalLogNaming.Compression.ofName("x.log.gz")).isEqualTo(TerminalLogNaming.Compression.GZIP);
        assertThat(TerminalLogNaming.Compression.ofName("x.zst")).isEqualTo(TerminalLogNaming.Compression.ZSTD);
        assertThat(TerminalLogNaming.Compression.ofName("x.bz2")).isEqualTo(TerminalLogNaming.Compression.BZIP2);
        assertThat(TerminalLogNaming.Compression.ofName("x.log")).isEqualTo(TerminalLogNaming.Compression.NONE);
        assertThat(TerminalLogNaming.Compression.ofName("x.GZ")).isEqualTo(TerminalLogNaming.Compression.GZIP);
        assertThat(TerminalLogNaming.Compression.ofName("gz")).isEqualTo(TerminalLogNaming.Compression.NONE);
        assertThat(TerminalLogNaming.Compression.ofName("")).isEqualTo(TerminalLogNaming.Compression.NONE);
        assertThat(TerminalLogNaming.Compression.ofName(null)).isEqualTo(TerminalLogNaming.Compression.NONE);
    }
}
