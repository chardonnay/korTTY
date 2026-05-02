package de.kortty.core;

import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class AiChatManagerTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-ai-chat-manager-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to delete temp path " + path, e);
                    }
                });
        }
    }

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
        assertThat(saved.getId()).isNotNull();
        assertThat(manager.getAllChats().size()).isEqualTo(1);

        AiChatManager reloaded = new AiChatManager(tempDir);
        reloaded.load();
        SavedAiChat persisted = reloaded.findById(saved.getId()).orElseThrow();
        assertThat(persisted.getTitle()).isEqualTo("SSH-Ausgabe debuggen");
        assertThat(persisted.getConnectionDisplayName()).isEqualTo("prod-shell");
        assertThat(persisted.getMessages().size()).isEqualTo(2);
        assertThat(persisted.getMessages().get(1).getAiProfileName()).isEqualTo("GPT Support");

        persisted.setTitle("SSH-Fehler mit Proxy");
        SavedAiChat updated = reloaded.saveChat(persisted);
        assertThat(updated.getTitle()).isEqualTo("SSH-Fehler mit Proxy");
        assertThat(updated.getUpdatedAt() >= updated.getCreatedAt()).isTrue();

        assertThat(reloaded.deleteChat(updated.getId())).isTrue();
        assertThat(reloaded.getAllChats().isEmpty()).isTrue();
    }

    @Test
    void getAllChatsReturnsNewestFirst() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        SavedAiChat first = manager.saveChat(chat("Erster Chat"));
        Thread.sleep(5L);
        SavedAiChat second = manager.saveChat(chat("Zweiter Chat"));

        List<SavedAiChat> chats = manager.getAllChats();
        assertThat(chats.size()).isEqualTo(2);
        assertThat(chats.get(0).getId()).isEqualTo(second.getId());
        assertThat(chats.get(1).getId()).isEqualTo(first.getId());
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
