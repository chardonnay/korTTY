package de.kortty.core.sftp.transfer;

/**
 * The answers to "the target already exists".
 *
 * <p>Resuming is not an answer here: whether an existing part file is continued is decided by
 * {@link ResumePlanner} from the part itself, independent of whether the target exists.
 */
public enum ConflictAction {
    /** Replace the existing file. For a folder onto a folder: merge into it. */
    OVERWRITE,
    /** Leave the existing entry alone and do not transfer this item. */
    SKIP,
    /** Keep both: transfer under a free name chosen by {@link UniqueNames#next}. */
    RENAME,
    /** Stop the whole batch; nothing more is transferred or asked. */
    CANCEL_ALL
}
