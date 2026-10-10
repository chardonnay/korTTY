package de.kortty.ui;

import java.util.Comparator;

/**
 * Sort keys and comparators for the file browser listing. Hidden entries (a name starting with
 * a dot) always come first, then directories before files, regardless of key or direction; the
 * {@code ascending} flag only reverses the chosen key, and a case-insensitive name comparison is
 * the final tie-breaker.
 *
 * <p>Generalizes the previous hard-coded "directories first, then name" ordering
 * into a testable, key-driven comparator with no JavaFX or filesystem dependency.
 */
final class FileBrowserSort {

    /** Broad, user-selectable sort keys. */
    enum Key {
        NAME, SIZE, DATE
    }

    /** Minimal view of a file-browser entry the comparators need. */
    interface Entry {
        String name();

        long size();

        long lastModified();

        boolean directory();
    }

    private FileBrowserSort() {
    }

    /** A dotfile or dot-folder, which the listing keeps at the top. */
    static boolean hidden(Entry entry) {
        String name = entry.name();
        return name != null && name.startsWith(".");
    }

    /**
     * Builds a comparator that keeps hidden entries first and folders before files, orders by
     * {@code key}, then tie-breaks on name.
     */
    static Comparator<Entry> comparator(Key key, boolean ascending) {
        Comparator<Entry> byName = Comparator.comparing(Entry::name, String.CASE_INSENSITIVE_ORDER);
        Comparator<Entry> byKey = switch (key) {
            case SIZE -> Comparator.comparingLong(Entry::size);
            case DATE -> Comparator.comparingLong(Entry::lastModified);
            case NAME -> byName;
        };
        if (!ascending) {
            byKey = byKey.reversed();
        }
        // Hidden entries first and folders before files, independent of key/direction; name as
        // the stable tie-breaker.
        return Comparator.comparing((Entry entry) -> !hidden(entry))
            .thenComparing((Entry entry) -> !entry.directory())
            .thenComparing(byKey)
            .thenComparing(byName);
    }
}
