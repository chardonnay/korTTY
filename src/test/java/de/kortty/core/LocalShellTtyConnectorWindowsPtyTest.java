package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

/**
 * Live Windows check that local shells run under ConPTY, not pty4j's default winpty: the winpty
 * agent drives a hidden console window with window messages, which interfered with typing in
 * other applications while korTTY was running.
 */
class LocalShellTtyConnectorWindowsPtyTest {

    @DataProvider
    Object[][] shells() {
        // Each command echoes the unexpanded variable; only the shell's own output expands it.
        return new Object[][] {
            {"cmd.exe", "echo kortty-%NUMBER_OF_PROCESSORS%-ok"},
            {"powershell.exe -NoLogo -NoProfile", "Write-Output ('kortty-' + $env:NUMBER_OF_PROCESSORS + '-ok')"},
        };
    }

    @Test(dataProvider = "shells", timeOut = 60_000)
    void windowsLocalShellRunsUnderConPty(String shell, String echoCommand) throws Exception {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("ConPTY is Windows-only");
        }
        ServerConnection connection = new ServerConnection();
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand(shell);
        LocalShellTtyConnector connector = new LocalShellTtyConnector(connection);
        try {
            if (!connector.connect()) {
                throw new SkipException("No pty available for a live local shell in this environment");
            }
        } catch (Exception e) {
            throw new SkipException("No pty available for a live local shell in this environment", e);
        }
        StringBuffer output = new StringBuffer();
        Thread reader = new Thread(() -> {
            char[] buf = new char[4096];
            try {
                int count;
                while ((count = connector.read(buf, 0, buf.length)) >= 0) {
                    output.append(buf, 0, count);
                }
            } catch (Exception ignored) {
            }
        }, "test-windows-pty-reader");
        reader.setDaemon(true);
        reader.start();
        try {
            long shellPid = connector.getShellPid().orElseThrow();
            Optional<String> parent = ProcessHandle.of(shellPid)
                .flatMap(ProcessHandle::parent)
                .flatMap(handle -> handle.info().command());
            assertThat(parent.orElse("").toLowerCase(Locale.ROOT)).doesNotContain("winpty-agent");
            assertThat(ProcessHandle.current().descendants()
                .map(handle -> handle.info().command().orElse("").toLowerCase(Locale.ROOT))
                .noneMatch(command -> command.endsWith("winpty-agent.exe"))).isTrue();

            awaitOutput(output, ">");
            // Typed junk must be discarded by clearInputLineSequence(), or the command would not run.
            connector.write("junk".getBytes(StandardCharsets.UTF_8));
            Thread.sleep(300);
            connector.write(connector.clearInputLineSequence());
            Thread.sleep(300);
            connector.write((echoCommand + "\r").getBytes(StandardCharsets.UTF_8));
            String expected = "kortty-" + System.getenv("NUMBER_OF_PROCESSORS") + "-ok";
            awaitOutput(output, expected);
            assertThat(output.toString()).contains(expected);
        } finally {
            connector.close();
        }
        reader.join(5_000);
    }

    private static void awaitOutput(StringBuffer output, String text) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (!output.toString().contains(text) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
    }
}
