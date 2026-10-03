package de.kortty.ui.actions;

import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

/**
 * The command palette's terminal and tab commands, run against a stub terminal tab in a registry as
 * MainWindow builds it: enabled only while a terminal tab is selected, Broadcast Mode switchable on
 * with a second pane and off at any time, every command acting on the tab selected when it runs,
 * the right-click menus' labels under Terminal and Tabs, and no second Find.
 */
class TerminalPaletteActionsTest {

    /** A terminal tab that records the commands it gets. */
    private static final class StubTerminal implements TerminalPaletteActions.Target {
        final List<String> calls = new ArrayList<>();
        int panes = 1;
        boolean broadcasting;

        @Override
        public void clearBuffer() {
            calls.add("clearBuffer");
        }

        @Override
        public void splitRight() {
            calls.add("splitRight");
            panes++;
        }

        @Override
        public void splitDown() {
            calls.add("splitDown");
            panes++;
        }

        @Override
        public int paneCount() {
            return panes;
        }

        @Override
        public boolean isBroadcasting() {
            return broadcasting;
        }

        @Override
        public void toggleBroadcast() {
            calls.add("toggleBroadcast");
            broadcasting = !broadcasting;
        }

        @Override
        public void duplicate() {
            calls.add("duplicate");
        }

        @Override
        public void reconnect() {
            calls.add("reconnect");
        }
    }

    private static final List<String> IDS = List.of(
        TerminalPaletteActions.CLEAR_BUFFER, TerminalPaletteActions.SPLIT_RIGHT, TerminalPaletteActions.SPLIT_DOWN,
        TerminalPaletteActions.BROADCAST, TerminalPaletteActions.DUPLICATE, TerminalPaletteActions.RECONNECT);

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
        }
        assertThat(find(registry, "terminal.contextMenu.clearBuffer").category())
            .isEqualTo("[palette.category.terminal]");
        assertThat(find(registry, "terminal.contextMenu.splitRightSame").category())
            .isEqualTo("[palette.category.terminal]");
        assertThat(find(registry, "terminal.contextMenu.splitDownSame").category())
            .isEqualTo("[palette.category.terminal]");
        assertThat(find(registry, "terminal.contextMenu.broadcastMode").category())
            .isEqualTo("[palette.category.terminal]");
        assertThat(find(registry, "tab.contextMenu.duplicate").category()).isEqualTo("[palette.category.tab]");
        assertThat(find(registry, "dashboard.reconnect").category()).isEqualTo("[palette.category.tab]");
    }

    @Test
    void findIsNotListedASecondTime() {
        List<String> ids = registry(new AtomicReference<>(), null).snapshot().stream().map(AppAction::id).toList();

        assertThat(ids).doesNotContain("terminal.contextMenu.find");
        assertThat(TerminalPaletteActions.KEYS).doesNotContain("terminal.contextMenu.find");
        assertThat(TerminalPaletteActions.KEYS).containsAtLeastElementsIn(IDS);
        assertThat(TerminalPaletteActions.KEYS).containsAtLeast("palette.category.terminal", "palette.category.tab");
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
    void aTerminalTabWithOnePaneEnablesEverythingButSwitchingBroadcastOn() {
        StubTerminal terminal = new StubTerminal();
        ActionRegistry registry = registry(new AtomicReference<>(terminal), null);

        for (String id : IDS) {
            boolean broadcast = id.equals(TerminalPaletteActions.BROADCAST);
            assertThat(find(registry, id).isEnabled()).isEqualTo(!broadcast);
        }
        assertThat(find(registry, TerminalPaletteActions.BROADCAST).isCheckable()).isTrue();
        assertThat(registry.run(TerminalPaletteActions.BROADCAST)).isFalse();
        assertThat(terminal.calls).isEmpty();
    }

    @Test
    void broadcastModeNeedsASecondPaneToSwitchOnButAlwaysSwitchesOff() {
        StubTerminal terminal = new StubTerminal();
        ActionRegistry registry = registry(new AtomicReference<>(terminal), null);
        AppAction broadcast = find(registry, TerminalPaletteActions.BROADCAST);

        terminal.panes = 2;
        assertThat(broadcast.isEnabled()).isTrue();
        assertThat(broadcast.isChecked()).isFalse();
        assertThat(registry.run(TerminalPaletteActions.BROADCAST)).isTrue();
        assertThat(broadcast.isChecked()).isTrue();

        // The second pane closed while broadcast mode was on: it can still be switched off.
        terminal.panes = 1;
        assertThat(broadcast.isEnabled()).isTrue();
        assertThat(registry.run(TerminalPaletteActions.BROADCAST)).isTrue();
        assertThat(broadcast.isChecked()).isFalse();
        assertThat(broadcast.isEnabled()).isFalse();
        assertThat(terminal.calls).containsExactly("toggleBroadcast", "toggleBroadcast").inOrder();
    }

    @Test
    void everyCommandReachesItsTerminalTab() {
        StubTerminal terminal = new StubTerminal();
        ActionRegistry registry = registry(new AtomicReference<>(terminal), null);

        for (String id : IDS) {
            assertThat(registry.run(id)).isTrue();
        }

        // Two splits make a second pane, so Broadcast Mode switches on.
        assertThat(terminal.calls).containsExactly("clearBuffer", "splitRight", "splitDown", "toggleBroadcast",
            "duplicate", "reconnect").inOrder();
        assertThat(terminal.broadcasting).isTrue();
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
        terminal.panes = 2;
        terminal.broadcasting = true;
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
            assertThat(entry.checked()).isEqualTo(entry.key().equals("action:" + TerminalPaletteActions.BROADCAST));
        }
        withTerminal.get(0).run().run();
        assertThat(terminal.calls).containsExactly("clearBuffer");
    }
}
