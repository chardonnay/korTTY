package de.kortty.core.remote.edit;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/**
 * One server file edited in a local editor, however it travels: over SFTP as the login user
 * ({@link RemoteEditSession}) or as root over sudo ({@link SudoEditSession}). The SFTP manager's
 * <b>Remote edits</b> list drives every kind through this view. Not thread-safe: the caller runs
 * one action at a time per edit.
 */
public interface RemoteEdit extends AutoCloseable {

    /** The remote file being edited (a link's target, when a link was opened). */
    String remotePath();

    /** The local copy the editor works on. */
    Path localFile();

    /** The SHA-256 of what the server had when korTTY last read or wrote it. */
    String baselineSha256();

    /** The conflict the last upload stopped at, or {@code null}. */
    RemoteEditSession.Conflict conflict();

    /** How many uploads went to the server. */
    int uploads();

    /** When the last upload finished, or {@code null}. */
    Instant lastUpload();

    /** Whether the local copy differs from what the server got last. */
    boolean hasUnsyncedChanges();

    /** Uploads the local copy unless the remote file changed meanwhile. */
    RemoteEditSession.UploadResult upload() throws IOException;

    /** Uploads the local copy even though the remote file changed: the user chose to overwrite it. */
    RemoteEditSession.UploadResult forceUpload() throws IOException;

    /** Copies the local copy to {@code target}, replacing a file there. */
    void saveLocalCopy(Path target) throws IOException;

    /** Whether {@link #close()} ran. */
    boolean isClosed();

    /** Deletes the local copy and its private folder, and forgets any secret; idempotent. */
    @Override
    void close();
}
