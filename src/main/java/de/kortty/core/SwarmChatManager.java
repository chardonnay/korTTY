package de.kortty.core;

import de.kortty.model.SavedSwarmChat;
import de.kortty.model.SavedSwarmMessage;
import de.kortty.model.SavedSwarmServerSummary;
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
 * Persists and manages saved swarm chats. Structural mirror of {@code AiChatManager}.
 */
public class SwarmChatManager {

    private static final Logger logger = LoggerFactory.getLogger(SwarmChatManager.class);
    private static final String SWARM_CHATS_FILE = "swarm-chats.xml";
    private static final JAXBContext JAXB_CONTEXT;

    static {
        try {
            JAXB_CONTEXT = JAXBContext.newInstance(
                SwarmChatsWrapper.class, SavedSwarmChat.class, SavedSwarmMessage.class, SavedSwarmServerSummary.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Path configDir;
    private final List<SavedSwarmChat> chats = new ArrayList<>();

    public SwarmChatManager(Path configDir) {
        this.configDir = Objects.requireNonNull(configDir, "configDir");
    }

    public synchronized void load() throws Exception {
        Path file = configDir.resolve(SWARM_CHATS_FILE);
        if (!Files.exists(file)) {
            chats.clear();
            logger.info("No saved swarm chats file found, starting empty");
            return;
        }

        Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
        SwarmChatsWrapper wrapper = (SwarmChatsWrapper) unmarshaller.unmarshal(file.toFile());

        chats.clear();
        if (wrapper != null && wrapper.getChats() != null) {
            for (SavedSwarmChat chat : wrapper.getChats()) {
                if (chat != null) {
                    chats.add(normalizeChat(new SavedSwarmChat(chat)));
                }
            }
        }
        sortChatsInPlace();
        logger.info("Loaded {} saved swarm chats from {}", chats.size(), file);
    }

    public synchronized void save() throws Exception {
        Path file = configDir.resolve(SWARM_CHATS_FILE);
        Files.createDirectories(configDir);

        SwarmChatsWrapper wrapper = new SwarmChatsWrapper();
        wrapper.setChats(copyChats(chats));

        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(wrapper, file.toFile());
        logger.info("Saved {} swarm chats to {}", chats.size(), file);
    }

    public synchronized List<SavedSwarmChat> getAllChats() {
        sortChatsInPlace();
        return copyChats(chats);
    }

    public synchronized Optional<SavedSwarmChat> findById(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        return chats.stream()
            .filter(chat -> chatId.equals(chat.getId()))
            .findFirst()
            .map(SavedSwarmChat::new);
    }

    public synchronized SavedSwarmChat saveChat(SavedSwarmChat chat) throws Exception {
        SavedSwarmChat normalized = normalizeChat(new SavedSwarmChat(Objects.requireNonNull(chat, "chat")));
        int existingIndex = indexOfChat(normalized.getId());
        long now = System.currentTimeMillis();
        if (existingIndex >= 0) {
            SavedSwarmChat existing = chats.get(existingIndex);
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
        return new SavedSwarmChat(normalized);
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

    private SavedSwarmChat normalizeChat(SavedSwarmChat chat) {
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

        List<SavedSwarmMessage> normalizedMessages = new ArrayList<>();
        if (chat.getMessages() != null) {
            for (SavedSwarmMessage message : chat.getMessages()) {
                if (message == null) {
                    continue;
                }
                SavedSwarmMessage copy = new SavedSwarmMessage(message);
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
            .comparingLong(SavedSwarmChat::getUpdatedAt)
            .reversed()
            .thenComparing(SavedSwarmChat::getTitle, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
    }

    private List<SavedSwarmChat> copyChats(List<SavedSwarmChat> source) {
        List<SavedSwarmChat> copies = new ArrayList<>();
        if (source == null) {
            return copies;
        }
        for (SavedSwarmChat chat : source) {
            if (chat != null) {
                copies.add(new SavedSwarmChat(chat));
            }
        }
        return copies;
    }

    @XmlRootElement(name = "swarmChats")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class SwarmChatsWrapper {

        @XmlElement(name = "swarmChat")
        private List<SavedSwarmChat> chats = new ArrayList<>();

        public List<SavedSwarmChat> getChats() {
            return chats;
        }

        public void setChats(List<SavedSwarmChat> chats) {
            this.chats = chats != null ? chats : new ArrayList<>();
        }
    }
}
