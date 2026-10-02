package de.kortty.persistence;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/**
 * The session id and the history file name both come from the project XML, which may have been
 * shared by someone else. Only plain names inside {@code history/} may ever be read, written or
 * deleted — never a file the name points at through {@code ..}, an absolute path or a sub-folder.
 */
class HistoryStorageTest {

    private Path configDir;

    private Path outsideFile;

    private HistoryStorage storage;

    @BeforeMethod
    void setUp() throws IOException {
        configDir = Files.createTempDirectory("kortty-history-storage");
        storage = new HistoryStorage(configDir);
        // A valid gzip file next to history/, the kind a traversal name would reach.
        outsideFile = configDir.resolve("outside.history.gz");
        writeGzip(outsideFile, "secret outside the history directory");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (configDir == null || !Files.exists(configDir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(configDir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void savesLoadsAndDeletesAPlainHistoryFile() throws IOException {
        String sessionId = UUID.randomUUID().toString();

        String fileName = storage.saveHistory(sessionId, "prompt$ ls\nfile.txt\n");

        assertThat(fileName).isEqualTo(sessionId + ".history.gz");
        assertThat(Files.exists(configDir.resolve("history").resolve(fileName))).isTrue();
        assertThat(storage.loadHistory(fileName)).isEqualTo("prompt$ ls\nfile.txt\n");

        storage.deleteHistory(fileName);

        assertThat(Files.exists(configDir.resolve("history").resolve(fileName))).isFalse();
    }

    @Test
    void aMissingPlainHistoryFileLoadsAsNull() throws IOException {
        assertThat(storage.loadHistory(UUID.randomUUID() + ".history.gz")).isNull();
    }

    @Test
    void loadRefusesEveryNameThatLeavesTheHistoryDirectory() {
        for (String name : traversalNames()) {
            assertThrows("loadHistory accepted " + name, IOException.class, () -> storage.loadHistory(name));
        }
    }

    @Test
    void deleteRefusesEveryNameThatLeavesTheHistoryDirectory() {
        for (String name : traversalNames()) {
            assertThrows("deleteHistory accepted " + name, IOException.class, () -> storage.deleteHistory(name));
            assertWithMessage("deleteHistory(" + name + ") removed a file outside history/")
                .that(Files.exists(outsideFile)).isTrue();
        }
    }

    @Test
    void saveRefusesSessionIdsThatWouldNameAFileOutsideTheHistoryDirectory() throws IOException {
        List<String> sessionIds = new ArrayList<>(List.of(
            "../outside", "../../escaped", "/tmp/kortty-escaped", "sub/dir", "a\\b", "a.b", "..", "", " ",
            "id with space", "id\u0000nul", outsideFile.toAbsolutePath().toString()));
        sessionIds.add(null);

        for (String sessionId : sessionIds) {
            assertThrows("saveHistory accepted session id " + sessionId, IOException.class,
                () -> storage.saveHistory(sessionId, "overwritten"));
        }

        assertThat(Files.exists(configDir.resolve("escaped.history.gz"))).isFalse();
        assertThat(Files.exists(configDir.getParent().resolve("escaped.history.gz"))).isFalse();
        try (Stream<Path> stored = Files.list(configDir.resolve("history"))) {
            assertThat(stored.toList()).isEmpty();
        }
    }

    @Test
    void namePredicatesAcceptOnlyPlainIdsAndFileNames() {
        String uuid = UUID.randomUUID().toString();

        assertThat(HistoryStorage.isValidSessionId(uuid)).isTrue();
        assertThat(HistoryStorage.isValidHistoryFileName(uuid + ".history.gz")).isTrue();
        assertThat(HistoryStorage.isValidSessionId("../" + uuid)).isFalse();
        assertThat(HistoryStorage.isValidSessionId(null)).isFalse();
        assertThat(HistoryStorage.isValidHistoryFileName(uuid)).isFalse();
        assertThat(HistoryStorage.isValidHistoryFileName(uuid + ".history.gz.bak")).isFalse();
        assertThat(HistoryStorage.isValidHistoryFileName(null)).isFalse();
    }

    private List<String> traversalNames() {
        List<String> names = new ArrayList<>(List.of(
            "../outside.history.gz",
            "../../outside.history.gz",
            "./../outside.history.gz",
            "sub/../../outside.history.gz",
            "x.history.gz/../../outside.history.gz",
            "..\\outside.history.gz",
            "sub/inner.history.gz",
            outsideFile.toAbsolutePath().toString(),
            "/etc/passwd",
            "..",
            ".",
            "",
            ".history.gz",
            "a.b.history.gz",
            "outside.history.gz.bak",
            "outside.txt"));
        names.add(null);
        return names;
    }

    private static void writeGzip(Path file, String content) throws IOException {
        try (OutputStream out = Files.newOutputStream(file);
             GZIPOutputStream gzip = new GZIPOutputStream(out);
             Writer writer = new OutputStreamWriter(gzip, StandardCharsets.UTF_8)) {
            writer.write(content);
        }
    }
}
