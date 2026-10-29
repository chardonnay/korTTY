package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.TerminalOutputStream;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.clipboard.ClipboardLocation;
import com.sithtermfx.core.clipboard.ClipboardReadRequest;
import com.sithtermfx.core.clipboard.ClipboardWriteRequest;
import com.sithtermfx.core.clipboard.TerminalClipboardHandler;
import de.kortty.isolation.StrictEscapeFilterTtyConnector;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.Test;

/**
 * OSC 5522 through korTTY's connector chain into the real SithTermFX emulator: it passes the shell
 * integration's splitter untouched, its answers go back out through the pane's connector, and the strict
 * terminal mode keeps every form of it, the paste-event mode and the DECRQM query away from the emulator.
 */
class ClipboardProtocolFlowTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";
    private static final String ST = ESC + "\\";

    /** Records what the protocol asks of it and allows everything. */
    private static final class RecordingHandler implements TerminalClipboardHandler {
        final List<ClipboardWriteRequest> writes = new ArrayList<>();
        final List<ClipboardReadRequest> reads = new ArrayList<>();
        int listings;

        @Override
        public @NotNull CompletionStage<List<String>> getMimeTypes(@NotNull ClipboardLocation location) {
            listings++;
            return CompletableFuture.completedFuture(List.of("text/plain"));
        }

        @Override
        public @NotNull CompletionStage<Map<String, byte[]>> read(@NotNull ClipboardReadRequest request) {
            reads.add(request);
            return CompletableFuture.completedFuture(Map.of("text/plain", "clip".getBytes(StandardCharsets.UTF_8)));
        }

        @Override
        public @NotNull CompletionStage<Void> write(@NotNull ClipboardWriteRequest request) {
            writes.add(request);
            return CompletableFuture.completedFuture(null);
        }

        boolean untouched() {
            return writes.isEmpty() && reads.isEmpty() && listings == 0;
        }
    }

    /**
     * Wires the terminal's answers to {@code connector}, as SithTermFX's {@code TerminalStarter} does with the
     * pane's (decorated) connector.
     */
    private static void answerThrough(Screen screen, TtyConnector connector) {
        screen.terminal.setTerminalOutput(new TerminalOutputStream() {
            @Override
            public void sendBytes(byte @NotNull [] response, boolean userInput) {
                try {
                    connector.write(response);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }

            @Override
            public void sendString(@NotNull String string, boolean userInput) {
                try {
                    connector.write(string);
                } catch (IOException e) {
                    throw new IllegalStateException(e);
                }
            }
        });
    }

    private static String osc(String metadata, String payload) {
        return ESC + "]5522;" + metadata + (payload.isEmpty() ? "" : ";" + payload) + ST;
    }

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String clipboardSession() {
        return "before"
            + ESC + "[?5522$p"
            + osc("type=write", "") + osc("type=wdata:mime=" + b64("text/plain"), b64("from the server"))
            + osc("type=wdata", "")
            + osc("type=read:id=r1", b64("text/plain"))
            + ESC + "[?5522h"
            + "after";
    }

    @Test
    void theProtocolPassesTheShellIntegrationAndItsAnswersGoBackThroughTheConnector() throws IOException {
        for (int chunk : new int[] {1, 3, 7, 4096}) {
            Screen screen = new Screen(40, 4, 10);
            RecordingHandler handler = new RecordingHandler();
            screen.terminal.setClipboardHandler(handler);
            ScriptedConnector remote = ScriptedConnector.inChunksOf(clipboardSession(), chunk);
            TtyConnector pane = new ShellIntegrationTtyConnector(remote, event -> { });
            answerThrough(screen, pane);

            screen.run(pane);

            assertThat(handler.writes).hasSize(1);
            assertThat(new String(handler.writes.get(0).getData().get("text/plain"), StandardCharsets.UTF_8))
                .isEqualTo("from the server");
            assertThat(handler.reads).hasSize(1);
            assertWithMessage("the paste-event mode reached the emulator")
                .that(screen.terminal.getClipboardPasteEventsMode()).isTrue();
            String answers = String.join("", remote.written());
            assertWithMessage("in chunks of %s, the answers reach the remote side", chunk).that(answers)
                .contains(ESC + "[?5522;2$y");
            assertThat(answers).contains("]5522;type=write:status=DONE");
            assertThat(answers).contains("]5522;type=read:status=OK:id=r1");
            assertWithMessage("nothing of it is printed").that(screen.buffer.getLine(0).getText().strip())
                .isEqualTo("beforeafter");
        }
    }

    @Test
    void theStrictModeKeepsTheProtocolFromTheEmulatorEvenWithAHandler() throws IOException {
        Screen screen = new Screen(40, 4, 10);
        RecordingHandler handler = new RecordingHandler();
        // korTTY sets no handler in the strict mode; this one shows the filter alone already suffices.
        screen.terminal.setClipboardHandler(handler);
        ScriptedConnector remote = ScriptedConnector.inChunksOf(clipboardSession()
            + ESC + "[?55:22h" + ESC + "[?1049;5522h" + ESC + "[?1049l", 5);
        TtyConnector pane = new ShellIntegrationTtyConnector(new StrictEscapeFilterTtyConnector(remote), event -> { });
        answerThrough(screen, pane);

        screen.run(pane);

        assertThat(handler.untouched()).isTrue();
        assertThat(screen.terminal.getClipboardPasteEventsMode()).isFalse();
        assertWithMessage("no answer at all: not even the DECRQM report").that(remote.written()).isEmpty();
    }

    @Test
    void generatedStreamsNeverReachTheProtocolThroughTheStrictMode() throws IOException {
        Random random = new Random(0x5522L);
        String[] strays = {"", ":", " ", "\r", "\u0000", "\u009D", ESC, "<", "!", "\""};
        for (int stream = 0; stream < 500; stream++) {
            StringBuilder output = new StringBuilder();
            for (int part = 0; part < 6; part++) {
                String stray = strays[random.nextInt(strays.length)];
                switch (random.nextInt(6)) {
                    case 0 -> output.append(ESC).append("[?").append(stray).append("5522").append("hl".charAt(random.nextInt(2)));
                    case 1 -> output.append(ESC).append("[?55").append(stray).append("22").append("$hlp".charAt(random.nextInt(4)))
                        .append(random.nextBoolean() ? "p" : "");
                    case 2 -> output.append(ESC).append("[?1;").append(stray).append("5522;25").append("hl".charAt(random.nextInt(2)));
                    case 3 -> output.append(random.nextBoolean() ? ESC + "]" : "\u009D").append("5522;type=")
                        .append(random.nextBoolean() ? "read;" + b64("text/plain") : "write")
                        .append(random.nextBoolean() ? BEL : ST);
                    case 4 -> output.append(ESC).append("[?5522").append(stray).append("$p");
                    default -> output.append("text").append(stray).append("\r\n");
                }
            }
            Screen screen = new Screen(30, 4, 10);
            RecordingHandler handler = new RecordingHandler();
            screen.terminal.setClipboardHandler(handler);
            ScriptedConnector remote = ScriptedConnector.inRandomChunks(output.toString(), random, 1 + random.nextInt(9));
            TtyConnector pane = new ShellIntegrationTtyConnector(new StrictEscapeFilterTtyConnector(remote), event -> { });
            answerThrough(screen, pane);

            screen.run(pane);

            assertWithMessage("stream %s", stream).that(handler.untouched()).isTrue();
            assertWithMessage("stream %s", stream).that(screen.terminal.getClipboardPasteEventsMode()).isFalse();
            assertWithMessage("stream %s", stream).that(String.join("", remote.written())).doesNotContain("5522");
        }
    }

    @Test
    void withoutAHandlerTheEmulatorReportsTheProtocolUnsupported() throws IOException {
        Screen screen = new Screen(40, 4, 10);
        ScriptedConnector remote = ScriptedConnector.inChunksOf(ESC + "[?5522$p" + osc("type=read", b64("text/plain")), 4);
        answerThrough(screen, remote);

        screen.run(remote);

        assertThat(String.join("", remote.written())).isEqualTo(ESC + "[?5522;0$y");
        assertThat(screen.terminal.getClipboardPasteEventsMode()).isNull();
    }

    @Test
    void aPasteGoesOutAsAPasteEventOnlyWhileTheProgramAskedForThem() throws IOException {
        Screen screen = new Screen(40, 4, 10);
        RecordingHandler handler = new RecordingHandler();
        screen.terminal.setClipboardHandler(handler);
        StringBuilder answers = new StringBuilder();
        screen.terminal.setTerminalOutput(new TerminalOutputStream() {
            @Override
            public void sendBytes(byte @NotNull [] response, boolean userInput) {
                answers.append(new String(response, StandardCharsets.UTF_8));
            }

            @Override
            public void sendString(@NotNull String string, boolean userInput) {
                answers.append(string);
            }
        });

        assertWithMessage("without the mode the paste goes the usual way")
            .that(screen.terminal.getClipboardProtocol().sendPasteEvent()).isFalse();

        screen.interpret(ESC + "[?5522h");
        assertThat(screen.terminal.getClipboardProtocol().sendPasteEvent()).isTrue();
        assertWithMessage("the paste event lists the types with a one-time password")
            .that(answers.toString()).contains("]5522;type=read:status=OK");
        assertThat(answers.toString()).contains(":pw=");
        assertThat(handler.listings).isEqualTo(1);

        screen.terminal.setClipboardHandler(null);
        assertWithMessage("korTTY's strict mode sets no handler: no paste events")
            .that(screen.terminal.getClipboardProtocol().sendPasteEvent()).isFalse();
    }

    /** Source-level wiring: every pane's session gets the tab's handler, except in the strict mode. */
    @Test
    void everyPaneGetsTheTabsHandlerUnlessItIsStrict() throws IOException {
        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        String decorate = view.substring(view.indexOf("private TtyConnector decorateTerminalConnector("),
            view.indexOf("public void setIsolationRequested("));
        assertThat(decorate).contains("applyClipboardAccess(widget, strict ? null : clipboardAccess);");
        assertThat(decorate).contains("decorated = new de.kortty.isolation.StrictEscapeFilterTtyConnector(baseConnector);");
        assertWithMessage("the tab's cleanup refuses every later request").that(view)
            .contains("clipboardAccess = null;\n        if (access != null) {\n            access.close();");

        String tab = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalTab.java"), StandardCharsets.UTF_8).replace("\r\n", "\n");
        assertThat(tab).contains("this.terminalView.setClipboardAccess(TerminalClipboardAccess.forTab(this));");
    }
}
