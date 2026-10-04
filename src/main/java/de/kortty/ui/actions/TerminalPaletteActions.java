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
 * <b>Clear Buffer</b> of the focused pane, and <b>Duplicate</b> and <b>Reconnect</b> of the tab.
 * They carry the labels of the terminal's and the tab's right-click menus (and the Dashboard's
 * Reconnect), under the categories Terminal and Tabs.
 *
 * <p>Not among them, because a menu-bar item does the same and the palette harvests that item with
 * its menu path, shortcut and check mark: <b>Find</b> (<i>Edit → Find…</i>), the same-server splits
 * (<i>View → Panes → Split Right / Split Down</i>, which split the focused pane on its own server
 * the way <i>Split Right (same server)</i> and <i>Split Down (same server)</i> in the right-click
 * menu do, and move the keyboard into the new pane) and <b>Broadcast Mode</b> (<i>View → Panes →
 * Broadcast to All Panes of This Tab</i>, the same mode with the same rule: on with a second pane,
 * off at any time). Listing them here as well would give the palette two rows for one command.
 *
 * <p>Every command asks for the selected terminal tab when the palette reads its state and again
 * when it runs, so it is enabled only while a terminal tab is selected, and it acts on the tab that
 * is selected when it runs. Its id is the i18n key of its label.
 *
 * <p>FX-free: the window supplies the selected tab as a {@link Target} and the texts, so a test can
 * stand in a stub tab.
 */
public final class TerminalPaletteActions {

    /** Clear Buffer of the focused pane. */
    public static final String CLEAR_BUFFER = "terminal.contextMenu.clearBuffer";
    /** Duplicate of the tab. */
    public static final String DUPLICATE = "tab.contextMenu.duplicate";
    /** Reconnect of the tab. */
    public static final String RECONNECT = "dashboard.reconnect";

    /** The category of the pane commands. */
    public static final String TERMINAL_CATEGORY = "palette.category.terminal";
    /** The category of the tab commands. */
    public static final String TAB_CATEGORY = "palette.category.tab";

    /** Every text these commands show: their labels, then their categories. */
    public static final List<String> KEYS = List.of(CLEAR_BUFFER, DUPLICATE, RECONNECT, TERMINAL_CATEGORY,
        TAB_CATEGORY);

    /** The selected terminal tab and its focused pane, the pane the keyboard types into. */
    public interface Target {

        /** Clears the scrollback and the screen of the focused pane. */
        void clearBuffer();

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
            action(CLEAR_BUFFER, text, terminal, clearBufferChord, aTerminal, selectedTerminal, Target::clearBuffer),
            action(DUPLICATE, text, tab, null, aTerminal, selectedTerminal, Target::duplicate),
            action(RECONNECT, text, tab, null, aTerminal, selectedTerminal, Target::reconnect));
    }

    private static AppAction action(String key, Function<String, String> text, String category,
                                    @Nullable KeyCombination chord, BooleanSupplier enabled,
                                    Supplier<? extends Target> selectedTerminal,
                                    Consumer<Target> command) {
        return new AppAction(key, text.apply(key), category, chord, List.of(), enabled, null,
            () -> {
                Target target = selectedTerminal.get();
                if (target != null) {
                    command.accept(target);
                }
            },
            true, false);
    }
}
