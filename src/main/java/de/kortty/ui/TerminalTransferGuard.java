package de.kortty.ui;

import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.FileTransferGate;

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
 *
 * <p>Both paths copy files between this computer and a server, so the organization's file-transfer
 * policy ({@link FileTransferGate}, D6) applies too: a denied drop is rejected while the files are
 * still being dragged, and a denied snippet copy is greyed out in the menus.
 */
final class TerminalTransferGuard {

    /** The transfer paths into a terminal directory. */
    enum Transfer {
        /** Files dropped onto a terminal pane. */
        DROP("terminal.dragDrop.foreignSession", FileTransferGate.Route.TERMINAL_DROP),
        /** Snippets copied as files to the terminal directory. */
        SNIPPET_COPY("snippets.transfer.foreignSession", FileTransferGate.Route.SNIPPET_COPY_TO_TERMINAL);

        private final String refusalKey;
        private final FileTransferGate.Route route;

        Transfer(String refusalKey, FileTransferGate.Route route) {
            this.refusalKey = refusalKey;
            this.route = route;
        }

        /** The file-transfer policy route of this path. */
        FileTransferGate.Route route() {
            return route;
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

    /** The policy's refusal of {@code transfer} under {@code policy}, or empty when it may copy files. */
    static Optional<String> policyRefusal(Transfer transfer, EffectivePolicy policy) {
        if (transfer == null) {
            throw new IllegalArgumentException("transfer");
        }
        return Optional.ofNullable(FileTransferGate.check(policy, transfer.route()).reason());
    }

    /** Whether the policy of this korTTY lets {@code transfer} copy files; cheap, safe on the FX thread. */
    static boolean allowedByPolicy(Transfer transfer) {
        return FileTransferGate.current(transfer.route()).allowed();
    }
}
