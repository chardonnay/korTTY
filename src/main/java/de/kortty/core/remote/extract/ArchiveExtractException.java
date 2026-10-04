package de.kortty.core.remote.extract;

import de.kortty.core.remote.RemoteCommandException;

import java.util.Objects;

/**
 * A remote extraction was refused or failed. The message is a fixed text; {@link #detail()} holds
 * the tool name, the offending member or the tool's first error line for the dialog.
 */
public final class ArchiveExtractException extends RemoteCommandException {

    public enum Reason {
        UNSUPPORTED_FORMAT,
        MISSING_TOOL,
        PASSWORD_PROTECTED,
        LISTING_UNREADABLE,
        UNSAFE_MEMBER,
        UNSAFE_LINK,
        EXTRACT_FAILED,
        NO_FREE_NAME
    }

    private final Reason reason;
    private final String detail;
    private final ArchiveMemberValidator.Reason memberReason;

    ArchiveExtractException(Reason reason, String detail) {
        this(reason, detail, null);
    }

    ArchiveExtractException(Reason reason, String detail, ArchiveMemberValidator.Reason memberReason) {
        super("Archive extraction stopped: " + Objects.requireNonNull(reason, "reason"));
        this.reason = reason;
        this.detail = detail == null ? "" : detail;
        this.memberReason = memberReason;
    }

    public Reason reason() {
        return reason;
    }

    /** The tool, member or error line this is about; empty when there is none. */
    public String detail() {
        return detail;
    }

    /** For {@link Reason#UNSAFE_MEMBER}: why the member was refused; otherwise null. */
    public ArchiveMemberValidator.Reason memberReason() {
        return memberReason;
    }
}
