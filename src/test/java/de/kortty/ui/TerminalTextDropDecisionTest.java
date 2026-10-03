package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.ui.TerminalTextDropDecision.Action;
import de.kortty.ui.TerminalTextDropDecision.Drag;
import org.testng.annotations.Test;

/**
 * What a drag over a terminal does: files keep their SFTP copy, text is pasted only as a copy, the
 * split pane keeps its pane moves, the drag-and-drop setting turns both off, and the internal
 * clipboard mode keeps out text from other applications.
 */
class TerminalTextDropDecisionTest {

    /** Text from another application, such as a browser or an editor, which offers a copy. */
    private static final Drag FOREIGN_TEXT = new Drag(false, false, true, true, false);

    /** Text dragged inside korTTY, such as from another korTTY window. */
    private static final Drag KORTTY_TEXT = new Drag(false, false, true, true, true);

    @Test
    void textFromAnotherApplicationIsPasted() {
        assertThat(TerminalTextDropDecision.decide(FOREIGN_TEXT, true, false)).isEqualTo(Action.PASTE_TEXT);
        assertThat(TerminalTextDropDecision.decide(KORTTY_TEXT, true, false)).isEqualTo(Action.PASTE_TEXT);
    }

    @Test
    void internalClipboardModeRefusesTextFromAnotherApplicationOnly() {
        assertThat(TerminalTextDropDecision.decide(FOREIGN_TEXT, true, true)).isEqualTo(Action.REFUSE);
        assertWithMessage("text dragged inside korTTY stays inside korTTY")
            .that(TerminalTextDropDecision.decide(KORTTY_TEXT, true, true)).isEqualTo(Action.PASTE_TEXT);
        assertWithMessage("a foreign drag that offers no copy is refused all the same")
            .that(TerminalTextDropDecision.decide(new Drag(false, false, true, false, false), true, true))
            .isEqualTo(Action.REFUSE);
    }

    @Test
    void splitPaneMovesAreLeftToTheSplitPane() {
        for (boolean files : new boolean[] {false, true}) {
            for (boolean text : new boolean[] {false, true}) {
                for (boolean internal : new boolean[] {false, true}) {
                    Drag move = new Drag(true, files, text, true, true);
                    assertWithMessage("files=%s text=%s internal=%s", files, text, internal)
                        .that(TerminalTextDropDecision.decide(move, true, internal)).isEqualTo(Action.IGNORE);
                }
            }
        }
    }

    @Test
    void filesTakePrecedenceOverTheirPathAsText() {
        Drag fromFileManager = new Drag(false, true, true, true, false);
        Drag filesOnly = new Drag(false, true, false, true, false);

        assertThat(TerminalTextDropDecision.decide(fromFileManager, true, false)).isEqualTo(Action.COPY_FILES);
        assertThat(TerminalTextDropDecision.decide(filesOnly, true, false)).isEqualTo(Action.COPY_FILES);
        assertWithMessage("the clipboard mode does not govern file copies, as before text drops existed")
            .that(TerminalTextDropDecision.decide(fromFileManager, true, true)).isEqualTo(Action.COPY_FILES);
    }

    @Test
    void theDragAndDropSettingTurnsOffFilesAndText() {
        Drag files = new Drag(false, true, false, true, false);

        assertThat(TerminalTextDropDecision.decide(files, false, false)).isEqualTo(Action.IGNORE);
        assertThat(TerminalTextDropDecision.decide(FOREIGN_TEXT, false, false)).isEqualTo(Action.IGNORE);
        assertThat(TerminalTextDropDecision.decide(FOREIGN_TEXT, false, true)).isEqualTo(Action.IGNORE);
    }

    @Test
    void anInAppMoveIsNotAPaste() {
        // The connection tree drags a connection as its id in a plain string, offering a move only.
        Drag connectionFromTree = new Drag(false, false, true, false, true);

        assertThat(TerminalTextDropDecision.decide(connectionFromTree, true, false)).isEqualTo(Action.IGNORE);
        assertThat(TerminalTextDropDecision.decide(connectionFromTree, true, true)).isEqualTo(Action.IGNORE);
    }

    @Test
    void aMoveOnlyDragFromAnotherApplicationIsNotTaken() {
        // Accepting a move would make the source application delete the text it dragged.
        Drag moveOnly = new Drag(false, false, true, false, false);

        assertThat(TerminalTextDropDecision.decide(moveOnly, true, false)).isEqualTo(Action.IGNORE);
    }

    @Test
    void aDragWithNeitherFilesNorTextIsNotOurs() {
        // A tab moved between windows, or snippets dragged onto a folder, carry only korTTY formats.
        Drag tabTransfer = new Drag(false, false, false, false, true);

        assertThat(TerminalTextDropDecision.decide(tabTransfer, true, false)).isEqualTo(Action.IGNORE);
        assertThat(TerminalTextDropDecision.decide(tabTransfer, true, true)).isEqualTo(Action.IGNORE);
    }
}
