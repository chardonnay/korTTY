package de.kortty.shellintegration;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The folder of a local shell's shell-integration wrapper: owner-only from the start, never reused,
 * written only inside itself, deleted with its connector, and swept after a crash without touching
 * anything that is not a wrapper folder.
 */
class ShellIntegrationWrapperDirectoryTest {

    private Path temp;

    private Path root;

    @BeforeMethod
    void setUp() throws IOException {
        temp = Files.createTempDirectory("kortty-si-dir");
        root = temp.resolve("config").resolve(ShellIntegrationWrapperDirectory.ROOT_NAME);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        try (var walk = Files.walk(temp)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void createsANewFolderInTheRootAndWritesTheFiles() throws IOException {
        ShellIntegrationWrapperDirectory directory = ShellIntegrationWrapperDirectory.create(root);

        assertThat(directory.path().getParent()).isEqualTo(root);
        assertThat(directory.path().getFileName().toString()).matches("tab-[0-9a-f]{32}");
        directory.write(Map.of("bashrc", "echo é\n", "zsh/.zshrc", "true\n"));

        assertThat(Files.readString(directory.path().resolve("bashrc"), StandardCharsets.UTF_8)).isEqualTo("echo é\n");
        assertThat(Files.readString(directory.path().resolve("zsh/.zshrc"))).isEqualTo("true\n");
        assertThat(directory.shellPath()).doesNotContain("\\");
        assertThat(directory.shellPath()).endsWith(directory.path().getFileName().toString());
    }

    @Test
    void neverReusesAFolder() throws IOException {
        ShellIntegrationWrapperDirectory first = ShellIntegrationWrapperDirectory.create(root);
        ShellIntegrationWrapperDirectory second = ShellIntegrationWrapperDirectory.create(root);
        assertThat(second.path()).isNotEqualTo(first.path());
    }

    @Test
    void isOwnerOnlyWhereTheFileSystemHasPosixPermissions() throws IOException {
        if (!temp.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            throw new SkipException("No POSIX permissions on this file system");
        }
        ShellIntegrationWrapperDirectory directory = ShellIntegrationWrapperDirectory.create(root);
        directory.write(Map.of("bashrc", "x\n", "zsh/.zshenv", "y\n"));

        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(root))).isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.path())))
            .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.path().resolve("zsh"))))
            .isEqualTo("rwx------");
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(directory.path().resolve("bashrc"))))
            .isEqualTo("rw-------");
        assertThat(PosixFilePermissions.toString(
            Files.getPosixFilePermissions(directory.path().resolve("zsh/.zshenv")))).isEqualTo("rw-------");
    }

    @Test
    void writesOnlyNewFilesInsideItself() throws IOException {
        ShellIntegrationWrapperDirectory directory = ShellIntegrationWrapperDirectory.create(root);
        directory.write(Map.of("bashrc", "x\n"));

        assertThrows(FileAlreadyExistsException.class, () -> directory.write(Map.of("bashrc", "y\n")));
        assertThat(Files.readString(directory.path().resolve("bashrc"))).isEqualTo("x\n");
        assertThrows(IOException.class, () -> directory.write(Map.of("../escape", "y\n")));
        assertThrows(IOException.class, () -> directory.write(Map.of(".", "y\n")));
        assertThat(Files.exists(root.resolve("escape"))).isFalse();
    }

    @Test
    void refusesARootThatIsASymbolicLink() throws IOException {
        Path target = Files.createDirectories(temp.resolve("elsewhere"));
        Files.createDirectories(root.getParent());
        try {
            Files.createSymbolicLink(root, target);
        } catch (UnsupportedOperationException | IOException e) {
            throw new SkipException("Cannot create symbolic links here: " + e);
        }
        assertThrows(IOException.class, () -> ShellIntegrationWrapperDirectory.create(root));
    }

    @Test
    void deleteRemovesTheFolderAndEverythingInIt() throws IOException {
        ShellIntegrationWrapperDirectory directory = ShellIntegrationWrapperDirectory.create(root);
        directory.write(Map.of("bashrc", "x\n", "zsh/.zshrc", "y\n"));

        assertThat(directory.delete()).isTrue();
        assertThat(Files.exists(directory.path())).isFalse();
        assertThat(Files.isDirectory(root)).isTrue();
        // A second delete, from the monitor thread and from close(), is harmless.
        assertThat(directory.delete()).isTrue();
    }

    @Test
    void deleteStaleRemovesOnlyOldWrapperFolders() throws IOException {
        ShellIntegrationWrapperDirectory old = ShellIntegrationWrapperDirectory.create(root);
        old.write(Map.of("bashrc", "x\n"));
        ShellIntegrationWrapperDirectory fresh = ShellIntegrationWrapperDirectory.create(root);
        Path unrelated = Files.createDirectory(root.resolve("not-a-wrapper"));
        Path unrelatedFile = Files.writeString(root.resolve("tab-file"), "keep");
        Instant now = Instant.parse("2026-10-04T12:00:00Z");
        Files.setLastModifiedTime(old.path(), FileTime.from(now.minus(Duration.ofHours(25))));
        Files.setLastModifiedTime(fresh.path(), FileTime.from(now.minus(Duration.ofHours(1))));
        Files.setLastModifiedTime(unrelated, FileTime.from(now.minus(Duration.ofDays(30))));
        Files.setLastModifiedTime(unrelatedFile, FileTime.from(now.minus(Duration.ofDays(30))));

        int deleted = ShellIntegrationWrapperDirectory.deleteStale(root, Duration.ofDays(1), now);

        assertThat(deleted).isEqualTo(1);
        assertThat(Files.exists(old.path())).isFalse();
        assertThat(Files.isDirectory(fresh.path())).isTrue();
        assertThat(Files.isDirectory(unrelated)).isTrue();
        assertThat(Files.readString(unrelatedFile)).isEqualTo("keep");
    }

    @Test
    void deleteStaleOfAMissingRootDoesNothing() {
        assertThat(ShellIntegrationWrapperDirectory.deleteStale(temp.resolve("missing"), Duration.ofDays(1),
            Instant.now())).isEqualTo(0);
    }
}
