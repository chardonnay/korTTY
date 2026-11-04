package de.kortty.core;

import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAgentStep;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.SavedAiWebToolCall;
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
    void webToolCallsOfAnAssistantReplySurviveSaveAndReload() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        manager.load();
        SavedAiWebToolCall search = new SavedAiWebToolCall();
        search.setKind("SEARCH");
        search.setTool("web_search");
        search.setInput("kortty release");
        search.setSuccess(true);
        search.setSources(List.of(new SavedAiWebToolCall.Source("korTTY", "https://example.test/kortty")));
        SavedAiWebToolCall read = new SavedAiWebToolCall();
        read.setKind("EXTRACT");
        read.setTool("web_extract");
        read.setInput("https://example.test/manual.pdf");
        read.setSuccess(false);
        read.setMessage("HTTP 403");
        SavedAiChatMessage answer = message(SavedAiChatMessage.ROLE_ASSISTANT, "Antwort", "GPT Support");
        answer.setWebToolCalls(List.of(search, read));
        SavedAiChat chat = chat("Recherche");
        chat.setMessages(List.of(message(SavedAiChatMessage.ROLE_USER, "Was ist neu?", null), answer));

        SavedAiChat saved = manager.saveChat(chat);
        AiChatManager reloaded = new AiChatManager(tempDir);
        reloaded.load();
        SavedAiChat persisted = reloaded.findById(saved.getId()).orElseThrow();

        assertThat(persisted.getMessages().get(0).getWebToolCalls()).isEmpty();
        List<SavedAiWebToolCall> calls = persisted.getMessages().get(1).getWebToolCalls();
        assertThat(calls).hasSize(2);
        assertThat(calls.get(0).getInput()).isEqualTo("kortty release");
        assertThat(calls.get(0).isSuccess()).isTrue();
        assertThat(calls.get(0).getSources()).hasSize(1);
        assertThat(calls.get(0).getSources().get(0).getUrl()).isEqualTo("https://example.test/kortty");
        assertThat(calls.get(1).getKind()).isEqualTo("EXTRACT");
        assertThat(calls.get(1).isSuccess()).isFalse();
        assertThat(calls.get(1).getMessage()).isEqualTo("HTTP 403");
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

    @Test
    void agentRunOriginAndStepsSurviveSaveAndReload() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        manager.load();

        SavedAiChat chat = new SavedAiChat();
        chat.setTitle("Disk");
        chat.setOrigin(SavedAiChat.ORIGIN_AGENT_RUN);
        chat.setSourceRunId("run-42");
        chat.setSourceTerminalSessionId("tab-1");
        chat.setSourceConnectionId("conn-1");
        chat.setSourceJournalId("journal-7");
        chat.setSourceJournalEntryId("entry-9");
        SavedAiChatMessage reply = message(SavedAiChatMessage.ROLE_ASSISTANT, "80% full.", "GPT Ops");
        SavedAgentStep step = new SavedAgentStep();
        step.setType("ACTION");
        step.setTitle("Run(df -h)");
        step.setCommand("df -h");
        step.setExitCode(0);
        step.setOutput("/dev/disk1 100G");
        step.setTruncated(true);
        reply.setAgentSteps(List.of(step));
        chat.setMessages(List.of(message(SavedAiChatMessage.ROLE_USER, "Disk?", null), reply));
        String id = manager.saveChat(chat).getId();

        AiChatManager reloaded = new AiChatManager(tempDir);
        reloaded.load();
        SavedAiChat persisted = reloaded.findById(id).orElseThrow();
        assertThat(persisted.isAgentRunChat()).isTrue();
        assertThat(persisted.getSourceRunId()).isEqualTo("run-42");
        assertThat(persisted.getSourceTerminalSessionId()).isEqualTo("tab-1");
        assertThat(persisted.getSourceConnectionId()).isEqualTo("conn-1");
        assertThat(persisted.getSourceJournalId()).isEqualTo("journal-7");
        assertThat(persisted.getSourceJournalEntryId()).isEqualTo("entry-9");
        List<SavedAgentStep> steps = persisted.getMessages().get(1).getAgentSteps();
        assertThat(steps).hasSize(1);
        assertThat(steps.get(0).getCommand()).isEqualTo("df -h");
        assertThat(steps.get(0).getExitCode()).isEqualTo(0);
        assertThat(steps.get(0).getOutput()).isEqualTo("/dev/disk1 100G");
        assertThat(steps.get(0).isTruncated()).isTrue();
        assertThat(persisted.getMessages().get(0).getAgentSteps()).isEmpty();
    }

    @Test
    void legacyChatFileLoadsAndSavesWithoutAnyNewElement() throws Exception {
        Files.writeString(tempDir.resolve(AiChatManager.AI_CHATS_FILE), """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <aiChats>
                <chat>
                    <id>chat-legacy</id>
                    <title>Old chat</title>
                    <createdAt>1700000000000</createdAt>
                    <updatedAt>1700000000000</updatedAt>
                    <messages>
                        <message>
                            <role>USER</role>
                            <content>Hello</content>
                            <createdAt>1700000000000</createdAt>
                        </message>
                    </messages>
                </chat>
            </aiChats>
            """);

        AiChatManager manager = new AiChatManager(tempDir);
        manager.load();
        SavedAiChat legacy = manager.findById("chat-legacy").orElseThrow();
        assertThat(legacy.isAgentRunChat()).isFalse();
        assertThat(legacy.getMessages().get(0).getAgentSteps()).isEmpty();
        manager.save();

        String rewritten = Files.readString(tempDir.resolve(AiChatManager.AI_CHATS_FILE));
        assertThat(rewritten).contains("Old chat");
        assertThat(rewritten).doesNotContain("origin");
        assertThat(rewritten).doesNotContain("sourceRunId");
        assertThat(rewritten).doesNotContain("agentSteps");
    }

    @Test
    void copiesOfAChatDoNotShareItsAgentSteps() {
        SavedAiChat chat = new SavedAiChat();
        SavedAiChatMessage reply = message(SavedAiChatMessage.ROLE_ASSISTANT, "ok", "GPT");
        SavedAgentStep step = new SavedAgentStep();
        step.setTitle("Run(ls)");
        reply.setAgentSteps(List.of(step));
        chat.setOrigin(SavedAiChat.ORIGIN_AGENT_RUN);
        chat.setMessages(List.of(reply));

        SavedAiChat copy = new SavedAiChat(chat);
        copy.getMessages().get(0).getAgentSteps().get(0).setTitle("changed");
        copy.setSourceRunId("run-copy");

        assertThat(chat.getMessages().get(0).getAgentSteps().get(0).getTitle()).isEqualTo("Run(ls)");
        assertThat(chat.getSourceRunId()).isNull();
        assertThat(new SavedAiChatMessage(reply).getAgentSteps().get(0).getTitle()).isEqualTo("Run(ls)");
        assertThat(copy.isAgentRunChat()).isTrue();
    }

    @Test
    void changeListenersHearAboutSavedAndDeletedChatsButNotAboutLoad() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        manager.load();
        List<String> heard = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.function.Consumer<String> listener = heard::add;
        manager.addChangeListener(listener);

        String id = manager.saveChat(new SavedAiChat()).getId();
        assertThat(heard).containsExactly(id);

        manager.load();
        assertThat(heard).containsExactly(id);

        assertThat(manager.deleteChat(id)).isTrue();
        assertThat(heard).containsExactly(id, id).inOrder();

        assertThat(manager.deleteChat(id)).isFalse();
        assertThat(heard).hasSize(2);

        manager.removeChangeListener(listener);
        manager.saveChat(new SavedAiChat());
        assertThat(heard).hasSize(2);
    }

    @Test
    void aChangeListenerRunsWithoutTheManagerLockSoItCanReadTheChat() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        manager.load();
        java.util.concurrent.atomic.AtomicBoolean readable = new java.util.concurrent.atomic.AtomicBoolean();
        manager.addChangeListener(chatId -> {
            java.util.concurrent.atomic.AtomicBoolean found = new java.util.concurrent.atomic.AtomicBoolean();
            Thread reader = new Thread(() -> found.set(manager.findById(chatId).isPresent()));
            reader.start();
            try {
                reader.join(3_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            readable.set(found.get());
        });

        manager.saveChat(new SavedAiChat());

        assertThat(readable.get()).isTrue();
    }

    @Test
    void findBySourceRunIdReturnsACopyOrNothing() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        manager.load();
        SavedAiChat chat = new SavedAiChat();
        chat.setOrigin(SavedAiChat.ORIGIN_AGENT_RUN);
        chat.setSourceRunId("run-7");
        String id = manager.saveChat(chat).getId();

        SavedAiChat found = manager.findBySourceRunId("run-7").orElseThrow();
        assertThat(found.getId()).isEqualTo(id);
        found.setSourceRunId("changed");
        assertThat(manager.findBySourceRunId("run-7")).isPresent();
        assertThat(manager.findBySourceRunId("run-8")).isEmpty();
        assertThat(manager.findBySourceRunId(null)).isEmpty();
        assertThat(manager.findBySourceRunId(" ")).isEmpty();
    }

    @Test
    void escapeCharactersFromCommandOutputAreRemovedBeforeSaving() throws Exception {
        AiChatManager manager = new AiChatManager(tempDir);
        manager.load();
        SavedAiChat chat = new SavedAiChat();
        chat.setTitle("Colors");
        SavedAiChatMessage reply = message(SavedAiChatMessage.ROLE_ASSISTANT, "a\u001B[31mred\u0001 b", "GPT");
        SavedAgentStep step = new SavedAgentStep();
        step.setTitle("Run(ls)");
        step.setOutput("\u001B[0mline\u0007");
        reply.setAgentSteps(List.of(step));
        chat.setMessages(List.of(reply));
        String id = manager.saveChat(chat).getId();

        AiChatManager reloaded = new AiChatManager(tempDir);
        reloaded.load();
        SavedAiChat persisted = reloaded.findById(id).orElseThrow();
        assertThat(persisted.getMessages().get(0).getContent()).isEqualTo("a[31mred b");
        assertThat(persisted.getMessages().get(0).getAgentSteps().get(0).getOutput()).isEqualTo("[0mline");
        assertThat(Files.readString(tempDir.resolve(AiChatManager.AI_CHATS_FILE))).doesNotContain("\u001B");
    }

    private SavedAiChatMessage message(String role, String content, String profileName) {
        SavedAiChatMessage message = new SavedAiChatMessage();
        message.setRole(role);
        message.setContent(content);
        message.setAiProfileName(profileName);
        return message;
    }
}
