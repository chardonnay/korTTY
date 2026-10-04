package de.kortty.ui.sftp;

/** Opens the package-private, JavaFX-free decisions of {@link SftpTransferQueuePane} to tests in other packages. */
public final class SftpTransferQueuePaneTestAccess {

    private SftpTransferQueuePaneTestAccess() {
    }

    public static boolean retryEnabled(boolean hasQueue, boolean anyRetryable, boolean transferAllowed) {
        return SftpTransferQueuePane.retryEnabled(hasQueue, anyRetryable, transferAllowed);
    }
}
