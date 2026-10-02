package com.sithtermfx.ui.split;

import de.kortty.ui.TerminalPaneActions;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The terminal's right-click Copy, Paste, Clear Buffer and Find entries, and Extras &gt; Font Size
 * &gt; Increase/Decrease, did nothing for months: they looked their target methods up by reflection
 * on the widget's runtime class, which became a subclass that declares none of them. The menu now
 * calls {@link TerminalPaneActions} directly; this test fires every entry against a recording
 * implementation and checks that each one reaches the pane exactly once, in menu order.
 *
 * <p>Toolkit-free: {@code editActions}/{@code fontSizeActions} build no JavaFX controls.
 */
public class TerminalSplitPaneEditActionsTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties", "messages_de.properties", "messages_it.properties", "messages_es.properties",
        "messages_pt.properties", "messages_fr.properties", "messages_hr.properties", "messages_nl.properties");

    @Test
    public void editEntriesCallThePaneInMenuOrder() {
        RecordingPaneActions pane = new RecordingPaneActions();

        List<TerminalSplitPane.TerminalMenuAction> actions = TerminalSplitPane.editActions(pane);

        assertThat(keys(actions)).containsExactly(
            "terminal.contextMenu.copy",
            "terminal.contextMenu.paste",
            "terminal.contextMenu.clearBuffer",
            "terminal.contextMenu.find").inOrder();
        for (TerminalSplitPane.TerminalMenuAction action : actions) {
            action.action().run();
        }
        assertThat(pane.calls).containsExactly("copySelection", "paste", "clearBuffer", "showFind").inOrder();
    }

    @Test
    public void eachEditEntryCallsOnlyItsOwnCommand() {
        List<String> expected = List.of("copySelection", "paste", "clearBuffer", "showFind");
        for (int i = 0; i < expected.size(); i++) {
            RecordingPaneActions pane = new RecordingPaneActions();
            TerminalSplitPane.editActions(pane).get(i).action().run();
            assertThat(pane.calls).containsExactly(expected.get(i));
        }
    }

    @Test
    public void fontSizeEntriesCallThePaneAndTheGivenReset() {
        RecordingPaneActions pane = new RecordingPaneActions();
        List<String> resets = new ArrayList<>();

        List<TerminalSplitPane.TerminalMenuAction> actions =
            TerminalSplitPane.fontSizeActions(pane, () -> resets.add("reset"));

        assertThat(keys(actions)).containsExactly(
            "terminal.contextMenu.increase",
            "terminal.contextMenu.decrease",
            "terminal.contextMenu.reset").inOrder();
        actions.get(0).action().run();
        assertThat(pane.calls).containsExactly("increaseFontSize");
        actions.get(1).action().run();
        assertThat(pane.calls).containsExactly("increaseFontSize", "decreaseFontSize").inOrder();
        assertThat(resets).isEmpty();
        actions.get(2).action().run();
        assertThat(resets).containsExactly("reset");
        assertThat(pane.calls).hasSize(2);
    }

    @Test
    public void everyMenuLabelIsTranslatedInEveryBundle() throws IOException {
        List<String> menuKeys = new ArrayList<>(keys(TerminalSplitPane.editActions(new RecordingPaneActions())));
        menuKeys.addAll(keys(TerminalSplitPane.fontSizeActions(new RecordingPaneActions(), () -> { })));
        for (String bundle : BUNDLES) {
            Properties properties = new Properties();
            try (InputStream in = TerminalSplitPaneEditActionsTest.class.getResourceAsStream("/i18n/" + bundle)) {
                assertThat(in).isNotNull();
                properties.load(in);
            }
            for (String key : menuKeys) {
                assertWithMessage(bundle + " " + key).that(properties.getProperty(key)).isNotEmpty();
            }
        }
    }

    private static List<String> keys(List<TerminalSplitPane.TerminalMenuAction> actions) {
        return actions.stream().map(TerminalSplitPane.TerminalMenuAction::i18nKey).toList();
    }

    private static final class RecordingPaneActions implements TerminalPaneActions {
        final List<String> calls = new ArrayList<>();

        @Override
        public void copySelection() {
            calls.add("copySelection");
        }

        @Override
        public void paste() {
            calls.add("paste");
        }

        @Override
        public void clearBuffer() {
            calls.add("clearBuffer");
        }

        @Override
        public void showFind() {
            calls.add("showFind");
        }

        @Override
        public void increaseFontSize() {
            calls.add("increaseFontSize");
        }

        @Override
        public void decreaseFontSize() {
            calls.add("decreaseFontSize");
        }
    }
}
