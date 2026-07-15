package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class LocalShellTtyConnectorTest {

    @Test
    void resolveShellCommandUsesConfiguredCommandWhenPresent() {
        assertThat(LocalShellTtyConnector.resolveShellCommand("powershell.exe"))
            .containsExactly("powershell.exe");
        assertThat(LocalShellTtyConnector.resolveShellCommand("cmd.exe"))
            .containsExactly("cmd.exe");
    }

    @Test
    void resolveShellCommandSplitsArgumentsOnWhitespace() {
        assertThat(LocalShellTtyConnector.resolveShellCommand("wsl.exe -d Ubuntu"))
            .containsExactly("wsl.exe", "-d", "Ubuntu")
            .inOrder();
    }

    @Test
    void resolveShellCommandKeepsQuotedPathWithSpacesIntact() {
        // Git Bash lives at a path with spaces; the quoted path must stay one token, args separate.
        assertThat(LocalShellTtyConnector.resolveShellCommand("\"C:\\Program Files\\Git\\bin\\bash.exe\" --login -i"))
            .containsExactly("C:\\Program Files\\Git\\bin\\bash.exe", "--login", "-i")
            .inOrder();
    }

    @Test
    void resolveShellCommandFallsBackToOsDefaultWhenBlank() {
        List<String> fromNull = LocalShellTtyConnector.resolveShellCommand(null);
        List<String> fromBlank = LocalShellTtyConnector.resolveShellCommand("   ");
        assertThat(fromNull).isNotEmpty();
        assertThat(fromBlank).isEqualTo(fromNull);
        assertThat(fromNull).isEqualTo(LocalShellTtyConnector.defaultShellCommand());
    }

    @Test
    void defaultShellCommandIsPowerShellOnWindows() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            assertThat(LocalShellTtyConnector.defaultShellCommand()).containsExactly("powershell.exe");
        } else {
            // On non-Windows the default must still be a single, non-blank command token.
            assertThat(LocalShellTtyConnector.defaultShellCommand()).hasSize(1);
            assertThat(LocalShellTtyConnector.defaultShellCommand().get(0)).isNotEmpty();
        }
    }

    @Test
    void startDirectoryMatchesPtySpawnSemantics() {
        String homeAbs = new java.io.File(System.getProperty("user.home")).getAbsolutePath();

        // No configured directory: default to the user's home directory (never the JVM cwd, which
        // is "/" when the app is launched from the macOS Finder/Dock). The pty is spawned there via
        // PtyProcessBuilder.setDirectory, so getStartDirectory() must report home to stay in sync.
        de.kortty.model.ServerConnection connection = new de.kortty.model.ServerConnection();
        assertThat(new LocalShellTtyConnector(connection).getStartDirectory())
            .isEqualTo(homeAbs);

        // A configured but nonexistent directory is ignored and also falls back to home.
        connection.setLocalShellWorkingDirectory("/definitely/not/a/real/dir/xyz");
        assertThat(new LocalShellTtyConnector(connection).getStartDirectory())
            .isEqualTo(homeAbs);

        // A configured existing directory is used as-is (absolute form).
        connection.setLocalShellWorkingDirectory(homeAbs);
        assertThat(new LocalShellTtyConnector(connection).getStartDirectory())
            .isEqualTo(homeAbs);
    }

    @Test
    void homeDirectoryIsUserHomeOnPosixAndUntrackedOnWindows() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        LocalShellTtyConnector connector = new LocalShellTtyConnector(new de.kortty.model.ServerConnection());
        if (windows) {
            assertThat(connector.getHomeRemoteDirectory()).isNull();
        } else {
            assertThat(connector.getHomeRemoteDirectory()).isEqualTo(System.getProperty("user.home"));
        }
    }

    @Test
    void connectRejectsNonLocalShellProtocol() {
        de.kortty.model.ServerConnection connection = new de.kortty.model.ServerConnection();
        connection.setProtocol(ConnectionProtocol.SSH_TCP);
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        try {
            connector.connect();
            org.testng.Assert.fail("Expected IllegalStateException for non-local-shell protocol");
        } catch (IllegalStateException expected) {
            // expected
        } catch (Exception other) {
            org.testng.Assert.fail("Unexpected exception type: " + other);
        }
    }

    /**
     * Regression guard for the "Load as text file" cwd bug: after the user cd's in a local shell,
     * the connector must report the shell's NEW working directory (read from the OS), not the
     * directory it was spawned in. Before the fix the working directory came only from prompt
     * parsing, which yields nothing when the prompt shows just the folder basename (macOS zsh
     * default), so file loads resolved against the stale spawn directory. Also asserts the
     * JAT-safety contract: getCurrentWorkingDirectory() serves the trusted cached value and never forks.
     * POSIX only — the OS query is unsupported on Windows (returns null there by design).
     */
    @Test(timeOut = 30_000)
    void currentDirectoryReflectsCdInLocalShell() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            throw new org.testng.SkipException("Live cwd lookup is unsupported on Windows");
        }

        java.nio.file.Path target = java.nio.file.Files.createTempDirectory("kortty-cwd-test").toRealPath();
        de.kortty.model.ServerConnection connection = new de.kortty.model.ServerConnection();
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand("/bin/sh");

        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        assertThat(connector.connect()).isTrue();

        // Drain shell output so the PTY never blocks on a full buffer.
        Thread reader = new Thread(() -> {
            char[] buf = new char[4096];
            try {
                while (connector.read(buf, 0, buf.length) >= 0) {
                    // discard
                }
            } catch (Exception ignored) {
            }
        }, "test-cwd-reader");
        reader.setDaemon(true);
        reader.start();

        try {
            // Spawn cwd is the JVM cwd; after cd the live query must report the new directory.
            connector.write("cd '" + target + "'\n");
            assertThat(connector.hasUnresolvedWorkingDirectoryChange()).isTrue();

            java.nio.file.Path reported = null;
            for (int i = 0; i < 40 && reported == null; i++) {
                Thread.sleep(300);
                String current = connector.readLiveWorkingDirectory();
                if (current != null && java.nio.file.Path.of(current).toRealPath().equals(target)) {
                    reported = java.nio.file.Path.of(current).toRealPath();
                }
            }
            assertThat(reported).isEqualTo(target);
            assertThat(connector.hasUnresolvedWorkingDirectoryChange()).isFalse();

            // JAT-safety contract: after a live read, the transport-neutral getter serves the
            // non-expiring cached value (non-blocking) — it must match the live ground truth.
            Thread.sleep(700); // longer than the removed 500 ms cache TTL
            String cached = connector.getCurrentWorkingDirectory();
            assertThat(cached).isNotNull();
            assertThat(java.nio.file.Path.of(cached).toRealPath()).isEqualTo(target);
        } finally {
            connector.close();
            java.nio.file.Files.deleteIfExists(target);
        }
    }

    /** getCurrentWorkingDirectory() must never fork the OS query; a fresh connector reports null. */
    @Test(timeOut = 30_000)
    void currentDirectoryIsNullBeforeAnyLiveReadOnPosix() throws Exception {
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        if (windows) {
            throw new org.testng.SkipException("Live cwd lookup is unsupported on Windows");
        }
        de.kortty.model.ServerConnection connection = new de.kortty.model.ServerConnection();
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand("/bin/sh");
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        assertThat(connector.connect()).isTrue();
        try {
            // No live read yet -> cache is cold -> the JAT-safe getter returns null (no fork).
            assertThat(connector.getCurrentWorkingDirectory()).isNull();
        } finally {
            connector.close();
        }
    }

    @Test(timeOut = 30_000)
    void trustedAbsoluteHintClearsDirtyStateButRelativeHintDoesNot() throws Exception {
        java.nio.file.Path target = java.nio.file.Files.createTempDirectory("kortty-cwd-hint-");
        de.kortty.model.ServerConnection connection = new de.kortty.model.ServerConnection();
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand(LocalShellTtyConnector.isWindows() ? "cmd.exe" : "/bin/sh");
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        assertThat(connector.connect()).isTrue();
        try {
            connector.updateCurrentWorkingDirectoryHint(target.toString());
            assertThat(connector.getCurrentWorkingDirectory()).isNotNull();
            connector.write("cd somewhere\r");
            assertThat(connector.hasUnresolvedWorkingDirectoryChange()).isTrue();
            assertThat(connector.getCurrentWorkingDirectory()).isNull();
            connector.updateCurrentWorkingDirectoryHint("relative/prompt/path");
            assertThat(connector.hasUnresolvedWorkingDirectoryChange()).isTrue();
            connector.updateCurrentRemoteDirectoryHint(target.toString());
            assertThat(connector.hasUnresolvedWorkingDirectoryChange()).isFalse();
            assertThat(java.nio.file.Path.of(connector.getCurrentWorkingDirectory()).toRealPath())
                .isEqualTo(target.toRealPath());
        } finally {
            connector.close();
            java.nio.file.Files.deleteIfExists(target);
        }
    }

    /**
     * Regression guard: closing a local shell while a terminal reader thread is blocked in read()
     * must not deadlock (previously the window/tab close froze the whole app). The test runs close()
     * on a watchdog thread with a bounded join so a regression FAILS the test instead of hanging it.
     */
    @Test(timeOut = 30_000)
    void closeDoesNotHangWhenAReaderIsBlockedInRead() throws Exception {
        de.kortty.model.ServerConnection connection = new de.kortty.model.ServerConnection();
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        connection.setLocalShellCommand(windows ? "cmd.exe" : "/bin/sh");

        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        assertThat(connector.connect()).isTrue();

        java.util.concurrent.atomic.AtomicBoolean readerExited = new java.util.concurrent.atomic.AtomicBoolean(false);
        Thread reader = new Thread(() -> {
            char[] buf = new char[4096];
            try {
                while (connector.read(buf, 0, buf.length) >= 0) {
                    // consume, mirroring the terminal emulator's blocking read loop
                }
            } catch (Exception ignored) {
            }
            readerExited.set(true);
        }, "test-emulator-reader");
        reader.setDaemon(true);
        reader.start();

        // Let the reader settle into a blocking read() with no shell output pending.
        Thread.sleep(1200);

        Thread closer = new Thread(connector::close, "test-closer");
        closer.setDaemon(true);
        closer.start();
        closer.join(10_000);

        assertThat(closer.isAlive()).isFalse(); // close() returned (did not deadlock)
        reader.join(5_000);
        assertThat(readerExited.get()).isTrue(); // the blocked reader was unblocked by close()
    }
}
