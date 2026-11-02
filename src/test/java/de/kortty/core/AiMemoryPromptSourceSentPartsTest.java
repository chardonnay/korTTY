package de.kortty.core;

import de.kortty.memory.MemoryBlock;
import de.kortty.memory.MemoryBlock.Origin;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenizerType;
import de.kortty.policy.UserMemoryMode;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * The masked memory a request carries keeps the parts it reports, and a chat request reports the memory it sends to its
 * own "what was sent" summary.
 */
class AiMemoryPromptSourceSentPartsTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String TOKEN = "AKIAIOSFODNN7EXAMPLE";

    private Path root;
    private MemoryStore store;

    @BeforeMethod
    void useTemporaryMemory() throws IOException {
        root = Files.createTempDirectory("kortty-memory-sent-parts-");
        store = new MemoryStore(root.resolve("memory"), Clock.fixed(NOW, ZoneOffset.UTC));
        MemoryService service = new MemoryService(store, name -> null);
        service.setAiMemoryAllowedSupplier(() -> true);
        service.setUserMemoryModeSupplier(() -> UserMemoryMode.EDITABLE);
        MemoryService.replaceInstanceForTesting(service);
    }

    @AfterMethod(alwaysRun = true)
    void restoreMemory() throws IOException {
        MemoryService.replaceInstanceForTesting(null);
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void theMaskedCopyKeepsTheReportedPartsOfTheGlobalAndConnectionFiles() throws IOException {
        write(MemoryScope.global(), "Team rule.");
        write(MemoryScope.connection("conn-1"), "Host note.");

        MemoryBlock raw = MemoryService.get().blockFor("conn-1", AiTokenizerType.ESTIMATE, 1500);
        MemoryBlock sent = new AiMemoryPromptSource("conn-1", cloudProfile(), 1500).block();

        assertThat(raw.sentParts()).isNotEmpty();
        assertThat(sent.sentParts()).isEqualTo(raw.sentParts());
        assertThat(sent.includedEntryIds()).isEqualTo(raw.includedEntryIds());
        assertThat(sent.omittedCount()).isEqualTo(raw.omittedCount());
        assertThat(sent.sentPieces()).hasSize(2);
        assertThat(sent.sentPieces().get(0).origin()).isEqualTo(Origin.GLOBAL);
        assertThat(sent.sentPieces().get(0).text()).isEqualTo("Team rule.");
        assertThat(sent.sentPieces().get(1).origin()).isEqualTo(Origin.CONNECTION);
        assertThat(sent.sentPieces().get(1).text()).isEqualTo("Host note.");
    }

    @Test
    void theMaskedPiecesCarryTheMaskedTextAndItsTokens() {
        MemoryService.get().setOrganizationMemorySupplier(
            () -> List.of(new OrganizationRule("Keys", "Staging key " + TOKEN + ".")));

        MemoryBlock sent = new AiMemoryPromptSource(null, cloudProfile(), 1500).block();

        MemoryBlock.SentPiece organization = sent.sentPieces().get(0);
        assertThat(organization.origin()).isEqualTo(Origin.ORGANIZATION);
        assertThat(organization.text()).doesNotContain(TOKEN.substring(4));
        assertThat(organization.tokens())
            .isEqualTo(AiTokenCounter.countTextTokens(organization.text(), AiTokenizerType.ESTIMATE));
    }

    @Test
    void aChatRequestReportsTheMemoryItSendsToItsOwnSummary() throws Exception {
        write(MemoryScope.global(), "Team rule.");
        AiProfile profile = cloudProfile();
        AiSkillPromptSupport support = new AiSkillPromptSupport(false, false, List.of(), Set.of(),
            new AiMemoryPromptSource(null, profile, 1500), profile);

        AiSentContextCollector.Reply reply = AiSentContextCollector.capture(() -> {
            String prompt = support.appendChatSkills("base", new AiRequest(AiAction.ASK, "code", "box", "en", "question"));
            return new AiExecutionResult(prompt, null);
        });

        assertThat(reply.sent().memory().text()).contains("Team rule.");
        assertThat(reply.sent().entries()).hasSize(1);
        assertThat(reply.sent().skills()).isEmpty();
    }

    private void write(MemoryScope scope, String text) throws IOException {
        MemoryStore.Snapshot current = store.read(scope);
        store.write(scope, current.sha256(), text + "\n", MemoryStore.WriteReason.USER_SAVE, null);
    }

    private static AiProfile cloudProfile() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.com/v1");
        profile.setTokenizerType(AiTokenizerType.ESTIMATE);
        return profile;
    }
}
