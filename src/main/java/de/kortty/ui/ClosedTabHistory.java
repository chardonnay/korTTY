package de.kortty.ui;

import de.kortty.core.DisplayTextSanitizer;
import de.kortty.model.ServerConnection;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * The terminal tabs the user closed in this session, newest first: what File › Reopen Closed Tab,
 * File › Recently Closed, the tab menu's Reopen Closed Tab and Cmd+Opt+Shift+T (Ctrl+Alt+Shift+T on
 * Windows and Linux) bring back. One history for the whole application, which
 * {@link MainWindow} keeps; FX thread only, in memory only, so it ends with korTTY.
 *
 * <p>An {@link Entry} is what one close took away: a single tab, the tabs one command closed
 * together, or the terminal tabs of a closed window. {@link RecentlyClosedRecorder} decides which
 * closes are remembered. A {@link ClosedTab} keeps the connection's id and a sanitised copy of the
 * connection — no temporary SSH key, and never a plaintext password — plus the tab group, the name
 * the user gave the tab and its terminal effect. Nothing of the screen is kept: a reopened tab
 * starts a new session. At most {@link #MAX_ENTRIES} entries are kept; the oldest goes first.
 */
final class ClosedTabHistory {

    /** How many closes the history remembers. */
    static final int MAX_ENTRIES = 25;

    /** Longest tab name the Recently Closed menu shows, in characters. */
    static final int LABEL_LENGTH = 60;

    /**
     * One closed terminal tab, captured before the tab released anything.
     *
     * @param connectionId           the connection's id, to find the saved connection again
     * @param snapshot               the connection as the tab used it, sanitised by
     *                               {@link ConnectionAuthResolver#sanitize}: no temporary key
     *                               text, lifetime or {@code TEMPORARY:} key path
     * @param usedTemporaryKey       whether the tab signed in with a temporary SSH key, which a reopen
     *                               of the snapshot has to ask for again
     * @param tabGroup               the tab group (not the connection's), or {@code null}
     * @param customTitle            the name the user gave the tab, or {@code null}
     * @param terminalEffectPluginId the terminal effect of the tab's first pane, or {@code null}
     * @param terminalEffectSpeed    that effect's animation speed, or {@code null} without an effect
     */
    record ClosedTab(@Nullable String connectionId, ServerConnection snapshot, boolean usedTemporaryKey,
                     @Nullable String tabGroup, @Nullable String customTitle,
                     @Nullable String terminalEffectPluginId, @Nullable Double terminalEffectSpeed) {

        ClosedTab {
            Objects.requireNonNull(snapshot, "snapshot");
        }

        /**
         * Captures a tab that is closing. The connection is copied and sanitised here, so neither a
         * later change to the tab's connection nor a reopen can reach into the entry.
         *
         * @param temporaryKeyInUse whether the tab was opened with a temporary SSH key of its own
         */
        static ClosedTab capture(ServerConnection connection, boolean temporaryKeyInUse, @Nullable String tabGroup,
                                 @Nullable String customTitle, @Nullable String terminalEffectPluginId,
                                 @Nullable Double terminalEffectSpeed) {
            Objects.requireNonNull(connection, "connection");
            return new ClosedTab(connection.getId(), ConnectionAuthResolver.sanitize(connection),
                temporaryKeyInUse || ConnectionAuthResolver.carriesTemporaryKey(connection),
                tabGroup, customTitle,
                terminalEffectPluginId, terminalEffectPluginId != null ? terminalEffectSpeed : null);
        }

        /**
         * The name the Recently Closed menu shows: the tab's own name, else the connection's, without
         * control or bidi characters (a shared connection's name comes from someone else's file) and
         * cut to {@link #LABEL_LENGTH} characters.
         */
        String label() {
            String name = customTitle != null && !customTitle.isBlank() ? customTitle : snapshot.getDisplayName();
            return DisplayTextSanitizer.sanitize(name, LABEL_LENGTH);
        }
    }

    /**
     * What one close took away.
     *
     * @param tabs   the closed terminal tabs in tab order; never empty
     * @param window whether they are the tabs of a closed window, which reopen in a window of their own
     */
    record Entry(List<ClosedTab> tabs, boolean window) {

        Entry {
            tabs = List.copyOf(tabs);
            if (tabs.isEmpty()) {
                throw new IllegalArgumentException("An entry holds at least one tab");
            }
        }

        /** The same entry with only {@code remaining} of its tabs, for example those whose sign-in was cancelled. */
        Entry withTabs(List<ClosedTab> remaining) {
            return new Entry(remaining, window);
        }

        /**
         * The labels of the first {@code max} tabs joined by commas, then {@code +N} for the N tabs not
         * named — a count without a word, so no language needs a plural form for it.
         */
        String names(int max) {
            List<String> labels = new ArrayList<>();
            for (int i = 0; i < tabs.size() && i < max; i++) {
                labels.add(tabs.get(i).label());
            }
            if (tabs.size() > max) {
                labels.add("+" + (tabs.size() - max));
            }
            return String.join(", ", labels);
        }
    }

    /**
     * What a reopen connects to.
     *
     * @param connection           the connection to sign in with: the saved one, or a fresh copy of
     *                             the snapshot that the sign-in may change without touching the entry
     * @param needsNewTemporaryKey whether a temporary SSH key has to be asked for first: the tab used
     *                             one, and the snapshot no longer holds it
     */
    record Target(ServerConnection connection, boolean needsNewTemporaryKey) {
    }

    /** Newest first. */
    private final List<Entry> entries = new ArrayList<>();

    /** Remembers {@code entry} as the newest; the oldest entries beyond {@link #MAX_ENTRIES} are forgotten. */
    void push(Entry entry) {
        entries.add(0, Objects.requireNonNull(entry, "entry"));
        trim();
    }

    /** The newest entry, left in the history. */
    Optional<Entry> latest() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.get(0));
    }

    /** Takes the newest entry out of the history. */
    Optional<Entry> pollLatest() {
        return entries.isEmpty() ? Optional.empty() : Optional.of(entries.remove(0));
    }

    /**
     * Takes {@code entry} (this very instance) out of the history.
     *
     * @return the position it had, newest first, for {@link #putBack}; {@code -1} when it is no
     *         longer there (another menu or window reopened it already)
     */
    int take(Entry entry) {
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) == entry) {
                entries.remove(i);
                return i;
            }
        }
        return -1;
    }

    /**
     * Puts an entry back at the position {@link #take} reported, or at the end when the history got
     * shorter meanwhile; the oldest entries beyond {@link #MAX_ENTRIES} are forgotten.
     */
    void putBack(int index, Entry entry) {
        Objects.requireNonNull(entry, "entry");
        entries.add(Math.max(0, Math.min(index, entries.size())), entry);
        trim();
    }

    /** The entries, newest first, as a copy. */
    List<Entry> entries() {
        return List.copyOf(entries);
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    int size() {
        return entries.size();
    }

    /** Forgets every entry (File › Recently Closed › Clear List). */
    void clear() {
        entries.clear();
    }

    private void trim() {
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(entries.size() - 1);
        }
    }

    /**
     * What reopening {@code tab} connects to. The saved connection with the tab's id comes first, so
     * edits made after the close apply — a new host, a jump server, a login method — and the server
     * policy is checked against them. An unsaved connection (a Quick Connect session, a local shell, a
     * shared connection) falls back to a copy of the snapshot. A one-off login choice made in Quick
     * Connect for a saved connection is not replayed: the saved connection's own login applies.
     *
     * @param savedConnections a saved connection by id, or {@code null}
     */
    static Target resolveConnection(ClosedTab tab, Function<String, ServerConnection> savedConnections) {
        ServerConnection saved = tab.connectionId() != null ? savedConnections.apply(tab.connectionId()) : null;
        if (saved != null) {
            return new Target(saved, false);
        }
        return new Target(ServerConnection.copyForAuth(tab.snapshot()), tab.usedTemporaryKey());
    }

    /**
     * Whether a reopen that ended with {@code status} leaves its tab in the history: when the user
     * cancelled a password, temporary-key or vault question, so the tab can be reopened later. A tab
     * that opened, a target the server policy blocks and a missing connection are done with.
     */
    static boolean keepsEntry(ConnectionAuthResolver.Status status) {
        return switch (status) {
            case NEEDS_PASSWORD, NEEDS_TEMP_KEY, NEEDS_UNLOCK -> true;
            case READY, BLOCKED, MISSING -> false;
        };
    }
}
