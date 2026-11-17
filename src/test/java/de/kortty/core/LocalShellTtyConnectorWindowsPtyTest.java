package de.kortty.core;

import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TerminalOutputStream;
import com.sithtermfx.core.TtyBasedArrayDataStream;
import com.sithtermfx.core.emulator.SithEmulator;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.testng.SkipException;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.fail;

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
        StyleState style = new StyleState();
        TerminalTextBuffer screen = new TerminalTextBuffer(80, 24, style);
        SithTerminal terminal = new SithTerminal(silentDisplay(), screen, style);
        terminal.setTerminalOutput(new TerminalOutputStream() {
            @Override
            public void sendBytes(byte[] bytes, boolean userInput) {
                try {
                    connector.write(bytes);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }

            @Override
            public void sendString(String text, boolean userInput) {
                sendBytes(text.getBytes(StandardCharsets.UTF_8), userInput);
            }
        });
        Thread reader = new Thread(() -> {
            SithEmulator emulator = new SithEmulator(new TtyBasedArrayDataStream(connector), terminal);
            try {
                while (emulator.hasNext()) {
                    emulator.next();
                }
            } catch (IOException ended) {
                // Closing the connector ends the terminal reader.
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

            awaitScreen(screen, text -> text.contains(">"), "shell prompt");
            // Typed junk must be discarded by clearInputLineSequence(), or the command would not run.
            String junk = "korttyDiscardMe";
            connector.write(junk.getBytes(StandardCharsets.UTF_8));
            awaitScreen(screen, text -> text.contains(junk), "echoed input");
            connector.write(connector.clearInputLineSequence());
            awaitScreen(screen, text -> !text.contains(junk), "cleared input");
            connector.write((echoCommand + "\r").getBytes(StandardCharsets.UTF_8));
            String expected = "kortty-" + System.getenv("NUMBER_OF_PROCESSORS") + "-ok";
            awaitScreen(screen, text -> text.contains(expected), "expanded command output");
        } finally {
            connector.close();
            reader.join(5_000);
        }
    }

    private static void awaitScreen(TerminalTextBuffer buffer, Predicate<String> expected, String description)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        String screen;
        do {
            buffer.lock();
            try {
                screen = buffer.getScreenLines();
            } finally {
                buffer.unlock();
            }
            if (expected.test(screen)) {
                return;
            }
            Thread.sleep(50);
        } while (System.nanoTime() < deadline);
        fail("Timed out waiting for " + description + ". Terminal screen:\n" + screen);
    }

    private static TerminalDisplay silentDisplay() {
        return (TerminalDisplay) Proxy.newProxyInstance(TerminalDisplay.class.getClassLoader(),
            new Class<?>[] {TerminalDisplay.class}, (proxy, method, args) -> {
                Class<?> type = method.getReturnType();
                if (type.isPrimitive() && type != void.class) {
                    return Array.get(Array.newInstance(type, 1), 0);
                }
                return type == String.class ? "" : null;
            });
    }
}
