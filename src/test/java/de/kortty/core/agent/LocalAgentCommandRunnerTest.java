package de.kortty.core.agent;

import de.kortty.core.LocalShellTtyConnector;
import de.kortty.core.agent.AgentCommandRunner.ShellKind;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class LocalAgentCommandRunnerTest {

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    @Test
    void resolveShellKindMatchesConnectionShellOnWindows() {
        if (isWindows()) {
            assertThat(LocalAgentCommandRunner.resolveShellKind("cmd.exe")).isEqualTo(ShellKind.WINDOWS_CMD);
            assertThat(LocalAgentCommandRunner.resolveShellKind("powershell.exe")).isEqualTo(ShellKind.WINDOWS_POWERSHELL);
            assertThat(LocalAgentCommandRunner.resolveShellKind(null)).isEqualTo(ShellKind.WINDOWS_POWERSHELL);
            // Custom / unknown commands default to PowerShell per the agreed Windows behavior.
            assertThat(LocalAgentCommandRunner.resolveShellKind("wsl.exe -d Ubuntu")).isEqualTo(ShellKind.WINDOWS_POWERSHELL);
        } else {
            assertThat(LocalAgentCommandRunner.resolveShellKind("powershell.exe")).isEqualTo(ShellKind.POSIX);
            assertThat(LocalAgentCommandRunner.resolveShellKind("cmd.exe")).isEqualTo(ShellKind.POSIX);
            assertThat(LocalAgentCommandRunner.resolveShellKind(null)).isEqualTo(ShellKind.POSIX);
        }
    }

    @Test
    void windowsProbeEmitsSharedKeyValueContract() {
        String script = AgentProbeScripts.windowsPowerShell("cmd.exe");
        // Reports the agent's command language so the model generates matching commands.
        assertThat(script).contains("shell=cmd.exe");
        // Emits the same keys the shared probe parser understands.
        assertThat(script).contains("osRelease=");
        assertThat(script).contains("currentUser=");
        assertThat(script).contains("homeDir=");
        assertThat(script).contains("currentDir=");
        // Windows has no sudo/root escalation.
        assertThat(script).contains("sudoAvailable=false");
        assertThat(script).contains("alreadyRoot=false");
        assertThat(script).contains("rootEscalationMode=none");
    }

    @Test
    void posixProbeIsShellScriptWithSudoDetection() {
        assertThat(AgentProbeScripts.POSIX).contains("sudoAvailable=");
        assertThat(AgentProbeScripts.POSIX).contains("currentDir=");
    }

    @Test(timeOut = 30_000)
    void liveDirectoryIsSnapshottedForProbeAndMultipleCommands() throws Exception {
        Path root = Files.createTempDirectory("kortty-agent-snapshot-");
        Path start = Files.createDirectory(root.resolve("start"));
        Path live = Files.createDirectory(root.resolve("live space ü"));
        Path later = Files.createDirectory(root.resolve("later"));
        try {
            StubLocalConnector connector = new StubLocalConnector(start);
            connector.refreshedDirectory = live.toString();
            LocalAgentCommandRunner runner = new LocalAgentCommandRunner(connector, null);

            AgentCommandRunner.ExecResult probe = runner.runProbe(true, () -> false);
            assertThat(probe.exitCode()).isEqualTo(0);
            assertThat(Path.of(probeValue(probe.stdout(), "currentDir")).toRealPath())
                .isEqualTo(live.toRealPath());

            connector.refreshedDirectory = later.toString();
            assertWorkingDirectory(runner.exec(cwdCommand(runner.shellKind()), null, null, () -> false, true), live);
            assertWorkingDirectory(runner.exec(cwdCommand(runner.shellKind()), null, null, () -> false, true), live);
            assertThat(connector.refreshCount).isEqualTo(1);
            assertThat(runner.currentWorkingDirectory()).isEqualTo(live.toString());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test(timeOut = 30_000)
    void falseTrackedFlagAlwaysUsesShellStartDirectory() throws Exception {
        Path root = Files.createTempDirectory("kortty-agent-start-");
        Path start = Files.createDirectory(root.resolve("start"));
        Path live = Files.createDirectory(root.resolve("live"));
        try {
            StubLocalConnector connector = new StubLocalConnector(start);
            connector.refreshedDirectory = live.toString();
            LocalAgentCommandRunner runner = new LocalAgentCommandRunner(connector, null);

            assertWorkingDirectory(runner.exec(cwdCommand(runner.shellKind()), null, null, () -> false, true), live);
            assertWorkingDirectory(runner.exec(cwdCommand(runner.shellKind()), null, null, () -> false, false), start);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void fallbackProbeHintCannotOverwriteTrackedInteractiveDirectory() throws Exception {
        Path root = Files.createTempDirectory("kortty-agent-probe-hint-");
        Path start = Files.createDirectory(root.resolve("start"));
        Path live = Files.createDirectory(root.resolve("live"));
        try {
            StubLocalConnector connector = new StubLocalConnector(start);
            connector.cachedDirectory = live.toString();
            LocalAgentCommandRunner runner = new LocalAgentCommandRunner(connector, null);

            runner.runProbe(false, () -> false);
            runner.updateDirectoryHints(null, start.toString());

            assertThat(connector.cachedDirectory).isEqualTo(live.toString());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test(timeOut = 30_000)
    void promptHintPrecedesCachedDirectoryWhenLiveRefreshIsUnavailable() throws Exception {
        Path root = Files.createTempDirectory("kortty-agent-hint-");
        Path start = Files.createDirectory(root.resolve("start"));
        Path cached = Files.createDirectory(root.resolve("cached"));
        Path hint = Files.createDirectory(root.resolve("hint"));
        try {
            StubLocalConnector connector = new StubLocalConnector(start);
            connector.cachedDirectory = cached.toString();
            LocalAgentCommandRunner runner = new LocalAgentCommandRunner(connector, hint.toString());

            assertWorkingDirectory(runner.exec(cwdCommand(runner.shellKind()), null, null, () -> false, true), hint);
            assertThat(connector.cachedDirectory).isEqualTo(hint.toString());
        } finally {
            deleteRecursively(root);
        }
    }

    @Test(timeOut = 30_000)
    void cachedThenStartDirectoryProvideSafeFallbacks() throws Exception {
        Path root = Files.createTempDirectory("kortty-agent-fallback-");
        Path start = Files.createDirectory(root.resolve("start"));
        Path cached = Files.createDirectory(root.resolve("cached"));
        try {
            StubLocalConnector cachedConnector = new StubLocalConnector(start);
            cachedConnector.cachedDirectory = cached.toString();
            LocalAgentCommandRunner cachedRunner = new LocalAgentCommandRunner(cachedConnector, "relative-prompt");
            assertWorkingDirectory(
                cachedRunner.exec(cwdCommand(cachedRunner.shellKind()), null, null, () -> false, true),
                cached);

            StubLocalConnector startConnector = new StubLocalConnector(start);
            LocalAgentCommandRunner startRunner = new LocalAgentCommandRunner(startConnector, null);
            assertWorkingDirectory(
                startRunner.exec(cwdCommand(startRunner.shellKind()), null, null, () -> false, true),
                start);
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void unresolvedDirectoryChangeRefusesStaleFallback() throws Exception {
        Path start = Files.createTempDirectory("kortty-agent-unresolved-");
        try {
            StubLocalConnector connector = new StubLocalConnector(start);
            connector.cachedDirectory = start.toString();
            connector.unresolved = true;
            LocalAgentCommandRunner runner = new LocalAgentCommandRunner(connector, "relative-prompt");
            try {
                runner.exec(cwdCommand(runner.shellKind()), null, null, () -> false, true);
                throw new AssertionError("expected unresolved-directory failure");
            } catch (IllegalStateException expected) {
                assertThat(expected).hasMessageThat().isNotEmpty();
            }
        } finally {
            deleteRecursively(start);
        }
    }

    @Test
    void unmappableAbsoluteHintRefusesCachedAndStartFallbacks() throws Exception {
        Path root = Files.createTempDirectory("kortty-agent-unmappable-");
        Path start = Files.createDirectory(root.resolve("start"));
        try {
            StubLocalConnector connector = new StubLocalConnector(start);
            connector.cachedDirectory = start.toString();
            String foreignOrMissing = "/definitely/not/a/mappable/kortty-agent-directory";
            LocalAgentCommandRunner runner = new LocalAgentCommandRunner(connector, foreignOrMissing);
            try {
                runner.exec(cwdCommand(runner.shellKind()), null, null, () -> false, true);
                throw new AssertionError("expected unmappable-directory failure");
            } catch (IllegalStateException expected) {
                assertThat(expected).hasMessageThat().isNotEmpty();
            }
        } finally {
            deleteRecursively(root);
        }
    }

    @Test
    void connectionStateDelegatesToActivePtyConnector() throws Exception {
        Path start = Files.createTempDirectory("kortty-agent-state-");
        try {
            StubLocalConnector connector = new StubLocalConnector(start);
            LocalAgentCommandRunner runner = new LocalAgentCommandRunner(connector, null);
            assertThat(runner.isConnected()).isTrue();
            connector.connected = false;
            assertThat(runner.isConnected()).isFalse();
            try {
                runner.runProbe(true, () -> false);
                throw new AssertionError("expected disconnected runner failure");
            } catch (IllegalStateException expected) {
                assertThat(expected).hasMessageThat().contains("not connected");
            }
        } finally {
            deleteRecursively(start);
        }
    }

    @Test
    void factoryPreservesLegacyOverloadAndAcceptsRunContextHint() throws Exception {
        Path start = Files.createTempDirectory("kortty-agent-factory-");
        try {
            StubLocalConnector connector = new StubLocalConnector(start);
            assertThat(((LocalAgentCommandRunner) AgentCommandRunners.forConnector(connector)).connector())
                .isSameInstanceAs(connector);
            assertThat(((LocalAgentCommandRunner) AgentCommandRunners.forConnector(connector, start.toString())).connector())
                .isSameInstanceAs(connector);
        } finally {
            deleteRecursively(start);
        }
    }

    @Test(timeOut = 30_000)
    void realLocalShellCdExecutesAgainstNewDirectoryNotSameNamedStartFile() throws Exception {
        if (isWindows()) {
            throw new org.testng.SkipException("POSIX PTY integration test");
        }
        Path root = Files.createTempDirectory("kortty-agent-real-cwd-");
        Path start = Files.createDirectory(root.resolve("start"));
        Path target = Files.createDirectory(root.resolve("Ziel mit Leerzeichen ü"));
        Path startFile = Files.writeString(start.resolve("same-name.txt"), "start");
        Path targetFile = Files.writeString(target.resolve("same-name.txt"), "target");
        Path ready = target.resolve(".kortty-ready");

        ServerConnection connection = localConnection(start);
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        assertThat(connector.connect()).isTrue();
        Thread reader = new Thread(() -> {
            char[] buffer = new char[4096];
            try {
                while (connector.read(buffer, 0, buffer.length) >= 0) {
                    // drain PTY output
                }
            } catch (Exception ignored) {
            }
        }, "test-local-agent-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            String quotedTarget = target.toString().replace("'", "'\\''");
            connector.write("cd '" + quotedTarget + "' && touch .kortty-ready\r");
            for (int i = 0; i < 50 && !Files.exists(ready); i++) {
                Thread.sleep(100);
            }
            assertThat(Files.exists(ready)).isTrue();
            assertThat(connector.hasUnresolvedWorkingDirectoryChange()).isTrue();

            LocalAgentCommandRunner runner = new LocalAgentCommandRunner(connector, null);
            AgentCommandRunner.ExecResult result = runner.exec(
                "printf updated > same-name.txt", null, null, () -> false, true);
            assertThat(result.exitCode()).isEqualTo(0);
            assertThat(Files.readString(startFile)).isEqualTo("start");
            assertThat(Files.readString(targetFile)).isEqualTo("updated");
            assertThat(connector.hasUnresolvedWorkingDirectoryChange()).isFalse();
        } finally {
            connector.close();
            deleteRecursively(root);
        }
    }

    private static String cwdCommand(ShellKind shellKind) {
        return switch (shellKind) {
            case WINDOWS_POWERSHELL -> "(Get-Location).Path";
            case WINDOWS_CMD -> "cd";
            case POSIX -> "pwd";
        };
    }

    private static void assertWorkingDirectory(AgentCommandRunner.ExecResult result, Path expected) throws Exception {
        assertThat(result.exitCode()).isEqualTo(0);
        assertThat(Path.of(result.stdout().trim()).toRealPath()).isEqualTo(expected.toRealPath());
    }

    private static String probeValue(String output, String key) {
        String prefix = key + "=";
        for (String line : output.split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        throw new AssertionError("missing probe key: " + key);
    }

    private static void deleteRecursively(Path root) throws Exception {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static final class StubLocalConnector extends LocalShellTtyConnector {
        private final Path startDirectory;
        private String refreshedDirectory;
        private String cachedDirectory;
        private boolean unresolved;
        private boolean connected = true;
        private int refreshCount;

        private StubLocalConnector(Path startDirectory) {
            super(localConnection(startDirectory));
            this.startDirectory = startDirectory;
        }

        @Override
        public String getStartDirectory() {
            return startDirectory.toString();
        }

        @Override
        public String refreshCurrentWorkingDirectory() {
            refreshCount++;
            return refreshedDirectory;
        }

        @Override
        public String getCurrentWorkingDirectory() {
            return cachedDirectory;
        }

        @Override
        public void updateCurrentWorkingDirectoryHint(String directory) {
            String trusted = normalizeTrustedLocalDirectory(directory);
            if (trusted != null) {
                cachedDirectory = trusted;
                unresolved = false;
            }
        }

        @Override
        public boolean hasUnresolvedWorkingDirectoryChange() {
            return unresolved;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }
    }

    private static ServerConnection localConnection(Path startDirectory) {
        ServerConnection connection = new ServerConnection();
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellWorkingDirectory(startDirectory.toString());
        connection.setLocalShellCommand(isWindows() ? "powershell.exe" : "/bin/sh");
        return connection;
    }
}
