package de.kortty.memory;

import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.memory.MemoryStore.WriteReason;
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
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

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

    /** A service whose policy gates allow the memory, as the application's policy does by default. */
    private MemoryService service() {
        MemoryService service = new MemoryService(store, name -> environment.get(name));
        service.setAiMemoryAllowedSupplier(() -> true);
        service.setUserMemoryModeSupplier(() -> UserMemoryMode.EDITABLE);
        service.setExternalFilesAllowedSupplier(() -> true);
        return service;
    }

    @Test
    void withoutThePolicySuppliersNothingIsReadOrWritten() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        MemoryService service = new MemoryService(store, name -> null);

        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).isEmpty()).isTrue();
        assertThat(service.isUserMemoryWritable()).isFalse();
        assertThrows(MemoryReadOnlyException.class, () -> store.write(
            MemoryScope.global(), EMPTY_DIGEST, "Other rule.\n", WriteReason.USER_SAVE, null));
    }

    @Test
    void cachedBlockForIsEmptyAtFirstThenComesFromTheBackgroundWithoutReadingTheFile() throws Exception {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        MemoryService service = service();

        assertThat(service.cachedBlockFor(null, AiTokenizerType.ESTIMATE, 1500).isEmpty()).isTrue();
        String text = "";
        for (int attempt = 0; attempt < 500 && text.isEmpty(); attempt++) {
            Thread.sleep(10);
            text = service.cachedBlockFor(null, AiTokenizerType.ESTIMATE, 1500).text();
        }
        assertThat(text).contains("Team rule.");

        // The file changes behind the service's back. A caller on the UI thread keeps getting the block computed
        // in the background, because it never reads the file itself; the refresh happens off the calling thread.
        Files.writeString(store.fileOf(MemoryScope.global()), "Changed behind the back.\n");
        assertThat(service.cachedBlockFor(null, AiTokenizerType.ESTIMATE, 1500).text()).contains("Team rule.");
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

    @Test
    void organisationMemoryIsSentWhateverTheUserSwitchTheModeAndTheKillSwitchSay() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Personal note.\n", WriteReason.USER_SAVE, null);
        environment = Map.of(MemoryService.KILL_SWITCH_VARIABLE, "off");
        MemoryService service = service();
        service.setEnabledSupplier(() -> false);
        service.setUserMemoryModeSupplier(() -> UserMemoryMode.DISABLED);
        service.setOrganizationMemorySupplier(() -> List.of(new OrganizationRule("Policy", "Use the audit log.")));

        String text = service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text();

        assertThat(text).contains("Use the audit log.");
        assertThat(text).doesNotContain("Personal note.");
    }

    @Test
    void aDeniedMemoryFeatureSendsNothingNotEvenOrganisationMemory() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Personal note.\n", WriteReason.USER_SAVE, null);
        MemoryService service = service();
        service.setAiMemoryAllowedSupplier(() -> false);
        service.setOrganizationMemorySupplier(() -> List.of(new OrganizationRule("Policy", "Use the audit log.")));

        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).isEmpty()).isTrue();
    }

    @Test
    void externalFilesNeedTheUsersMemoryAndTheExternalAllowance() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Personal note.\n", WriteReason.USER_SAVE, null);
        MemoryService service = service();
        service.setExternalSourcesSupplier(connectionId -> List.of(new ExternalSource("CLAUDE.md", "Reference fact.")));
        service.setExternalFilesAllowedSupplier(() -> false);

        String denied = service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text();
        assertThat(denied).contains("Personal note.");
        assertThat(denied).doesNotContain("Reference fact.");

        service.setExternalFilesAllowedSupplier(() -> true);
        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text()).contains("Reference fact.");

        service.setUserMemoryModeSupplier(() -> UserMemoryMode.DISABLED);
        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text()).doesNotContain("Reference fact.");
    }

    @Test
    void thePolicyCapLowersTheRequestedBudget() throws IOException {
        StringBuilder rules = new StringBuilder();
        for (int index = 1; index <= 60; index++) {
            rules.append("Rule number ").append(index)
                .append(" keeps the team consistent with the shared guideline text.\n\n");
        }
        store.write(MemoryScope.global(), EMPTY_DIGEST, rules.toString(), WriteReason.USER_SAVE, null);
        MemoryService service = service();
        assertThat(service.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text()).contains("Rule number 60 ");

        service.setBudgetCapSupplier(() -> OptionalInt.of(300));
        MemoryBlock capped = service.blockFor(null, AiTokenizerType.ESTIMATE, 1500);

        assertThat(capped.tokens()).isAtMost(300);
        assertThat(capped.text()).contains("Rule number 1 ");
        assertThat(capped.text()).doesNotContain("Rule number 60 ");
    }

    @Test
    void aReadOnlyPolicyRefusesEveryWriteAndKeepsTheFileUnchanged() throws IOException {
        store.write(MemoryScope.global(), EMPTY_DIGEST, "Team rule.\n", WriteReason.USER_SAVE, null);
        MemoryService service = service();
        service.setUserMemoryModeSupplier(() -> UserMemoryMode.READ_ONLY);
        assertThat(service.isUserMemoryWritable()).isFalse();
        String digest = store.read(MemoryScope.global()).sha256();

        assertThrows(MemoryReadOnlyException.class, () -> store.write(MemoryScope.global(), digest,
            "Changed.\n", WriteReason.USER_SAVE, null));
        assertThrows(MemoryReadOnlyException.class, () -> store.write(MemoryScope.global(), digest,
            "", WriteReason.USER_CLEAR, null));
        assertThrows(MemoryReadOnlyException.class, () -> store.appendKorttyEntry(MemoryScope.global(),
            "A new rule.", "chat", MemoryConsent.grant(MemoryScope.global(), NOW)));
        assertThrows(MemoryReadOnlyException.class, () -> store.archiveKorttyEntries(MemoryScope.global(),
            Set.of("abc123"), MemoryConsent.grant(MemoryScope.global(), NOW)));
        assertThat(store.read(MemoryScope.global()).text()).isEqualTo("Team rule.\n");

        service.setUserMemoryModeSupplier(() -> UserMemoryMode.EDITABLE);
        assertThat(service.isUserMemoryWritable()).isTrue();
        store.write(MemoryScope.global(), digest, "Changed.\n", WriteReason.USER_SAVE, null);
        assertThat(store.read(MemoryScope.global()).text()).isEqualTo("Changed.\n");
    }

    @Test
    void aDeniedFeatureAlsoRefusesWritesAndTheStoreGateCanBeSetDirectly() throws IOException {
        MemoryService service = service();
        service.setAiMemoryAllowedSupplier(() -> false);
        assertThat(service.isUserMemoryWritable()).isFalse();
        assertThrows(MemoryReadOnlyException.class, () -> store.write(MemoryScope.global(), EMPTY_DIGEST,
            "Note.\n", WriteReason.USER_SAVE, null));

        MemoryStore gated = new MemoryStore(root.resolve("gated"), Clock.fixed(NOW, ZoneOffset.UTC));
        gated.setWriteGate(() -> false);
        assertThrows(MemoryReadOnlyException.class, () -> gated.write(MemoryScope.global(), EMPTY_DIGEST,
            "Note.\n", WriteReason.USER_SAVE, null));
        assertThat(Files.exists(gated.fileOf(MemoryScope.global()))).isFalse();
        gated.setWriteGate(() -> {
            throw new IllegalStateException("policy broke");
        });
        assertThrows(MemoryReadOnlyException.class, () -> gated.write(MemoryScope.global(), EMPTY_DIGEST,
            "Note.\n", WriteReason.USER_SAVE, null));
        gated.setWriteGate(() -> true);
        gated.write(MemoryScope.global(), EMPTY_DIGEST, "Note.\n", WriteReason.USER_SAVE, null);
        assertThat(Files.exists(gated.fileOf(MemoryScope.global()))).isTrue();
    }
}
