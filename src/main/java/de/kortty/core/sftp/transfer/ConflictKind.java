package de.kortty.core.sftp.transfer;

import java.util.List;

/**
 * The kinds of conflict. "Apply to all" answers are remembered per kind, so replacing every file
 * never also replaces a symbolic link or a folder.
 */
public enum ConflictKind {
    /** A file onto an existing file. */
    FILE(List.of(ConflictAction.OVERWRITE, ConflictAction.SKIP, ConflictAction.RENAME, ConflictAction.CANCEL_ALL)),
    /** A folder onto an existing folder: merged without asking. */
    FOLDER(List.of(ConflictAction.OVERWRITE, ConflictAction.SKIP, ConflictAction.RENAME, ConflictAction.CANCEL_ALL)),
    /** The existing entry is a symbolic link: it is never replaced. */
    SYMLINK(List.of(ConflictAction.SKIP, ConflictAction.RENAME, ConflictAction.CANCEL_ALL)),
    /** A file onto a folder, a folder onto a file, or anything onto a special file. */
    TYPE_MISMATCH(List.of(ConflictAction.SKIP, ConflictAction.RENAME, ConflictAction.CANCEL_ALL));

    private final List<ConflictAction> allowed;

    ConflictKind(List<ConflictAction> allowed) {
        this.allowed = allowed;
    }

    /** The answers offered for this kind, in button order. */
    public List<ConflictAction> allowedActions() {
        return allowed;
    }

    /** Whether {@code action} may be applied to a conflict of this kind. */
    public boolean allows(ConflictAction action) {
        return action != null && allowed.contains(action);
    }
}
