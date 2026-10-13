package de.kortty.core;

import com.google.gson.JsonParser;
import de.kortty.model.TerminalLogBadge;
import de.kortty.model.TerminalLogConfig;
import de.kortty.model.TerminalLogConfig.LogFormat;
import java.io.BufferedReader;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDate;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

public class TerminalLogEnhancementsTest {
    private static final String STAMP = "2026-01-01 12:00:00.000";
    private static Map<String, String> fields(String line) {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("timestamp", STAMP); result.put("line", line);
        result.put("username", "alice"); result.put("directory", "../work"); result.put("directoryBase", "/home/alice");
        return result;
    }
    private static String document(LogFormat format) {
        return TerminalLogDocument.header(format, Map.of("connection", "192.0.2.1", "host", "192.0.2.1"))
            + TerminalLogDocument.entry(format, fields("one <&> \"quoted\"\nsecond"))
            + (format == LogFormat.JSON ? ",\n" : "\n")
            + TerminalLogDocument.entry(format, fields("two"))
            + (format == LogFormat.JSON ? "" : "\n") + TerminalLogDocument.footer(format, 2);
    }
    private static String convert(String input, LogFormat from, LogFormat to, BitSet selection) throws Exception {
        StringWriter out = new StringWriter();
        long count = TerminalLogDocument.convert(new BufferedReader(new StringReader(input)), out,
            from, to, "fallback", null, selection, () -> false);
        assertThat(count).isGreaterThan(0L);
        return out.toString();
    }
    @Test public void literalContextPrefixInTerminalOutputIsNeverMistakenForMetadata() throws Exception {
        String literal = "@kortty-context {\"username\": \"literal\"}\toutput";
        for (boolean legacy : List.of(false, true)) {
            String plain = legacy ? "[" + STAMP + "] " + literal + "\n"
                : TerminalLogDocument.header(LogFormat.PLAIN_TEXT, Map.of("connection", "demo"))
                    + TerminalLogDocument.entry(LogFormat.PLAIN_TEXT, fields(literal)) + "\n";
            var entry = JsonParser.parseString(convert(plain, LogFormat.PLAIN_TEXT, LogFormat.JSON, null))
                .getAsJsonObject().getAsJsonArray("entries").get(0).getAsJsonObject();
            assertThat(entry.get("line").getAsString()).isEqualTo(literal);
        }
    }

    @Test public void roundTripsAllFormatsWithoutLosingContextOrEscaping() throws Exception {
        for (LogFormat from : LogFormat.values()) {
            for (LogFormat to : LogFormat.values()) {
                var object = JsonParser.parseString(convert(convert(document(from), from, to, null), to, LogFormat.JSON, null)).getAsJsonObject();
                assertThat(object.get("host").getAsString()).isEqualTo("192.0.2.1");
                var entries = object.getAsJsonArray("entries");
                assertThat(entries.size()).isEqualTo(2);
                assertThat(entries.get(0).getAsJsonObject().get("line").getAsString()).isEqualTo(fields("one <&> \"quoted\"\nsecond").get("line"));
                assertThat(entries.get(0).getAsJsonObject().get("directory").getAsString()).isEqualTo("../work");
            }
        }
    }
    @Test public void formattedSelectionExportsOriginalCompleteEntry() throws Exception {
        Path dir = Files.createTempDirectory("log-format-test");
        try {
            Path raw = dir.resolve("raw.json"), pretty = dir.resolve("pretty.json"), map = dir.resolve("map.bin");
            Files.writeString(raw, document(LogFormat.JSON));
            TerminalLogPresentation.formatJson(raw, pretty, map, () -> false);
            List<String> lines = Files.readAllLines(pretty);
            int selected = java.util.stream.IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains("one <&>")).findFirst().orElseThrow();
            BitSet display = new BitSet(); display.set(selected);
            BitSet original = TerminalLogPresentation.sourceSelection(map, display);
            var result = JsonParser.parseString(convert(document(LogFormat.JSON), LogFormat.JSON, LogFormat.JSON, original)).getAsJsonObject();
            assertThat(result.getAsJsonArray("entries").size()).isEqualTo(1);
            assertThat(JsonParser.parseString(Files.readString(pretty))).isEqualTo(JsonParser.parseString(document(LogFormat.JSON)));
        } finally { SessionJournalExportProtection.deleteRecursively(dir); }
    }
    @Test public void formattedXmlSelectionMapsBackWithoutAddingDisplayWhitespace() throws Exception {
        Path dir = Files.createTempDirectory("log-xml-view-test");
        try {
            Path raw = dir.resolve("raw.xml"), pretty = dir.resolve("pretty.xml"), map = dir.resolve("map.bin");
            Files.writeString(raw, document(LogFormat.XML));
            TerminalLogPresentation.formatXml(raw, pretty, map, () -> false);
            var lines = Files.readAllLines(pretty);
            int row = java.util.stream.IntStream.range(0, lines.size()).filter(i -> lines.get(i).contains("username=")).findFirst().orElseThrow();
            BitSet displayed = new BitSet(); displayed.set(row);
            BitSet selected = TerminalLogPresentation.sourceSelection(map, displayed);
            String json = convert(document(LogFormat.XML), LogFormat.XML, LogFormat.JSON, selected);
            var entries = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("entries");
            assertThat(entries.size()).isEqualTo(1);
            assertThat(entries.get(0).getAsJsonObject().get("line").getAsString()).isEqualTo("one <&> \"quoted\"\nsecond");
            assertThat(lines.size()).isGreaterThan(document(LogFormat.XML).split("\n").length);
        } finally { SessionJournalExportProtection.deleteRecursively(dir); }
    }

    @Test public void cancelledAndInvalidExportsLeaveNoTargetOrTemporaryFile() throws Exception {
        Path dir = Files.createTempDirectory("log-cancel-test");
        try {
            var file = fixture(dir, LogFormat.JSON, TerminalLogNaming.Compression.NONE);
            try (var options = new TerminalLogExportOptions(LogFormat.XML, TerminalLogExportOptions.Archive.GZIP, null, null)) {
                Path target = dir.resolve("cancelled.xml.gz");
                assertThat(TerminalLogExporter.exportOne(file, target, options, false, null, null, List.of(), null, () -> true).status())
                    .isEqualTo(TerminalLogExporter.Status.CANCELLED);
                assertThat(Files.exists(target)).isFalse();
                Files.writeString(file.path(), "{\"timestamp\": broken}\n");
                Files.setLastModifiedTime(file.path(), file.modified());
                assertThat(TerminalLogExporter.exportOne(file, target, options, false, null, null, List.of(), null, null).status())
                    .isEqualTo(TerminalLogExporter.Status.FAILED);
                try (var files = Files.list(dir)) { assertThat(files.count()).isEqualTo(1); }
            }
        } finally { SessionJournalExportProtection.deleteRecursively(dir); }
    }

    @Test public void rejectsExternalEntitiesAndMalformedInput() {
        assertThrows(java.io.IOException.class, () -> convert("<!DOCTYPE terminal-log SYSTEM \"file:///etc/passwd\">\n" + document(LogFormat.XML), LogFormat.XML, LogFormat.JSON, null));
        assertThrows(java.io.IOException.class, () -> convert("{\"timestamp\": garbage}\n", LogFormat.JSON, LogFormat.XML, null));
    }
    @Test public void exportsEachArchiveAndEncryptedZip() throws Exception {
        Path dir = Files.createTempDirectory("log-export-test");
        try {
            TerminalLogFile file = fixture(dir, LogFormat.JSON, TerminalLogNaming.Compression.NONE);
            for (var archive : TerminalLogExportOptions.Archive.values()) {
                try (var options = new TerminalLogExportOptions(LogFormat.XML, archive,
                        archive == TerminalLogExportOptions.Archive.ZIP ? "test-only-password".toCharArray() : null, null)) {
                    Path target = dir.resolve(TerminalLogExporter.targetName(file, options, null, false));
                    var result = TerminalLogExporter.exportOne(file, target, options, false, null, null, List.of(dir), null, null);
                    assertThat(result.status()).isEqualTo(TerminalLogExporter.Status.OK);
                    String xml;
                    if (archive == TerminalLogExportOptions.Archive.ZIP) {
                        try (var zip = new net.lingala.zip4j.ZipFile(target.toFile(), "test-only-password".toCharArray())) {
                            assertThat(zip.isEncrypted()).isTrue();
                            try (var in = zip.getInputStream(zip.getFileHeaders().get(0))) { xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                        }
                    } else {
                        try (var in = TerminalLogCodecs.openInput(target)) { xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                    }
                    assertThat(JsonParser.parseString(convert(xml, LogFormat.XML, LogFormat.JSON, null)).getAsJsonObject().getAsJsonArray("entries").size()).isEqualTo(2);
                    assertThat(TerminalLogExporter.exportOne(file, target, options, false, null, null, List.of(), null, null).status()).isEqualTo(TerminalLogExporter.Status.EXISTS);
                }
            }
        } finally { SessionJournalExportProtection.deleteRecursively(dir); }
    }
    @Test public void holdSurvivesCompressionAndBlocksBothDeletionPathsAndIsSearchable() throws Exception {
        Path dir = Files.createTempDirectory("log-hold-test");
        try {
            TerminalLogFile file = fixture(dir, LogFormat.JSON, TerminalLogNaming.Compression.GZIP);
            var hold = TerminalLogBadge.defaults().get(0);
            TerminalLogBadges.write(file.path(), List.of(hold));
            assertThat(TerminalLogBadges.sidecar(file.path())).isEqualTo(TerminalLogBadges.sidecar(file.path().resolveSibling(file.name().replace(".gz", ""))));
            assertThat(TerminalLogRetention.sweep(dir, 1, LocalDate.of(2026, 10, 13), java.util.Set.of())).isEqualTo(0);
            assertThat(TerminalLogFiles.delete(List.of(file), Instant.now()).get(0).status()).isEqualTo(TerminalLogFiles.DeleteStatus.SKIPPED_HOLD);
            var scanned = TerminalLogCatalog.scan(List.of(new TerminalLogCatalog.Folder(dir, dir, 1, true)), LocalDate.now(), Instant.now()).get(0);
            assertThat(scanned.deletableFrom()).isNull();
            assertThat(new TerminalLogFilter("HoLd", null, null, null).test(scanned)).isTrue();
            assertThat(scanned.displayConnectionName()).isNotEqualTo("192.0.2.1");
            Files.writeString(TerminalLogBadges.sidecar(file.path()), "broken");
            assertThat(TerminalLogRetention.sweep(dir, 1, LocalDate.of(2026, 10, 13), java.util.Set.of())).isEqualTo(0);
            TerminalLogBadges.write(file.path(), List.of());
            assertThat(TerminalLogRetention.sweep(dir, 1, LocalDate.of(2026, 10, 13), java.util.Set.of())).isEqualTo(1);
        } finally { SessionJournalExportProtection.deleteRecursively(dir); }
    }
    @Test public void aliasesAreStableAndDoNotReplaceRealNames() {
        assertThat(TerminalLogIdentity.displayName("192.0.2.1", "192.0.2.1")).isEqualTo(TerminalLogIdentity.displayName("192.0.2.1", "192.0.2.1"));
        assertThat(TerminalLogIdentity.displayName("production", "192.0.2.1")).isEqualTo("production");
        assertThat(TerminalLogIdentity.displayName("192.0.2.1", "server.example")).isEqualTo("192.0.2.1");
        assertThat(TerminalLogger.relativeDirectory("/home/alice", "/home/work")).isEqualTo("../work");
        assertThat(TerminalLogger.relativeDirectory("/", "/srv")).isEqualTo("srv");
    }
    @Test public void capturesUsernameAndDirectoryBeforeQueueDrain() throws Exception {
        Path dir = Files.createTempDirectory("log-context-test");
        try {
            TerminalLogConfig config = new TerminalLogConfig();
            config.setFormat(LogFormat.JSON); config.setCompress(false); config.setRetentionDays(0);
            config.setIncludeUsername(true); config.setDirectoryMode(TerminalLogConfig.DirectoryMode.RELATIVE);
            TerminalLogger log = new TerminalLogger(config, "demo", dir, java.time.Clock.systemUTC(), new SessionJournalRedactor());
            log.setHost("192.0.2.9"); log.start();
            log.log("first\n", "alice", "/home/alice");
            log.log("second\n", "alice", "/srv/app"); log.stop();
            try (var paths = Files.list(dir)) {
                Path file = paths.filter(path -> path.toString().endsWith(".json")).findFirst().orElseThrow();
                var entries = JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonArray("entries");
                assertThat(entries.get(0).getAsJsonObject().get("directory").getAsString()).isEqualTo(".");
                assertThat(entries.get(1).getAsJsonObject().get("directory").getAsString()).isEqualTo("../../srv/app");
                assertThat(entries.get(0).getAsJsonObject().get("username").getAsString()).isEqualTo("alice");
            }
        } finally { SessionJournalExportProtection.deleteRecursively(dir); }
    }

    @Test public void gpgWrapsPasswordZipAndRoundTripsWithAnIsolatedTestKey() throws Exception {
        String gpg = SessionJournalExportProtection.resolveGpg().orElseThrow(() -> new org.testng.SkipException("GPG unavailable"));
        Path dir = SessionJournalExportProtection.privateTempDir("log-gpg-test-");
        try {
            runGpg(gpg, dir, "--pinentry-mode", "loopback", "--passphrase", "", "--quick-generate-key", "korTTY Test <test@example.invalid>", "rsa2048", "encr", "1d");
            Path publicKey = dir.resolve("test-key.asc");
            runGpg(gpg, dir, "--armor", "--output", publicKey.toString(), "--export", "test@example.invalid");
            var file = fixture(dir, LogFormat.JSON, TerminalLogNaming.Compression.NONE);
            for (var archive : TerminalLogExportOptions.Archive.values()) {
                try (var options = new TerminalLogExportOptions(LogFormat.XML, archive,
                        archive == TerminalLogExportOptions.Archive.ZIP ? "test-only-password".toCharArray() : null,
                        SessionJournalExportProtection.Protection.gpg("test@example.invalid", publicKey.toString()))) {
                    Path encrypted = dir.resolve("export" + options.suffix());
                    assertThat(TerminalLogExporter.exportOne(file, encrypted, options, false, null, null, List.of(), null, null).status())
                        .isEqualTo(TerminalLogExporter.Status.OK);
                    Path plain = dir.resolve("decrypted" + archive.suffix);
                    runGpg(gpg, dir, "--pinentry-mode", "loopback", "--passphrase", "", "--output", plain.toString(), "--decrypt", encrypted.toString());
                    String xml;
                    if (archive == TerminalLogExportOptions.Archive.ZIP) {
                        try (var zip = new net.lingala.zip4j.ZipFile(plain.toFile(), "test-only-password".toCharArray());
                             var in = zip.getInputStream(zip.getFileHeaders().get(0))) {
                            xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                        }
                    } else {
                        try (var in = TerminalLogCodecs.openInput(plain)) { xml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
                    }
                    assertThat(xml).contains("<terminal-log");
                }
            }
        } finally { SessionJournalExportProtection.deleteRecursively(dir); }
    }

    private static void runGpg(String executable, Path home, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of(executable, "--no-options", "--homedir", home.toString(), "--batch", "--yes"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD).start();
        try {
            if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { throw new java.io.IOException("GPG test timed out"); }
            assertThat(process.exitValue()).isEqualTo(0);
        } finally { if (process.isAlive()) { process.destroyForcibly(); } }
    }

    private static TerminalLogFile fixture(Path dir, LogFormat format, TerminalLogNaming.Compression compression) throws Exception {
        Path file = dir.resolve("2026-01-01-12-00-00-demo_1." + format.getExtension() + compression.suffix);
        try (var out = TerminalLogCodecs.openOutput(file, compression)) { out.write(document(format).getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        FileTime old = FileTime.from(Instant.parse("2026-01-01T12:01:00Z")); Files.setLastModifiedTime(file, old);
        return new TerminalLogFile(file, dir, TerminalLogNaming.parse(file.getFileName().toString()), Files.size(file), old, null, false);
    }
}
