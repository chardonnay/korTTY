package de.kortty.memory;

import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.memory.MemoryStore.WriteReason;
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
import java.util.Map;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

class MemoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");
    private static final String EMPTY_DIGEST = MemoryFormat.sha256("");

    private Path root;
    private MemoryStore store;
    private Map<String, String> environment;

    @BeforeMethod
    void createStore() throws IOException {
        root = Files.createTempDirectory("kortty-memory-service-");
        store = new MemoryStore(root.resolve("memory"), Clock.fixed(NOW, ZoneOffset.UTC));
        environment = Map.of();
    }

    @AfterMethod(alwaysRun = true)
    void deleteStore() throws IOException {
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private MemoryService service() {
        return new MemoryService(store, name -> environment.get(name));
    }

    @Test
    void blockCombinesTheGlobalAndTheConnectionMemory() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        store.write(MemoryScope.connection("conn-1"), EMPTY_DIGEST, "Host note.\n", WriteReason.USER_SAVE, null);

        MemoryBlock block = service().blockFor("conn-1", AiTokenizerType.ESTIMATE, 1500);

        assertThat(block.text()).contains("Team rule.");
        assertThat(block.text()).contains("Host note.");
        assertThat(service().blockFor(null, AiTokenizerType.ESTIMATE, 1500).text()).doesNotContain("Host note.");
    }

    @Test
    void killSwitchTurnsMemoryOff() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        environment = Map.of(MemoryService.KILL_SWITCH_VARIABLE, " OFF ");
        assertThat(service().blockFor(null, AiTokenizerType.ESTIMATE, 1500).isEmpty()).isTrue();

        environment = Map.of(MemoryService.KILL_SWITCH_VARIABLE, "on");
        assertThat(service().blockFor(null, AiTokenizerType.ESTIMATE, 1500).isEmpty()).isFalse();
    }

    @Test
    void disabledSupplierTurnsMemoryOff() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        MemoryService service = service();
        service.setEnabledSupplier(() -> false);
        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).isEmpty()).isTrue();
    }

    @Test
    void aWriteThroughTheStoreIsVisibleInTheNextBlock() throws IOException {
        MemoryService service = service();
        store.write(MemoryScope.global(), EMPTY_DIGEST, "First rule.\n", WriteReason.USER_SAVE, null);
        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text()).contains("First rule.");

        MemoryStore.Snapshot current = store.read(MemoryScope.global());
        store.write(MemoryScope.global(), current.sha256(), "Second rule.\n", WriteReason.USER_SAVE, null);

        MemoryBlock block = service.blockFor(null, AiTokenizerType.ESTIMATE, 1500);
        assertThat(block.text()).contains("Second rule.");
        assertThat(block.text()).doesNotContain("First rule.");
    }

    @Test
    void organisationAndExternalSourcesAreIncluded() {
        MemoryService service = service();
        service.setOrganizationMemorySupplier(() -> List.of(new OrganizationRule("Policy", "Use the audit log.")));
        service.setExternalSourcesSupplier(connectionId -> List.of(new ExternalSource("CLAUDE.md", "Be brief.")));

        String text = service.blockFor("conn-9", AiTokenizerType.ESTIMATE, 1500).text();

        assertThat(text).contains("Use the audit log.");
        assertThat(text).contains("Be brief.");
    }

    @Test
    void aThrowingSupplierDoesNotDropTheMemory() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        MemoryService service = service();
        service.setOrganizationMemorySupplier(() -> {
            throw new IllegalStateException("policy broke");
        });

        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text()).contains("Team rule.");
    }

    @Test
    void anUnreadableMemoryDirectoryFailsOpenWithAnEmptyBlock() throws IOException {
        Path blocked = root.resolve("blocked");
        Files.writeString(blocked, "not a directory");
        MemoryService service = new MemoryService(new MemoryStore(blocked), name -> null);
        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).isEmpty()).isTrue();
    }
}
