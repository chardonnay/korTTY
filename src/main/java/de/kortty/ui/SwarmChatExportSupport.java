package de.kortty.ui;

import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.SavedSwarmMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * Maps swarm chat messages onto the regular AI-chat message model so the shared
 * {@code AiChatExportService} (plain text / Markdown / PDF) can export swarm conversations
 * unchanged. Server summaries are intentionally dropped — the aggregated markdown answer already
 * carries the per-server table.
 */
final class SwarmChatExportSupport {

    private SwarmChatExportSupport() {
    }

    static List<SavedAiChatMessage> toChatMessages(List<SavedSwarmMessage> messages) {
        List<SavedAiChatMessage> converted = new ArrayList<>();
        if (messages == null) {
            return converted;
        }
        for (SavedSwarmMessage message : messages) {
            if (message == null) {
                continue;
            }
            SavedAiChatMessage chatMessage = new SavedAiChatMessage();
            chatMessage.setRole(message.getRole());
            chatMessage.setContent(message.getContent());
            chatMessage.setCreatedAt(message.getCreatedAt());
            chatMessage.setAiProfileId(message.getAiProfileId());
            chatMessage.setAiProfileName(message.getAiProfileName());
            converted.add(chatMessage);
        }
        return converted;
    }
}
