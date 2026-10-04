package de.kortty.policy;

import de.kortty.ui.I18n;

import java.util.Objects;

/**
 * The one question every file-transfer route asks before it copies a byte between this computer
 * and a server: does the enterprise policy allow it ({@link PolicyFeature#FILE_TRANSFER}), and if
 * not, what to tell the user. Pure and JavaFX-free; the routes decide how to show the refusal
 * (a disabled button, a status line, a failed job).
 *
 * <p>Scope (D6): the routes of {@link Route}. Browsing, remote-only operations (rename, delete,
 * permissions, archives, search, remote copy) and in-app viewing (which stays under
 * {@link LoadIntoEditorMode}) are never asked here. Shell commands such as {@code scp} typed into a
 * terminal cannot be blocked by korTTY.
 */
public final class FileTransferGate {

    /** The i18n key of the refusal; takes the organization suffix as {@code {0}}. */
    public static final String DENIED_KEY = "policy.fileTransfer.denied";

    /** Every route that copies files between this computer and a server. */
    public enum Route {
        /** The SFTP manager's Upload button and drops onto its server panel. */
        SFTP_UPLOAD,
        /** The SFTP manager's Download button and server rows dropped onto its local panel. */
        SFTP_DOWNLOAD,
        /** Dragging server rows out of the SFTP manager onto the desktop or another program. */
        SFTP_DRAG_OUT,
        /** Files dropped onto a terminal pane (applied by the terminal). */
        TERMINAL_DROP,
        /** A snippet folder copied into a terminal's server (applied by the snippet tree). */
        SNIPPET_COPY_TO_TERMINAL,
        /** The JobScheduler's SFTP upload action. */
        JOB_SFTP_UPLOAD,
        /** The JobScheduler's SFTP download action. */
        JOB_SFTP_DOWNLOAD,
        /** The JobScheduler's SFTP sync action, in either direction. */
        JOB_SFTP_SYNC,
        /** The JobScheduler's rsync action: it copies files between this computer and a server too. */
        JOB_RSYNC_SYNC
    }

    /**
     * The answer for one route.
     *
     * @param route        the route asked about
     * @param allowed      whether it may copy files
     * @param organization the organization named by the policy file, or null
     */
    public record Verdict(Route route, boolean allowed, String organization) {

        public Verdict {
            Objects.requireNonNull(route, "route");
        }

        /** The i18n key of the refusal, or null when allowed. */
        public String reasonKey() {
            return allowed ? null : DENIED_KEY;
        }

        /** The refusal for the user (status line, tooltip, job result), or null when allowed. */
        public String reason() {
            if (allowed) {
                return null;
            }
            return I18n.get(DENIED_KEY, organization == null || organization.isBlank() ? "" : " (" + organization + ")");
        }
    }

    private FileTransferGate() {
    }

    /** Whether {@code policy} allows {@code route}, and why not. */
    public static Verdict check(EffectivePolicy policy, Route route) {
        EffectivePolicy effective = policy != null ? policy : EffectivePolicy.unrestricted();
        return new Verdict(route, effective.fileTransferAllowed(), effective.organization().orElse(null));
    }

    /** {@link #check} against the policy of this korTTY ({@link PolicyManager#effective()}). */
    public static Verdict current(Route route) {
        return check(PolicyManager.effective(), route);
    }
}
