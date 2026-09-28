package de.kortty.ui;

/**
 * A hosted dialog that must be asked before its host disposes it (unsaved work).
 *
 * <p>Contract: prompt and save if needed, but never close. {@code true} means the caller may
 * dispose the dialog now; {@code false} vetoes. Implementations keep no "approved" state, so a
 * caller that aborts its own close after an approval leaves nothing stale behind.
 */
interface HostedCloseGuard {

    boolean confirmHostedClose();
}
