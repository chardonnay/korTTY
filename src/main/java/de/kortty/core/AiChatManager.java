package de.kortty.core;

import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists and manages saved AI chats.
 */
public class AiChatManager {

    private static final Logger logger = LoggerFactory.getLogger(AiChatManager.class);
    private static final String AI_CHATS_FILE = "ai-chats.xml";

    private final Path configDir;
    private final List<SavedAiChat> chats = new ArrayList<>();

    public AiChatManager(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
    }

    public synchronized void load() throws Exception {
        Path file = configDir.resolve(AI_CHATS_FILE);
        if (!Files.exists(file)) {
            chats.clear();
            logger.info("No saved AI chats file found, starting empty");
            return;
        }

        JAXBContext context = JAXBContext.newInstance(AiChatsWrapper.class, SavedAiChat.class, SavedAiChatMessage.class);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        AiChatsWrapper wrapper = (AiChatsWrapper) unmarshaller.unmarshal(file.toFile());

        chats.clear();
        if (wrapper != null && wrapper.getChats() != null) {
            for (SavedAiChat chat : wrapper.getChats()) {
                if (chat != null) {
                    chats.add(normalizeChat(new SavedAiChat(chat)));
                }
            }
        }
        sortChatsInPlace();
        logger.info("Loaded {} saved AI chats from {}", chats.size(), file);
    }

    public synchronized void save() throws Exception {
        Path file = configDir.resolve(AI_CHATS_FILE);
        Files.createDirectories(configDir);

        AiChatsWrapper wrapper = new AiChatsWrapper();
        wrapper.setChats(copyChats(chats));

        JAXBContext context = JAXBContext.newInstance(AiChatsWrapper.class, SavedAiChat.class, SavedAiChatMessage.class);
        Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(wrapper, file.toFile());
        logger.info("Saved {} AI chats to {}", chats.size(), file);
    }

    public synchronized List<SavedAiChat> getAllChats() {
        sortChatsInPlace();
        return copyChats(chats);
    }

    public synchronized Optional<SavedAiChat> findById(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        return chats.stream()
            .filter(chat -> chatId.equals(chat.getId()))
            .findFirst()
            .map(SavedAiChat::new);
    }

    public synchronized SavedAiChat saveChat(SavedAiChat chat) throws Exception {
        SavedAiChat normalized = normalizeChat(new SavedAiChat(Objects.requireNonNull(chat, "chat")));
        int existingIndex = indexOfChat(normalized.getId());
        long now = System.currentTimeMillis();
        if (existingIndex >= 0) {
            SavedAiChat existing = chats.get(existingIndex);
            normalized.setCreatedAt(existing.getCreatedAt());
            normalized.setUpdatedAt(now);
            chats.set(existingIndex, normalized);
        } else {
            if (normalized.getCreatedAt() <= 0L) {
                normalized.setCreatedAt(now);
            }
            normalized.setUpdatedAt(now);
            chats.add(normalized);
        }
        sortChatsInPlace();
        save();
        de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.AI_CHAT_SAVED,
            java.util.Map.of("kind", "chat", "is_new", existingIndex < 0));
        return new SavedAiChat(normalized);
    }

    public synchronized boolean deleteChat(String chatId) throws Exception {
        int index = indexOfChat(chatId);
        if (index < 0) {
            return false;
        }
        chats.remove(index);
        save();
        return true;
    }

    private int indexOfChat(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return -1;
        }
        for (int i = 0; i < chats.size(); i++) {
            if (chatId.equals(chats.get(i).getId())) {
                return i;
            }
        }
        return -1;
    }

    private SavedAiChat normalizeChat(SavedAiChat chat) {
        long now = System.currentTimeMillis();
        if (chat.getId() == null || chat.getId().isBlank()) {
            chat.setId(UUID.randomUUID().toString());
        }
        if (chat.getCreatedAt() <= 0L) {
            chat.setCreatedAt(now);
        }
        if (chat.getUpdatedAt() <= 0L) {
            chat.setUpdatedAt(chat.getCreatedAt());
        }

        List<SavedAiChatMessage> normalizedMessages = new ArrayList<>();
        if (chat.getMessages() != null) {
            for (SavedAiChatMessage message : chat.getMessages()) {
                if (message == null) {
                    continue;
                }
                SavedAiChatMessage copy = new SavedAiChatMessage(message);
                if (copy.getCreatedAt() <= 0L) {
                    copy.setCreatedAt(chat.getCreatedAt());
                }
                normalizedMessages.add(copy);
            }
        }
        chat.setMessages(normalizedMessages);
        return chat;
    }

    private void sortChatsInPlace() {
        chats.sort(Comparator
            .comparingLong(SavedAiChat::getUpdatedAt)
            .reversed()
            .thenComparing(SavedAiChat::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
    }

    private List<SavedAiChat> copyChats(List<SavedAiChat> source) {
        List<SavedAiChat> copies = new ArrayList<>();
        if (source == null) {
            return copies;
        }
        for (SavedAiChat chat : source) {
            if (chat != null) {
                copies.add(new SavedAiChat(chat));
            }
        }
        return copies;
    }

    @XmlRootElement(name = "aiChats")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class AiChatsWrapper {

        @XmlElement(name = "chat")
        private List<SavedAiChat> chats = new ArrayList<>();

        public List<SavedAiChat> getChats() {
            return chats;
        }

        public void setChats(List<SavedAiChat> chats) {
            this.chats = chats != null ? chats : new ArrayList<>();
        }
    }
}
