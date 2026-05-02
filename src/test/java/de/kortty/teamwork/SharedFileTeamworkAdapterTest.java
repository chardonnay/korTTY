package de.kortty.teamwork;

import org.testng.SkipException;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;


/**
 * Unit tests for SharedFileTeamworkAdapter.toPath() to prevent regressions
 * on UNC, legacy file:////host/Share, and file:///C|/path handling.
 */
public class SharedFileTeamworkAdapterTest {

    @Test
    void toPath_fileHostShare_preservesUnc() {
        skipUnlessWindows();

        Path p = SharedFileTeamworkAdapter.toPath("file://host/Share");
        assertThat(p).isNotNull();
        assertWithMessage("UNC should start with //").that(p.toString().replace('\\', '/').startsWith("//")).isTrue();
        assertWithMessage("UNC should contain host").that(p.toString().contains("host")).isTrue();
        assertWithMessage("UNC should contain Share").that(p.toString().contains("Share")).isTrue();
    }

    @Test
    void toPath_fileFourSlashHostShare_preservesUnc() {
        skipUnlessWindows();

        Path p = SharedFileTeamworkAdapter.toPath("file:////host/Share");
        assertThat(p).isNotNull();
        String s = p.toString().replace('\\', '/');
        assertWithMessage("Legacy file://// should yield path starting with //").that(s.startsWith("//")).isTrue();
        assertWithMessage("Path should contain host").that(s.contains("host")).isTrue();
        assertWithMessage("Path should contain Share").that(s.contains("Share")).isTrue();
    }

    @Test
    void toPath_filePipeDrive_normalizesToColon() {
        Path p = SharedFileTeamworkAdapter.toPath("file:///C|/path/to/file");
        assertThat(p).isNotNull();
        String s = p.toString().replace('\\', '/');
        assertWithMessage("C| should be normalized to C:").that(s.contains("C:")).isTrue();
        assertWithMessage("Pipe should not appear in path").that(s.contains("|")).isFalse();
        assertWithMessage("Path segment should be preserved").that(s.contains("path")).isTrue();
    }

    @Test
    void toPath_nullOrBlank_returnsNull() {
        assertThat(SharedFileTeamworkAdapter.toPath(null)).isNull();
        assertThat(SharedFileTeamworkAdapter.toPath("")).isNull();
        assertThat(SharedFileTeamworkAdapter.toPath("   ")).isNull();
    }

    @Test
    void toPath_plainPath_unchanged() {
        Path p = SharedFileTeamworkAdapter.toPath("/home/user/file");
        assertThat(p).isNotNull();
        assertThat(p).isEqualTo(Paths.get("/home/user/file"));
    }

    @Test
    void teamworkRecycleBinServiceJaxbContextLoads() throws Exception {
        Path dir = Files.createTempDirectory("kortty-recycle-test");
        try {
            TeamworkRecycleBinService service = new TeamworkRecycleBinService(dir);
            service.load();
            assertThat(service.getDeleted().isEmpty()).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to delete temp path " + path, e);
                    }
                });
        }
    }

    private static void skipUnlessWindows() {
        if (!System.getProperty("os.name").toLowerCase().contains("win")) {
            throw new SkipException("Windows-only UNC path test");
        }
    }
}
