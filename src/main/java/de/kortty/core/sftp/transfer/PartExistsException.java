package de.kortty.core.sftp.transfer;

import de.kortty.ui.I18n;

import java.io.IOException;

/**
 * The part file of a transfer already exists: another transfer is writing it, or an interrupted one
 * left it behind. It is never truncated blindly; the caller resumes it or treats it as a conflict.
 */
public final class PartExistsException extends IOException {

    private final String partPath;

    public PartExistsException(String partPath, Throwable cause) {
        super(I18n.get("sftp.error.partExists", partPath), cause);
        this.partPath = partPath;
    }

    /** The part path, local or remote, that was found. */
    public String partPath() {
        return partPath;
    }
}
