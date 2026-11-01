package de.kortty.core;

import de.kortty.memory.MemoryBlock;
import de.kortty.memory.MemoryBlockBuilder;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenizerType;
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
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

class AiMemoryPromptSourceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String TOKEN = "AKIAIOSFODNN7EXAMPLE";

    private Path root;
    private MemoryStore store;

    @BeforeMethod
    void useTemporaryMemory() throws IOException {
        root = Files.createTempDirectory("kortty-memory-source-");
        store = new MemoryStore(root.resolve("memory"), Clock.fixed(NOW, ZoneOffset.UTC));
        MemoryService service = new MemoryService(store, name -> null);
        service.setAiMemoryAllowedSupplier(() -> true);
        service.setUserMemoryModeSupplier(() -> de.kortty.policy.UserMemoryMode.EDITABLE);
        MemoryService.replaceInstanceForTesting(service);
    }

    /** Resets the application's service to the lazily created default, the state before the test. */
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
    void aConnectionSourceCarriesTheGlobalAndTheConnectionMemory() throws IOException {
        write(MemoryScope.global(), "Team rule.");
        write(MemoryScope.connection("conn-1"), "Host note.");

        String text = new AiMemoryPromptSource("conn-1", cloudProfile(), 1500).block().text();

        assertThat(text).contains("Team rule.");
        assertThat(text).contains("Host note.");
    }

    @Test
    void aNullConnectionCarriesTheGlobalMemoryOnly() throws IOException {
        write(MemoryScope.global(), "Team rule.");
        write(MemoryScope.connection("conn-1"), "Host note.");

        String text = new AiMemoryPromptSource(null, cloudProfile(), 1500).block().text();

        assertThat(text).contains("Team rule.");
        assertThat(text).doesNotContain("Host note.");
    }

    @Test
    void previewStartsEmptyAndTakesTheMemoryFromTheBackgroundCache() throws Exception {
        write(MemoryScope.global(), "Team rule.");

        assertThat(AiMemoryPromptSource.preview(null, cloudProfile(), 1500).block().isEmpty()).isTrue();
        String text = "";
        for (int attempt = 0; attempt < 500 && text.isEmpty(); attempt++) {
            Thread.sleep(10);
            text = AiMemoryPromptSource.preview(null, cloudProfile(), 1500).block().text();
        }

        assertThat(text).contains("Team rule.");
    }

    @Test
    void aZeroBudgetCarriesNothing() throws IOException {
        write(MemoryScope.global(), "Team rule.");

        assertThat(new AiMemoryPromptSource(null, cloudProfile(), 0).block().isEmpty()).isTrue();
    }

    @Test
    void aMaskedBlockCountsItsMaskedText() {
        MemoryService.get().setOrganizationMemorySupplier(
            () -> List.of(new OrganizationRule("Keys", "Staging key " + TOKEN + ".")));
        AiProfile profile = cloudProfile();

        MemoryBlock block = new AiMemoryPromptSource(null, profile, 1500).block();

        assertThat(block.text()).doesNotContain(TOKEN.substring(4));
        assertThat(block.tokens()).isEqualTo(AiTokenCounter.countTextTokens(block.text(), AiTokenizerType.ESTIMATE));
        assertThat(block.text()).startsWith(MemoryBlockBuilder.OPEN_TAG);
    }

    @Test
    void aTokenIsKeptForAnEmbeddedProfile() {
        MemoryService.get().setOrganizationMemorySupplier(
            () -> List.of(new OrganizationRule("Keys", "Staging key " + TOKEN + ".")));
        AiProfile embedded = new AiProfile();
        embedded.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);

        assertThat(new AiMemoryPromptSource(null, embedded, 1500).block().text()).contains(TOKEN);
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
