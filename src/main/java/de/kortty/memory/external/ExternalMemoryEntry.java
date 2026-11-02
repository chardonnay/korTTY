package de.kortty.memory.external;

import java.time.Instant;

/**
 * One approved reference memory file.
 *
 * @param id              a stable id for the entry
 * @param path            the file's canonical path, as {@code toRealPath} gives it
 * @param root            the canonical folder the file was approved under: the home folder or a chosen project folder
 * @param tool            the tool the file belongs to
 * @param connectionId    the connection whose scope the entry belongs to, or {@code null} for the global scope
 * @param active          whether the entry may be sent; a refused secret switches it off
 * @param approvedSha256  the SHA-256 of the file's bytes the user approved
 * @param approvedAt      when the user approved the text
 * @param status          the status of the last check
 * @param reason          a short explanation of a status that is not {@link ExternalMemoryStatus#OK}, never the file's text
 */
public record ExternalMemoryEntry(String id, String path, String root, ExternalMemoryTool tool, String connectionId,
                                  boolean active, String approvedSha256, Instant approvedAt,
                                  ExternalMemoryStatus status, String reason) {

    public ExternalMemoryEntry {
        tool = tool == null ? ExternalMemoryTool.OTHER : tool;
        status = status == null ? ExternalMemoryStatus.MISSING : status;
        reason = reason == null ? "" : reason;
        connectionId = connectionId == null || connectionId.isBlank() ? null : connectionId.strip();
    }

    /** Whether the entry is in the global scope, which every request sees. */
    public boolean global() {
        return connectionId == null;
    }

    /** Whether the entry applies to a request for {@code connectionKey}: global entries always, the others for their connection. */
    public boolean appliesTo(String connectionKey) {
        return global() || connectionId.equals(connectionKey);
    }

    public ExternalMemoryEntry withActive(boolean value) {
        return new ExternalMemoryEntry(id, path, root, tool, connectionId, value, approvedSha256, approvedAt, status,
            reason);
    }

    public ExternalMemoryEntry withStatus(ExternalMemoryStatus newStatus, String newReason) {
        return new ExternalMemoryEntry(id, path, root, tool, connectionId, active, approvedSha256, approvedAt,
            newStatus, newReason);
    }

    /** The entry approved again at {@code sha256}: active and {@link ExternalMemoryStatus#OK} until it changes. */
    public ExternalMemoryEntry withApproval(String sha256, Instant at) {
        return new ExternalMemoryEntry(id, path, root, tool, connectionId, true, sha256, at,
            ExternalMemoryStatus.OK, "");
    }
}
