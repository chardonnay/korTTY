package de.kortty.core.sftp.transfer;

/** Which way a file travels. */
public enum TransferDirection {
    /** From this computer to the server. */
    UPLOAD,
    /** From the server to this computer. */
    DOWNLOAD
}
