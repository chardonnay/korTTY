package de.kortty.ui;

import javafx.scene.control.Tab;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Where "Insert into terminal/editor" from the snippet library goes. In tab mode the snippet
 * workspace is itself the selected tab, so the target must come from the last selected terminal
 * (or file editor) tab instead of the selected one.
 */
class MainWindowInsertTargetTest {

    /** Stand-ins for TerminalTab / FileEditorTab (which need a live terminal or editor). */
    private static final class TerminalLike extends Tab {
    }

    private static final class EditorLike extends Tab {
    }

    private final Tab workspace = new Tab("Snippets");
    private final TerminalLike first = new TerminalLike();
    private final TerminalLike second = new TerminalLike();
    private final EditorLike editor = new EditorLike();

    @Test
    void selectedTabOfTheRequestedTypeWins() {
        List<Tab> open = List.of(workspace, first, second, editor);

        assertThat(MainWindow.chooseInsertTarget(TerminalLike.class, second, first, open)).isSameInstanceAs(second);
        assertThat(MainWindow.chooseInsertTarget(EditorLike.class, editor, null, open)).isSameInstanceAs(editor);
    }

    @Test
    void workspaceSelectedFallsBackToTheLastSelectedTabOfThatType() {
        List<Tab> open = List.of(workspace, first, second, editor);

        assertThat(MainWindow.chooseInsertTarget(TerminalLike.class, workspace, first, open)).isSameInstanceAs(first);
        assertThat(MainWindow.chooseInsertTarget(EditorLike.class, workspace, editor, open)).isSameInstanceAs(editor);
    }

    @Test
    void closedLastTabIsIgnoredAndTheOnlyOpenTabOfThatTypeIsUsed() {
        List<Tab> open = List.of(workspace, second);

        assertThat(MainWindow.chooseInsertTarget(TerminalLike.class, workspace, first, open)).isSameInstanceAs(second);
    }

    @Test
    void severalCandidatesWithoutHistoryAreAmbiguous() {
        List<Tab> open = List.of(workspace, first, second);

        assertThat(MainWindow.chooseInsertTarget(TerminalLike.class, workspace, null, open)).isNull();
    }

    @Test
    void noTabOfThatTypeMeansNoTarget() {
        List<Tab> open = List.of(workspace, editor);

        assertThat(MainWindow.chooseInsertTarget(TerminalLike.class, workspace, null, open)).isNull();
        assertThat(MainWindow.chooseInsertTarget(TerminalLike.class, workspace, editor, open)).isNull();
        assertThat(MainWindow.chooseInsertTarget(TerminalLike.class, null, null, List.of())).isNull();
        Tab none = MainWindow.<Tab>chooseInsertTarget(null, first, first, List.of(first));
        assertThat(none).isNull();
    }

    @Test
    void selectedTabNoLongerOpenIsNotATarget() {
        List<Tab> open = List.of(workspace, second);

        assertThat(MainWindow.chooseInsertTarget(TerminalLike.class, first, null, open)).isSameInstanceAs(second);
    }
}
