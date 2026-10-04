package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.ConflictResolver.Resolution;
import javafx.scene.control.Dialog;

/** Opens the package-private parts of the SFTP UI to the manual's screenshot generator. */
public final class SftpScreenshotAccess {

    private SftpScreenshotAccess() {
    }

    /** Shows the events collected so far right away instead of on the next timer tick. FX thread. */
    public static void flush(SftpTransferQueuePane pane) {
        pane.flush();
    }

    /** The themed "File already exists" dialog for {@code model}, not yet shown. FX thread. */
    public static Dialog<Resolution> conflictDialog(SftpConflictViewModel model) {
        return SftpConflictDialog.build(model);
    }
}
