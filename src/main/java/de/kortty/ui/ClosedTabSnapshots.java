package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.model.SessionSnapshot;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Turns the {@link ClosedTabHistory} into what the session snapshot keeps, and back.
 *
 * <p>The snapshot keeps ids only: the saved connection a closed tab used, its tab group, the name
 * the user gave it and its terminal effect, never the sanitised copy of the connection the history
 * holds in memory. So no host, user name, password or temporary SSH key of a closed tab reaches the
 * disk. After a restart, a closed tab comes back with its saved connection as it is now; a tab whose
 * connection was deleted, or was never saved (a Quick Connect session), is left out, and an entry
 * left without a tab goes with it. Pure: no toolkit, no file.
 */
final class ClosedTabSnapshots {

    private ClosedTabSnapshots() {
    }

    /** The history's entries, newest first, as the snapshot keeps them. */
    static List<SessionSnapshot.ClosedEntry> toSnapshot(List<ClosedTabHistory.Entry> entries) {
        List<SessionSnapshot.ClosedEntry> stored = new ArrayList<>();
        for (ClosedTabHistory.Entry entry : entries) {
            List<SessionSnapshot.ClosedTab> tabs = new ArrayList<>();
            for (ClosedTabHistory.ClosedTab tab : entry.tabs()) {
                if (tab.connectionId() == null || tab.connectionId().isBlank()) {
                    continue;
                }
                tabs.add(new SessionSnapshot.ClosedTab(tab.connectionId(), tab.usedTemporaryKey(),
                    tab.tabGroup(), tab.customTitle(), tab.terminalEffectPluginId(), tab.terminalEffectSpeed()));
            }
            if (!tabs.isEmpty()) {
                stored.add(new SessionSnapshot.ClosedEntry(entry.window(), tabs));
            }
        }
        return stored;
    }

    /**
     * The entries a snapshot kept, newest first, with each tab's saved connection found again by
     * its id. At most {@link ClosedTabHistory#MAX_ENTRIES} entries come back.
     *
     * @param savedConnections a saved connection by id, or {@code null} when there is none
     */
    static List<ClosedTabHistory.Entry> fromSnapshot(List<SessionSnapshot.ClosedEntry> stored,
                                                     Function<String, ServerConnection> savedConnections) {
        Objects.requireNonNull(savedConnections, "savedConnections");
        List<ClosedTabHistory.Entry> entries = new ArrayList<>();
        if (stored == null) {
            return entries;
        }
        for (SessionSnapshot.ClosedEntry entry : stored) {
            if (entries.size() >= ClosedTabHistory.MAX_ENTRIES) {
                break;
            }
            if (entry == null || entry.getTabs() == null) {
                continue;
            }
            List<ClosedTabHistory.ClosedTab> tabs = new ArrayList<>();
            for (SessionSnapshot.ClosedTab tab : entry.getTabs()) {
                if (tab == null || tab.getConnectionId() == null || tab.getConnectionId().isBlank()) {
                    continue;
                }
                ServerConnection saved = savedConnections.apply(tab.getConnectionId());
                if (saved == null) {
                    continue;
                }
                tabs.add(ClosedTabHistory.ClosedTab.capture(saved, tab.isUsedTemporaryKey(), blankToNull(tab.getTabGroup()),
                    blankToNull(tab.getCustomTitle()), blankToNull(tab.getTerminalEffectPluginId()),
                    tab.getTerminalEffectSpeed()));
            }
            if (!tabs.isEmpty()) {
                entries.add(new ClosedTabHistory.Entry(tabs, entry.isWindow()));
            }
        }
        return entries;
    }

    private static String blankToNull(String text) {
        return text == null || text.isBlank() ? null : text;
    }
}
