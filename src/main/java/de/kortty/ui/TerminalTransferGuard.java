package de.kortty.ui;

import java.util.Optional;

/**
 * Decides whether a file transfer into a terminal pane's working directory may start. Files dropped
 * onto a pane and snippets copied to the terminal directory are written over the pane's original SSH
 * login (or on this computer for a local shell), into the directory the shell reports. After
 * {@code su}, {@code sudo -i} or a nested {@code ssh} that directory belongs to another user or host,
 * so the copy would land somewhere the user does not expect: it is refused instead.
 *
 * <p>The foreign-session verdict comes from {@link TerminalView#isForeignSessionActive}, which reads
 * the screen and must run on the JavaFX thread. Callers evaluate it there and pass the plain boolean
 * here before they start a worker thread.
 */
final class TerminalTransferGuard {

    /** The transfer paths into a terminal directory. */
    enum Transfer {
        /** Files dropped onto a terminal pane. */
        DROP("terminal.dragDrop.foreignSession"),
        /** Snippets copied as files to the terminal directory. */
        SNIPPET_COPY("snippets.transfer.foreignSession");

        private final String refusalKey;

        Transfer(String refusalKey) {
            this.refusalKey = refusalKey;
        }
    }

    private TerminalTransferGuard() {
    }

    /** The i18n key of the refusal message, or empty when the transfer may start. */
    static Optional<String> refusalKey(Transfer transfer, boolean foreignSessionActive) {
        if (transfer == null) {
            throw new IllegalArgumentException("transfer");
        }
        return foreignSessionActive ? Optional.of(transfer.refusalKey) : Optional.empty();
    }
}
