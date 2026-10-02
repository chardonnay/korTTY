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
        long total = 0;
        for (SftpFileItem item : items) {
            long size = item.getSizeBytes();
            if (size < 0) {
                // Unknown size: it could be anything.
                return Verdict.TOO_LARGE;
            }
            total += size;
            if (total > MAX_TOTAL_BYTES) {
                return Verdict.TOO_LARGE;
            }
        }
        return Verdict.ALLOWED;
    }
}
