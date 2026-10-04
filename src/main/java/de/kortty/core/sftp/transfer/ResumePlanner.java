package de.kortty.core.sftp.transfer;

import java.util.Objects;

/**
 * Decides whether a transfer starts fresh, continues an existing part file or throws the part away.
 *
 * <p>The decision only uses what korTTY wrote into its local {@link ResumeIndex} and what the part
 * and the source look like now; nothing is stored on the server. A part is continued only when all
 * of these hold:
 * <ul>
 *   <li>the index has an entry for exactly this transfer,</li>
 *   <li>the source still has the size and modification time the entry recorded,</li>
 *   <li>the part is a regular file (never a symbolic link or a folder) and not larger than the
 *       source,</li>
 *   <li>a remote part belongs to the login user who created it,</li>
 *   <li>the part still has the size and modification time korTTY last saw, when it saw them.</li>
 * </ul>
 * Anything else restarts from byte 0. After a {@link Kind#RESUME} decision the caller still
 * compares the last bytes before the offset on both sides ({@link #overlapLength(long)}) and
 * restarts on a mismatch, which catches a source rewritten with the same size and time.
 */
public final class ResumePlanner {

    /** How many bytes before the resume offset are re-read on both sides and compared. */
    public static final int OVERLAP_BYTES = 64 * 1024;

    private ResumePlanner() {
    }

    /** The three possible plans. */
    public enum Kind {
        /** No part exists: create one. */
        FRESH,
        /** Continue the existing part at {@link Decision#offset()}. */
        RESUME,
        /** Delete the existing part and its index entry, then start fresh. */
        RESTART
    }

    /** Why a part is not continued; for logs and tests. */
    public enum Reason {
        NONE,
        NO_INDEX_ENTRY,
        NOT_A_REGULAR_FILE,
        SOURCE_SIZE_CHANGED,
        SOURCE_TIME_CHANGED,
        SOURCE_TIME_UNKNOWN,
        PART_LARGER_THAN_SOURCE,
        PART_OWNER_DIFFERS,
        PART_CHANGED,
        PART_EMPTY,
        OVERLAP_MISMATCH
    }

    /** The plan for one transfer. */
    public record Decision(Kind kind, long offset, Reason reason) {
        public Decision {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reason, "reason");
            if (kind != Kind.RESUME && offset != 0) {
                throw new IllegalArgumentException("only a resume has an offset");
            }
        }

        static Decision fresh() {
            return new Decision(Kind.FRESH, 0, Reason.NONE);
        }

        static Decision resume(long offset) {
            return new Decision(Kind.RESUME, offset, Reason.NONE);
        }

        static Decision restart(Reason reason) {
            return new Decision(Kind.RESTART, 0, reason);
        }
    }

    /**
     * The source file as it is now.
     *
     * @param mtimeMillis its modification time, or {@code null} when the server did not report one
     */
    public record SourceState(long size, Long mtimeMillis) {
    }

    /**
     * The part file as {@code lstat} sees it now.
     *
     * @param ownerKey {@link RemoteFinalizer#ownerKey} of a remote part, {@code null} when unknown
     *     or for a local part
     */
    public record PartState(boolean exists, boolean regularFile, long size, long mtimeMillis, String ownerKey) {

        /** Nothing has the part's name. */
        public static final PartState ABSENT = new PartState(false, false, 0, 0, null);
    }

    /**
     * Plans the transfer.
     *
     * @param entry the index entry of this transfer, or {@code null}
     * @param remotePart whether the part lives on the server, so its owner must be checked
     */
    public static Decision plan(ResumeIndex.Entry entry, SourceState source, PartState part, boolean remotePart) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(part, "part");
        if (!part.exists()) {
            return Decision.fresh();
        }
        if (!part.regularFile()) {
            return Decision.restart(Reason.NOT_A_REGULAR_FILE);
        }
        if (entry == null) {
            return Decision.restart(Reason.NO_INDEX_ENTRY);
        }
        if (source.mtimeMillis() == null) {
            return Decision.restart(Reason.SOURCE_TIME_UNKNOWN);
        }
        if (entry.sourceSize() != source.size()) {
            return Decision.restart(Reason.SOURCE_SIZE_CHANGED);
        }
        if (entry.sourceMtimeMillis() != source.mtimeMillis()) {
            return Decision.restart(Reason.SOURCE_TIME_CHANGED);
        }
        if (part.size() > source.size()) {
            return Decision.restart(Reason.PART_LARGER_THAN_SOURCE);
        }
        if (remotePart && (part.ownerKey() == null || !part.ownerKey().equals(entry.partOwner()))) {
            return Decision.restart(Reason.PART_OWNER_DIFFERS);
        }
        if (entry.partStateKnown()
                && (entry.partSize() != part.size() || entry.partMtimeMillis() != part.mtimeMillis())) {
            return Decision.restart(Reason.PART_CHANGED);
        }
        if (part.size() == 0) {
            return Decision.restart(Reason.PART_EMPTY);
        }
        return Decision.resume(part.size());
    }

    /** How many bytes before {@code offset} the overlap check compares. */
    public static int overlapLength(long offset) {
        return (int) Math.min(OVERLAP_BYTES, Math.max(0, offset));
    }
}
