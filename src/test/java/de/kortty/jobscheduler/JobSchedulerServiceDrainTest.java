package de.kortty.jobscheduler;

import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;

class JobSchedulerServiceDrainTest {

    @Test
    void schedulerOwnsNoTimerWithoutEnabledFutureJobs() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-idle");
        JobSchedulerService service = null;
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            service = new JobSchedulerService(
                repository,
                new JobScheduleCalculator(),
                (job, runId) -> JobExecutionOutcome.success("done", null, null, null),
                Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin")));

            service.start();
            assertThat(service.isTickScheduled()).isFalse();

            ScheduledJob job = new ScheduledJob();
            job.setName("Future job");
            job.setSchedule(JobSchedule.dailyInterval(60));
            service.saveJob(job);
            assertThat(service.isTickScheduled()).isTrue();

            job.setEnabled(false);
            service.saveJob(job);
            assertThat(service.isTickScheduled()).isFalse();
        } finally {
            if (service != null) {
                service.shutdownSchedulerThreads();
            }
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void drainWaitsForRunningJobAndBlocksNewStarts() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-drain");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSchedulerService service = new JobSchedulerService(
                repository,
                new JobScheduleCalculator(),
                (job, runId) -> {
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return JobExecutionOutcome.success("done", null, null, null);
                },
                Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin")));

            ScheduledJob job = new ScheduledJob();
            job.setName("Long job");
            repository.upsertJob(job);
            service.runJobNow(job.getId());
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            service.beginDrainForShutdown();
            service.runJobNow(job.getId());
            release.countDown();
            service.awaitDrain();

            assertThat(service.hasActiveJobs()).isFalse();
            assertThat(service.isDraining()).isTrue();
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void cancelActiveJobRequestsCancellationInterruptsWorkerAndPersistsCancelledRun() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-cancel");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch interrupted = new CountDownLatch(1);
            JobSchedulerService service = new JobSchedulerService(
                repository,
                new JobScheduleCalculator(),
                (job, runId) -> {
                    started.countDown();
                    try {
                        Thread.sleep(5_000);
                    } catch (InterruptedException e) {
                        interrupted.countDown();
                        Thread.currentThread().interrupt();
                    }
                    return JobExecutionOutcome.success("done", null, null, null);
                },
                Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin")));

            ScheduledJob job = new ScheduledJob();
            job.setName("Cancelable job");
            repository.upsertJob(job);
            service.runJobNow(job.getId());
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(service.getActiveJobSummaries()).hasSize(1);
            assertThat(service.getActiveJobSummaries().get(0).cancellationRequested()).isFalse();

            assertThat(service.cancelJob(job.getId())).isTrue();

            assertThat(interrupted.await(2, TimeUnit.SECONDS)).isTrue();
            service.awaitDrain();
            assertThat(service.hasActiveJobs()).isFalse();
            assertThat(service.getJournalForJob(job.getId())).hasSize(1);
            JobJournalEntry entry = service.getJournalForJob(job.getId()).get(0);
            assertThat(entry.getStatus()).isEqualTo(JobRunStatus.CANCELLED);
            assertThat(entry.getSummary()).contains("cancelled");
            assertThat(entry.getStartedAt()).startsWith("2026-05-04T10:00");
            assertThat(entry.getStartedAt()).contains("[Europe/Berlin]");
            assertThat(entry.getFinishedAt()).startsWith("2026-05-04T10:00");
            assertThat(entry.getFinishedAt()).contains("[Europe/Berlin]");
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void deleteActiveJobIsBlocked() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-delete-active");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSchedulerService service = new JobSchedulerService(
                repository,
                new JobScheduleCalculator(),
                (job, runId) -> {
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return JobExecutionOutcome.success("done", null, null, null);
                },
                Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin")));

            ScheduledJob job = new ScheduledJob();
            job.setName("Running job");
            repository.upsertJob(job);
            service.runJobNow(job.getId());
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            try {
                service.deleteJob(job.getId());
                throw new AssertionError("Expected active job deletion to be blocked.");
            } catch (IllegalStateException expected) {
                assertThat(expected).hasMessageThat().contains("running");
            } finally {
                release.countDown();
                service.awaitDrain();
            }

            assertThat(service.findJob(job.getId())).isPresent();
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void completedRunDoesNotResurrectDeletedRepositoryJob() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-no-resurrect");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSchedulerService service = new JobSchedulerService(
                repository,
                new JobScheduleCalculator(),
                (job, runId) -> {
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return JobExecutionOutcome.success("done", null, null, null);
                },
                Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin")));

            ScheduledJob job = new ScheduledJob();
            job.setName("Externally deleted job");
            repository.upsertJob(job);
            service.runJobNow(job.getId());
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();

            repository.deleteJob(job.getId());
            release.countDown();
            service.awaitDrain();

            assertThat(service.findJob(job.getId())).isEmpty();
            assertThat(service.getJournalForJob(job.getId())).hasSize(1);
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void runJobNowSkipsJobDeletedAfterInitialLookup() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-run-now-deleted");
        try {
            DeleteOnFindRepository repository = new DeleteOnFindRepository(dir);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSchedulerService service = new JobSchedulerService(
                repository,
                new JobScheduleCalculator(),
                (job, runId) -> {
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return JobExecutionOutcome.success("done", null, null, null);
                },
                Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin")));

            ScheduledJob job = new ScheduledJob();
            job.setName("Deleted before manual submit");
            repository.upsertJob(job);
            repository.deleteOnNextFind();

            try {
                service.runJobNow(job.getId());
                assertThat(started.await(500, TimeUnit.MILLISECONDS)).isFalse();
                assertThat(service.hasActiveJobs()).isFalse();
                assertThat(service.findJob(job.getId())).isEmpty();
            } finally {
                release.countDown();
                service.awaitDrain();
            }
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void tickSkipsJobDeletedAfterSnapshot() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-tick-deleted");
        try {
            DeleteOnGetJobsRepository repository = new DeleteOnGetJobsRepository(dir);
            CountDownLatch started = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            JobSchedulerService service = new JobSchedulerService(
                repository,
                new JobScheduleCalculator(),
                (job, runId) -> {
                    started.countDown();
                    try {
                        release.await(2, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return JobExecutionOutcome.success("done", null, null, null);
                },
                Clock.fixed(Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin")));

            ScheduledJob job = new ScheduledJob();
            job.setName("Deleted after tick snapshot");
            job.setNextRunAt(ZonedDateTime.parse("2026-05-04T09:59:00+02:00[Europe/Berlin]").toString());
            repository.upsertJob(job);
            repository.deleteOnNextGetJobs();

            try {
                invokeTick(service);
                assertThat(started.await(500, TimeUnit.MILLISECONDS)).isFalse();
                assertThat(service.hasActiveJobs()).isFalse();
                assertThat(service.findJob(job.getId())).isEmpty();
            } finally {
                release.countDown();
                service.awaitDrain();
            }
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    private void invokeTick(JobSchedulerService service) throws Exception {
        Method tick = JobSchedulerService.class.getDeclaredMethod("tick");
        tick.setAccessible(true);
        tick.invoke(service);
    }

    @Test
    void enabledScheduledJobsRequireEnabledScheduleAndFutureRun() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-scheduler-power-state");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            Clock clock = Clock.fixed(
                Instant.parse("2026-05-04T08:00:00Z"), ZoneId.of("Europe/Berlin"));
            JobSchedulerService service = new JobSchedulerService(
                repository,
                new JobScheduleCalculator(),
                (job, runId) -> JobExecutionOutcome.success("done", null, null, null),
                clock);

            ScheduledJob future = new ScheduledJob();
            future.setNextRunAt("2026-05-04T12:00:00+02:00[Europe/Berlin]");
            repository.upsertJob(future);
            assertThat(service.hasEnabledScheduledJobs()).isTrue();

            future.getSchedule().setEnabled(false);
            repository.upsertJob(future);
            assertThat(service.hasEnabledScheduledJobs()).isFalse();

            future.getSchedule().setEnabled(true);
            future.setEnabled(false);
            repository.upsertJob(future);
            assertThat(service.hasEnabledScheduledJobs()).isFalse();

            future.setEnabled(true);
            future.setNextRunAt("2026-05-04T09:00:00+02:00[Europe/Berlin]");
            repository.upsertJob(future);
            assertThat(service.hasEnabledScheduledJobs()).isFalse();
        } finally {
            Files.deleteIfExists(dir.resolve(JobSchedulerRepository.FILE_NAME));
            Files.deleteIfExists(dir);
        }
    }

    private static final class DeleteOnFindRepository extends JobSchedulerRepository {
        private boolean deleteOnFind;

        private DeleteOnFindRepository(Path configDir) {
            super(configDir);
        }

        private void deleteOnNextFind() {
            deleteOnFind = true;
        }

        @Override
        public synchronized Optional<ScheduledJob> findJob(String jobId) {
            Optional<ScheduledJob> found = super.findJob(jobId);
            if (deleteOnFind && found.isPresent()) {
                deleteOnFind = false;
                super.deleteJob(jobId);
            }
            return found;
        }
    }

    private static final class DeleteOnGetJobsRepository extends JobSchedulerRepository {
        private boolean deleteOnGetJobs;

        private DeleteOnGetJobsRepository(Path configDir) {
            super(configDir);
        }

        private void deleteOnNextGetJobs() {
            deleteOnGetJobs = true;
        }

        @Override
        public synchronized List<ScheduledJob> getJobs() {
            List<ScheduledJob> jobs = super.getJobs();
            if (deleteOnGetJobs && !jobs.isEmpty()) {
                deleteOnGetJobs = false;
                super.deleteJob(jobs.get(0).getId());
            }
            return jobs;
        }
    }
}
