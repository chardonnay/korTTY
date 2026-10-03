package de.kortty.ui.sftp;

import java.time.Duration;
import java.util.List;

/**
 * Which remote rows may be dragged out of the window, onto the desktop or into another program.
 *
 * <p>JavaFX has no files that are fetched only when they are dropped: whatever a drag offers the
 * operating system has to exist when the drag starts. Remote files are therefore downloaded to a
 * temporary folder while the window waits, which is only acceptable for a small selection. Within
 * the window every selection can be dragged; there the drop target downloads it in the background.
 */
public final class SftpDragOutPolicy {

    /** At most this many files can be dragged out of the window at once. */
    public static final int MAX_FILES = 20;
    /** At most this many bytes, all files together, can be dragged out of the window. */
    public static final long MAX_TOTAL_BYTES = 16L * 1024 * 1024;
    /** How long the window waits for the download before the drag goes on without the files. */
    public static final Duration MAX_WAIT = Duration.ofSeconds(5);

    /** Why a selection can or cannot be dragged out of the window. */
    public enum Verdict {
        ALLOWED,
        NOTHING_SELECTED,
        CONTAINS_FOLDER,
        TOO_MANY_FILES,
        TOO_LARGE
    }

    /** The file-type bits of a POSIX mode. */
    private static final int TYPE_MASK = 0170000;
    /** The file-type bits of a regular file. */
    private static final int REGULAR_FILE = 0100000;

    /**
     * What the server reports for a dragged name once links are followed.
     *
     * @param mode the POSIX mode with its file-type bits, or {@code 0} when the server sent none
     * @param size the size in bytes, or a negative value when the server sent none
     */
    public record Resolved(int mode, long size) {
        /** A regular file, or a name of unknown type (whose size still counts). */
        public boolean regularFile() {
            int type = mode & TYPE_MASK;
            return type == 0 || type == REGULAR_FILE;
        }
    }

    private SftpDragOutPolicy() {
    }

    /**
     * Regular files only, at most {@link #MAX_FILES} of them and {@link #MAX_TOTAL_BYTES} together
     * by their listed sizes. The parent entry {@code ..} counts as a folder.
     */
    public static Verdict check(List<SftpFileItem> items) {
        if (items == null || items.isEmpty()) {
            return Verdict.NOTHING_SELECTED;
        }
        for (SftpFileItem item : items) {
            if (item == null || !item.isFile() || item.isParentEntry()) {
                return Verdict.CONTAINS_FOLDER;
            }
        }
        if (items.size() > MAX_FILES) {
            return Verdict.TOO_MANY_FILES;
        }
        return checkSizes(items.stream().mapToLong(SftpFileItem::getSizeBytes).toArray());
    }

    /**
     * The same caps for what the server reports when each dragged name is resolved, checked before
     * anything is downloaded. A listing shows a symbolic link with the size of the link itself, so a
     * link to a large file, or to a device such as {@code /dev/zero} that never ends, passes
     * {@link #check}. A folder, device, pipe or socket counts as {@link Verdict#CONTAINS_FOLDER}.
     */
    public static Verdict checkResolved(List<Resolved> files) {
        if (files == null || files.isEmpty()) {
            return Verdict.NOTHING_SELECTED;
        }
        for (Resolved file : files) {
            if (file == null || !file.regularFile()) {
                return Verdict.CONTAINS_FOLDER;
            }
        }
        if (files.size() > MAX_FILES) {
            return Verdict.TOO_MANY_FILES;
        }
        return checkSizes(files.stream().mapToLong(Resolved::size).toArray());
    }

    private static Verdict checkSizes(long[] sizes) {
        long total = 0;
        for (long size : sizes) {
            if (size < 0) {
                // Unknown size: it could be anything.
                return Verdict.TOO_LARGE;
            }
            // Compared before adding, so a reported size near Long.MAX_VALUE cannot overflow the sum.
            if (size > MAX_TOTAL_BYTES - total) {
                return Verdict.TOO_LARGE;
            }
            total += size;
        }
        return Verdict.ALLOWED;
    }
}
