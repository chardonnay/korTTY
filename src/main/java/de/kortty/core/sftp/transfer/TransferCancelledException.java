package de.kortty.core.sftp.transfer;

import java.io.IOException;

/** Thrown by {@link SftpStreamCopier} when its {@link TransferCancellation} was cancelled. */
public final class TransferCancelledException extends IOException {

    private static final long serialVersionUID = 1L;

    public TransferCancelledException() {
        super("Transfer cancelled");
    }

    public TransferCancelledException(Throwable cause) {
        super("Transfer cancelled", cause);
    }
}
