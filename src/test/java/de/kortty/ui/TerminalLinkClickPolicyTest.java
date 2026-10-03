package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.SelectionUtil;
import com.sithtermfx.core.model.TerminalSelection;
import de.kortty.ui.TerminalLinkClickPolicy.Action;
import de.kortty.ui.TerminalLinkClickPolicy.HitKind;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static de.kortty.ui.TerminalLinkClickPolicy.Action.OPEN;
import static de.kortty.ui.TerminalLinkClickPolicy.Action.PASS;
import static de.kortty.ui.TerminalLinkClickPolicy.Action.SELECT_LINE;
import static de.kortty.ui.TerminalLinkClickPolicy.Action.SELECT_WORD;
import static de.kortty.ui.TerminalLinkClickPolicy.Action.SWALLOW;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.AUTO;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.NONE;
import static de.kortty.ui.TerminalLinkClickPolicy.HitKind.OSC8;

/**
 * SithTermFX opened a terminal link on every plain primary click: twice on a double-click, after a
 * drag-selection released over it, and while tmux or vim had mouse reporting on. These cases pin
 * korTTY's gate: a link opens only on a single, still Cmd/Ctrl+click (never with AltGr, which
 * Windows reports as Ctrl+Alt), a plain click on an OSC 8 link does nothing, and a plain double or
 * triple click on one selects the word or the line as it does on other text.
 */
public class TerminalLinkClickPolicyTest {

    private static final int WIDTH = 40;
    private static final int HEIGHT = 4;

    @DataProvider
    public Object[][] namedClicks() {
        // description, primary, popupTrigger, shortcut, alt, clickCount, stillSincePress, hit, localMouseAction, expected
        return new Object[][] {
            {"Cmd/Ctrl+click on an OSC 8 link", true, false, true, false, 1, true, OSC8, true, OPEN},
            {"Cmd/Ctrl+click on an OSC 8 link with mouse reporting on", true, false, true, false, 1, true, OSC8, false, OPEN},
            {"Cmd/Ctrl+click on a plain-text link", true, false, true, false, 1, true, AUTO, true, OPEN},
            {"Cmd/Ctrl+double-click on an OSC 8 link", true, false, true, false, 2, true, OSC8, true, SWALLOW},
            {"Cmd/Ctrl+double-click on a plain-text link", true, false, true, false, 2, true, AUTO, true, SWALLOW},
            {"Cmd/Ctrl+triple-click on an OSC 8 link", true, false, true, false, 3, true, OSC8, true, SWALLOW},
            {"Cmd/Ctrl+drag released on an OSC 8 link", true, false, true, false, 1, false, OSC8, true, SWALLOW},
            {"Cmd/Ctrl+drag released on a plain-text link", true, false, true, false, 1, false, AUTO, true, SWALLOW},
            {"AltGr (Ctrl+Alt) click on an OSC 8 link", true, false, true, true, 1, true, OSC8, true, SWALLOW},
            {"AltGr (Ctrl+Alt) click on a plain-text link", true, false, true, true, 1, true, AUTO, true, PASS},
            {"AltGr (Ctrl+Alt) double-click on an OSC 8 link", true, false, true, true, 2, true, OSC8, true, SELECT_WORD},
            {"plain click on an OSC 8 link", true, false, false, false, 1, true, OSC8, true, SWALLOW},
            {"plain click on an OSC 8 link with mouse reporting on", true, false, false, false, 1, true, OSC8, false, SWALLOW},
            {"plain drag released on an OSC 8 link", true, false, false, false, 1, false, OSC8, true, SWALLOW},
            {"plain click on a plain-text link", true, false, false, false, 1, true, AUTO, true, PASS},
            {"plain double-click on a plain-text link", true, false, false, false, 2, true, AUTO, true, PASS},
            {"plain double-click on an OSC 8 link", true, false, false, false, 2, true, OSC8, true, SELECT_WORD},
            {"plain double-click on an OSC 8 link with mouse reporting on", true, false, false, false, 2, true, OSC8, false, SWALLOW},
            {"plain triple-click on an OSC 8 link", true, false, false, false, 3, true, OSC8, true, SELECT_LINE},
            {"plain triple-click on an OSC 8 link with mouse reporting on", true, false, false, false, 3, true, OSC8, false, SWALLOW},
            {"plain quadruple-click on an OSC 8 link", true, false, false, false, 4, true, OSC8, true, SELECT_LINE},
            {"Alt+click on an OSC 8 link", true, false, false, true, 1, true, OSC8, true, SWALLOW},
            {"secondary click on an OSC 8 link", false, false, false, false, 1, true, OSC8, true, PASS},
            {"Cmd/Ctrl+secondary click on an OSC 8 link", false, false, true, false, 1, true, OSC8, true, PASS},
            {"macOS Ctrl+click (popup trigger) on an OSC 8 link", true, true, false, false, 1, true, OSC8, true, PASS},
            {"Cmd/Ctrl+click on no link", true, false, true, false, 1, true, NONE, true, PASS},
            {"plain double-click on no link", true, false, false, false, 2, true, NONE, true, PASS},
            {"plain triple-click on no link", true, false, false, false, 3, true, NONE, true, PASS},
        };
    }

    @Test(dataProvider = "namedClicks")
    public void namedClick(String description, boolean primary, boolean popupTrigger, boolean shortcut, boolean alt,
                           int clickCount, boolean still, HitKind hit, boolean local, Action expected) {
        assertWithMessage(description)
            .that(TerminalLinkClickPolicy.decide(primary, popupTrigger, shortcut, alt, clickCount, still, hit, local))
            .isEqualTo(expected);
    }

    @Test
    public void fullTableHoldsTheRules() {
        int combinations = 0;
        for (boolean primary : new boolean[] {true, false}) {
            for (boolean popupTrigger : new boolean[] {true, false}) {
                for (boolean shortcut : new boolean[] {true, false}) {
                    for (boolean alt : new boolean[] {true, false}) {
                        for (int clickCount = 0; clickCount <= 4; clickCount++) {
                            for (boolean still : new boolean[] {true, false}) {
                                for (HitKind hit : HitKind.values()) {
                                    for (boolean local : new boolean[] {true, false}) {
                                        combinations++;
                                        Action action = TerminalLinkClickPolicy.decide(
                                            primary, popupTrigger, shortcut, alt, clickCount, still, hit, local);
                                        String row = "primary=" + primary + " popup=" + popupTrigger
                                            + " shortcut=" + shortcut + " alt=" + alt + " clicks=" + clickCount
                                            + " still=" + still + " hit=" + hit + " local=" + local + " -> " + action;
                                        boolean openGesture = shortcut && !alt;
                                        boolean linkClick = primary && !popupTrigger && hit != NONE;

                                        // Opens exactly on a single, still Cmd/Ctrl+click without Alt on a link.
                                        assertWithMessage(row).that(action == OPEN)
                                            .isEqualTo(linkClick && openGesture && clickCount == 1 && still);
                                        // Other buttons, the context-menu gesture and cells without a link
                                        // are left to SithTermFX and the split pane.
                                        if (!linkClick) {
                                            assertWithMessage(row).that(action).isEqualTo(PASS);
                                        }
                                        // SithTermFX would navigate on a primary click on an OSC 8 cell
                                        // instead of selecting, so such a click never reaches it.
                                        if (linkClick && hit == OSC8) {
                                            assertWithMessage(row).that(action).isNotEqualTo(PASS);
                                        }
                                        // A plain-text link is ordinary text to SithTermFX: without the open
                                        // gesture its selection works unchanged.
                                        if (linkClick && hit == AUTO && !openGesture) {
                                            assertWithMessage(row).that(action).isEqualTo(PASS);
                                        }
                                        // korTTY selects only on OSC 8 cells, only where SithTermFX would
                                        // select, and never on an open gesture.
                                        if (action == SELECT_WORD || action == SELECT_LINE) {
                                            assertWithMessage(row).that(hit).isEqualTo(OSC8);
                                            assertWithMessage(row).that(local).isTrue();
                                            assertWithMessage(row).that(openGesture).isFalse();
                                            if (action == SELECT_WORD) {
                                                assertWithMessage(row).that(clickCount).isEqualTo(2);
                                            } else {
                                                assertWithMessage(row).that(clickCount).isAtLeast(3);
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        assertThat(combinations).isEqualTo(2 * 2 * 2 * 2 * 5 * 2 * 3 * 2);
    }

    @Test
    public void doubleClickSelectsTheLinkWordLikeSithTermFx() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("see ");
        fixture.link("https://example.com/docs", "docs-link");
        fixture.write(" now");

        TerminalSelection selection = TerminalLinkClickPolicy.wordSelection(fixture.buffer, new Point(7, 0));

        assertThat(selection.getStart()).isEqualTo(new Point(4, 0));
        assertThat(selection.getEnd()).isEqualTo(new Point(12, 0));
        // The panel turns a selection into text the same way: the end cell is included.
        var run = selection.pointsForRun(WIDTH);
        assertThat(SelectionUtil.getSelectedText(run.getFirst(), run.getSecond(), fixture.buffer)).isEqualTo("docs-link");
    }

    @Test
    public void tripleClickSelectsTheWholeWrappedLine() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("first\r\n");
        // The emulator hands the buffer at most one row per write and wraps before the next one.
        fixture.terminal.setLinkUriStarted("https://example.com/long");
        fixture.write("L".repeat(WIDTH));
        fixture.write("L".repeat(5));
        fixture.terminal.setLinkUriFinished();
        fixture.write("\r\nlast");
        assertThat(fixture.buffer.getLine(1).isWrapped()).isTrue();

        for (int row : new int[] {1, 2}) {
            TerminalSelection selection = TerminalLinkClickPolicy.lineSelection(fixture.buffer, new Point(3, row));
            assertWithMessage("row " + row).that(selection.getStart()).isEqualTo(new Point(0, 1));
            assertWithMessage("row " + row).that(selection.getEnd()).isEqualTo(new Point(WIDTH, 2));
        }
    }

    @Test
    public void tripleClickOnTheLastRowStaysOnTheScreen() {
        EmulatorTextBufferFixture fixture = fixture();
        fixture.write("\r\n\r\n\r\n");
        fixture.link("https://example.com/", "bottom");
        // A last row marked as wrapping into the row below, which does not exist.
        fixture.buffer.getLine(HEIGHT - 1).setWrapped(true);

        TerminalSelection selection = TerminalLinkClickPolicy.lineSelection(fixture.buffer, new Point(2, HEIGHT - 1));

        assertThat(selection.getStart()).isEqualTo(new Point(0, HEIGHT - 1));
        assertThat(selection.getEnd()).isEqualTo(new Point(WIDTH, HEIGHT - 1));
    }

    @Test
    public void everyPanelInstallsTheClickFilter() throws IOException {
        String widget = source("src/main/java/de/kortty/ui/KorttyTermWidget.java").replaceAll("\\s+", " ");

        int panelConstructor = widget.indexOf("super(settingsProvider, terminalTextBuffer, styleState);");
        int install = widget.indexOf("TerminalLinkClickPolicy.install(this, "
            + "new TerminalLinkResolver(() -> plainTextLinkKinds.get()), target -> linkOpener.open(target));");
        int nextMember = widget.indexOf("void setPlainTextLinkKinds(", panelConstructor);
        assertThat(panelConstructor).isAtLeast(0);
        assertThat(install).isGreaterThan(panelConstructor);
        assertThat(install).isLessThan(nextMember);
        assertThat(widget).contains("private TerminalLinkOpener linkOpener = TerminalLinkOpener.system();");
    }

    @Test
    public void theClickGateIsACanvasFilterSoItRunsBeforeSithTermFx() throws IOException {
        String policy = NoHyperlinkFilterGuardTest.codeOnly(source("src/main/java/de/kortty/ui/TerminalLinkClickPolicy.java"));

        assertThat(policy).contains("panel.getCanvas().addEventFilter(MouseEvent.MOUSE_CLICKED,");
        assertThat(policy).doesNotContain("addEventHandler(");
        assertThat(policy).contains("event.consume();");
        assertThat(policy).contains("panel.getCanvas().requestFocus();");
    }

    /** A 40x4 text buffer driven by a real SithTermFX emulator, with korTTY's OSC 8 provider. */
    private static EmulatorTextBufferFixture fixture() {
        return new EmulatorTextBufferFixture(WIDTH, HEIGHT, 100, new KorttyOsc8LinkInfoProvider());
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }
}
