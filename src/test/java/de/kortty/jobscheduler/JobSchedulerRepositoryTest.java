package de.kortty.jobscheduler;

import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.ZonedDateTime;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class JobSchedulerRepositoryTest {

    @Test
    void sessionJournalSettingsAndRunLinksSurviveAReload() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-journal");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            ScheduledJob job = new ScheduledJob();
            job.setName("Checks");
            de.kortty.model.AutomationJournalConfig config = job.getSessionJournal();
            config.setEnabled(true);
            config.setAiMode(de.kortty.model.AutomationJournalAiMode.ON_FAILURE);
            config.setKeepMode(de.kortty.model.AutomationJournalKeepMode.ONLY_ON_FAILURE);
            config.setRetentionMode(de.kortty.model.AutomationJournalRetentionMode.FIXED_DATE);
            config.setExpiryDate("2027-03-01");
            config.setMaxJournals(7);
            config.setMaxStorageMb(250);
            config.setDedupEnabled(false);
            config.setAiProfileId("local-llama");
            repository.upsertJob(job);
            JobJournalEntry entry = JobJournalEntry.system(JobRunStatus.FAILED, "failed", "details");
            entry.setSessionJournalDirs(List.of("/journals/a", "/journals/b"));
            entry.setDuplicateOfJournalDirs(List.of("/journals/old"));
            repository.appendJournal(entry);
            repository.save();

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();

            de.kortty.model.AutomationJournalConfig loaded = reloaded.getJobs().get(0).getSessionJournal();
            assertThat(loaded.isEnabled()).isTrue();
            assertThat(loaded.getAiMode()).isEqualTo(de.kortty.model.AutomationJournalAiMode.ON_FAILURE);
            assertThat(loaded.getKeepMode()).isEqualTo(de.kortty.model.AutomationJournalKeepMode.ONLY_ON_FAILURE);
            assertThat(loaded.getRetentionMode()).isEqualTo(de.kortty.model.AutomationJournalRetentionMode.FIXED_DATE);
            assertThat(loaded.getExpiryDate()).isEqualTo("2027-03-01");
            assertThat(loaded.getMaxJournals()).isEqualTo(7);
            assertThat(loaded.getMaxStorageMb()).isEqualTo(250);
            assertThat(loaded.isDedupEnabled()).isFalse();
            assertThat(loaded.getAiProfileId()).isEqualTo("local-llama");
            JobJournalEntry loadedEntry = reloaded.getJournal().get(0);
            assertThat(loadedEntry.getSessionJournalDirs()).containsExactly("/journals/a", "/journals/b").inOrder();
            assertThat(loadedEntry.getDuplicateOfJournalDirs()).containsExactly("/journals/old");
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void aJobFileWithoutSessionJournalSettingsLoadsWithJournalsOff() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-legacy");
        try {
            Files.writeString(dir.resolve(JobSchedulerRepository.FILE_NAME), """
                <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
                <jobScheduler>
                    <jobs>
                        <job><id>j1</id><name>Legacy</name><enabled>true</enabled></job>
                    </jobs>
                    <journal>
                        <journalEntry><id>e1</id><jobId>j1</jobId><status>SUCCESS</status></journalEntry>
                    </journal>
                </jobScheduler>
                """);
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            repository.load();

            assertThat(repository.getJobs().get(0).getSessionJournal().isEnabled()).isFalse();
            assertThat(repository.getJournal()).isNotEmpty();
            assertThat(repository.getJournal().get(0).getSessionJournalDirs()).isEmpty();
            ScheduledJob legacy = repository.getJobs().get(0);
            assertThat(legacy.getNotificationConfig()).isNull();
            assertThat(legacy.effectiveNotificationConfig().getTriggers())
                .containsExactly(JobNotificationTrigger.FAILED, JobNotificationTrigger.BLOCKED);
            assertThat(legacy.effectiveNotificationConfig().isDesktop()).isTrue();
            assertThat(legacy.effectiveNotificationConfig().getWebhookTargetIds()).isEmpty();
            assertThat(repository.findLastFinishedStatus("j1")).hasValue(JobRunStatus.SUCCESS);
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void notificationConfigSurvivesAReload() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-notifications");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            ScheduledJob custom = new ScheduledJob();
            custom.setName("Custom");
            JobNotificationConfig config = new JobNotificationConfig();
            config.setTriggers(java.util.EnumSet.of(JobNotificationTrigger.RECOVERED, JobNotificationTrigger.SUCCESS));
            config.setDesktop(false);
            config.setWebhookTargetIds(List.of("hook-a", " hook-b ", "hook-a", ""));
            custom.setNotificationConfig(config);
            ScheduledJob silent = new ScheduledJob();
            silent.setName("Silent");
            JobNotificationConfig none = new JobNotificationConfig();
            none.setTriggers(List.of());
            silent.setNotificationConfig(none);
            ScheduledJob defaults = new ScheduledJob();
            defaults.setName("Defaults");
            repository.upsertJob(custom);
            repository.upsertJob(silent);
            repository.upsertJob(defaults);
            repository.save();

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();

            JobNotificationConfig loaded = reloaded.findJob(custom.getId()).orElseThrow().getNotificationConfig();
            assertThat(loaded).isNotNull();
            assertThat(loaded.getTriggers())
                .containsExactly(JobNotificationTrigger.RECOVERED, JobNotificationTrigger.SUCCESS);
            assertThat(loaded.isDesktop()).isFalse();
            assertThat(loaded.getWebhookTargetIds()).containsExactly("hook-a", "hook-b").inOrder();
            JobNotificationConfig loadedNone = reloaded.findJob(silent.getId()).orElseThrow().getNotificationConfig();
            assertThat(loadedNone).isNotNull();
            assertThat(loadedNone.getTriggers()).isEmpty();
            assertThat(reloaded.findJob(defaults.getId()).orElseThrow().getNotificationConfig()).isNull();
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void lastFinishedStatusIgnoresRunningEntriesAndPrefersTheLatestRun() {
        JobSchedulerRepository repository = new JobSchedulerRepository(Path.of("unused"));
        repository.appendJournal(journalEntry("j1", JobRunStatus.FAILED, "2026-05-04T08:00:00Z"));
        repository.appendJournal(journalEntry("j1", JobRunStatus.SUCCESS, "2026-05-04T07:00:00Z"));
        repository.appendJournal(journalEntry("j1", JobRunStatus.RUNNING, "2026-05-04T09:00:00Z"));
        repository.appendJournal(journalEntry("j2", JobRunStatus.BLOCKED, "2026-05-04T10:00:00Z"));

        assertThat(repository.findLastFinishedStatus("j1")).hasValue(JobRunStatus.FAILED);
        assertThat(repository.findLastFinishedStatus("j2")).hasValue(JobRunStatus.BLOCKED);
        assertThat(repository.findLastFinishedStatus("missing")).isEmpty();
        assertThat(repository.findLastFinishedStatus(null)).isEmpty();

        repository.appendJournal(journalEntry("j1", JobRunStatus.CANCELLED, "2026-05-04T08:00:00Z"));
        assertThat(repository.findLastFinishedStatus("j1")).hasValue(JobRunStatus.CANCELLED);
    }

    private static JobJournalEntry journalEntry(String jobId, JobRunStatus status, String finishedAt) {
        JobJournalEntry entry = new JobJournalEntry();
        entry.setJobId(jobId);
        entry.setStatus(status);
        entry.setStartedAt(finishedAt);
        entry.setFinishedAt(finishedAt);
        return entry;
    }

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

    @Test
    void corruptSchedulerFileIsQuarantinedAndAFreshFileIsWritten() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-corrupt");
        byte[] truncated = "<jobScheduler><jobs><job><id>j1</id><name>Nightly".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(dir.resolve(JobSchedulerRepository.FILE_NAME), truncated);
        Path backup = null;
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            // Used to throw, and the shutdown save then wrote the empty scheduler over the file.
            repository.load();

            backup = repository.getLoadFailureBackup().orElseThrow();
            assertThat(backup.getFileName().toString()).matches("job-scheduler\\.xml\\.corrupt-\\d{8}-\\d{6}");
            assertThat(Files.readAllBytes(backup)).isEqualTo(truncated);
            assertThat(repository.getJobs()).isEmpty();

            ScheduledJob job = new ScheduledJob();
            job.setName("After recovery");
            repository.upsertJob(job);
            repository.save();

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();
            assertThat(reloaded.getJobs()).hasSize(1);
            assertThat(reloaded.getJobs().get(0).getName()).isEqualTo("After recovery");
            assertThat(Files.readAllBytes(backup)).isEqualTo(truncated);
        } finally {
            if (backup != null) {
                Files.deleteIfExists(backup);
            }
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveIsRefusedWhenTheUnreadableFileCouldNotBeMovedAside() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-blocked");
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) == null
            || "root".equals(System.getProperty("user.name"))) {
            Files.deleteIfExists(dir);
            throw new SkipException("needs POSIX directory permissions to make the rename fail");
        }
        byte[] truncated = "<jobScheduler><jobs>".getBytes(StandardCharsets.UTF_8);
        Path file = Files.write(dir.resolve(JobSchedulerRepository.FILE_NAME), truncated);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);

            expectThrows(Exception.class, repository::load);
            assertThat(repository.isSaveBlocked()).isTrue();
            assertThat(repository.getLoadFailureBackup()).isEmpty();

            // What JobSchedulerService.shutdownSchedulerThreads does on quit.
            repository.upsertJob(new ScheduledJob());
            expectThrows(IllegalStateException.class, repository::save);
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        try {
            assertThat(Files.readAllBytes(file)).isEqualTo(truncated);
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void schedulerFileIsOwnerOnly() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-mode");
        Path file = dir.resolve(JobSchedulerRepository.FILE_NAME);
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            repository.save();
            if (Files.getFileAttributeView(file, PosixFileAttributeView.class) == null) {
                throw new SkipException("POSIX file attributes are not supported on this platform");
            }
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");

            // A file an older korTTY created with the default umask is tightened on the next save.
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
            repository.save();
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(file))).isEqualTo("rw-------");
            try (var siblings = Files.list(dir)) {
                assertThat(siblings.map(p -> p.getFileName().toString()).toList())
                    .containsExactly(JobSchedulerRepository.FILE_NAME);
            }
        } finally {
            Files.deleteIfExists(file);
            Files.deleteIfExists(dir);
        }
    }
}
