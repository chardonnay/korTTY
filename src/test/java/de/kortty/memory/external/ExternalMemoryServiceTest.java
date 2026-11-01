package de.kortty.memory.external;

import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class ExternalMemoryServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-02T10:00:00Z");

    private Path root;
    private Path home;
    private Path file;
    private ExternalMemoryService service;

    @BeforeMethod
    void createService() throws IOException {
        root = ExternalMemoryFixtures.tempDirectory("kortty-external-service-");
        home = Files.createDirectories(root.resolve("home"));
        file = ExternalMemoryFixtures.write(home.resolve(".claude/CLAUDE.md"), "Use tabs.\n");
        ExternalMemoryRegistry registry = new ExternalMemoryRegistry(root.resolve("memory/external.json"));
        service = new ExternalMemoryService(registry, home, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterMethod(alwaysRun = true)
    void deleteTree() throws IOException {
        ExternalMemoryFixtures.deleteTree(root);
    }

    @Test
    void anApprovedFileIsSentWithItsToolLabel() throws IOException {
        approve(file, null);

        List<ExternalSource> sources = service.sourcesFor(null);

        assertThat(sources).hasSize(1);
        assertThat(sources.get(0).label()).isEqualTo("Claude Code: CLAUDE.md");
        assertThat(sources.get(0).content()).isEqualTo("Use tabs.\n");
        assertThat(sources.get(0).tool()).isEqualTo(ExternalMemoryTool.CLAUDE_CODE);
    }

    @Test
    void aChangedFileIsNotSentUntilItIsApprovedAgain() throws IOException {
        ExternalMemoryEntry entry = approve(file, null);

        ExternalMemoryFixtures.write(file, "Use spaces now, and a longer text.\n");

        assertThat(service.sourcesFor(null)).isEmpty();
        assertThat(service.entries().get(0).status()).isEqualTo(ExternalMemoryStatus.CHANGED);

        ExternalMemoryService.Review review = service.review(file, home);
        assertThat(review.approvable()).isTrue();
        service.reapprove(entry.id(), review);

        assertThat(service.entries().get(0).status()).isEqualTo(ExternalMemoryStatus.OK);
        assertThat(service.sourcesFor(null).get(0).content()).isEqualTo("Use spaces now, and a longer text.\n");
    }

    @Test
    void aFileThatHoldsASecretIsRefusedAndSwitchedOff() throws IOException {
        ExternalMemoryEntry entry = approve(file, null);

        ExternalMemoryFixtures.write(file, "Use tabs.\npassword = hunter2hunter2\n");

        assertThat(service.sourcesFor(null)).isEmpty();
        ExternalMemoryEntry stored = service.entries().get(0);
        assertThat(stored.id()).isEqualTo(entry.id());
        assertThat(stored.status()).isEqualTo(ExternalMemoryStatus.REFUSED_SECRET);
        assertThat(stored.reason()).startsWith("SECRET:2:");
        assertThat(stored.active()).isFalse();
        assertThat(service.review(file, home).problem()).hasValue(ExternalMemoryService.Problem.SECRET);
    }

    @Test
    void aConnectionScopeIsSentOnlyForItsConnection() throws IOException {
        approve(file, "conn-1");

        assertThat(service.sourcesFor("conn-1")).hasSize(1);
        assertThat(service.sourcesFor("conn-2")).isEmpty();
        assertThat(service.sourcesFor(null)).isEmpty();
    }

    @Test
    void aSwitchedOffEntryIsNotSentButStaysApproved() throws IOException {
        ExternalMemoryEntry entry = approve(file, null);
        service.setActive(entry.id(), false);

        assertThat(service.sourcesFor(null)).isEmpty();
        assertThat(service.entries().get(0).approvedSha256()).isEqualTo(entry.approvedSha256());
    }

    @Test
    void thePolicyRefusesAFileThatWasApproved() throws IOException {
        approve(file, null);

        service.setPolicyGate(path -> false);

        assertThat(service.sourcesFor(null)).isEmpty();
        assertThat(service.entries().get(0).status()).isEqualTo(ExternalMemoryStatus.REFUSED_PATH);
        assertThat(service.entries().get(0).reason()).isEqualTo("POLICY");
        assertThat(service.review(file, home).problem()).hasValue(ExternalMemoryService.Problem.POLICY);
    }

    @Test
    void aDeletedFileIsMissing() throws IOException {
        approve(file, null);

        Files.delete(file);

        assertThat(service.sourcesFor(null)).isEmpty();
        assertThat(service.entries().get(0).status()).isEqualTo(ExternalMemoryStatus.MISSING);
    }

    @Test
    void approvingTheSameFileTwiceForOneScopeIsRefused() throws IOException {
        approve(file, null);
        ExternalMemoryService.Review review = service.review(file, home);

        ExternalMemoryService.RefusedException refused = expectThrows(ExternalMemoryService.RefusedException.class,
            () -> service.approve(review, ExternalMemoryTool.CLAUDE_CODE, null));
        assertThat(refused.problem()).isEqualTo(ExternalMemoryService.Problem.DUPLICATE);
    }

    @Test
    void aSecretIsNeverApproved() throws IOException {
        ExternalMemoryFixtures.write(file, "password = hunter2hunter2\n");
        ExternalMemoryService.Review review = service.review(file, home);

        assertThat(review.approvable()).isFalse();
        expectThrows(ExternalMemoryService.RefusedException.class,
            () -> service.approve(review, ExternalMemoryTool.CLAUDE_CODE, null));
    }

    @Test
    void aFileChangedAfterTheReviewIsNotApproved() throws IOException {
        ExternalMemoryService.Review review = service.review(file, home);
        ExternalMemoryFixtures.write(file, "Changed after the review, and longer.\n");

        ExternalMemoryService.RefusedException refused = expectThrows(ExternalMemoryService.RefusedException.class,
            () -> service.approve(review, ExternalMemoryTool.CLAUDE_CODE, null));
        assertThat(refused.problem()).isEqualTo(ExternalMemoryService.Problem.CHANGED);
        assertThat(service.entries()).isEmpty();
    }

    @Test
    void aReviewShowsTheRepositoryTheFileLiesIn() throws IOException {
        Files.createDirectories(home.resolve(".git"));

        ExternalMemoryService.Review review = service.review(file, home);

        assertThat(review.repository()).hasValue(home.toRealPath());
    }

    private ExternalMemoryEntry approve(Path reviewed, String connectionId) throws IOException {
        ExternalMemoryService.Review review = service.review(reviewed, home);
        assertThat(review.approvable()).isTrue();
        return service.approve(review, ExternalMemoryTool.CLAUDE_CODE, connectionId);
    }
}
