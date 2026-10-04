package de.kortty.ui.sftp;

import de.kortty.policy.LoadIntoEditorMode;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.PolicyRestrictionException;

/**
 * What the enterprise policy's {@code load-into-snippet-editor} mode lets the SFTP manager do with
 * the files of the server: the same rule the terminal's "Open in Snippet Editor" follows.
 *
 * <ul>
 *   <li>{@link LoadIntoEditorMode#ALLOW} — remote files open in the snippet editor with
 *       <b>Overwrite remote file</b> and <b>Save as</b>, and remote images open in the viewer.</li>
 *   <li>{@link LoadIntoEditorMode#READ_ONLY} — remote files and images still open, but the editor
 *       has no action that writes back to the server; saving as a snippet stays available.</li>
 *   <li>{@link LoadIntoEditorMode#DENY} — <b>Edit with Snippet Editor</b> and <b>Open image</b>
 *       are disabled for remote files.</li>
 * </ul>
 *
 * <p>Files of the local pane are on the user's own computer, not the target system, so the gate
 * never restricts them. Pure and FX-free; the SFTP manager asks it each time a menu opens, a
 * selection changes or an action runs.
 */
public final class SftpEditGate {

    private static final SftpEditGate ALLOW = new SftpEditGate(LoadIntoEditorMode.ALLOW);
    private static final SftpEditGate READ_ONLY = new SftpEditGate(LoadIntoEditorMode.READ_ONLY);
    private static final SftpEditGate DENY = new SftpEditGate(LoadIntoEditorMode.DENY);

    private final LoadIntoEditorMode mode;

    private SftpEditGate(LoadIntoEditorMode mode) {
        this.mode = mode;
    }

    /** The gate for {@code mode}; {@code null} (no policy value) means {@link LoadIntoEditorMode#ALLOW}. */
    public static SftpEditGate of(LoadIntoEditorMode mode) {
        if (mode == null) {
            return ALLOW;
        }
        return switch (mode) {
            case ALLOW -> ALLOW;
            case READ_ONLY -> READ_ONLY;
            case DENY -> DENY;
        };
    }

    /** The gate for the policy in force now. */
    public static SftpEditGate current() {
        return of(PolicyManager.effective().loadIntoSnippetEditor());
    }

    public LoadIntoEditorMode mode() {
        return mode;
    }

    /** Whether a remote file may be opened in the snippet editor at all. */
    public boolean remoteEditorAvailable() {
        return mode != LoadIntoEditorMode.DENY;
    }

    /** Whether a remote image may be downloaded into the image viewer. */
    public boolean remoteImageAvailable() {
        return mode != LoadIntoEditorMode.DENY;
    }

    /** Local files are the user's own; the policy never restricts editing them here. */
    public boolean localEditorAvailable() {
        return true;
    }

    /** Whether the editor may write an opened remote file, or a copy of it, back to the server. */
    public boolean remoteWriteBackAllowed() {
        return mode == LoadIntoEditorMode.ALLOW;
    }

    /**
     * {@code action} when the editor may write back to the server, else {@code null}: the snippet
     * editor shows a {@code null} overwrite or save-as action as a locked button.
     */
    public <T> T remoteWriteAction(T action) {
        return remoteWriteBackAllowed() ? action : null;
    }

    /**
     * Backstop for every write-back path, in case an action outlived a policy change.
     *
     * @param message the localized reason for the exception
     * @throws PolicyRestrictionException when write-back is not allowed
     */
    public void requireRemoteWriteBack(String message) {
        if (!remoteWriteBackAllowed()) {
            throw new PolicyRestrictionException(message);
        }
    }

    @Override
    public String toString() {
        return "SftpEditGate[" + mode + "]";
    }
}
