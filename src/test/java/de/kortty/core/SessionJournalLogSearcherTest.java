package de.kortty.core;

import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.regex.PatternSyntaxException;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class SessionJournalLogSearcherTest {

    private static final OffsetDateTime BASE =
        OffsetDateTime.of(2026, 8, 3, 14, 15, 3, 0, ZoneOffset.ofHours(2));

    private Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-session-journal-search-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete temp path " + path, e);
                }
            });
        }
    }

    @DataProvider(name = "formats")
    Object[][] formats() {
        return new Object[][] {
            {SessionJournalLogFormat.XML},
            {SessionJournalLogFormat.JSON},
            {SessionJournalLogFormat.YAML},
        };
    }

    private static SessionJournalMeta sampleMeta() {
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setHost("192.168.1.50");
        meta.setPort(22);
        meta.setUsername("daniel");
        meta.setConnectionName("web");
        meta.setAppVersion("2.8.0");
        meta.setStartedAt(BASE.minusSeconds(1));
        return meta;
    }

    private Path writePart(SessionJournalLogFormat format, int part,
                           List<SessionJournalLogEntry> entries) throws IOException {
        SessionJournalLogSerializer serializer = SessionJournalLogSerializer.forFormat(format);
        StringBuilder sb = new StringBuilder();
        sb.append(serializer.header("journal-1", part, sampleMeta(), "tab-session-1"));
        for (SessionJournalLogEntry entry : entries) {
            sb.append(serializer.entryLine(entry));
        }
        sb.append(serializer.footer());
        Path file = tempDir.resolve(SessionJournalLogReader.partFileName(part, format));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    private static SessionJournalLogEntry out(long seq, String text) {
        return new SessionJournalLogEntry(seq, BASE.plusSeconds(seq),
            SessionJournalLogEntry.Kind.OUT, text, false, false, null);
    }

    private static SessionJournalLogEntry in(long seq, String text) {
        return new SessionJournalLogEntry(seq, BASE.plusSeconds(seq),
            SessionJournalLogEntry.Kind.IN, text, false, false, null);
    }

    @Test(dataProvider = "formats")
    void findsMatchesAcrossCompressedAndPlainParts(SessionJournalLogFormat format) throws IOException {
        Path part1 = writePart(format, 1, List.of(
            out(1, "perl result_complex.pl started"),
            in(2, "ls -la"),
            out(3, "result_complex.pl: died at line 42")));
        SessionJournalLogCompressor.compress(part1);
        writePart(format, 2, List.of(
            out(4, "all quiet here"),
            out(5, "rerun of result_complex.pl succeeded")));

        SessionJournalLogSearcher.Result result = SessionJournalLogSearcher.search(
            tempDir, SessionJournalLogSearcher.Spec.ofLiteral(List.of("result_complex.pl")), 100, null);

        assertThat(result.totalMatches()).isEqualTo(3);
        assertThat(result.truncated()).isFalse();
        assertThat(result.hits().stream().map(SessionJournalLogSearcher.Hit::seq).toList())
            .containsExactly(1L, 3L, 5L).inOrder();
        assertThat(result.hits().get(0).part()).isEqualTo(1);
        assertThat(result.hits().get(2).part()).isEqualTo(2);
        assertThat(result.hits().get(1).snippet()).contains("died at line 42");
    }

    @Test
    void skipsPartialLinesAndCountsRepeats() throws IOException {
        writePart(SessionJournalLogFormat.JSON, 1, List.of(
            new SessionJournalLogEntry(1, BASE, SessionJournalLogEntry.Kind.OUT,
                "ping: no route to host", false, true, null),
            new SessionJournalLogEntry(2, BASE.plusSeconds(1), SessionJournalLogEntry.Kind.OUT,
                "ping: no route to host", false, false, null),
            new SessionJournalLogEntry(3, BASE.plusSeconds(9), SessionJournalLogEntry.Kind.OUT,
                "ping: no route to host", false, false, null, 7)));

        SessionJournalLogSearcher.Result result = SessionJournalLogSearcher.search(
            tempDir, SessionJournalLogSearcher.Spec.ofLiteral(List.of("no route")), 100, null);

        // partial seq 1 skipped; seq 2 counts once, coalesced seq 3 stands for 7 occurrences
        assertThat(result.hits()).hasSize(2);
        assertThat(result.totalMatches()).isEqualTo(8);
        assertThat(result.hits().get(1).repeat()).isEqualTo(7);
    }

    @Test
    void capsHitsButKeepsCounting() throws IOException {
        writePart(SessionJournalLogFormat.JSON, 1, List.of(
            out(1, "error one"), out(2, "error two"), out(3, "error three"), out(4, "error four")));

        SessionJournalLogSearcher.Result result = SessionJournalLogSearcher.search(
            tempDir, SessionJournalLogSearcher.Spec.ofLiteral(List.of("error")), 2, null);

        assertThat(result.hits()).hasSize(2);
        assertThat(result.totalMatches()).isEqualTo(4);
        assertThat(result.truncated()).isTrue();
    }

    @Test
    void matchesCaseInsensitivelyByDefaultAndSupportsMatchAll() throws IOException {
        writePart(SessionJournalLogFormat.JSON, 1, List.of(
            out(1, "ERROR in script.sh"), out(2, "warning in script.sh"), out(3, "error elsewhere")));

        SessionJournalLogSearcher.Result any = SessionJournalLogSearcher.search(
            tempDir, SessionJournalLogSearcher.Spec.ofLiteral(List.of("error")), 100, null);
        assertThat(any.totalMatches()).isEqualTo(2);

        SessionJournalLogSearcher.Result all = SessionJournalLogSearcher.search(
            tempDir, new SessionJournalLogSearcher.Spec(
                List.of("error", "script.sh"), true, false, false, null, null, null), 100, null);
        assertThat(all.hits().stream().map(SessionJournalLogSearcher.Hit::seq).toList())
            .containsExactly(1L);
    }

    @Test
    void supportsRegexAndRejectsInvalidPatterns() throws IOException {
        writePart(SessionJournalLogFormat.JSON, 1, List.of(
            out(1, "exit code 1"), out(2, "exit code 0"), out(3, "exit code 17")));

        SessionJournalLogSearcher.Result result = SessionJournalLogSearcher.search(
            tempDir, new SessionJournalLogSearcher.Spec(
                List.of("exit code [1-9]\\d*"), false, false, true, null, null, null), 100, null);
        assertThat(result.hits().stream().map(SessionJournalLogSearcher.Hit::seq).toList())
            .containsExactly(1L, 3L).inOrder();

        assertThrows(PatternSyntaxException.class, () -> SessionJournalLogSearcher.search(
            tempDir, new SessionJournalLogSearcher.Spec(
                List.of("[unclosed"), false, false, true, null, null, null), 100, null));
    }

    @Test
    void filtersByKindAndTimeWindow() throws IOException {
        writePart(SessionJournalLogFormat.JSON, 1, List.of(
            out(1, "match early"), in(2, "match input"), out(10, "match late")));

        SessionJournalLogSearcher.Result inputsOnly = SessionJournalLogSearcher.search(
            tempDir, new SessionJournalLogSearcher.Spec(
                List.of("match"), false, false, false, null, null,
                EnumSet.of(SessionJournalLogEntry.Kind.IN)), 100, null);
        assertThat(inputsOnly.hits().stream().map(SessionJournalLogSearcher.Hit::seq).toList())
            .containsExactly(2L);

        SessionJournalLogSearcher.Result windowed = SessionJournalLogSearcher.search(
            tempDir, new SessionJournalLogSearcher.Spec(
                List.of("match"), false, false, false,
                BASE.plusSeconds(2), BASE.plusSeconds(9), null), 100, null);
        assertThat(windowed.hits().stream().map(SessionJournalLogSearcher.Hit::seq).toList())
            .containsExactly(2L);
    }

    @Test
    void stopsAtCancellationAndReturnsCollectedHits() throws IOException {
        // More than one cancel-check interval of lines so the cancellation actually triggers.
        List<SessionJournalLogEntry> entries = new java.util.ArrayList<>();
        for (long seq = 1; seq <= 2000; seq++) {
            entries.add(out(seq, "filler line " + seq));
        }
        writePart(SessionJournalLogFormat.JSON, 1, entries);

        SessionJournalLogSearcher.Result result = SessionJournalLogSearcher.search(
            tempDir, SessionJournalLogSearcher.Spec.ofLiteral(List.of("filler")), 10_000, () -> true);

        assertThat(result.hits().size()).isLessThan(2000);
    }

    @Test
    void blankTermsMatchNothingAndMissingJournalIsEmpty() {
        SessionJournalLogSearcher.Result blank = SessionJournalLogSearcher.search(
            tempDir, SessionJournalLogSearcher.Spec.ofLiteral(List.of("  ", "")), 100, null);
        assertThat(blank.totalMatches()).isEqualTo(0);

        SessionJournalLogSearcher.Result missing = SessionJournalLogSearcher.search(
            tempDir.resolve("does-not-exist"),
            SessionJournalLogSearcher.Spec.ofLiteral(List.of("x")), 100, null);
        assertThat(missing.hits()).isEmpty();
    }

    @Test
    void snippetCentersOnMatchAndFlattensNewlines() {
        String longText = "prefix ".repeat(40) + "NEEDLE" + " suffix".repeat(40);
        String snippet = SessionJournalLogSearcher.snippet(longText, longText.indexOf("NEEDLE"));
        assertThat(snippet).contains("NEEDLE");
        assertThat(snippet.length()).isAtMost(210);
        assertThat(SessionJournalLogSearcher.snippet("a\nb", 0)).isEqualTo("a b");
    }
}
