package de.kortty.core;

import com.google.gson.JsonParser;
import de.kortty.model.TerminalLogConfig;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class TerminalLogTrimmerTest {

    private static final TerminalLogConfig.LogFormat PLAIN = TerminalLogConfig.LogFormat.PLAIN_TEXT;
    private static final TerminalLogConfig.LogFormat XML = TerminalLogConfig.LogFormat.XML;
    private static final TerminalLogConfig.LogFormat JSON = TerminalLogConfig.LogFormat.JSON;

    private static final String CONNECTION = "web01";

    /** Entries on both edges of 15:00 to 15:30 at the millisecond; the end is exclusive at 15:31:00.000. */
    private static final String[][] EDGES = {
        {"2026-08-04 14:59:59.999", "before"},
        {"2026-08-04 15:00:00.000", "first"},
        {"2026-08-04 15:30:59.999", "last"},
        {"2026-08-04 15:31:00.000", "after"}
    };

    private static final String[][] SESSION = {
        {"2026-08-04 14:50:00.000", "early"},
        {"2026-08-04 15:00:00.000", "one"},
        {"2026-08-04 15:05:00.000", "two"},
        {"2026-08-04 15:10:00.250", "three"},
        {"2026-08-04 15:20:00.000", "four"},
        {"2026-08-04 15:40:00.000", "late"}
    };

    private static final LogTimeRange AFTERNOON = LogTimeRange.of(
        LocalDate.of(2026, 8, 4), LocalTime.of(15, 0), LocalDate.of(2026, 8, 4), LocalTime.of(15, 30));

    private static final LogTimeRange MINUTE_OF_ONE = LogTimeRange.of(
        LocalDate.of(2026, 8, 4), LocalTime.of(15, 0), LocalDate.of(2026, 8, 4), LocalTime.of(15, 0));

    private static final LogTimeRange NEXT_DAY = LogTimeRange.of(LocalDate.of(2026, 8, 5), null, null, null);

    private Path tempDir;

    /** The output and how many entries it carries. */
    private record Trimmed(String text, long kept) {
    }

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-terminal-trimmer-test");
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete " + path, e);
                }
            });
        }
    }

    private static Trimmed runTrim(TerminalLogConfig.LogFormat format, String fallbackName,
                                   LogTimeRange range, String source) throws IOException {
        StringWriter out = new StringWriter();
        long kept = TerminalLogTrimmer.trim(new BufferedReader(new StringReader(source)), out, format,
            fallbackName, range, () -> false);
        return new Trimmed(out.toString(), kept);
    }

    /**
     * Writes entries in the shape {@link TerminalLogger} puts on disk. Every trimmed output is compared
     * against this, so a drift in either the logger or the trimmer shows up as a failing comparison.
     */
    private static String render(TerminalLogConfig.LogFormat format, String connection, boolean footer,
                                 String[][] entries) {
        StringBuilder sb = new StringBuilder();
        switch (format) {
            case PLAIN_TEXT -> {
            }
            case XML -> sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<terminal-log connection=\"")
                .append(escapeXml(connection)).append("\">\n");
            case JSON -> sb.append("{\n  \"connection\": \"").append(escapeJson(connection))
                .append("\",\n  \"entries\": [\n");
        }
        for (int i = 0; i < entries.length; i++) {
            String timestamp = entries[i][0];
            String text = entries[i][1];
            switch (format) {
                case PLAIN_TEXT -> sb.append('[').append(timestamp).append("] ").append(text).append('\n');
                case XML -> sb.append("  <entry timestamp=\"").append(timestamp).append("\">")
                    .append(escapeXml(text)).append("</entry>\n");
                case JSON -> {
                    if (i > 0) {
                        sb.append(",\n");
                    }
                    sb.append("    {\"timestamp\": \"").append(timestamp).append("\", \"line\": \"")
                        .append(escapeJson(text)).append("\"}");
                }
            }
        }
        if (footer) {
            switch (format) {
                case PLAIN_TEXT -> {
                }
                case XML -> sb.append("</terminal-log>\n");
                case JSON -> sb.append(entries.length > 0 ? "\n" : "").append("  ]\n}\n");
            }
        }
        return sb.toString();
    }

    private static String escapeXml(String text) {
        return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;");
    }

    private static String escapeJson(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
            .replace("\r", "\\r").replace("\t", "\\t");
    }

    /** Parses with external entities off, so a hostile name cannot pull in a file. */
    private static Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        return factory.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private static int xmlEntryCount(String xml) throws Exception {
        return parseXml(xml).getElementsByTagName("entry").getLength();
    }

    private static int jsonEntryCount(String json) {
        return JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("entries").size();
    }

    @Test
    void emptyRangeYieldsAValidDocumentWithNoEntries() throws Exception {
        for (TerminalLogConfig.LogFormat format : TerminalLogConfig.LogFormat.values()) {
            Trimmed trimmed = runTrim(format, CONNECTION, NEXT_DAY, render(format, CONNECTION, true, EDGES));

            assertWithMessage(format.name()).that(trimmed.kept()).isEqualTo(0);
            assertWithMessage(format.name()).that(trimmed.text())
                .isEqualTo(render(format, CONNECTION, true, new String[0][]));
        }
        assertThat(xmlEntryCount(runTrim(XML, CONNECTION, NEXT_DAY,
            render(XML, CONNECTION, true, EDGES)).text())).isEqualTo(0);
        assertThat(jsonEntryCount(runTrim(JSON, CONNECTION, NEXT_DAY,
            render(JSON, CONNECTION, true, EDGES)).text())).isEqualTo(0);
    }

    @Test
    void rangeStartsInclusiveAndEndsExclusiveAtTheMillisecond() throws Exception {
        for (TerminalLogConfig.LogFormat format : TerminalLogConfig.LogFormat.values()) {
            Trimmed trimmed = runTrim(format, CONNECTION, AFTERNOON, render(format, CONNECTION, true, EDGES));

            assertWithMessage(format.name()).that(trimmed.kept()).isEqualTo(2);
            assertWithMessage(format.name()).that(trimmed.text())
                .isEqualTo(render(format, CONNECTION, true, new String[][] {EDGES[1], EDGES[2]}));
        }
    }

    @Test
    void halfMillisecondEdgesFollowTheSameRule() throws Exception {
        // The same rule at the middle of a millisecond, where a rounding mistake would show first.
        String[][] halves = {
            {"2026-08-04 14:59:59.500", "before"},
            {"2026-08-04 15:00:00.500", "first"},
            {"2026-08-04 15:30:59.500", "last"},
            {"2026-08-04 15:31:00.500", "after"}
        };
        for (TerminalLogConfig.LogFormat format : TerminalLogConfig.LogFormat.values()) {
            Trimmed trimmed = runTrim(format, CONNECTION, AFTERNOON, render(format, CONNECTION, true, halves));

            assertWithMessage(format.name()).that(trimmed.kept()).isEqualTo(2);
            assertWithMessage(format.name()).that(trimmed.text())
                .isEqualTo(render(format, CONNECTION, true, new String[][] {halves[1], halves[2]}));
        }
    }

    @Test
    void oneEntryInRangeIsWrittenAsItWas() throws Exception {
        for (TerminalLogConfig.LogFormat format : TerminalLogConfig.LogFormat.values()) {
            Trimmed trimmed = runTrim(format, CONNECTION, MINUTE_OF_ONE, render(format, CONNECTION, true, EDGES));

            assertWithMessage(format.name()).that(trimmed.kept()).isEqualTo(1);
            assertWithMessage(format.name()).that(trimmed.text())
                .isEqualTo(render(format, CONNECTION, true, new String[][] {EDGES[1]}));
        }
    }

    @Test
    void manyEntriesKeepTheirOrderAndTheirExactTimestamps() throws Exception {
        for (TerminalLogConfig.LogFormat format : TerminalLogConfig.LogFormat.values()) {
            Trimmed trimmed = runTrim(format, CONNECTION, AFTERNOON, render(format, CONNECTION, true, SESSION));

            assertWithMessage(format.name()).that(trimmed.kept()).isEqualTo(4);
            assertWithMessage(format.name()).that(trimmed.text())
                .isEqualTo(render(format, CONNECTION, true, Arrays.copyOfRange(SESSION, 1, 5)));
        }
    }

    @Test
    void plainTextContinuationLinesStayWithTheirEntry() {
        String[][] entries = {
            {"2026-08-04 15:00:00.000", "first\nsecond\nthird"},
            {"2026-08-04 14:00:00.000", "outside\nlines that go with it"},
            {"2026-08-04 15:02:00.000", "last"}
        };
        String[][] expected = {entries[0], entries[2]};

        try {
            Trimmed trimmed = runTrim(PLAIN, CONNECTION, AFTERNOON, render(PLAIN, CONNECTION, true, entries));

            assertThat(trimmed.kept()).isEqualTo(2);
            assertThat(trimmed.text()).isEqualTo(render(PLAIN, CONNECTION, true, expected));
            assertThat(trimmed.text()).doesNotContain("lines that go with it");
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void xmlContinuationLinesStayWithTheirEntryUntilItsClosingTag() throws Exception {
        String[][] entries = {
            {"2026-08-04 15:00:00.000", "first\nsecond"},
            {"2026-08-04 14:00:00.000", "out\nside"},
            {"2026-08-04 15:02:00.000", "last\n"}
        };
        String[][] expected = {entries[0], entries[2]};

        Trimmed trimmed = runTrim(XML, CONNECTION, AFTERNOON, render(XML, CONNECTION, true, entries));

        assertThat(trimmed.kept()).isEqualTo(2);
        assertThat(trimmed.text()).isEqualTo(render(XML, CONNECTION, true, expected));
        assertThat(xmlEntryCount(trimmed.text())).isEqualTo(2);
    }

    @Test
    void sourceCutShortBeforeItsFooterStillYieldsAValidDocument() throws Exception {
        String[][] kept = {EDGES[1], EDGES[2]};

        Trimmed xml = runTrim(XML, CONNECTION, AFTERNOON, render(XML, CONNECTION, false, EDGES)
            + "  <entry timestamp=\"2026-08-04 15:10:00.000\">half wri");
        assertThat(xml.kept()).isEqualTo(2);
        assertThat(xml.text()).isEqualTo(render(XML, CONNECTION, true, kept));
        assertThat(xmlEntryCount(xml.text())).isEqualTo(2);

        Trimmed json = runTrim(JSON, CONNECTION, AFTERNOON, render(JSON, CONNECTION, false, EDGES));
        assertThat(json.kept()).isEqualTo(2);
        assertThat(json.text()).isEqualTo(render(JSON, CONNECTION, true, kept));
        assertThat(jsonEntryCount(json.text())).isEqualTo(2);
    }

    @Test
    void aJsonEntryCutInsideAnEscapedQuoteIsDroppedNotKept() {
        // The truncated value ends in an escaped quote and a brace, which looks like a finished entry
        // to a naive check; only the unescaped closing quote makes an entry whole.
        String source = render(JSON, CONNECTION, false, new String[][] {EDGES[1]})
            + ",\n    {\"timestamp\": \"2026-08-04 15:10:00.000\", \"line\": \"x\\\"}";
        try {
            Trimmed trimmed = runTrim(JSON, CONNECTION, AFTERNOON, source);

            assertThat(trimmed.kept()).isEqualTo(1);
            assertThat(trimmed.text()).isEqualTo(render(JSON, CONNECTION, true, new String[][] {EDGES[1]}));
            assertThat(jsonEntryCount(trimmed.text())).isEqualTo(1);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void theConnectionNameFromTheSourceHeaderSurvivesEscaping() throws Exception {
        String name = "Tom & \"Jerry\" <prod>";
        for (TerminalLogConfig.LogFormat format : new TerminalLogConfig.LogFormat[] {XML, JSON}) {
            String source = render(format, name, true, new String[][] {EDGES[1]});

            Trimmed trimmed = runTrim(format, "fallback", null, source);

            assertWithMessage(format.name()).that(trimmed.text()).isEqualTo(source);
            if (format == XML) {
                assertThat(parseXml(trimmed.text()).getDocumentElement().getAttribute("connection")).isEqualTo(name);
            } else {
                assertThat(JsonParser.parseString(trimmed.text()).getAsJsonObject()
                    .get("connection").getAsString()).isEqualTo(name);
            }
        }
    }

    @Test
    void theFallbackNameIsUsedOnlyWhenTheSourceHasNoHeader() throws Exception {
        String fallback = "a&b\"c";
        String xmlWithoutHeader = "  <entry timestamp=\"2026-08-04 15:00:00.000\">first</entry>\n</terminal-log>\n";
        Trimmed xml = runTrim(XML, fallback, AFTERNOON, xmlWithoutHeader);
        assertThat(parseXml(xml.text()).getDocumentElement().getAttribute("connection")).isEqualTo(fallback);
        assertThat(xml.kept()).isEqualTo(1);

        String jsonWithoutHeader = "    {\"timestamp\": \"2026-08-04 15:00:00.000\", \"line\": \"first\"}\n  ]\n}\n";
        Trimmed json = runTrim(JSON, fallback, AFTERNOON, jsonWithoutHeader);
        assertThat(JsonParser.parseString(json.text()).getAsJsonObject().get("connection").getAsString())
            .isEqualTo(fallback);
        assertThat(json.kept()).isEqualTo(1);
    }

    @Test
    void linesBeforeTheFirstEntryAreDropped() throws Exception {
        String[][] kept = {EDGES[1]};

        Trimmed plain = runTrim(PLAIN, CONNECTION, null,
            "garbage before the first entry\n\n" + render(PLAIN, CONNECTION, true, kept));
        assertThat(plain.text()).isEqualTo(render(PLAIN, CONNECTION, true, kept));

        Trimmed xml = runTrim(XML, CONNECTION, null, "<junk/>\n" + render(XML, CONNECTION, true, kept));
        assertThat(xml.text()).isEqualTo(render(XML, CONNECTION, true, kept));

        Trimmed json = runTrim(JSON, CONNECTION, null, "not json at all\n" + render(JSON, CONNECTION, true, kept));
        assertThat(json.text()).isEqualTo(render(JSON, CONNECTION, true, kept));
    }

    @Test
    void crlfInputIsWrittenWithLineFeedsOnly() throws Exception {
        String[][] kept = {EDGES[1], EDGES[2]};
        for (TerminalLogConfig.LogFormat format : TerminalLogConfig.LogFormat.values()) {
            String lf = render(format, CONNECTION, true, kept);

            Trimmed trimmed = runTrim(format, CONNECTION, AFTERNOON,
                render(format, CONNECTION, true, EDGES).replace("\n", "\r\n"));

            assertWithMessage(format.name()).that(trimmed.text()).isEqualTo(lf);
        }
    }

    @Test
    void cancellationStopsTheTrimWithACancelledException() {
        String source = render(PLAIN, CONNECTION, true, SESSION);
        AtomicInteger polls = new AtomicInteger();
        boolean cancelled = false;
        try {
            TerminalLogTrimmer.trim(new BufferedReader(new StringReader(source)), new StringWriter(), PLAIN,
                CONNECTION, null, () -> polls.incrementAndGet() > 2);
        } catch (TerminalLogTrimmer.CancelledException expected) {
            cancelled = true;
        } catch (IOException e) {
            throw new AssertionError(e);
        }
        assertThat(cancelled).isTrue();
    }

    @Test
    void trimmingWhatTheLoggerWroteReproducesItExactly() throws Exception {
        // The reference for the on-disk shape is the logger itself: with no range nothing is dropped, so
        // the trimmed output must match what it wrote byte for byte, header and footer included.
        for (TerminalLogConfig.LogFormat format : TerminalLogConfig.LogFormat.values()) {
            Path dir = Files.createDirectories(tempDir.resolve(format.name().toLowerCase(Locale.ROOT)));
            TerminalLogConfig config = new TerminalLogConfig();
            config.setEnabled(true);
            config.setFormat(format);
            config.setCompress(false);
            config.setRotateDaily(false);
            config.setRetentionDays(0);
            TerminalLogger logger = new TerminalLogger(config, CONNECTION, dir, Clock.systemDefaultZone(),
                new SessionJournalRedactor());
            logger.start();
            logger.log("first line\nsecond & \"quoted\" <tag>\n");
            logger.log("third\n");
            logger.stop();

            String written;
            try (Stream<Path> files = Files.list(dir)) {
                written = Files.readString(files.findFirst().orElseThrow()).replace("\r\n", "\n");
            }
            Trimmed trimmed = runTrim(format, "unused", null, written);

            assertWithMessage(format.name()).that(trimmed.text()).isEqualTo(written);
            assertWithMessage(format.name()).that(trimmed.kept()).isEqualTo(3);
        }
    }
}
