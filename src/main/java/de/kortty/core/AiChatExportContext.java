package de.kortty.core;

import java.time.LocalDateTime;

/**
 * Immutable metadata for an AI chat export.
 */
public record AiChatExportContext(
    String title,
    LocalDateTime exportTimestamp,
    String activeProfileName,
    int messageCount) {

    public AiChatExportContext {
        title = title != null && !title.isBlank() ? title.trim() : "AI Chat Export";
        exportTimestamp = exportTimestamp != null ? exportTimestamp : LocalDateTime.now();
        activeProfileName = activeProfileName != null && !activeProfileName.isBlank() ? activeProfileName.trim() : null;
        messageCount = Math.max(0, messageCount);
    }
}
