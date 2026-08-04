package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.TerminalLogConfig;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

import static com.google.common.truth.Truth.assertThat;

class TerminalLoggerTest {

    /** A clock the test moves by hand, so midnight is reachable without waiting for it. */
    private static final class MovableClock extends Clock {
        private volatile Instant instant;

        MovableClock(String isoInstant) {
            this.instant = Instant.parse(isoInstant);
        }

        void advance(Duration amount) {
            instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    private Path tempDir;
    private MovableClock clock;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-terminal-logger-test");
        clock = new MovableClock("2026-08-04T14:30:12Z");
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete " + path, e);
                }
            });
        }
    }

    private TerminalLogConfig config(TerminalLogConfig.LogFormat format) {
        TerminalLogConfig config = new TerminalLogConfig();
        config.setEnabled(true);
        config.setFormat(format);
        config.setCompress(true);
        config.setRotateDaily(true);
        config.setRetentionDays(0); // retention has its own test; keep this one deterministic
        return config;
    }

    private TerminalLogger logger(TerminalLogConfig config) {
        return new TerminalLogger(config, "web01", tempDir, clock);
    }

    private List<Path> files() throws IOException {
        try (var stream = Files.list(tempDir)) {
            return stream.sorted().toList();
        }
    }

    private String readArchive(Path file) throws IOException {
        if (!file.getFileName().toString().endsWith(".gz")) {
            return Files.readString(file, StandardCharsets.UTF_8);
        }
        try (InputStream in = new GZIPInputStream(Files.newInputStream(file))) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void writesTheOutputToAFileNamedAfterTheServerAndTheMoment() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.PLAIN_TEXT));
        logger.start();
        logger.log("hello world\n");
        logger.awaitQuiet(2000);
        logger.stop();

        List<Path> files = files();
        assertThat(files).hasSize(1);
        assertThat(files.get(0).getFileName().toString())
            .isEqualTo("2026-08-04-14-30-12-web01_1.log.gz");
        assertThat(readArchive(files.get(0))).contains("hello world");
    }

    @Test
    void keepsEverythingCapturedRightBeforeADisconnect() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.PLAIN_TEXT));
        logger.start();
        for (int i = 0; i < 500; i++) {
            logger.log("line-" + i + "\n");
        }
        // No awaitQuiet: stop() has to drain the queue itself. The previous implementation
        // interrupted the writer thread first and lost whatever was still in flight.
        logger.stop();

        String content = readArchive(files().get(0));
        assertThat(content).contains("line-0");
        assertThat(content).contains("line-499");
    }

    @Test
    void writesAPendingPromptThatNeverGotItsNewline() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.PLAIN_TEXT));
        logger.start();
        logger.log("daniel@web01:~$ ");
        logger.stop();

        assertThat(readArchive(files().get(0))).contains("daniel@web01:~$");
    }

    @Test
    void preservesUmlautsAndIndentation() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.PLAIN_TEXT));
        logger.start();
        logger.log("    drwxr-xr-x  Größe Übergrößenträger\n");
        logger.log("[32mgrün[0m\n");
        logger.awaitQuiet(2000);
        logger.stop();

        String content = readArchive(files().get(0));
        // The old sanitizer kept only ASCII 32..126 and trimmed every line, so both were lost.
        assertThat(content).contains("Größe Übergrößenträger");
        assertThat(content).contains("    drwxr-xr-x");
        // Colour codes still have no business in a log file.
        assertThat(content).contains("grün");
        assertThat(content).doesNotContain("");
    }

    @Test
    void collapsesAProgressBarInsteadOfLoggingEveryFrame() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.PLAIN_TEXT));
        logger.start();
        logger.log("progress 10%\rprogress 50%\rprogress 100%\n");
        logger.awaitQuiet(2000);
        logger.stop();

        String content = readArchive(files().get(0));
        assertThat(content).contains("progress 100%");
        assertThat(content).doesNotContain("progress 10%");
    }

    @Test
    void rollsOverAtMidnightEvenWhenTheConnectionIsIdle() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.PLAIN_TEXT));
        logger.start();
        logger.log("before midnight\n");
        logger.awaitQuiet(2000);

        clock.advance(Duration.ofHours(10)); // 2026-08-05T00:30Z, no output in between
        Thread.sleep(400);                   // let the writer thread notice the new day

        logger.log("after midnight\n");
        logger.awaitQuiet(2000);
        logger.stop();

        List<Path> files = files();
        assertThat(files).hasSize(2);
        assertThat(files.get(0).getFileName().toString()).startsWith("2026-08-04-");
        assertThat(files.get(1).getFileName().toString()).startsWith("2026-08-05-");
        assertThat(readArchive(files.get(0))).contains("before midnight");
        assertThat(readArchive(files.get(0))).doesNotContain("after midnight");
        assertThat(readArchive(files.get(1))).contains("after midnight");
    }

    @Test
    void keepsItsSequenceNumberAcrossTheMidnightRoll() throws Exception {
        // Another connection already holds _1, so this session is _2 and must stay _2.
        Files.createFile(tempDir.resolve("2026-08-04-14-30-12-web01_1.log.gz"));

        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.PLAIN_TEXT));
        logger.start();
        logger.log("day one\n");
        logger.awaitQuiet(2000);
        clock.advance(Duration.ofHours(10));
        Thread.sleep(400);
        logger.log("day two\n");
        logger.awaitQuiet(2000);
        logger.stop();

        assertThat(files().stream()
            .map(path -> path.getFileName().toString())
            .filter(name -> name.startsWith("2026-08-05-"))
            .toList())
            .containsExactly("2026-08-05-00-30-12-web01_2.log.gz");
    }

    @Test
    void producesAnXmlArchiveAParserAccepts() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.XML));
        logger.start();
        logger.log("plain line\n");
        logger.log("hostile ]]> & <tag> \"quoted\"\n");
        logger.awaitQuiet(2000);
        logger.stop();

        Path archive = files().get(0);
        assertThat(archive.getFileName().toString()).endsWith(".xml.gz");
        String xml = readArchive(archive);
        // The previous implementation wrote the header twice, so this never parsed at all.
        assertThat(xml.indexOf("<?xml")).isEqualTo(xml.lastIndexOf("<?xml"));

        try (InputStream in = new GZIPInputStream(Files.newInputStream(archive))) {
            var document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(in);
            var entries = document.getElementsByTagName("entry");
            assertThat(entries.getLength()).isEqualTo(2);
            assertThat(entries.item(1).getTextContent()).contains("]]>");
        }
    }

    @Test
    void producesAJsonArchiveAParserAccepts() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.JSON));
        logger.start();
        logger.log("first\n");
        logger.log("with \"quotes\" and \\ backslash\n");
        logger.awaitQuiet(2000);
        logger.stop();

        String json = readArchive(files().get(0));
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        assertThat(root.get("connection").getAsString()).isEqualTo("web01");
        assertThat(root.getAsJsonArray("entries")).hasSize(2);
        assertThat(root.getAsJsonArray("entries").get(1).getAsJsonObject().get("line").getAsString())
            .isEqualTo("with \"quotes\" and \\ backslash");
    }

    @Test
    void writesValidJsonEvenWhenTheConnectionProducedNothing() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.JSON));
        logger.start();
        logger.log("one line\n");
        logger.awaitQuiet(2000);
        logger.stop();

        JsonObject root = JsonParser.parseString(readArchive(files().get(0))).getAsJsonObject();
        assertThat(root.getAsJsonArray("entries")).hasSize(1);
    }

    @Test
    void createsNoFileAtAllWhenTheHostStaysSilent() throws Exception {
        TerminalLogger logger = logger(config(TerminalLogConfig.LogFormat.PLAIN_TEXT));
        logger.start();
        Thread.sleep(300);
        logger.stop();

        // A lazily opened file means a quiet connection leaves no empty archive behind.
        assertThat(files()).isEmpty();
    }

    @Test
    void rotatesOnSizeIntoNumberedPartsWithoutLosingTheFirstOne() throws Exception {
        TerminalLogConfig config = config(TerminalLogConfig.LogFormat.PLAIN_TEXT);
        config.setMaxFileSizeMB(1);
        TerminalLogger logger = logger(config);
        logger.start();
        String chunk = "x".repeat(1000);
        for (int i = 0; i < 1200; i++) {
            logger.log(chunk + "\n");
        }
        logger.stop();

        List<String> names = files().stream().map(path -> path.getFileName().toString()).toList();
        // The old rotateLog() deleted the file instead; both parts must survive.
        assertThat(names.size()).isAtLeast(2);
        assertThat(names.stream().anyMatch(name -> name.contains(".p2."))).isTrue();
    }

    @Test
    void leavesTheFilePlainWhenCompressionIsTurnedOff() throws Exception {
        TerminalLogConfig config = config(TerminalLogConfig.LogFormat.PLAIN_TEXT);
        config.setCompress(false);
        TerminalLogger logger = logger(config);
        logger.start();
        logger.log("uncompressed\n");
        logger.awaitQuiet(2000);
        logger.stop();

        assertThat(files().get(0).getFileName().toString()).endsWith(".log");
        assertThat(readArchive(files().get(0))).contains("uncompressed");
    }
}
