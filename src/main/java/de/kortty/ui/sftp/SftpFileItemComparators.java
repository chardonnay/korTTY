package de.kortty.ui.sftp;

import java.util.Comparator;

/**
 * Sort orders for the SFTP manager's file tables. The parent entry ({@code ..}) stays on top in
 * either direction, and a case-insensitive name comparison is the final tie-breaker, as in the
 * local file browser.
 */
public final class SftpFileItemComparators {

    private static final Comparator<SftpFileItem> BY_NAME =
        Comparator.comparing(item -> nameOf(item), String.CASE_INSENSITIVE_ORDER);

    private SftpFileItemComparators() {
    }

    /**
     * The Type column order, documented as the default sort order in the guide:
     * <ol>
     *   <li>the parent entry {@code ..} (always first)</li>
     *   <li>directories starting with {@code .}</li>
     *   <li>other directories</li>
     *   <li>files starting with {@code .}</li>
     *   <li>other files</li>
     * </ol>
     * Each group is sorted by name. Descending reverses the groups and the names, but keeps
     * {@code ..} first.
     */
    public static Comparator<SftpFileItem> type(boolean ascending) {
        Comparator<SftpFileItem> byGroupAndName =
            Comparator.comparingInt(SftpFileItemComparators::typeGroup).thenComparing(BY_NAME);
        return parentFirst(ascending ? byGroupAndName : byGroupAndName.reversed());
    }

    /**
     * The Size column order: by byte count, with directories before files in both directions and
     * the name as tie-breaker.
     */
    public static Comparator<SftpFileItem> size(boolean ascending) {
        Comparator<SftpFileItem> bySize = Comparator.comparingLong(SftpFileItem::getSizeBytes);
        if (!ascending) {
            bySize = bySize.reversed();
        }
        return parentFirst(Comparator.comparing((SftpFileItem item) -> item.isFile())
            .thenComparing(bySize)
            .thenComparing(BY_NAME));
    }

    /** {@code order}, but with the parent entry {@code ..} ahead of everything else. */
    public static Comparator<SftpFileItem> parentFirst(Comparator<? super SftpFileItem> order) {
        return Comparator.comparing((SftpFileItem item) -> !isParent(item)).thenComparing(order);
    }

    private static int typeGroup(SftpFileItem item) {
        boolean hidden = nameOf(item).startsWith(".");
        if (!item.isFile()) {
            return hidden ? 1 : 2;
        }
        return hidden ? 3 : 4;
    }

    private static boolean isParent(SftpFileItem item) {
        return item.isParentEntry() || "..".equals(item.getName());
    }

    private static String nameOf(SftpFileItem item) {
        return item.getName() != null ? item.getName() : "";
    }
}
