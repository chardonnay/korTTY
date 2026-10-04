package de.kortty.core.sftp.transfer;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/** The resume index: owner-only, no paths or content in the file, survives a restart, prunes, tolerates junk. */
class ResumeIndexTest {

    private Path dir;

    @BeforeMethod
    void setUp() throws IOException {
        dir = Files.createTempDirectory("kortty-resume-index-");
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static ResumeIndex.Key key(String remote) {
        return ResumeIndex.Key.of("conn-42", TransferDirection.DOWNLOAD, remote, Path.of("/home/me/secret-folder/q3-report.pdf"));
    }

    @Test
    void theFileIsOwnerOnlyAndHoldsNoPathsOrContent() throws IOException {
        ResumeIndex index = ResumeIndex.inConfigDir(dir.resolve("config"));
        index.recordStart(key("/srv/private/q3-report.pdf"), 1234, 5678, "uid:1000");

        Path file = dir.resolve("config").resolve(ResumeIndex.FILE_NAME);
        String json = Files.readString(file);
        assertThat(json).contains("\"sourceSize\":1234");
        assertThat(json).doesNotContain("q3-report");
        assertThat(json).doesNotContain("secret-folder");
        assertThat(json).doesNotContain("/srv/private");
        assertThat(json).doesNotContain("conn-42");
        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
        }
    }

    @Test
    void entriesSurviveANewInstanceAndRemoveForgetsThem() {
        Path file = dir.resolve(ResumeIndex.FILE_NAME);
        ResumeIndex first = new ResumeIndex(file);
        first.recordStart(key("/a"), 10, 20, null);
        first.put(key("/b"), new ResumeIndex.Entry(1, 2, 3, 4, "owner:me", first.now()));

        ResumeIndex second = new ResumeIndex(file);
        assertThat(second.get(key("/a")).orElseThrow().partStateKnown()).isFalse();
        assertThat(second.get(key("/b")).orElseThrow())
            .isEqualTo(first.get(key("/b")).orElseThrow());
        second.remove(key("/a"));
        assertThat(new ResumeIndex(file).get(key("/a"))).isEmpty();
        assertThat(new ResumeIndex(file).size()).isEqualTo(1);
    }

    @Test
    void keysDifferInEveryComponent() {
        ResumeIndex.Key base = new ResumeIndex.Key("c", TransferDirection.UPLOAD, "/r", "/l");
        assertThat(base.id()).isNotEqualTo(new ResumeIndex.Key("d", TransferDirection.UPLOAD, "/r", "/l").id());
        assertThat(base.id()).isNotEqualTo(new ResumeIndex.Key("c", TransferDirection.DOWNLOAD, "/r", "/l").id());
        assertThat(base.id()).isNotEqualTo(new ResumeIndex.Key("c", TransferDirection.UPLOAD, "/s", "/l").id());
        assertThat(base.id()).isNotEqualTo(new ResumeIndex.Key("c", TransferDirection.UPLOAD, "/r", "/m").id());
        assertThat(new ResumeIndex.Key("a/b", TransferDirection.UPLOAD, "c", "/l").id())
            .isNotEqualTo(new ResumeIndex.Key("a", TransferDirection.UPLOAD, "b/c", "/l").id());
    }

    @Test
    void oldEntriesArePruned() {
        Path file = dir.resolve(ResumeIndex.FILE_NAME);
        Instant start = Instant.parse("2026-01-01T00:00:00Z");
        new ResumeIndex(file, Clock.fixed(start, ZoneOffset.UTC)).recordStart(key("/old"), 1, 1, null);
        Clock later = Clock.fixed(start.plus(ResumeIndex.MAX_AGE).plus(Duration.ofDays(1)), ZoneOffset.UTC);
        assertThat(new ResumeIndex(file, later).get(key("/old"))).isEmpty();
    }

    @Test
    void aCorruptFileIsMovedAsideAndTheIndexStartsEmpty() throws IOException {
        Path file = Files.writeString(dir.resolve(ResumeIndex.FILE_NAME), "{not json");
        ResumeIndex index = new ResumeIndex(file);
        assertThat(index.get(key("/a"))).isEmpty();
        index.recordStart(key("/a"), 1, 2, null);
        assertThat(new ResumeIndex(file).get(key("/a"))).isPresent();
    }
}
