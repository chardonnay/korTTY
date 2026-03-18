package de.kortty.core;

import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatManagerTest {

    @TempDir
    Path tempDir;

    @Test
    void saveLoadUpdateAndDeleteChatRoundTrips() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        manager.load();

        SavedAiChat chat = new SavedAiChat();
        chat.setTitle("SSH-Ausgabe debuggen");
        chat.setSelectedText("fatal: unable to access repository");
        chat.setConnectionDisplayName("prod-shell");
        chat.setResponseLanguageCode("de");
        chat.setActiveAiProfileId("profile-1");
        chat.setActiveAiProfileName("GPT Support");
        chat.setMessages(List.of(
            message(SavedAiChatMessage.ROLE_USER, "Woran liegt der Fehler?", null),
            message(SavedAiChatMessage.ROLE_ASSISTANT, "Pruefe DNS und Proxy.", "GPT Support")));

        SavedAiChat saved = manager.saveChat(chat);
        assertNotNull(saved.getId());
        assertEquals(1, manager.getAllChats().size());

        AiChatManager reloaded = new AiChatManager(tempDir);
        reloaded.load();
        SavedAiChat persisted = reloaded.findById(saved.getId()).orElseThrow();
        assertEquals("SSH-Ausgabe debuggen", persisted.getTitle());
        assertEquals("prod-shell", persisted.getConnectionDisplayName());
        assertEquals(2, persisted.getMessages().size());
        assertEquals("GPT Support", persisted.getMessages().get(1).getAiProfileName());

        persisted.setTitle("SSH-Fehler mit Proxy");
        SavedAiChat updated = reloaded.saveChat(persisted);
        assertEquals("SSH-Fehler mit Proxy", updated.getTitle());
        assertTrue(updated.getUpdatedAt() >= updated.getCreatedAt());

        assertTrue(reloaded.deleteChat(updated.getId()));
        assertTrue(reloaded.getAllChats().isEmpty());
    }

    @Test
    void getAllChatsReturnsNewestFirst() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        SavedAiChat first = manager.saveChat(chat("Erster Chat"));
        Thread.sleep(5L);
        SavedAiChat second = manager.saveChat(chat("Zweiter Chat"));

        List<SavedAiChat> chats = manager.getAllChats();
        assertEquals(2, chats.size());
        assertEquals(second.getId(), chats.get(0).getId());
        assertEquals(first.getId(), chats.get(1).getId());
    }

    private SavedAiChat chat(String title) {
        SavedAiChat chat = new SavedAiChat();
        chat.setTitle(title);
        chat.setSelectedText("uname -a");
        chat.setResponseLanguageCode("de");
        chat.setMessages(List.of(message(SavedAiChatMessage.ROLE_USER, "Bitte analysieren", null)));
        return chat;
    }

    private SavedAiChatMessage message(String role, String content, String profileName) {
        SavedAiChatMessage message = new SavedAiChatMessage();
        message.setRole(role);
        message.setContent(content);
        message.setAiProfileName(profileName);
        return message;
    }
}
