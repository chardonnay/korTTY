package de.kortty.ui;

import de.kortty.model.IconToolbarSettings;
import de.kortty.model.IconToolbarSettings.Entry;
import de.kortty.model.IconToolbarSettings.Kind;
import de.kortty.model.IconToolbarSettings.Section;
import java.util.List;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;

public class ToolbarEditorModelTest {
    @Test
    void cancelLeavesOriginalUntouchedAndFinishCommitsOrderAndSection() {
        var original = IconToolbarSettings.defaults();
        Entry first = original.entries().getFirst();
        var editor = new ToolbarEditorModel(original);
        editor.place(first, Section.SETTINGS, null);
        assertThat(original.entries().getFirst().section()).isEqualTo(Section.TERMINAL);
        var committed = editor.finish(false);
        assertThat(committed.entries().getLast().id()).isEqualTo(first.id());
        assertThat(committed.entries().getLast().section()).isEqualTo(Section.SETTINGS);
        assertThat(committed.isVisible()).isFalse();
        assertThat(committed.entries()).hasSize(original.entries().size());
    }

    @Test
    void moveBeforeAndKeyboardMovementHaveStableOrderWithoutDuplicates() {
        var editor = new ToolbarEditorModel(IconToolbarSettings.defaults());
        Entry first = editor.entries().get(0);
        Entry second = editor.entries().get(1);
        editor.place(second, Section.TERMINAL, first.id());
        assertThat(editor.entries().get(0).id()).isEqualTo(second.id());
        editor.step(second.id(), 1);
        assertThat(editor.entries().get(0).id()).isEqualTo(first.id());
        assertThat(editor.entries().get(1).id()).isEqualTo(second.id());
        editor.step(first.id(), -1);
        editor.place(first, Section.TERMINAL, first.id());
        assertThat(editor.entries()).hasSize(15);
    }

    @Test
    void connectionAndActionDestinationsCannotBeConfusedButSeparatorsCanMove() {
        var editor = new ToolbarEditorModel(new IconToolbarSettings());
        Entry connection = Entry.create(Kind.CONNECTION, Section.CONNECTIONS, "demo");
        editor.place(connection, Section.TOOLS, null);
        assertThat(editor.entries()).isEmpty();
        editor.place(connection, Section.CONNECTIONS, null);
        editor.place(Entry.action("toolbar.localShell", Section.TERMINAL), Section.CONNECTIONS, null);
        Entry separator = Entry.create(Kind.SEPARATOR, Section.SETTINGS, "");
        editor.place(separator, Section.CONNECTIONS, null);
        assertThat(editor.entries()).hasSize(2);
        editor.remove(connection.id());
        assertThat(editor.entries().getFirst().kind()).isEqualTo(Kind.SEPARATOR);
    }

    @Test
    void emptyAndResetAreOnlySavedOnFinish() {
        var original = IconToolbarSettings.defaults();
        var editor = new ToolbarEditorModel(original);
        List.copyOf(editor.entries()).forEach(entry -> editor.remove(entry.id()));
        assertThat(editor.finish(true).entries()).isEmpty();
        assertThat(original.entries()).hasSize(15);
        editor.reset();
        assertThat(editor.finish(true).entries()).hasSize(15);
    }

    @Test
    void groupRenameAlsoUpdatesUnsavedButtonsWithoutChangingTheirPlacement() {
        var editor = new ToolbarEditorModel(new IconToolbarSettings());
        Entry group = Entry.create(Kind.GROUP, Section.CONNECTIONS, "Work/Prod/Web");
        editor.place(group, Section.CONNECTIONS, null);
        editor.renameGroup("Work/Prod", "Work/Live", "/");
        assertThat(editor.entries().getFirst().target()).isEqualTo("Work/Live/Web");
        assertThat(editor.entries().getFirst().id()).isEqualTo(group.id());
        assertThat(editor.entries().getFirst().section()).isEqualTo(Section.CONNECTIONS);
    }
}
