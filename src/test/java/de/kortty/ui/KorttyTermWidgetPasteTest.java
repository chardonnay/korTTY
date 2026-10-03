package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.ArrayTerminalDataStream;
import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TerminalMode;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.emulator.SithEmulator;
import com.sithtermfx.core.emulator.mouse.MouseFormat;
import com.sithtermfx.core.emulator.mouse.MouseMode;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.util.TermSize;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import javafx.scene.input.MouseButton;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.Test;

/**
 * The paste routing of {@link KorttyTermWidget} that does not need a running JavaFX toolkit: which
 * clicks korTTY takes from SithTermFX, how the pane's bracketed-paste state is read, and that a
 * reconnect starts unbracketed. {@code terminalContextMenuActionsSmoke} drives the real widget.
 */
class KorttyTermWidgetPasteTest {

    private static final String ESC = "\u001b";

    private static final Path TERMINAL_VIEW = Path.of("src/main/java/de/kortty/ui/TerminalView.java");

    private static final Path WIDGET = Path.of("src/main/java/de/kortty/ui/KorttyTermWidget.java");

    @Test
    void onlyALocalMiddleClickWithPasteOnMiddleClickPastesTheSelection() {
        assertThat(KorttyTermWidget.isLocalMiddleClickPaste(MouseButton.MIDDLE, true, true)).isTrue();

        assertThat(KorttyTermWidget.isLocalMiddleClickPaste(MouseButton.PRIMARY, true, true)).isFalse();
        assertThat(KorttyTermWidget.isLocalMiddleClickPaste(MouseButton.SECONDARY, true, true)).isFalse();
        assertThat(KorttyTermWidget.isLocalMiddleClickPaste(MouseButton.BACK, true, true)).isFalse();
        assertThat(KorttyTermWidget.isLocalMiddleClickPaste(null, true, true)).isFalse();
        assertWithMessage("a click reported to a program with mouse reporting is not a paste")
            .that(KorttyTermWidget.isLocalMiddleClickPaste(MouseButton.MIDDLE, true, false)).isFalse();
        assertWithMessage("paste on middle-click switched off")
            .that(KorttyTermWidget.isLocalMiddleClickPaste(MouseButton.MIDDLE, false, true)).isFalse();
    }

    @Test
    void theProgramSwitchesBracketedPasteOnAndOff() throws IOException {
        Emulated pane = new Emulated();

        pane.process(ESC + "[?2004h");
        assertThat(pane.display.bracketed).isTrue();
        assertThat(pane.bracketed()).isTrue();

        pane.process(ESC + "[?2004l");
        assertThat(pane.display.bracketed).isFalse();
        assertThat(pane.bracketed()).isFalse();
    }

    @Test
    void aTerminalResetEndsBracketedPasteAlthoughThePanelFlagStaysSet() throws IOException {
        Emulated pane = new Emulated();
        pane.process(ESC + "[?2004h");

        pane.process(ESC + "c");

        assertWithMessage("SithTermFX's reset clears its modes without telling the panel")
            .that(pane.display.bracketed).isTrue();
        assertThat(pane.terminal.isModelEnabled(TerminalMode.BracketedPasteMode)).isFalse();
        assertThat(pane.bracketed()).isFalse();

        pane.process(ESC + "[?2004h");
        assertThat(pane.bracketed()).isTrue();
    }

    @Test
    void aClearedPanelFlagWinsOverTheEmulatorMode() throws IOException {
        Emulated pane = new Emulated();
        pane.process(ESC + "[?2004h");

        // What resetBracketedPasteMode does for a new session: only the panel flag is cleared.
        assertThat(KorttyTermWidget.effectiveBracketedPasteMode(false, pane.terminal)).isFalse();
        assertWithMessage("a terminal other than SithTermFX's is trusted on the panel flag")
            .that(KorttyTermWidget.effectiveBracketedPasteMode(true, null)).isTrue();
        assertThat(KorttyTermWidget.effectiveBracketedPasteMode(false, null)).isFalse();
    }

    @Test
    void onlyANewConnectorStartsANewSession() {
        TtyConnector first = new NoopConnector();
        TtyConnector second = new NoopConnector();

        assertWithMessage("the first bind").that(TerminalView.startsNewTerminalSession(null, first)).isTrue();
        assertWithMessage("a reconnect").that(TerminalView.startsNewTerminalSession(first, second)).isTrue();
        assertWithMessage("a Mosh recovery rebinds the same connector")
            .that(TerminalView.startsNewTerminalSession(first, first)).isFalse();
    }

    @Test
    void everyPaneOfATabPastesThroughTheTabsGuard() throws IOException {
        String view = source(TERMINAL_VIEW);

        assertThat(body(view, "private void setupWidgetEventHandlers(SithTermFxWidget widget) {"))
            .contains("installPasteHandler(widget);");
        assertThat(body(view, "private void installPasteHandler(SithTermFxWidget widget) {"))
            .contains("korttyWidget.setPasteHandler(pasteGuard::paste);");
        assertThat(body(view, "private TtyConnector decorateTerminalConnector(SithTermFxWidget widget, TtyConnector connector) {"))
            .contains("resetBracketedPasteModeForNewSession(widget, baseConnector);");
    }

    @Test
    void theWidgetTakesTheMiddleClickBeforeSithTermFxSeesIt() throws IOException {
        String widget = source(WIDGET);

        assertThat(body(widget, "public KorttyTermWidget(int columns, int lines, SettingsProvider settingsProvider) {"))
            .contains("getTerminalPanel().getCanvas().addEventFilter(MouseEvent.MOUSE_CLICKED, this::pasteOnMiddleClick);");
        assertThat(body(widget, "private void pasteOnMiddleClick(MouseEvent event) {"))
            .contains("event.consume();");
    }

    private static String source(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code signature} to the first line that closes a member at four spaces. */
    private static String body(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("missing: %s", signature).that(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertWithMessage("no end for: %s", signature).that(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    /** A SithTermFX emulator without a toolkit, whose display records the bracketed-paste flag. */
    private static final class Emulated {
        final RecordingDisplay display = new RecordingDisplay();
        final SithTerminal terminal;

        Emulated() {
            StyleState styleState = new StyleState();
            TerminalTextBuffer buffer = new TerminalTextBuffer(40, 5, styleState, 10, null);
            terminal = new SithTerminal(display, buffer, styleState);
        }

        void process(String data) throws IOException {
            SithEmulator emulator = new SithEmulator(new ArrayTerminalDataStream(data.toCharArray()), terminal);
            while (emulator.hasNext()) {
                emulator.next();
            }
        }

        /** What {@link KorttyTermWidget#isBracketedPasteMode()} reports for this pane. */
        boolean bracketed() {
            return KorttyTermWidget.effectiveBracketedPasteMode(display.bracketed, terminal);
        }
    }

    /** The panel's side: SithTermFX's TerminalPanel only stores the flag the emulator sets. */
    private static final class RecordingDisplay implements TerminalDisplay {
        boolean bracketed;

        @Override
        public void setBracketedPasteMode(boolean bracketedPasteModeEnabled) {
            bracketed = bracketedPasteModeEnabled;
        }

        @Override
        public void setCursor(int x, int y) {
        }

        @Override
        public void setCursorShape(@Nullable CursorShape cursorShape) {
        }

        @Override
        public void beep() {
        }

        @Override
        public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        }

        @Override
        public void setCursorVisible(boolean isCursorVisible) {
        }

        @Override
        public void useAlternateScreenBuffer(boolean useAlternateScreenBuffer) {
        }

        @Override
        public String getWindowTitle() {
            return "";
        }

        @Override
        public void setWindowTitle(@NotNull String windowTitle) {
        }

        @Override
        public @Nullable TerminalSelection getSelection() {
            return null;
        }

        @Override
        public void terminalMouseModeSet(@NotNull MouseMode mouseMode) {
        }

        @Override
        public void setMouseFormat(@NotNull MouseFormat mouseFormat) {
        }

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }

    private static final class NoopConnector implements TtyConnector {

        @Override
        public int read(char[] buf, int offset, int length) {
            return -1;
        }

        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void write(String string) {
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean ready() {
            return false;
        }

        @Override
        public String getName() {
            return "noop";
        }

        @Override
        public void close() {
        }
    }
}
