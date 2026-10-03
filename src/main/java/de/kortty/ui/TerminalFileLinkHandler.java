package de.kortty.ui;

import org.jetbrains.annotations.NotNull;

/**
 * Opens the files that links in one terminal pane point to ({@link TerminalFileLink}), read as text
 * into the Snippet Editor. The terminal view gives one to every pane
 * ({@link KorttyTermWidget#setFileLinkHandler}); a pane without one opens no file and keeps OSC 8
 * {@code file:} targets as plain text.
 */
public interface TerminalFileLinkHandler {

    /**
     * Whether the pane opens files now: the policy allows loading files into the Snippet Editor and
     * the pane's session is an SSH or local shell session. Asked on the emulator thread for every
     * OSC 8 {@code file:} target and on the JavaFX thread for every lookup, so it must be cheap and
     * thread-safe.
     */
    boolean enabled();

    /**
     * Whether the pane opens {@code link}: {@link #enabled()}, and an OSC 8 target names the host the
     * pane's session runs on ({@link TerminalFileLink#hostAccepted}). Asked on the JavaFX thread.
     */
    boolean accepts(@NotNull TerminalFileLink link);

    /** Reads the file of an {@link #accepts accepted} link into the Snippet Editor. Called on the JavaFX thread. */
    void open(@NotNull TerminalFileLink link);
}
