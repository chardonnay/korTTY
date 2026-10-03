package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.ArrayTerminalDataStream;
import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.emulator.SithEmulator;
import com.sithtermfx.core.emulator.mouse.MouseFormat;
import com.sithtermfx.core.emulator.mouse.MouseMode;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.Test;

/**
 * What a shell's real output does to the tab title: escape sequences run through the SithTermFX
 * emulator into a {@link SithTerminal}, whose application-title listener feeds a
 * {@link ShellTitleTracker} the way {@code TerminalView} wires it. Pins the vendor behaviour the
 * feature relies on (OSC 0 and 2 reach the listener, XTWINOPS 23 hands back the pane's start title)
 * so a SithTermFX upgrade that changes it fails here.
 */
class ShellTitleEmulatorContractTest {

    private static final String ESC = "\u001B";
    private static final String BEL = "\u0007";
    private static final String ST = ESC + "\\";

    @Test
    void osc0AndOsc2RenameTheTabWithEitherTerminator() throws IOException {
        Session session = new Session();

        session.process(ESC + "]0;daniel@web01: ~" + BEL + "$ ");
        assertThat(session.shownTitle()).isEqualTo("daniel@web01: ~");

        session.process(ESC + "]2;vim notes.txt" + ST);
        assertThat(session.shownTitle()).isEqualTo("vim notes.txt");
    }

    @Test
    void controlAndBidiCharactersInATitleNeverReachTheTab() throws IOException {
        Session session = new Session();

        session.process(ESC + "]0;prod‮bd-gnitset\u0085db" + BEL);

        assertThat(session.shownTitle()).isEqualTo("prodbd-gnitset db");
    }

    @Test
    void aProgramThatRestoresTheStartTitleBringsBackTheConnectionName() throws IOException {
        Session session = new Session();

        // vim saves the title it finds, sets its own and restores the saved one on exit.
        session.process(ESC + "[22;0t" + ESC + "]2;notes.txt - VIM" + BEL);
        assertThat(session.shownTitle()).isEqualTo("notes.txt - VIM");
        session.process(ESC + "[23;0t");

        assertWithMessage("the pane's start title means no title, so the tab shows the connection's name")
                .that(session.shownTitle()).isNull();
        assertThat(session.listenerCalls).containsExactly("notes.txt - VIM", null).inOrder();
    }

    @Test
    void aRestoreAfterARealTitleBringsThatTitleBack() throws IOException {
        Session session = new Session();

        session.process(ESC + "]0;daniel@web01: ~" + BEL + ESC + "[22;0t" + ESC + "]2;htop" + BEL);
        session.process(ESC + "[23;0t");

        assertThat(session.shownTitle()).isEqualTo("daniel@web01: ~");
    }

    @Test
    void manyTitlesInOneChunkReachTheTabAsOneHandOver() throws IOException {
        Session session = new Session();
        StringBuilder spinner = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            spinner.append(ESC).append("]0;working ").append(i).append(BEL);
        }

        session.processWithoutDraining(spinner.toString());

        assertThat(session.fxQueue).hasSize(1);
        session.drainFx();
        assertThat(session.listenerCalls).containsExactly("working 199");
    }

    /** One pane: emulator, terminal and the tracker wired like {@code TerminalView} does it. */
    private static final class Session {
        private final SithTerminal terminal;
        private final Deque<Runnable> fxQueue = new ArrayDeque<>();
        private final List<String> listenerCalls = new ArrayList<>();
        private final ShellTitleTracker<String> tracker =
                new ShellTitleTracker<>(fxQueue::add, () -> "pane", () -> true);

        Session() {
            StyleState styleState = new StyleState();
            TerminalTextBuffer buffer = new TerminalTextBuffer(40, 5, styleState, 10, null);
            terminal = new SithTerminal(new TitleDisplay(), buffer, styleState);
            terminal.addApplicationTitleListener(title -> tracker.titleChanged("pane", title));
            tracker.setListener(listenerCalls::add);
        }

        void process(String data) throws IOException {
            processWithoutDraining(data);
            drainFx();
        }

        void processWithoutDraining(String data) throws IOException {
            SithEmulator emulator = new SithEmulator(new ArrayTerminalDataStream(data.toCharArray()), terminal);
            while (emulator.hasNext()) {
                emulator.next();
            }
        }

        void drainFx() {
            while (!fxQueue.isEmpty()) {
                fxQueue.poll().run();
            }
        }

        String shownTitle() {
            return tracker.shownTitle();
        }
    }

    /** Keeps the window title like SithTermFX's TerminalPanel, which starts with "Terminal". */
    private static final class TitleDisplay implements TerminalDisplay {
        private String title = ShellTitleTracker.PANE_DEFAULT_TITLE;

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
            return title;
        }

        @Override
        public void setWindowTitle(@NotNull String windowTitle) {
            title = windowTitle;
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
}
