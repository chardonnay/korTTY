package de.kortty.ui.actions;

import javafx.scene.input.KeyCombination;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The command palette's commands for the selected terminal tab that the menu bar does not have:
 * <b>Clear Buffer</b>, the two same-server splits and <b>Broadcast Mode</b> of the focused pane,
 * and <b>Duplicate</b> and <b>Reconnect</b> of the tab. They carry the labels of the terminal's and
 * the tab's right-click menus (and the Dashboard's Reconnect), under the categories Terminal and
 * Tabs. <b>Find</b> is not among them: <i>Edit → Find…</i> is a menu-bar item and the palette lists
 * it already.
 *
 * <p>Every command asks for the selected terminal tab when the palette reads its state and again
 * when it runs, so it is enabled only while a terminal tab is selected, and it acts on the tab that
 * is selected when it runs. Broadcast Mode is a toggle: it can be switched on once the tab has a
 * second pane, and switched off at any time. Its id, like the others', is the i18n key of its label.
 *
 * <p>FX-free: the window supplies the selected tab as a {@link Target} and the texts, so a test can
 * stand in a stub tab.
 */
public final class TerminalPaletteActions {

    /** Clear Buffer of the focused pane. */
    public static final String CLEAR_BUFFER = "terminal.contextMenu.clearBuffer";
    /** Split Right (same server) of the focused pane. */
    public static final String SPLIT_RIGHT = "terminal.contextMenu.splitRightSame";
    /** Split Down (same server) of the focused pane. */
    public static final String SPLIT_DOWN = "terminal.contextMenu.splitDownSame";
    /** Broadcast Mode of the tab's panes. */
    public static final String BROADCAST = "terminal.contextMenu.broadcastMode";
    /** Duplicate of the tab. */
    public static final String DUPLICATE = "tab.contextMenu.duplicate";
    /** Reconnect of the tab. */
    public static final String RECONNECT = "dashboard.reconnect";

    /** The category of the pane commands. */
    public static final String TERMINAL_CATEGORY = "palette.category.terminal";
    /** The category of the tab commands. */
    public static final String TAB_CATEGORY = "palette.category.tab";

    /** Every text these commands show: their labels, then their categories. */
    public static final List<String> KEYS = List.of(CLEAR_BUFFER, SPLIT_RIGHT, SPLIT_DOWN, BROADCAST, DUPLICATE,
        RECONNECT, TERMINAL_CATEGORY, TAB_CATEGORY);

    /** The selected terminal tab and its focused pane, the pane the keyboard types into. */
    public interface Target {

        /** Clears the scrollback and the screen of the focused pane. */
        void clearBuffer();

        /** Splits the focused pane with a new session to the same server, the new pane to its right. */
        void splitRight();

        /** Splits the focused pane with a new session to the same server, the new pane below it. */
        void splitDown();

        /** The number of panes of the tab, 1 without a split. */
        int paneCount();

        /** Whether broadcast mode is on, so what is typed goes to every pane of the tab. */
        boolean isBroadcasting();

        /** Switches broadcast mode on or off. */
        void toggleBroadcast();

        /** Opens a copy of the tab next to it, signing in again. */
        void duplicate();

        /** Connects the tab again. */
        void reconnect();
    }

    private TerminalPaletteActions() {
    }

    /**
     * The commands, in the order the palette lists them within their category.
     *
     * @param selectedTerminal the selected terminal tab, or {@code null} while another kind of tab
     *                         (or none) is selected; asked again for every state and every run
     * @param text             the text of an i18n key, such as {@code I18n::get}
     * @param clearBufferChord the terminal's own key for Clear Buffer, shown next to it ({@code null}
     *                         where it has none, as on Windows and Linux)
     */
    public static List<AppAction> actions(Supplier<? extends Target> selectedTerminal,
                                          Function<String, String> text,
                                          @Nullable KeyCombination clearBufferChord) {
        Objects.requireNonNull(selectedTerminal, "selectedTerminal");
        Objects.requireNonNull(text, "text");
        String terminal = text.apply(TERMINAL_CATEGORY);
        String tab = text.apply(TAB_CATEGORY);
        BooleanSupplier aTerminal = () -> selectedTerminal.get() != null;
        return List.of(
            action(CLEAR_BUFFER, text, terminal, clearBufferChord, aTerminal, null,
                selectedTerminal, Target::clearBuffer),
            action(SPLIT_RIGHT, text, terminal, null, aTerminal, null, selectedTerminal, Target::splitRight),
            action(SPLIT_DOWN, text, terminal, null, aTerminal, null, selectedTerminal, Target::splitDown),
            action(BROADCAST, text, terminal, null,
                () -> canToggleBroadcast(selectedTerminal.get()),
                () -> {
                    Target target = selectedTerminal.get();
                    return target != null && target.isBroadcasting();
                },
                selectedTerminal, Target::toggleBroadcast),
            action(DUPLICATE, text, tab, null, aTerminal, null, selectedTerminal, Target::duplicate),
            action(RECONNECT, text, tab, null, aTerminal, null, selectedTerminal, Target::reconnect));
    }

    /**
     * Whether broadcast mode can be switched in {@code target}: on only with a second pane to send
     * to, off always, so it never stays on where the right-click menu no longer offers it.
     */
    static boolean canToggleBroadcast(@Nullable Target target) {
        return target != null && (target.isBroadcasting() || target.paneCount() > 1);
    }

    private static AppAction action(String key, Function<String, String> text, String category,
                                    @Nullable KeyCombination chord, BooleanSupplier enabled,
                                    @Nullable BooleanSupplier checked,
                                    Supplier<? extends Target> selectedTerminal,
                                    Consumer<Target> command) {
        return new AppAction(key, text.apply(key), category, chord, List.of(), enabled, checked,
            () -> {
                Target target = selectedTerminal.get();
                if (target != null) {
                    command.accept(target);
                }
            },
            true, false);
    }
}
