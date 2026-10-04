package de.kortty.ui.sftp;

/**
 * What the session journal records about an edit as root: the action and the path, nothing else
 * (no content, no password, no command line).
 */
public final class SudoEditJournal {

    private SudoEditJournal() {
    }

    /** {@code sudo-edit <path>}; a line break in the path is shown as a space. */
    public static String note(String path) {
        return "sudo-edit " + (path == null ? "" : path.replace('\r', ' ').replace('\n', ' '));
    }
}
