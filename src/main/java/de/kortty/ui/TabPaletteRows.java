package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.ui.actions.TabPaletteSource;
import javafx.scene.control.Tab;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns the tabs of a main window into the command palette's tab rows ({@link TabPaletteSource}).
 *
 * <p>A terminal tab is titled with {@link TerminalTab#getEffectiveTitle()}, the name the tab bar
 * shows, and its detail names the connection's {@code user@host} and the tab group
 * ({@link TabPaletteSource#connectionDetail}). Any other tab is titled with its tab text. Every text
 * is cleaned by the palette row itself.
 */
final class TabPaletteRows {

    /** The texts these rows add; pinned by CommandPaletteI18nCoverageTest. */
    static final List<String> KEYS = List.of("palette.detail.currentTab", "palette.detail.window");

    /** The tab property that holds the tab's palette id. */
    static final String TAB_ID_PROPERTY = "kortty.palette.tabId";

    private static final AtomicLong NEXT_TAB_ID = new AtomicLong();

    private TabPaletteRows() {
    }

    /**
     * The id of {@code tab} in the palette's keys, given the first time it is asked for. It is kept in
     * the tab's properties, so it stays the same while the tab is open, also in another window, and
     * says nothing about what the tab shows.
     */
    static String tabId(Tab tab) {
        Object id = tab.getProperties().computeIfAbsent(TAB_ID_PROPERTY, key -> "t" + NEXT_TAB_ID.incrementAndGet());
        return id.toString();
    }

    /** The row of {@code tab}; choosing it runs {@code select}. */
    static TabPaletteSource.TabRow row(Tab tab, Runnable select) {
        if (tab instanceof TerminalTab terminalTab) {
            String title = terminalTab.getEffectiveTitle();
            ServerConnection connection = terminalTab.getConnection();
            String host = connection == null || connection.isLocalShell() ? null : connection.getHost();
            String username = connection == null ? null : connection.getUsername();
            return new TabPaletteSource.TabRow(tabId(tab), title,
                TabPaletteSource.connectionDetail(title, username, host, terminalTab.getConnectionTitle(),
                    terminalTab.getGroup()),
                select);
        }
        String text = tab.getText();
        return new TabPaletteSource.TabRow(tabId(tab), text != null ? text : "", "", select);
    }

    /** What another window is called in its rows: its place among the open windows, from 1. */
    static String windowLabel(int position) {
        return I18n.get("palette.detail.window", position);
    }

    /** The note on the row of the tab the window shows. */
    static String currentTabNote() {
        return I18n.get("palette.detail.currentTab");
    }
}
