package de.kortty.memory.external;

import de.kortty.policy.ExternalMemoryRoots;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;

class ExternalMemoryAccessTest {

    private Path root;
    private Path home;
    private Path outside;
    private Path project;

    @BeforeMethod
    void createFolders() throws IOException {
        root = ExternalMemoryFixtures.tempDirectory("kortty-external-access-");
        home = Files.createDirectories(root.resolve("home"));
        outside = Files.createDirectories(root.resolve("outside"));
        project = Files.createDirectories(root.resolve("work/project"));
    }

    @AfterMethod(alwaysRun = true)
    void deleteFolders() throws IOException {
        ExternalMemoryFixtures.deleteTree(root);
    }

    @Test
    void aMarkdownFileUnderTheHomeFolderIsAllowed() throws IOException {
        Path file = ExternalMemoryFixtures.write(home.resolve(".claude/CLAUDE.md"), "Rules.\n");

        ExternalMemoryAccess.Outcome outcome = access(p -> true).check(file, home);

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.file()).isEqualTo(file.toRealPath());
        assertThat(outcome.root()).isEqualTo(home.toRealPath());
    }

    @Test
    void aProjectFolderOutsideTheHomeFolderIsAllowedAsItsOwnRoot() throws IOException {
        Path file = ExternalMemoryFixtures.write(project.resolve("AGENTS.md"), "Rules.\n");

        ExternalMemoryAccess.Outcome outcome = access(p -> true).check(file, project);

        assertThat(outcome.allowed()).isTrue();
        assertThat(outcome.root()).isEqualTo(project.toRealPath());
    }

    @Test
    void aFileOutsideItsRootIsRefused() throws IOException {
        Path file = ExternalMemoryFixtures.write(outside.resolve("notes.md"), "Notes.\n");

        assertThat(access(p -> true).check(file, home).refusal()).hasValue(ExternalMemoryAccess.Refusal.OUTSIDE_ROOT);
    }

    @Test
    void aLinkThatLeavesTheRootIsRefused() throws IOException {
        Path target = ExternalMemoryFixtures.write(outside.resolve("secret.md"), "Not for korTTY.\n");
        Path link = home.resolve(".kiro/steering/link.md");
        Files.createDirectories(link.getParent());
        Files.createSymbolicLink(link, target);

        assertThat(access(p -> true).check(link, home).refusal()).hasValue(ExternalMemoryAccess.Refusal.OUTSIDE_ROOT);
    }

    @Test
    void aMissingFileIsNotFound() {
        assertThat(access(p -> true).check(home.resolve("missing.md"), home).refusal())
            .hasValue(ExternalMemoryAccess.Refusal.NOT_FOUND);
    }

    @Test
    void onlyMarkdownFilesQualify() throws IOException {
        Path file = ExternalMemoryFixtures.write(home.resolve("notes.txt"), "Text.\n");

        assertThat(access(p -> true).check(file, home).refusal()).hasValue(ExternalMemoryAccess.Refusal.NOT_MARKDOWN);
        assertThat(ExternalMemoryAccess.isMarkdown("rule.MDC")).isTrue();
    }

    @Test
    void thePolicyDecidesLast() throws IOException {
        Path file = ExternalMemoryFixtures.write(home.resolve("CLAUDE.md"), "Rules.\n");

        assertThat(access(p -> false).check(file, home).refusal()).hasValue(ExternalMemoryAccess.Refusal.POLICY);
    }

    @Test
    void thePolicyFolderAllowlistIsEnforced() throws IOException {
        Path allowed = ExternalMemoryFixtures.write(home.resolve("allowed/CLAUDE.md"), "Allowed.\n");
        Path other = ExternalMemoryFixtures.write(home.resolve("other/CLAUDE.md"), "Other.\n");
        ExternalMemoryRoots roots = ExternalMemoryRoots.of(List.of(home.resolve("allowed").toRealPath().toString()));
        ExternalMemoryAccess access = access(roots::allows);

        assertThat(access.check(allowed, home).allowed()).isTrue();
        assertThat(access.check(other, home).refusal()).hasValue(ExternalMemoryAccess.Refusal.POLICY);
    }

    @Test
    void aPolicyThatThrowsRefuses() throws IOException {
        Path file = ExternalMemoryFixtures.write(home.resolve("CLAUDE.md"), "Rules.\n");

        assertThat(access(p -> {
            throw new IllegalStateException("policy unreadable");
        }).check(file, home).refusal()).hasValue(ExternalMemoryAccess.Refusal.POLICY);
    }

    @Test
    void aGitRepositoryIsFoundAboveTheFile() throws IOException {
        Files.createDirectories(project.resolve(".git"));
        Path file = ExternalMemoryFixtures.write(project.resolve("docs/AGENTS.md"), "Rules.\n");

        assertThat(ExternalMemoryAccess.gitRepositoryRoot(file)).hasValue(project);
    }

    @Test
    void networkFileSystemTypesAreRecognised() {
        assertThat(ExternalMemoryAccess.NETWORK_FILE_STORE_TYPES).containsAtLeast("nfs", "smbfs", "cifs", "afpfs", "webdav");
        assertThat(ExternalMemoryAccess.isNetworkFileSystem(home)).isFalse();
    }

    private ExternalMemoryAccess access(Predicate<Path> policy) {
        return new ExternalMemoryAccess(home, policy);
    }
}
