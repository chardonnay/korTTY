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

    @Test
    void plausibleFileNameAcceptsSingleTokensAndQuotedNamesWithSpaces() {
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("notes.txt")).isTrue();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("  Makefile  ")).isTrue();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("\"notes final.txt\"")).isTrue();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("'my file.log'")).isTrue();
    }

    @Test
    void plausibleFileNameRejectsProseCommandsPathsAndOversizedNames() {
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("notes final.txt")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("error: connection refused")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("one.txt\ntwo.txt")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("../secret.txt")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("src/main")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("..")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName(null)).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("x".repeat(256))).isFalse();
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

    // ---- Paths printed in terminal output (links to files) ----

    private static final boolean DRIVE_LETTERS = java.io.File.separatorChar == '\\';

    @Test
    void remotePathKeepsAnAbsolutePathWhateverTheWorkingDirectory() {
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath("/home/daniel/work", "/var/log/syslog", "/home/daniel"))
            .isEqualTo("/var/log/syslog");
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath(null, "/etc/nginx/../hosts", null))
            .isEqualTo("/etc/hosts");
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath(null, "/../../etc/./hosts", null))
            .isEqualTo("/etc/hosts");
    }

    @Test
    void remoteHomePathResolvesAgainstTheSftpStartDirectory() {
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath("/srv/app", "~/notes/todo.md", "/home/daniel"))
            .isEqualTo("/home/daniel/notes/todo.md");
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath("/srv/app", "~", "/home/daniel/"))
            .isEqualTo("/home/daniel");
    }

    @Test
    void remoteRelativePathResolvesAgainstTheWorkingDirectory() {
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath("/srv/app", "./config/app.yml", "/home/daniel"))
            .isEqualTo("/srv/app/config/app.yml");
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath("/srv/app", "../shared/app.log", "/home/daniel"))
            .isEqualTo("/srv/shared/app.log");
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath("/srv/app/", "src/main/App.java", "/home/daniel"))
            .isEqualTo("/srv/app/src/main/App.java");
        // A home-relative working directory, as typed cd and OSC 7 leave it.
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath("~/work", "a/b.txt", "/home/daniel"))
            .isEqualTo("/home/daniel/work/a/b.txt");
        // No tracked directory: the SFTP start directory, as for a selected file name.
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath(null, "a/b.txt", "/home/daniel"))
            .isEqualTo("/home/daniel/a/b.txt");
        // Nothing known at all: SFTP resolves a relative path against its own start directory.
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath(null, "../a/b.txt", null))
            .isEqualTo("../a/b.txt");
    }

    @Test
    void remoteWindowsDrivePathBecomesTheSftpForm() {
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath("/home/daniel", "C:\\Users\\daniel\\app.log", null))
            .isEqualTo("/C:/Users/daniel/app.log");
        assertThat(RemoteTextFileSelectionSupport.resolveRemotePath(null, "d:/logs/app.log", null))
            .isEqualTo("/d:/logs/app.log");
    }

    @Test
    void remotePathRefusesNetworkPathsControlCharactersAndOtherUsersHomes() {
        for (String path : new String[] {
            "//fileserver/share/x.txt", "\\\\fileserver\\share\\x.txt", "/etc/pass\0wd", "/tmp/a\nb",
            "/tmp/\u202Eevil.txt", "~root/.ssh/id_rsa", "dir\\file.txt", "", null}) {
            try {
                RemoteTextFileSelectionSupport.resolveRemotePath("/home/daniel", path, "/home/daniel");
                throw new AssertionError("expected IllegalArgumentException for " + path);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    void localPathKeepsAnAbsolutePathAndIgnoresTheWorkingDirectory() throws Exception {
        Path absolute = Path.of("var", "log", "app.log").toAbsolutePath();
        // Even a working directory that could not be mapped does not matter for an absolute path.
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(
                DRIVE_LETTERS ? "/mnt/c/Users/daniel" : null, absolute.toString(), null, null))
            .isEqualTo(absolute);
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(
                null, absolute.resolve("..").resolve("other.log").toString(), null, null))
            .isEqualTo(absolute.getParent().resolve("other.log"));
    }

    @Test
    void localHomePathResolvesAgainstTheHomeDirectory() throws Exception {
        Path home = Path.of("home", "daniel").toAbsolutePath();
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(null, "~/notes/todo.md", null, home.toString()))
            .isEqualTo(home.resolve("notes").resolve("todo.md"));
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(null, "~", null, home.toString()))
            .isEqualTo(home);
        // Without a known home directory, the user's.
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(null, "~/x.txt", null, null))
            .isEqualTo(Path.of(System.getProperty("user.home")).resolve("x.txt").normalize());
    }

    @Test
    void localRelativePathResolvesAgainstTheWorkingDirectory() throws Exception {
        Path tracked = Path.of("tracked", "work").toAbsolutePath();
        Path start = Path.of("unused", "start").toAbsolutePath();
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(tracked.toString(), "./config/app.yml", start.toString(), null))
            .isEqualTo(tracked.resolve("config").resolve("app.yml"));
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(tracked.toString(), "../shared/app.log", start.toString(), null))
            .isEqualTo(tracked.getParent().resolve("shared").resolve("app.log"));
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(tracked.toString(), "src/main/App.java", start.toString(), null))
            .isEqualTo(tracked.resolve("src").resolve("main").resolve("App.java"));
        // No tracked directory: the shell's start directory.
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(null, "src/App.java", start.toString(), null))
            .isEqualTo(start.resolve("src").resolve("App.java"));
    }

    @Test
    void localRelativePathRefusesAnUnmappableWorkingDirectory() throws Exception {
        if (!DRIVE_LETTERS) {
            return; // on POSIX "/mnt/c/..." is a genuine absolute path
        }
        try {
            RemoteTextFileSelectionSupport.resolveLocalPath(
                "/mnt/c/Users/daniel", "src/App.java", Path.of("base", "start").toAbsolutePath().toString(), null);
            throw new AssertionError("expected UnmappableWorkingDirectoryException for a POSIX prompt path");
        } catch (RemoteTextFileSelectionSupport.UnmappableWorkingDirectoryException expected) {
            assertThat(expected.workingDirectory()).isEqualTo("/mnt/c/Users/daniel");
        }
    }

    @Test
    void localPathRefusesNetworkAndDevicePathsControlCharactersAndOtherUsersHomes() throws Exception {
        for (String path : new String[] {
            "//fileserver/share/x.txt", "\\\\fileserver\\share\\x.txt", "\\\\?\\C:\\x.txt", "\\\\.\\COM1",
            "/etc/pass\0wd", "/tmp/a\rb", "/tmp/\u202Eevil.txt", "~root/.ssh/id_rsa", "", null}) {
            try {
                RemoteTextFileSelectionSupport.resolveLocalPath(null, path, "start", null);
                throw new AssertionError("expected IllegalArgumentException for " + path);
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test
    void localPathOnWindowsReadsAFileUriDrivePathAndRefusesDriveRelativeOnes() throws Exception {
        if (!DRIVE_LETTERS) {
            // Without drive letters, a drive path or a backslash names no local file.
            for (String path : new String[] {"C:\\Users\\x.txt", "C:/Users/x.txt", "dir\\x.txt"}) {
                try {
                    RemoteTextFileSelectionSupport.resolveLocalPath(null, path, "start", null);
                    throw new AssertionError("expected IllegalArgumentException for " + path);
                } catch (IllegalArgumentException expected) {
                    // expected
                }
            }
            return;
        }
        assertThat(RemoteTextFileSelectionSupport.resolveLocalPath(null, "/C:/Users/daniel/x.txt", null, null))
            .isEqualTo(Path.of("C:\\Users\\daniel\\x.txt"));
        try {
            RemoteTextFileSelectionSupport.resolveLocalPath(null, "C:x.txt", "D:\\work", null);
            throw new AssertionError("expected IllegalArgumentException for a drive-relative path");
        } catch (IllegalArgumentException expected) {
            // expected
        }
        // A relative path inside a working directory on a network share is not read either.
        try {
            RemoteTextFileSelectionSupport.resolveLocalPath("\\\\fileserver\\share", "x.txt", "D:\\work", null);
            throw new AssertionError("expected IllegalArgumentException for a network working directory");
        } catch (IllegalArgumentException expected) {
            // expected
        }
    }

    @Test
    void onlyRelativePathsNeedTheWorkingDirectory() {
        assertThat(RemoteTextFileSelectionSupport.isWorkingDirectoryRelative("/etc/hosts")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isWorkingDirectoryRelative("~/x.txt")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isWorkingDirectoryRelative("~")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isWorkingDirectoryRelative("C:\\x.txt")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isWorkingDirectoryRelative("C:/x.txt")).isFalse();
        assertThat(RemoteTextFileSelectionSupport.isWorkingDirectoryRelative("./x.txt")).isTrue();
        assertThat(RemoteTextFileSelectionSupport.isWorkingDirectoryRelative("../x.txt")).isTrue();
        assertThat(RemoteTextFileSelectionSupport.isWorkingDirectoryRelative("src/App.java")).isTrue();
    }

    @Test
    void theFileNameRulesForASelectionAreUnchanged() {
        // Paths from links have their own resolvers; a selection is still one name in the directory.
        assertThat(RemoteTextFileSelectionSupport.isPlausibleFileName("/etc/hosts")).isFalse();
        try {
            RemoteTextFileSelectionSupport.normalizeSelectedFileName("src/App.java");
            throw new AssertionError("expected IllegalArgumentException for a path selection");
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
