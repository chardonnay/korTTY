package de.kortty.memory.external;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class ExternalMemoryDiscoveryTest {

    private Path root;
    private Path home;
    private Path outside;
    private Path project;

    @BeforeMethod
    void createTree() throws IOException {
        root = ExternalMemoryFixtures.tempDirectory("kortty-external-discovery-");
        home = Files.createDirectories(root.resolve("home"));
        outside = Files.createDirectories(root.resolve("outside"));
        project = Files.createDirectories(root.resolve("project"));
        ExternalMemoryFixtures.write(home.resolve(".claude/CLAUDE.md"), "Claude rules.\n");
        ExternalMemoryFixtures.write(home.resolve(".claude/rules/style.md"), "Style.\n");
        ExternalMemoryFixtures.write(home.resolve(".claude/rules/notes.txt"), "Not a rule file.\n");
        ExternalMemoryFixtures.write(home.resolve(".claude/projects/-Users-me-app/memory/MEMORY.md"), "Auto.\n");
        ExternalMemoryFixtures.write(home.resolve(".codex/AGENTS.md"), "Codex.\n");
        ExternalMemoryFixtures.write(home.resolve(".qwen/QWEN.md"), "Qwen.\n");
        ExternalMemoryFixtures.write(home.resolve(".qwen/projects/app/memory/MEMORY.md"), "Qwen auto.\n");
        ExternalMemoryFixtures.write(home.resolve(".kiro/steering/product.md"), "Kiro.\n");
        ExternalMemoryFixtures.write(home.resolve("Documents/Cline/Rules/team.md"), "Cline.\n");
        ExternalMemoryFixtures.write(project.resolve("CLAUDE.md"), "Project claude.\n");
        ExternalMemoryFixtures.write(project.resolve("AGENTS.md"), "Project agents.\n");
        ExternalMemoryFixtures.write(project.resolve(".cursor/rules/java.mdc"), "---\nglobs: *.java\n---\nJava.\n");
        ExternalMemoryFixtures.write(project.resolve(".clinerules/rules.md"), "Cline project.\n");
    }

    @AfterMethod(alwaysRun = true)
    void deleteTree() throws IOException {
        ExternalMemoryFixtures.deleteTree(root);
    }

    @Test
    void theHomeTableFindsTheKnownFilesWithTheirTools() {
        List<ExternalMemoryDiscovery.Candidate> found = discovery().homeCandidates();

        assertThat(labels(found)).containsExactly(
            "~/.claude/CLAUDE.md",
            "~/.claude/rules/style.md",
            "-Users-me-app",
            "~/.codex/AGENTS.md",
            "~/.qwen/QWEN.md",
            "app",
            "~/.kiro/steering/product.md",
            "~/Documents/Cline/Rules/team.md").inOrder();
        assertThat(found.stream().map(ExternalMemoryDiscovery.Candidate::tool).toList()).containsExactly(
            ExternalMemoryTool.CLAUDE_CODE, ExternalMemoryTool.CLAUDE_CODE, ExternalMemoryTool.CLAUDE_CODE,
            ExternalMemoryTool.CODEX, ExternalMemoryTool.QWEN, ExternalMemoryTool.QWEN, ExternalMemoryTool.KIRO,
            ExternalMemoryTool.CLINE).inOrder();
    }

    @Test
    void theProjectTableFindsTheRulesOfTheProjectFolder() throws IOException {
        List<ExternalMemoryDiscovery.Candidate> found = discovery().projectCandidates(project);

        assertThat(labels(found)).containsExactly("CLAUDE.md", "AGENTS.md", ".clinerules/rules.md",
            ".cursor/rules/java.mdc").inOrder();
        assertThat(found.get(0).root()).isEqualTo(project.toRealPath());
        assertThat(found.get(1).tool()).isEqualTo(ExternalMemoryTool.AGENTS_MD);
        assertThat(found.get(3).tool()).isEqualTo(ExternalMemoryTool.CURSOR);
    }

    @Test
    void aLinkThatLeavesItsFolderIsNotOffered() throws IOException {
        Path secret = ExternalMemoryFixtures.write(outside.resolve("leak.md"), "Leaked.\n");
        Files.createSymbolicLink(home.resolve(".kiro/steering/leak.md"), secret);

        List<ExternalMemoryDiscovery.Candidate> found = discovery().homeCandidates();

        assertThat(labels(found)).doesNotContain("~/.kiro/steering/leak.md");
        assertThat(found.stream().map(ExternalMemoryDiscovery.Candidate::path))
            .doesNotContain(secret.toRealPath());
    }

    @Test
    void aMissingFolderYieldsNothing() throws IOException {
        Path empty = Files.createDirectories(root.resolve("empty-home"));

        assertThat(new ExternalMemoryDiscovery(new ExternalMemoryAccess(empty, p -> true)).homeCandidates()).isEmpty();
    }

    @Test
    void theHomeCandidatesAreCanonicalAndRootedAtHome() throws IOException {
        ExternalMemoryDiscovery.Candidate codex = discovery().homeCandidates().stream()
            .filter(candidate -> candidate.tool() == ExternalMemoryTool.CODEX)
            .findFirst()
            .orElseThrow();

        assertThat(codex.root()).isEqualTo(home.toRealPath());
        assertThat(codex.path()).isEqualTo(home.toRealPath().resolve(".codex/AGENTS.md"));
    }

    private ExternalMemoryDiscovery discovery() {
        return new ExternalMemoryDiscovery(new ExternalMemoryAccess(home, p -> true));
    }

    private static List<String> labels(List<ExternalMemoryDiscovery.Candidate> candidates) {
        return candidates.stream().map(ExternalMemoryDiscovery.Candidate::label).toList();
    }
}
