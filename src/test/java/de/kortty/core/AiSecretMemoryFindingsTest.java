package de.kortty.core;

import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemorySecretException;
import de.kortty.memory.MemoryStore;
import de.kortty.memory.MemoryStore.WriteReason;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * The memory findings of the startup scan: the line and the kind of each one are kept, the value never; a write of the file
 * checks it again, so a file saved clean loses its flag, and a rescan after a restore flags what is there now.
 */
class AiSecretMemoryFindingsTest {

    private static final String PLAIN = "Keep this.\n";
    private static final String LEAK = "Keep this.\ndb_password=Hunter2xyz9\n";

    private Path root;
    private MemoryStore store;

    @BeforeMethod
    void createStore() throws IOException {
        root = Files.createTempDirectory("kortty-secret-findings-");
        store = new MemoryStore(root.resolve("memory"));
        Files.createDirectories(store.memoryDir());
    }

    @AfterMethod(alwaysRun = true)
    void deleteStore() throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void flaggedGlobalFile(AiSecretScanRegistry registry) throws IOException {
        Files.writeString(store.fileOf(MemoryScope.global()), LEAK);
        AiSecretStartupScan.run(List.of(), List.of(), store, registry);
    }

    @Test
    void theStartupScanKeepsTheLineAndTheKindButNotTheValue() throws IOException {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();

        flaggedGlobalFile(registry);

        List<AiSecretGuard.Finding> findings = registry.memoryFindings("global");
        assertThat(findings).isNotEmpty();
        assertThat(findings.get(0).line()).isEqualTo(2);
        assertThat(findings.get(0).category()).isEqualTo(AiSecretGuard.Category.SECRET_ASSIGNMENT);
        assertThat(findings.get(0).toString()).doesNotContain("Hunter2xyz9");
    }

    @Test
    void aWriteThatLeavesTheFileCleanClearsTheFlag() throws IOException {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();
        flaggedGlobalFile(registry);
        assertThat(registry.hasMemoryFinding("global")).isTrue();
        String digest = store.read(MemoryScope.global()).sha256();

        store.write(MemoryScope.global(), digest, PLAIN, WriteReason.USER_SAVE, null);

        assertThat(registry.hasMemoryFinding("global")).isFalse();
        assertThat(registry.memoryFindings("global")).isEmpty();
    }

    @Test
    void aRefusedWriteKeepsTheFlag() throws IOException {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();
        flaggedGlobalFile(registry);
        String digest = store.read(MemoryScope.global()).sha256();

        assertThrows(MemorySecretException.class,
            () -> store.write(MemoryScope.global(), digest, LEAK, WriteReason.USER_SAVE, null));

        assertThat(registry.hasMemoryFinding("global")).isTrue();
        assertThat(registry.memoryFindings("global")).isNotEmpty();
    }

    @Test
    void aCleanWriteToAnotherFileKeepsTheFlagOfThisOne() throws IOException {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();
        flaggedGlobalFile(registry);
        MemoryScope connection = MemoryScope.connection("conn-1");

        store.write(connection, store.read(connection).sha256(), PLAIN, WriteReason.USER_SAVE, null);

        assertThat(registry.hasMemoryFinding("global")).isTrue();
        assertThat(registry.hasMemoryFinding("connection-conn-1")).isFalse();
    }

    @Test
    void aRescanSeesAFileThatWasFixedOnDisk() throws IOException {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();
        flaggedGlobalFile(registry);
        Files.writeString(store.fileOf(MemoryScope.global()), PLAIN);

        AiSecretStartupScan.run(List.of(), List.of(), store, registry);

        assertThat(registry.hasMemoryFinding("global")).isFalse();
    }

    @Test
    void theRegistryReplacesAndRefreshesFindingsPerScope() {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();
        AiSecretGuard.Finding finding = new AiSecretGuard.Finding(AiSecretGuard.Category.TOKEN_FORMAT, 3, "***");

        registry.replaceMemoryFindings(Map.of("connection-a", List.of(finding)));
        assertThat(registry.memoryFindings("connection-a")).containsExactly(finding);

        registry.refreshMemory("connection-a", List.of());
        assertThat(registry.hasMemoryFinding("connection-a")).isFalse();

        registry.replaceMemoryScopes(Set.of("global"));
        assertThat(registry.hasMemoryFinding("global")).isTrue();
        assertThat(registry.memoryFindings("global")).isEmpty();
        assertThat(registry.memoryFindings(null)).isEmpty();
        assertThat(registry.memoryScopeKeys()).containsExactly("global");
    }
}
