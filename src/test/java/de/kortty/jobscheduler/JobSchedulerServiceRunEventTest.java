package de.kortty.jobscheduler;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerServiceRunEventTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin"));

    @Test
    void failureThenSuccessIsARecoveryAndCancellationsNeverNotify() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-run-events");
        Deque<JobExecutionOutcome> outcomes = new ArrayDeque<>(List.of(
            JobExecutionOutcome.failed("boom", 2, null, "stderr secret", "detail secret"),
            JobExecutionOutcome.success("fine", null, null, null),
            JobExecutionOutcome.cancelled("stopped", "by user")));
        JobSchedulerService service = newService(dir, (job, runId) -> outcomes.removeFirst());
        try {
            List<JobRunEvent> events = new CopyOnWriteArrayList<>();
            service.addRunEventListener(events::add);
            ScheduledJob job = new ScheduledJob();
            job.setName("Backup");
            JobNotificationConfig config = new JobNotificationConfig();
            config.setTriggers(EnumSet.allOf(JobNotificationTrigger.class));
            job.setNotificationConfig(config);
            service.getRepository().upsertJob(job);

            runAndWait(service, job);
            runAndWait(service, job);
            runAndWait(service, job);

            assertThat(events).hasSize(2);
            JobRunEvent failed = events.get(0);
            assertThat(failed.jobId()).isEqualTo(job.getId());
            assertThat(failed.jobName()).isEqualTo("Backup");
            assertThat(failed.status()).isEqualTo(JobRunStatus.FAILED);
            assertThat(failed.previousStatus()).isNull();
            assertThat(failed.exitCode()).isEqualTo(2);
            assertThat(failed.trigger()).isEqualTo("manual");
            assertThat(failed.summary()).isEqualTo("boom");
            assertThat(failed.finishedAt()).isEqualTo(CLOCK.instant());
            assertThat(failed.recovered()).isFalse();

            JobRunEvent recovered = events.get(1);
            assertThat(recovered.status()).isEqualTo(JobRunStatus.SUCCESS);
            assertThat(recovered.previousStatus()).isEqualTo(JobRunStatus.FAILED);
            assertThat(recovered.recovered()).isTrue();
            assertThat(recovered.matchingTriggers())
                .containsExactly(JobNotificationTrigger.SUCCESS, JobNotificationTrigger.RECOVERED);
            // The cancelled run is still journaled, it just publishes nothing.
            assertThat(service.getJournalForJob(job.getId())).hasSize(3);
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void defaultsNotifyOnlyFailuresAndBlocks() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-run-events-defaults");
        Deque<JobExecutionOutcome> outcomes = new ArrayDeque<>(List.of(
            JobExecutionOutcome.success("ok", null, null, null),
            JobExecutionOutcome.blocked("blocked", "policy"),
            JobExecutionOutcome.success("ok again", null, null, null)));
        JobSchedulerService service = newService(dir, (job, runId) -> outcomes.removeFirst());
        try {
            List<JobRunEvent> events = new CopyOnWriteArrayList<>();
            service.addRunEventListener(events::add);
            ScheduledJob job = new ScheduledJob();
            assertThat(job.getNotificationConfig()).isNull();
            service.getRepository().upsertJob(job);

            runAndWait(service, job);
            runAndWait(service, job);
            runAndWait(service, job);

            assertThat(events).hasSize(1);
            assertThat(events.get(0).status()).isEqualTo(JobRunStatus.BLOCKED);
            assertThat(events.get(0).previousStatus()).isEqualTo(JobRunStatus.SUCCESS);
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void aThrowingListenerDoesNotBreakPersistenceOrOtherListeners() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-run-events-throwing");
        JobSchedulerService service = newService(dir,
            (job, runId) -> JobExecutionOutcome.failed("boom", 1, null, null, null));
        try {
            List<JobRunEvent> events = new CopyOnWriteArrayList<>();
            service.addRunEventListener(event -> {
                throw new IllegalStateException("listener broke");
            });
            service.addRunEventListener(events::add);
            ScheduledJob job = new ScheduledJob();
            service.getRepository().upsertJob(job);

            runAndWait(service, job);

            assertThat(events).hasSize(1);
            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();
            assertThat(reloaded.getJournalForJob(job.getId())).hasSize(1);
            assertThat(reloaded.findJob(job.getId()).orElseThrow().getLastRunAt()).isNotNull();
            assertThat(service.hasActiveJobs()).isFalse();
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void noEventIsPublishedWhileDraining() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-run-events-drain");
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        JobSchedulerService service = newService(dir, (job, runId) -> {
            started.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return JobExecutionOutcome.failed("boom", 1, null, null, null);
        });
        try {
            List<JobRunEvent> events = new CopyOnWriteArrayList<>();
            service.addRunEventListener(events::add);
            ScheduledJob job = new ScheduledJob();
            service.getRepository().upsertJob(job);
            service.runJobNow(job.getId());
            assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();

            service.beginDrainForShutdown();
            release.countDown();
            service.awaitDrain();

            assertThat(events).isEmpty();
            assertThat(service.getJournalForJob(job.getId())).hasSize(1);
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void removedListenerReceivesNothing() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-run-events-remove");
        JobSchedulerService service = newService(dir,
            (job, runId) -> JobExecutionOutcome.failed("boom", 1, null, null, null));
        try {
            List<JobRunEvent> events = new CopyOnWriteArrayList<>();
            JobRunEventListener listener = events::add;
            service.addRunEventListener(listener);
            service.removeRunEventListener(listener);
            ScheduledJob job = new ScheduledJob();
            service.getRepository().upsertJob(job);

            runAndWait(service, job);

            assertThat(events).isEmpty();
        } finally {
            cleanup(service, dir);
        }
    }

    private static JobSchedulerService newService(Path dir, JobSchedulerService.JobRunner runner) {
        return new JobSchedulerService(new JobSchedulerRepository(dir), new JobScheduleCalculator(), runner, CLOCK);
    }

    /** Starts the job and waits until its worker has persisted the run and published its event. */
    private static void runAndWait(JobSchedulerService service, ScheduledJob job) throws InterruptedException {
        int before = service.getJournalForJob(job.getId()).size();
        service.runJobNow(job.getId());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (service.getJournalForJob(job.getId()).size() > before && !service.hasActiveJobs()) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Job run did not finish in time");
    }

    private static void cleanup(JobSchedulerService service, Path dir) throws Exception {
        service.shutdownSchedulerThreads(false);
        Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(dir);
    }
}
