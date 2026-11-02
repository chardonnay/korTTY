package de.kortty.memory.external;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class ExternalMemoryRegistryTest {

    private static final Instant APPROVED = Instant.parse("2026-11-02T10:00:00Z");
    private static final String DIGEST = "a".repeat(64);

    private Path directory;
    private Path file;

    @BeforeMethod
    void createDirectory() throws IOException {
        directory = ExternalMemoryFixtures.tempDirectory("kortty-external-registry-");
        file = directory.resolve(ExternalMemoryRegistry.FILE_NAME);
    }

    @AfterMethod(alwaysRun = true)
    void deleteDirectory() throws IOException {
        ExternalMemoryFixtures.deleteTree(directory);
    }

    @Test
    void entriesSurviveAReload() throws IOException {
        ExternalMemoryRegistry registry = new ExternalMemoryRegistry(file);
        ExternalMemoryEntry added = registry.add("/home/u/.claude/CLAUDE.md", "/home/u",
            ExternalMemoryTool.CLAUDE_CODE, null, DIGEST, APPROVED);
        registry.add("/home/u/project/AGENTS.md", "/home/u/project", ExternalMemoryTool.AGENTS_MD, "conn-1", DIGEST,
            APPROVED);

        List<ExternalMemoryEntry> reloaded = new ExternalMemoryRegistry(file).entries();

        assertThat(reloaded).hasSize(2);
        assertThat(reloaded.get(0)).isEqualTo(added);
        assertThat(reloaded.get(1).connectionId()).isEqualTo("conn-1");
        assertThat(reloaded.get(1).global()).isFalse();
        assertThat(reloaded.get(0).global()).isTrue();
    }

    @Test
    void theFileHoldsNoContentOfTheReferenceFile() throws IOException {
        new ExternalMemoryRegistry(file).add("/home/u/.codex/AGENTS.md", "/home/u", ExternalMemoryTool.CODEX, null,
            DIGEST, APPROVED);

        String text = Files.readString(file);

        assertThat(text).contains("\"approvedSha256\": \"" + DIGEST + "\"");
        assertThat(text).contains("\"status\": \"ok\"");
        assertThat(text).doesNotContain("Use tabs");
    }

    @Test
    void updateChangesOnlyTheNamedEntryAndPersistsIt() throws IOException {
        ExternalMemoryRegistry registry = new ExternalMemoryRegistry(file);
        ExternalMemoryEntry entry = registry.add("/home/u/.codex/AGENTS.md", "/home/u", ExternalMemoryTool.CODEX, null,
            DIGEST, APPROVED);

        registry.update(entry.id(), current -> current.withActive(false));

        assertThat(new ExternalMemoryRegistry(file).find(entry.id()).orElseThrow().active()).isFalse();
    }

    @Test
    void removeDropsTheApprovalButNotTheFile() throws IOException {
        ExternalMemoryRegistry registry = new ExternalMemoryRegistry(file);
        ExternalMemoryEntry entry = registry.add("/home/u/.codex/AGENTS.md", "/home/u", ExternalMemoryTool.CODEX, null,
            DIGEST, APPROVED);

        assertThat(registry.remove(entry.id())).isTrue();

        assertThat(new ExternalMemoryRegistry(file).entries()).isEmpty();
    }

    @Test
    void theSameFileCannotBeApprovedTwiceForOneScope() throws IOException {
        ExternalMemoryRegistry registry = new ExternalMemoryRegistry(file);
        registry.add("/home/u/.codex/AGENTS.md", "/home/u", ExternalMemoryTool.CODEX, null, DIGEST, APPROVED);

        assertThrows(IllegalArgumentException.class, () -> registry.add("/home/u/.codex/AGENTS.md", "/home/u",
            ExternalMemoryTool.CODEX, null, DIGEST, APPROVED));
        assertThat(registry.add("/home/u/.codex/AGENTS.md", "/home/u", ExternalMemoryTool.CODEX, "conn-2", DIGEST,
            APPROVED).connectionId()).isEqualTo("conn-2");
    }

    @Test
    void anUnparseableListStartsEmptyAndIsSetAsideAtTheNextChange() throws IOException {
        Files.writeString(file, "{ not json");
        ExternalMemoryRegistry registry = new ExternalMemoryRegistry(file);

        assertThat(registry.entries()).isEmpty();

        registry.add("/home/u/.codex/AGENTS.md", "/home/u", ExternalMemoryTool.CODEX, null, DIGEST, APPROVED);
        assertThat(Files.readString(file.resolveSibling(ExternalMemoryRegistry.FILE_NAME
            + ExternalMemoryRegistry.BROKEN_SUFFIX))).isEqualTo("{ not json");
        assertThat(new ExternalMemoryRegistry(file).entries()).hasSize(1);
    }

    @Test
    void aMalformedFieldMakesTheListUnparseableAndItIsSetAside() throws IOException {
        Files.writeString(file, "{\"version\": 1, \"entries\": [{\"id\": \"e1\", \"path\": \"/home/u/.codex/AGENTS.md\","
            + " \"root\": \"/home/u\", \"tool\": \"CODEX\", \"active\": true, \"approvedSha256\": \"" + DIGEST + "\","
            + " \"approvedAt\": \"yesterday\", \"status\": \"ok\"}]}");
        ExternalMemoryRegistry registry = new ExternalMemoryRegistry(file);

        assertThat(registry.entries()).isEmpty();

        registry.add("/home/u/.codex/AGENTS.md", "/home/u", ExternalMemoryTool.CODEX, null, DIGEST, APPROVED);
        assertThat(Files.exists(file.resolveSibling(ExternalMemoryRegistry.FILE_NAME
            + ExternalMemoryRegistry.BROKEN_SUFFIX))).isTrue();
        assertThat(new ExternalMemoryRegistry(file).entries()).hasSize(1);
    }

    @Test
    void anEmptyEntryMakesTheListUnparseableNotUnreadable() throws IOException {
        Files.writeString(file, "{\"version\": 1, \"entries\": [null]}");
        ExternalMemoryRegistry registry = new ExternalMemoryRegistry(file);

        registry.add("/home/u/.codex/AGENTS.md", "/home/u", ExternalMemoryTool.CODEX, null, DIGEST, APPROVED);

        assertThat(Files.exists(file.resolveSibling(ExternalMemoryRegistry.FILE_NAME
            + ExternalMemoryRegistry.BROKEN_SUFFIX))).isTrue();
        assertThat(new ExternalMemoryRegistry(file).entries()).hasSize(1);
    }

    @Test
    void theStatusWireNamesAreTheDocumentedOnes() {
        assertThat(ExternalMemoryStatus.REFUSED_SECRET.wireName()).isEqualTo("refused-secret");
        assertThat(ExternalMemoryStatus.fromWireName("refused-path")).isEqualTo(ExternalMemoryStatus.REFUSED_PATH);
        assertThat(ExternalMemoryStatus.fromWireName("unknown")).isEqualTo(ExternalMemoryStatus.MISSING);
    }
}
