package de.kortty.memory;

import java.io.IOException;
import java.util.Objects;

/**
 * The user's own changes to a memory file: a save of the editor, a restore of a history version and a clear. These are
 * the only callers of the user-action reasons outside the store; korTTY's own changes go through
 * {@link MemoryStore#write} with a consent instead. Each call names the digest the editor loaded, so a stale editor
 * cannot overwrite newer content.
 */
public final class MemoryUserActions {

    private final MemoryStore store;

    public MemoryUserActions(MemoryStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    /** Saves the text the user edited. */
    public MemoryStore.Snapshot save(MemoryScope scope, String expectedDigest, String text) throws IOException {
        return store.write(scope, expectedDigest, text, MemoryStore.WriteReason.USER_SAVE, null);
    }

    /** Replaces the file with an earlier version the user chose from the history. */
    public MemoryStore.Snapshot restore(MemoryScope scope, String expectedDigest, String historyText)
        throws IOException {
        return store.write(scope, expectedDigest, historyText, MemoryStore.WriteReason.USER_RESTORE, null);
    }

    /** Empties the file. The previous version stays in the history. */
    public MemoryStore.Snapshot clear(MemoryScope scope, String expectedDigest) throws IOException {
        return store.write(scope, expectedDigest, "", MemoryStore.WriteReason.USER_CLEAR, null);
    }
}
