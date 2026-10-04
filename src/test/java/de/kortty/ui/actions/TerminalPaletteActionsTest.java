package de.kortty.ui.actions;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The command palette's terminal and tab commands, run against a stub terminal tab in a registry as
 * MainWindow builds it: enabled only while a terminal tab is selected, every command acting on the
 * tab selected when it runs, the right-click menus' labels under Terminal and Tabs, the tab's watch
 * switches (Monitor for Activity, Monitor for Silence) with their check marks, and no second row for
 * a command the menu bar has (Find, the same-server splits and broadcast mode, which come from Edit
 * and View → Panes).
 */
class TerminalPaletteActionsTest {

    /** A terminal tab that records the commands it gets. */
    private static final class StubTerminal implements TerminalPaletteActions.Target {
        final List<String> calls = new ArrayList<>();

        @Override
        public void clearBuffer() {
            calls.add("clearBuffer");
        }

        @Override
        public void duplicate() {
            calls.add("duplicate");
        }

        @Override
        public void reconnect() {
            calls.add("reconnect");
        }

        boolean activity;
        boolean silence;

        @Override
        public boolean isMonitoringActivity() {
            return activity;
        }

        @Override
        public void toggleMonitoringActivity() {
            activity = !activity;
            calls.add("monitorActivity");
        }

        @Override
        public boolean isMonitoringSilence() {
            return silence;
        }

        @Override
        public void toggleMonitoringSilence() {
            silence = !silence;
            calls.add("monitorSilence");
        }
    }

    private static final List<String> IDS = List.of(
        TerminalPaletteActions.CLEAR_BUFFER, TerminalPaletteActions.DUPLICATE, TerminalPaletteActions.RECONNECT,
        TerminalPaletteActions.MONITOR_ACTIVITY, TerminalPaletteActions.MONITOR_SILENCE);

    /** The commands that are switches, with a check mark. */
    private static final List<String> SWITCHES = List.of(
        TerminalPaletteActions.MONITOR_ACTIVITY, TerminalPaletteActions.MONITOR_SILENCE);

    /** The key itself in brackets, so a test sees which key a text came from. */
    private static String text(String key) {
        return "[" + key + "]";
    }

    private static ActionRegistry registry(AtomicReference<TerminalPaletteActions.Target> selected,
                                           KeyCombination clearBufferChord) {
        ActionRegistry registry = new ActionRegistry();
        List<AppAction> actions =
            TerminalPaletteActions.actions(selected::get, TerminalPaletteActionsTest::text, clearBufferChord);
        registry.addContributor(() -> actions);
        return registry;
    }

    private static AppAction find(ActionRegistry registry, String id) {
        return registry.find(id).orElseThrow();
    }

    @Test
    void theCommandsCarryTheRightClickMenusLabelsUnderTerminalAndTabs() {
        ActionRegistry registry = registry(new AtomicReference<>(), null);

        List<AppAction> actions = registry.snapshot();

        assertThat(actions.stream().map(AppAction::id).toList()).containsExactlyElementsIn(IDS).inOrder();
        for (AppAction action : actions) {
            assertThat(action.label()).isEqualTo(text(action.id()));
            assertThat(action.stableId()).isTrue();
            assertThat(action.policyLocked()).isFalse();
            assertWithMessage(action.id()).that(action.isCheckable()).isEqualTo(SWITCHES.contains(action.id()));
        }
        assertThat(find(registry, "terminal.contextMenu.clearBuffer").category())
            .isEqualTo("[palette.category.terminal]");
        assertThat(find(registry, "tab.contextMenu.duplicate").category()).isEqualTo("[palette.category.tab]");
        assertThat(find(registry, "dashboard.reconnect").category()).isEqualTo("[palette.category.tab]");
        assertThat(find(registry, "tab.contextMenu.monitorActivity").category()).isEqualTo("[palette.category.tab]");
        assertThat(find(registry, "tab.contextMenu.monitorSilence").category()).isEqualTo("[palette.category.tab]");
    }

    @Test
    void theWatchSwitchesShowAndToggleTheStateOfTheSelectedTab() {
        StubTerminal first = new StubTerminal();
        StubTerminal second = new StubTerminal();
        AtomicReference<TerminalPaletteActions.Target> selected = new AtomicReference<>(first);
        ActionRegistry registry = registry(selected, null);
        AppAction activity = find(registry, TerminalPaletteActions.MONITOR_ACTIVITY);
        AppAction silence = find(registry, TerminalPaletteActions.MONITOR_SILENCE);

        assertWithMessage("off until switched on").that(activity.isChecked()).isFalse();
        assertThat(silence.isChecked()).isFalse();
        assertThat(ActionRegistry.runIfEnabled(activity)).isTrue();
        assertThat(activity.isChecked()).isTrue();
        assertThat(silence.isChecked()).isFalse();
        assertThat(ActionRegistry.runIfEnabled(silence)).isTrue();
        assertThat(ActionRegistry.runIfEnabled(activity)).isTrue();
        assertThat(activity.isChecked()).isFalse();
        assertThat(silence.isChecked()).isTrue();

        selected.set(second);
        assertWithMessage("each tab has its own switches").that(silence.isChecked()).isFalse();
        selected.set(null);
        assertThat(activity.isChecked()).isFalse();
        assertThat(ActionRegistry.runIfEnabled(activity)).isFalse();
        assertThat(first.calls).containsExactly("monitorActivity", "monitorSilence", "monitorActivity").inOrder();
        assertThat(second.calls).isEmpty();
    }

    /**
     * A menu-bar item the palette harvests is not listed a second time: Edit → Find…, and View → Panes →
     * Split Right / Split Down / Broadcast to All Panes of This Tab, which do what the right-click
     * menu's same-server splits and Broadcast Mode do.
     */
    @Test
    void commandsTheMenuBarHasAreNotListedASecondTime() {
        List<String> ids = registry(new AtomicReference<>(), null).snapshot().stream().map(AppAction::id).toList();

        for (String duplicate : List.of("terminal.contextMenu.find", "terminal.contextMenu.splitRightSame",
                "terminal.contextMenu.splitDownSame", "terminal.contextMenu.broadcastMode")) {
            assertWithMessage("a menu-bar item already offers " + duplicate).that(ids).doesNotContain(duplicate);
            assertThat(TerminalPaletteActions.KEYS).doesNotContain(duplicate);
        }
        assertThat(TerminalPaletteActions.KEYS).containsExactly(TerminalPaletteActions.CLEAR_BUFFER,
            TerminalPaletteActions.DUPLICATE, TerminalPaletteActions.RECONNECT, TerminalPaletteActions.MONITOR_ACTIVITY,
            TerminalPaletteActions.MONITOR_SILENCE, "palette.category.terminal", "palette.category.tab").inOrder();
    }

    @Test
    void withoutATerminalTabEveryCommandIsDisabledAndNothingRuns() {
        ActionRegistry registry = registry(new AtomicReference<>(), null);

        for (AppAction action : registry.snapshot()) {
            assertThat(action.isEnabled()).isFalse();
            assertThat(action.isChecked()).isFalse();
        }
        for (String id : IDS) {
            assertThat(registry.run(id)).isFalse();
        }
    }

    @Test
    void everyCommandReachesItsTerminalTab() {
        StubTerminal terminal = new StubTerminal();
        ActionRegistry registry = registry(new AtomicReference<>(terminal), null);

        for (String id : IDS) {
            assertThat(find(registry, id).isEnabled()).isTrue();
            assertThat(registry.run(id)).isTrue();
        }

        assertThat(terminal.calls).containsExactly("clearBuffer", "duplicate", "reconnect", "monitorActivity",
            "monitorSilence").inOrder();
    }

    @Test
    void aCommandActsOnTheTabSelectedWhenItRuns() {
        StubTerminal first = new StubTerminal();
        StubTerminal second = new StubTerminal();
        AtomicReference<TerminalPaletteActions.Target> selected = new AtomicReference<>(first);
        ActionRegistry registry = registry(selected, null);
        List<AppAction> snapshot = registry.snapshot();
        AppAction clear = snapshot.get(0);

        selected.set(second);
        assertThat(ActionRegistry.runIfEnabled(clear)).isTrue();
        selected.set(null);
        assertThat(ActionRegistry.runIfEnabled(clear)).isFalse();

        assertThat(first.calls).isEmpty();
        assertThat(second.calls).containsExactly("clearBuffer");
    }

    @Test
    void clearBufferShowsTheTerminalsOwnKeyWhereThereIsOne() {
        KeyCombination commandK = new KeyCodeCombination(KeyCode.K, KeyCombination.META_DOWN);

        ActionRegistry macOs = registry(new AtomicReference<>(), commandK);
        ActionRegistry windows = registry(new AtomicReference<>(), null);

        assertThat(find(macOs, TerminalPaletteActions.CLEAR_BUFFER).accelerator()).isEqualTo(commandK);
        assertThat(find(windows, TerminalPaletteActions.CLEAR_BUFFER).accelerator()).isNull();
        for (String id : IDS.subList(1, IDS.size())) {
            assertThat(find(macOs, id).accelerator()).isNull();
        }
    }

    @Test
    void thePaletteListsThemAsCommandsThatSayWhyTheyAreGreyedOut() {
        StubTerminal terminal = new StubTerminal();
        AtomicReference<TerminalPaletteActions.Target> selected = new AtomicReference<>();
        ActionPaletteSource source = new ActionPaletteSource(registry(selected, null), chord -> "chord",
            () -> "managed", () -> "unavailable");

        List<PaletteEntry> withoutTerminal = source.entries();
        selected.set(terminal);
        List<PaletteEntry> withTerminal = source.entries();

        assertThat(withoutTerminal.stream().map(PaletteEntry::key).toList())
            .containsExactlyElementsIn(IDS.stream().map(id -> "action:" + id).toList()).inOrder();
        for (PaletteEntry entry : withoutTerminal) {
            assertThat(entry.kind()).isEqualTo(PaletteEntry.Kind.ACTION);
            assertThat(entry.enabled()).isFalse();
            assertThat(entry.disabledReason()).isEqualTo("unavailable");
        }
        for (PaletteEntry entry : withTerminal) {
            assertThat(entry.enabled()).isTrue();
            assertThat(entry.checked()).isFalse();
        }
        withTerminal.get(0).run().run();
        assertThat(terminal.calls).containsExactly("clearBuffer");
    }
}
