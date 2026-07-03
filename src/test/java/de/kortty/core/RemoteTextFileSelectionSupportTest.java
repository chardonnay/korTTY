package de.kortty.core;

import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

public class RemoteTextFileSelectionSupportTest {

    @Test
    void normalizesSingleSelectedFileName() {
        assertThat(RemoteTextFileSelectionSupport.normalizeSelectedFileName("  notes.txt  "))
            .isEqualTo("notes.txt");
        assertThat(RemoteTextFileSelectionSupport.normalizeSelectedFileName("\"notes final.txt\""))
            .isEqualTo("notes final.txt");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsMultilineSelection() {
        RemoteTextFileSelectionSupport.normalizeSelectedFileName("one.txt\ntwo.txt");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsPathSeparatorsForSameDirectoryRule() {
        RemoteTextFileSelectionSupport.normalizeSelectedFileName("../secret.txt");
    }

    @Test
    void resolvesSelectionAgainstCurrentRemoteDirectory() {
        assertThat(RemoteTextFileSelectionSupport.resolveRemoteFilePath("/home/daniel/work", "notes.txt", "/home/daniel"))
            .isEqualTo("/home/daniel/work/notes.txt");
    }

    @Test
    void resolvesHomeRelativeWorkingDirectoryAgainstSftpStartDirectory() {
        assertThat(RemoteTextFileSelectionSupport.resolveRemoteFilePath("~/work", "notes.txt", "/home/daniel"))
            .isEqualTo("/home/daniel/work/notes.txt");
    }

    @Test
    void resolvesLocalSelectionAgainstStartDirectoryWhenNoWorkingDirectoryIsTracked() throws Exception {
        Path startDir = Path.of("some", "start", "dir").toAbsolutePath();
        assertThat(RemoteTextFileSelectionSupport.resolveLocalFilePath(null, "notes.txt", startDir.toString(), null))
            .isEqualTo(startDir.resolve("notes.txt"));
        assertThat(RemoteTextFileSelectionSupport.resolveLocalFilePath("  ", "notes.txt", startDir.toString(), null))
            .isEqualTo(startDir.resolve("notes.txt"));
    }

    @Test
    void resolvesLocalSelectionAgainstAbsoluteTrackedWorkingDirectory() throws Exception {
        Path tracked = Path.of("tracked", "work").toAbsolutePath();
        Path startDir = Path.of("unused", "start").toAbsolutePath();
        assertThat(RemoteTextFileSelectionSupport.resolveLocalFilePath(
                tracked.toString(), "notes.txt", startDir.toString(), null))
            .isEqualTo(tracked.resolve("notes.txt"));
    }

    @Test
    void resolvesLocalHomeRelativeWorkingDirectoryAgainstHomeDirectory() throws Exception {
        Path home = Path.of("home", "daniel").toAbsolutePath();
        Path startDir = Path.of("unused", "start").toAbsolutePath();
        assertThat(RemoteTextFileSelectionSupport.resolveLocalFilePath(
                "~", "notes.txt", startDir.toString(), home.toString()))
            .isEqualTo(home.resolve("notes.txt"));
        assertThat(RemoteTextFileSelectionSupport.resolveLocalFilePath(
                "~/work", "notes.txt", startDir.toString(), home.toString()))
            .isEqualTo(home.resolve("work").resolve("notes.txt"));
    }

    @Test
    void resolvesLocalHomeRelativeWorkingDirectoryAgainstStartDirectoryWithoutHome() throws Exception {
        Path startDir = Path.of("fallback", "start").toAbsolutePath();
        assertThat(RemoteTextFileSelectionSupport.resolveLocalFilePath(
                "~", "notes.txt", startDir.toString(), null))
            .isEqualTo(startDir.resolve("notes.txt"));
    }

    @Test
    void fallsBackToStartDirectoryForRelativeTrackedLocalWorkingDirectory() throws Exception {
        Path startDir = Path.of("base", "start").toAbsolutePath();
        // A relative tracked directory has no trustworthy base — fall back to the start directory.
        assertThat(RemoteTextFileSelectionSupport.resolveLocalFilePath(
                Path.of("sub", "dir").toString(), "notes.txt", startDir.toString(), null))
            .isEqualTo(startDir.resolve("notes.txt"));
    }

    @Test
    void refusesForeignNamespaceTrackedDirectoryOnWindows() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (!windows) {
            return; // on POSIX "/mnt/c/..." is a genuine absolute path and is used as tracked
        }
        // POSIX prompt paths from Git Bash/Cygwin/WSL are rooted but not absolute on Windows; the
        // shell is provably in a namespace we cannot address, so resolution must refuse instead of
        // silently targeting a same-named file in the start directory.
        try {
            RemoteTextFileSelectionSupport.resolveLocalFilePath(
                "/mnt/c/Users/daniel", "notes.txt", Path.of("base", "start").toAbsolutePath().toString(), null);
            throw new AssertionError("expected UnmappableWorkingDirectoryException for POSIX prompt path");
        } catch (RemoteTextFileSelectionSupport.UnmappableWorkingDirectoryException expected) {
            assertThat(expected.workingDirectory()).isEqualTo("/mnt/c/Users/daniel");
        }
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    void rejectsPathSeparatorsInLocalSelection() throws Exception {
        RemoteTextFileSelectionSupport.resolveLocalFilePath(null, "..\\secret.txt", "start", null);
    }

    @Test
    void rejectsDriveRelativeLocalSelectionOnWindows() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        if (!windows) {
            return; // "C:notes.txt" is a legal plain file name on POSIX filesystems
        }
        try {
            RemoteTextFileSelectionSupport.resolveLocalFilePath(null, "C:notes.txt", "D:\\work", null);
            throw new AssertionError("expected IllegalArgumentException for drive-relative selection");
        } catch (IllegalArgumentException expected) {
            // Windows-reserved characters must also map to the same validation error, not a raw
            // InvalidPathException from the background task.
        }
        try {
            RemoteTextFileSelectionSupport.resolveLocalFilePath(null, "config.ini:12", "D:\\work", null);
            throw new AssertionError("expected IllegalArgumentException for reserved characters");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    void decodesUtf8TextFile() throws Exception {
        assertThat(RemoteTextFileSelectionSupport.decodeUtf8TextFile("hello\nwelt".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo("hello\nwelt");
    }

    @Test(expectedExceptions = RemoteTextFileSelectionSupport.BinaryOrNonTextFileException.class)
    void rejectsNulByteBinaryFile() throws Exception {
        RemoteTextFileSelectionSupport.decodeUtf8TextFile(new byte[] {'a', 0, 'b'});
    }

    @Test(expectedExceptions = RemoteTextFileSelectionSupport.BinaryOrNonTextFileException.class)
    void rejectsInvalidUtf8File() throws Exception {
        RemoteTextFileSelectionSupport.decodeUtf8TextFile(new byte[] {(byte) 0xc3, 0x28});
    }
}
