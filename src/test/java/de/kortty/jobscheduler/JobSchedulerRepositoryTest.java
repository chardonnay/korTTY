package de.kortty.jobscheduler;

import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZonedDateTime;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerRepositoryTest {

    @Test
    void saveAndLoadPreservesJobsHostKeysAndJournal() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            ScheduledJob job = new ScheduledJob();
            job.setName("Nightly backup");
            job.setConnectionId("server-1");
            job.setTargetConnectionIds(java.util.List.of("server-1", "server-2"));
            job.setTargetGroupNames(java.util.List.of("prod", "prod/eu"));
            job.setHostKeyVerificationDisabled(true);
            job.getAction().setType(JobActionType.RSYNC_SYNC);
            job.getAction().setRsyncDirection(RsyncDirection.DOWNLOAD);
            job.getAction().setRsyncSourcePaths(java.util.List.of("/var/www", "/srv/data"));
            job.getAction().setRsyncTargetRoot("/Users/daniel/sync");
            job.getAction().setRsyncDeleteEnabled(true);
            repository.upsertJob(job);
            PinnedHostKey hostKey = new PinnedHostKey();
            hostKey.setConnectionId("server-1");
            hostKey.setFingerprintSha256("SHA256:test");
            hostKey.setPublicKeyLine("ssh-ed25519 AAAATEST");
            repository.upsertPinnedHostKey(hostKey);
            repository.appendJournal(JobJournalEntry.system(JobRunStatus.SUCCESS, "done", "details"));
            repository.save();

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();

            assertThat(reloaded.getJobs()).hasSize(1);
            assertThat(reloaded.getJobs().get(0).getName()).isEqualTo("Nightly backup");
            assertThat(reloaded.getJobs().get(0).getTargetConnectionIds()).containsExactly("server-1", "server-2").inOrder();
            assertThat(reloaded.getJobs().get(0).getTargetGroupNames()).containsExactly("prod", "prod/eu").inOrder();
            assertThat(reloaded.getJobs().get(0).isHostKeyVerificationDisabled()).isTrue();
            assertThat(reloaded.getJobs().get(0).getAction().getType()).isEqualTo(JobActionType.RSYNC_SYNC);
            assertThat(reloaded.getJobs().get(0).getAction().getRsyncDirection()).isEqualTo(RsyncDirection.DOWNLOAD);
            assertThat(reloaded.getJobs().get(0).getAction().getRsyncSourcePaths()).containsExactly("/var/www", "/srv/data").inOrder();
            assertThat(reloaded.getJobs().get(0).getAction().getRsyncTargetRoot()).isEqualTo("/Users/daniel/sync");
            assertThat(reloaded.getJobs().get(0).getAction().isRsyncDeleteEnabled()).isTrue();
            assertThat(reloaded.findPinnedHostKey("server-1").orElseThrow().getFingerprintSha256()).isEqualTo("SHA256:test");
            assertThat(reloaded.findPinnedHostKey("server-1").orElseThrow().getPublicKeyLine()).isEqualTo("ssh-ed25519 AAAATEST");
            assertThat(reloaded.getJournal()).hasSize(1);
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveAndLoadPreservesSnippetScriptAction() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-snippet");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            ScheduledJob job = new ScheduledJob();
            job.setName("Snippet cleanup");
            job.getAction().setType(JobActionType.SNIPPET_SCRIPT);
            job.getAction().setSnippetId("snippet-1");
            job.getAction().setSnippetArguments(List.of("--dry-run", "/srv/data"));
            repository.upsertJob(job);
            repository.save();

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();

            JobAction action = reloaded.getJobs().get(0).getAction();
            assertThat(action.getType()).isEqualTo(JobActionType.SNIPPET_SCRIPT);
            assertThat(action.getSnippetId()).isEqualTo("snippet-1");
            assertThat(action.getSnippetArguments()).containsExactly("--dry-run", "/srv/data").inOrder();
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void loadMigratesAiAgentAutoApproveDefaultOnlyOnce() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-ai-agent");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            ScheduledJob job = new ScheduledJob();
            job.setName("AI maintenance");
            job.getAction().setType(JobActionType.AI_AGENT);
            job.getAction().setAiAutoApproveCommands(false);
            repository.upsertJob(job);
            repository.save();

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();

            ScheduledJob migrated = reloaded.getJobs().get(0);
            assertThat(migrated.getAction().isAiAutoApproveCommands()).isTrue();

            migrated.getAction().setAiAutoApproveCommands(false);
            reloaded.upsertJob(migrated);
            reloaded.save();

            JobSchedulerRepository loadedAfterManualDisable = new JobSchedulerRepository(dir);
            loadedAfterManualDisable.load();

            assertThat(loadedAfterManualDisable.getJobs().get(0).getAction().isAiAutoApproveCommands()).isFalse();
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void deleteJournalEntriesRemovesOnlySelectedEntriesAndPersists() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-journal-delete");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            JobJournalEntry first = JobJournalEntry.system(JobRunStatus.SUCCESS, "first", "first details");
            JobJournalEntry second = JobJournalEntry.system(JobRunStatus.FAILED, "second", "second details");
            repository.appendJournal(first);
            repository.appendJournal(second);

            int deleted = repository.deleteJournalEntries(List.of(first.getId()));
            repository.save();

            assertThat(deleted).isEqualTo(1);
            assertThat(repository.getJournal().stream().map(JobJournalEntry::getId).toList())
                .containsExactly(second.getId());

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();

            assertThat(reloaded.getJournal().stream().map(JobJournalEntry::getId).toList())
                .containsExactly(second.getId());
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void deleteJournalEntriesOlderThanRemovesOnlyExpiredTimestampedEntries() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-journal-retention");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            ZonedDateTime now = ZonedDateTime.parse("2026-05-05T12:00:00+02:00[Europe/Berlin]");
            JobJournalEntry oldEntry = JobJournalEntry.system(JobRunStatus.SUCCESS, "old", "old details");
            oldEntry.setStartedAt(now.minusDays(4).toString());
            oldEntry.setFinishedAt(now.minusDays(3).toString());
            JobJournalEntry recentEntry = JobJournalEntry.system(JobRunStatus.SUCCESS, "recent", "recent details");
            recentEntry.setStartedAt(now.minusHours(4).toString());
            recentEntry.setFinishedAt(now.minusHours(3).toString());
            JobJournalEntry invalidTimestampEntry = JobJournalEntry.system(JobRunStatus.SUCCESS, "invalid", "invalid details");
            invalidTimestampEntry.setStartedAt("not-a-timestamp");
            invalidTimestampEntry.setFinishedAt("also-not-a-timestamp");
            repository.appendJournal(oldEntry);
            repository.appendJournal(recentEntry);
            repository.appendJournal(invalidTimestampEntry);

            int deleted = repository.deleteJournalEntriesOlderThan(now.minusDays(2).toInstant());
            repository.save();

            assertThat(deleted).isEqualTo(1);
            assertThat(repository.getJournal().stream().map(JobJournalEntry::getId).toList())
                .containsExactly(recentEntry.getId(), invalidTimestampEntry.getId());

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();

            assertThat(reloaded.getJournal().stream().map(JobJournalEntry::getId).toList())
                .containsExactly(recentEntry.getId(), invalidTimestampEntry.getId());
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void runnerRequiresPinnedHostKeyUnlessJobDisablesVerification() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-hostkey");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            JobSchedulerJobRunner runner = new JobSchedulerJobRunner(null, repository);
            ScheduledJob job = new ScheduledJob();
            ServerConnection connection = new ServerConnection();
            connection.setId("server-1");
            connection.setName("Server 1");

            try {
                runner.resolvePinnedHostKeyForJob(job, connection);
                throw new AssertionError("Expected missing host key pinning to block the job.");
            } catch (JobBlockedException expected) {
                assertThat(expected.getMessage()).contains("Host key pinning is required");
            }

            job.setHostKeyVerificationDisabled(true);
            assertThat(runner.resolvePinnedHostKeyForJob(job, connection)).isNull();
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void legacyConnectionIdIsStillAnEffectiveTarget() {
        ScheduledJob job = new ScheduledJob();
        job.setConnectionId("legacy-server");

        assertThat(job.getTargetConnectionIds()).containsExactly("legacy-server");
        assertThat(job.getTargetGroupNames()).isEmpty();
    }

    @Test
    void sudoServicePrefersServerCredentialOverGroupCredential() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-sudo");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            JobSchedulerSudoService sudoService = new JobSchedulerSudoService(repository);
            char[] master = "test-master-password".toCharArray();
            sudoService.setGroupSudoPassword("prod", "group-secret", master);
            sudoService.setServerSudoPassword("server-1", "server-secret", master);
            de.kortty.model.ServerConnection connection = new de.kortty.model.ServerConnection();
            connection.setId("server-1");
            connection.setGroup("prod");

            assertThat(sudoService.resolveSudoPassword(connection, master).orElseThrow()).isEqualTo("server-secret");
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }
}
