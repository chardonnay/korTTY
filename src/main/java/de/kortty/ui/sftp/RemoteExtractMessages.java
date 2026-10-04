package de.kortty.ui.sftp;

import de.kortty.core.remote.extract.ArchiveExtractException;
import de.kortty.core.remote.extract.RemoteArchiveExtractor;
import de.kortty.ui.I18n;

/** The texts of the SFTP manager's "Extract Here..." progress and errors. FX-free. */
public final class RemoteExtractMessages {

    private RemoteExtractMessages() {
    }

    public static String phaseText(RemoteArchiveExtractor.Phase phase) {
        return I18n.get(phaseKey(phase));
    }

    static String phaseKey(RemoteArchiveExtractor.Phase phase) {
        return switch (phase) {
            case CHECKING -> "sftp.extract.phase.checking";
            case LISTING -> "sftp.extract.phase.listing";
            case EXTRACTING -> "sftp.extract.phase.extracting";
            case VERIFYING -> "sftp.extract.phase.verifying";
            case MOVING -> "sftp.extract.phase.moving";
        };
    }

    public static String errorText(ArchiveExtractException error) {
        return I18n.get(errorKey(error.reason()), error.detail());
    }

    static String errorKey(ArchiveExtractException.Reason reason) {
        return switch (reason) {
            case UNSUPPORTED_FORMAT -> "sftp.extract.error.unsupported";
            case MISSING_TOOL -> "sftp.extract.error.missingTool";
            case PASSWORD_PROTECTED -> "sftp.extract.error.password";
            case LISTING_UNREADABLE -> "sftp.extract.error.listing";
            case UNSAFE_MEMBER -> "sftp.extract.error.unsafeMember";
            case UNSAFE_LINK -> "sftp.extract.error.unsafeLink";
            case EXTRACT_FAILED -> "sftp.extract.error.failed";
            case NO_FREE_NAME -> "sftp.extract.error.noFreeName";
        };
    }
}
