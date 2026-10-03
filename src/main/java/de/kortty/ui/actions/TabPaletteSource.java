package de.kortty.ui.actions;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The command palette's rows for the open tabs ({@code #}): first the tabs of the palette's own
 * window, the most recently used first and the one it shows last, then the terminal tabs of the
 * other open windows, each window's most recently used first. Choosing a row selects that tab, and
 * for another window's tab brings that window to the front first. FX-free: the window supplies the
 * rows, already in its order, and the texts.
 *
 * <p>The tab the window shows is listed last rather than first, so with only {@code #} typed Enter
 * goes back to the tab used before, and its row says it is the current tab. A row of another window
 * starts its detail with that window's label. Each row is keyed {@code tab:<id>} with an id that
 * stays the tab's own while it is open, also when it moves to another window.
 */
public final class TabPaletteSource implements PaletteSource {

    /** Prefix of a tab row's key. */
    public static final String KEY_PREFIX = "tab:";
    /** Between the parts of a row's detail. */
    public static final String DETAIL_SEPARATOR = " · ";

    /**
     * One open tab as the palette lists it.
     *
     * @param id     the tab's id for as long as it is open
     * @param title  the name the tab goes by in the tab bar, without badges, group or status
     * @param detail what tells the tab apart, such as {@code user@host}; may be empty
     * @param select selects the tab (and brings its window to the front)
     */
    public record TabRow(String id, String title, String detail, Runnable select) {
        public TabRow {
            if (id == null || id.isBlank()) {
                throw new IllegalArgumentException("A tab row needs an id");
            }
            Objects.requireNonNull(select, "select");
        }
    }

    /**
     * The tabs of one window.
     *
     * @param label      how another window is named in its rows, such as "Window 2"; not shown for
     *                   the palette's own window
     * @param tabs       the tabs, the most recently used first
     * @param selectedId the id of the tab the window shows, or {@code null}; only the palette's own
     *                   window lists it last
     */
    public record WindowTabs(String label, List<TabRow> tabs, String selectedId) {
        public WindowTabs {
            tabs = tabs == null ? List.of() : List.copyOf(tabs);
        }
    }

    private final Supplier<WindowTabs> ownWindow;
    private final Supplier<List<WindowTabs>> otherWindows;
    private final Supplier<String> currentTabNote;

    /**
     * @param ownWindow      the tabs of the window the palette belongs to
     * @param otherWindows   the terminal tabs of the other open windows, in window order
     * @param currentTabNote the note on the row of the tab the palette's window shows
     */
    public TabPaletteSource(Supplier<WindowTabs> ownWindow, Supplier<List<WindowTabs>> otherWindows,
                            Supplier<String> currentTabNote) {
        this.ownWindow = Objects.requireNonNull(ownWindow, "ownWindow");
        this.otherWindows = Objects.requireNonNull(otherWindows, "otherWindows");
        this.currentTabNote = Objects.requireNonNull(currentTabNote, "currentTabNote");
    }

    @Override
    public PaletteEntry.Kind kind() {
        return PaletteEntry.Kind.TAB;
    }

    @Override
    public List<PaletteEntry> entries() {
        List<PaletteEntry> entries = new ArrayList<>();
        WindowTabs own = ownWindow.get();
        if (own != null) {
            TabRow current = null;
            for (TabRow row : own.tabs()) {
                if (row.id().equals(own.selectedId()) && current == null) {
                    current = row;
                } else {
                    entries.add(entry(row, row.detail()));
                }
            }
            if (current != null) {
                entries.add(entry(current, join(current.detail(), currentTabNote.get())));
            }
        }
        List<WindowTabs> others = otherWindows.get();
        if (others != null) {
            for (WindowTabs window : others) {
                if (window == null) {
                    continue;
                }
                for (TabRow row : window.tabs()) {
                    entries.add(entry(row, join(window.label(), row.detail())));
                }
            }
        }
        return dedupe(entries);
    }

    /**
     * The detail of a terminal tab: who it is connected to, then its tab group. {@code user@host}
     * comes from the connection, never from a program in the terminal, so a tab whose shell set a
     * misleading title still shows where it is connected; it is left out when the title already is
     * exactly that. Without a host (a local shell) {@code fallbackName} names the connection instead.
     */
    public static String connectionDetail(String title, String username, String host, String fallbackName,
                                          String group) {
        String identity;
        if (host != null && !host.isBlank()) {
            identity = username != null && !username.isBlank() ? username.strip() + "@" + host.strip() : host.strip();
        } else {
            identity = fallbackName != null ? fallbackName.strip() : "";
        }
        if (title != null && identity.equalsIgnoreCase(title.strip())) {
            identity = "";
        }
        return join(identity, group);
    }

    /** The non-blank parts, separated by {@link #DETAIL_SEPARATOR}. */
    static String join(String... parts) {
        List<String> present = new ArrayList<>(parts.length);
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                present.add(part.strip());
            }
        }
        return String.join(DETAIL_SEPARATOR, present);
    }

    private static PaletteEntry entry(TabRow row, String detail) {
        return new PaletteEntry(PaletteEntry.Kind.TAB, KEY_PREFIX + row.id(), row.title(), detail, "", true, "",
            false, row.select());
    }

    /** A tab listed twice (it moved while the rows were collected) keeps its first row. */
    private static List<PaletteEntry> dedupe(List<PaletteEntry> entries) {
        List<PaletteEntry> unique = new ArrayList<>(entries.size());
        Set<String> keys = new HashSet<>();
        for (PaletteEntry entry : entries) {
            if (keys.add(entry.key())) {
                unique.add(entry);
            }
        }
        return unique;
    }
}
