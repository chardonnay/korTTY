package de.kortty.ui.sftp;

import java.util.Comparator;
import java.util.function.ToLongFunction;

/**
 * Sort orders for the SFTP manager's file tables. Whatever column and direction is chosen, the
 * entries stay in four groups, documented in the guide:
 * <ol>
 *   <li>the parent entry {@code ..} (always first)</li>
 *   <li>hidden folders ({@link SftpFileItem#isHidden()}, such as {@code .config})</li>
 *   <li>hidden files</li>
 *   <li>other folders</li>
 *   <li>other files</li>
 * </ol>
 * The column only orders the entries within a group; a case-insensitive name comparison is the
 * final tie-breaker, as in the local file browser.
 */
public final class SftpFileItemComparators {

    private static final Comparator<SftpFileItem> BY_NAME =
        Comparator.comparing(item -> nameOf(item), String.CASE_INSENSITIVE_ORDER);

    private SftpFileItemComparators() {
    }

    /**
     * The Type column order, which is also the default: the groups, each sorted by name.
     * Descending reverses the names within each group, never the groups.
     */
    public static Comparator<SftpFileItem> type(boolean ascending) {
        return grouped(ascending ? BY_NAME : BY_NAME.reversed());
    }

    /** The Size column order: by byte count within each group, the name as tie-breaker. */
    public static Comparator<SftpFileItem> size(boolean ascending) {
        return size(ascending, SftpFileItem::getSizeBytes);
    }

    /**
     * Like {@link #size(boolean)}, with the byte count from {@code bytes}, which can give a server
     * folder the size measured for it; folders without one stay together, ordered by name.
     */
    public static Comparator<SftpFileItem> size(boolean ascending, ToLongFunction<SftpFileItem> bytes) {
        Comparator<SftpFileItem> bySize = Comparator.comparingLong(bytes);
        return grouped(ascending ? bySize : bySize.reversed());
    }

    /**
     * {@code withinGroup} (any column's order, direction included) applied inside the fixed
     * groups, with {@code ..} ahead of everything and the name as last tie-breaker.
     */
    public static Comparator<SftpFileItem> grouped(Comparator<? super SftpFileItem> withinGroup) {
        return parentFirst(Comparator.comparingInt(SftpFileItemComparators::group)
            .thenComparing(withinGroup)
            .thenComparing(BY_NAME));
    }

    /** {@code order}, but with the parent entry {@code ..} ahead of everything else. */
    public static Comparator<SftpFileItem> parentFirst(Comparator<? super SftpFileItem> order) {
        return Comparator.comparing((SftpFileItem item) -> !isParent(item)).thenComparing(order);
    }

    /** 0 hidden folders, 1 hidden files, 2 folders, 3 files. */
    static int group(SftpFileItem item) {
        if (item.isHidden()) {
            return item.isFile() ? 1 : 0;
        }
        return item.isFile() ? 3 : 2;
    }

    private static boolean isParent(SftpFileItem item) {
        return item.isParentEntry() || "..".equals(item.getName());
    }

    private static String nameOf(SftpFileItem item) {
        return item.getName() != null ? item.getName() : "";
    }
}
