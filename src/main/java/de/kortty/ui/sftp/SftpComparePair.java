package de.kortty.ui.sftp;

import java.util.List;
import java.util.Objects;

/**
 * Which two entries "Compare" compares, from the selections of both panels: the two selected in
 * the active panel, or one selected in each panel. Two files are compared line by line, two
 * folders with everything inside them; a file and a folder cannot be compared. Any other selection
 * leaves the choice to the caller, which then compares the two folders shown.
 *
 * @param kind  what was picked
 * @param left  the left entry (the local one of a pair across the panels); null for {@link Kind#NONE}
 * @param right the right entry; null for {@link Kind#NONE}
 */
public record SftpComparePair(Kind kind, Entry left, Entry right) {

    /** What the selection holds. */
    public enum Kind {
        /** Two files. */
        FILES,
        /** Two folders. */
        FOLDERS,
        /** A file and a folder. */
        MIXED,
        /** Not exactly two entries in the active panel, or one in each panel. */
        NONE
    }

    /** Which panel an entry comes from. */
    public enum Side {
        LOCAL,
        REMOTE
    }

    /** One picked entry and its panel. */
    public record Entry(Side side, SftpFileItem item) {
        public Entry {
            Objects.requireNonNull(side, "side");
            Objects.requireNonNull(item, "item");
        }

        public boolean isRemote() {
            return side == Side.REMOTE;
        }
    }

    private static final SftpComparePair NONE = new SftpComparePair(Kind.NONE, null, null);

    /**
     * The pair to compare.
     *
     * @param local        the local panel's selection, in list order ({@code ..} is ignored)
     * @param remote       the server panel's selection, in list order
     * @param remoteActive whether the server panel is the active one
     */
    public static SftpComparePair choose(List<SftpFileItem> local, List<SftpFileItem> remote, boolean remoteActive) {
        List<SftpFileItem> localEntries = named(local);
        List<SftpFileItem> remoteEntries = named(remote);
        List<SftpFileItem> active = remoteActive ? remoteEntries : localEntries;
        Side activeSide = remoteActive ? Side.REMOTE : Side.LOCAL;
        if (active.size() == 2) {
            return of(new Entry(activeSide, active.get(0)), new Entry(activeSide, active.get(1)));
        }
        if (localEntries.size() == 1 && remoteEntries.size() == 1) {
            return of(new Entry(Side.LOCAL, localEntries.get(0)), new Entry(Side.REMOTE, remoteEntries.get(0)));
        }
        return NONE;
    }

    private static SftpComparePair of(Entry left, Entry right) {
        boolean leftFile = left.item().isFile();
        boolean rightFile = right.item().isFile();
        Kind kind = leftFile && rightFile ? Kind.FILES : !leftFile && !rightFile ? Kind.FOLDERS : Kind.MIXED;
        return new SftpComparePair(kind, left, right);
    }

    private static List<SftpFileItem> named(List<SftpFileItem> selection) {
        return selection == null ? List.of()
            : selection.stream().filter(item -> item != null && !item.isParentEntry()).toList();
    }
}
