package de.kortty.control;

import java.util.List;

/**
 * The result of every write verb. Carries counts only, never the text written, preserving the
 * Stage-2 privacy invariant that no terminal payload reaches a log or a reply.
 *
 * <p>Pure, any thread.
 *
 * @param paneId the pane written to
 * @param bytesWritten how many bytes reached the pty
 * @param bracketed whether the payload was wrapped in bracketed-paste markers
 * @param submitted whether a trailing CR was appended
 * @param keys the normalised key names for a key write; empty for a text write
 */
public record WriteResult(String paneId, int bytesWritten, boolean bracketed, boolean submitted,
                          List<String> keys) {

    public WriteResult {
        keys = keys == null ? List.of() : List.copyOf(keys);
    }
}
