package de.kortty.jobscheduler;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class JobSchedulerServiceDependencyTest {

    @Test
    void gateBlocksAScheduledRunUntilEveryPredecessorSucceeded() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-deps-gate");
        Map<String, JobRunStatus> results = new ConcurrentHashMap<>(Map.of("job1", JobRunStatus.FAILED));
        JobSchedulerService service = newService(dir, results);
        try {
            ScheduledJob job1 = store(service, "job1");
            ScheduledJob job3 = store(service, "job3", job1);

            runAndWait(service, job1, 1);
            service.submitJobForTest(job3.getId(), JobSchedulerService.TRIGGER_SCHEDULED);
            awaitJournal(service, job3, 1);
            JobJournalEntry blocked = service.getJournalForJob(job3.getId()).get(0);
            assertThat(blocked.getStatus()).isEqualTo(JobRunStatus.BLOCKED);
            assertThat(blocked.getDetailText()).contains("job1 last finished FAILED");
            assertThat(service.checkDependencies(job3.getId()).satisfied()).isFalse();

            results.put("job1", JobRunStatus.SUCCESS);
            runAndWait(service, job1, 2);
            assertThat(service.checkDependencies(job3.getId()).satisfied()).isTrue();
            service.submitJobForTest(job3.getId(), JobSchedulerService.TRIGGER_SCHEDULED);
            awaitJournal(service, job3, 2);
            assertThat(service.findLastFinishedStatus(job3.getId())).hasValue(JobRunStatus.SUCCESS);
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void manualRunsAreNotGated() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-deps-manual");
        JobSchedulerService service = newService(dir, new ConcurrentHashMap<>());
        try {
            ScheduledJob job1 = store(service, "job1");
            ScheduledJob job3 = store(service, "job3", job1);

            runAndWait(service, job3, 1);

            assertThat(service.findLastFinishedStatus(job3.getId())).hasValue(JobRunStatus.SUCCESS);
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void chainStartsTheJobOnceAllPredecessorsSucceededSinceItsLastRun() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-deps-chain");
        JobSchedulerService service = newService(dir, new ConcurrentHashMap<>());
        try {
            ScheduledJob job1 = store(service, "job1");
            ScheduledJob job2 = store(service, "job2");
            ScheduledJob job3 = chain(store(service, "job3", job1, job2), service);

            runAndWait(service, job1, 1);
            settle(service);
            assertThat(service.getJournalForJob(job3.getId())).isEmpty();

            runAndWait(service, job2, 1);
            awaitJournal(service, job3, 1);
            JobJournalEntry chained = service.getJournalForJob(job3.getId()).get(0);
            assertThat(chained.getStatus()).isEqualTo(JobRunStatus.SUCCESS);
            assertThat(chained.getTriggerType()).isEqualTo(JobSchedulerService.TRIGGER_DEPENDENCY);

            // job2's success predates job3's last run, so job1 alone does not start job3 again.
            runAndWait(service, job1, 2);
            settle(service);
            assertThat(service.getJournalForJob(job3.getId())).hasSize(1);

            runAndWait(service, job2, 2);
            awaitJournal(service, job3, 2);
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void chainDoesNotStartAfterAFailedPredecessor() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-deps-chain-fail");
        Map<String, JobRunStatus> results = new ConcurrentHashMap<>(Map.of("job1", JobRunStatus.FAILED));
        JobSchedulerService service = newService(dir, results);
        try {
            ScheduledJob job1 = store(service, "job1");
            ScheduledJob job2 = chain(store(service, "job2", job1), service);

            runAndWait(service, job1, 1);
            settle(service);

            assertThat(service.getJournalForJob(job2.getId())).isEmpty();
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void runWithSuccessorsStopsTheBranchBelowAFailure() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-deps-cascade");
        Map<String, JobRunStatus> results = new ConcurrentHashMap<>(Map.of("b", JobRunStatus.FAILED));
        JobSchedulerService service = newService(dir, results);
        try {
            ScheduledJob a = store(service, "a");
            ScheduledJob b = store(service, "b", a);
            ScheduledJob c = store(service, "c", b);
            ScheduledJob d = store(service, "d", a);

            List<String> order = service.runJobWithSuccessors(a.getId());

            assertThat(order.get(0)).isEqualTo(a.getId());
            assertThat(order).containsExactly(a.getId(), b.getId(), d.getId(), c.getId());
            awaitJournal(service, d, 1);
            awaitJournal(service, c, 1);
            settle(service);
            assertThat(service.findLastFinishedStatus(a.getId())).hasValue(JobRunStatus.SUCCESS);
            assertThat(service.findLastFinishedStatus(b.getId())).hasValue(JobRunStatus.FAILED);
            assertThat(service.findLastFinishedStatus(d.getId())).hasValue(JobRunStatus.SUCCESS);
            JobJournalEntry blocked = service.getJournalForJob(c.getId()).get(0);
            assertThat(blocked.getStatus()).isEqualTo(JobRunStatus.BLOCKED);
            assertThat(blocked.getDetailText()).contains("b finished FAILED");
            assertThat(service.getJournalForJob(c.getId())).hasSize(1);
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void savingACycleIsRejected() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-deps-cycle");
        JobSchedulerService service = newService(dir, new ConcurrentHashMap<>());
        try {
            ScheduledJob job1 = store(service, "job1");
            ScheduledJob job2 = store(service, "job2", job1);

            ScheduledJob draft = new ScheduledJob();
            draft.setId(job1.getId());
            draft.setName("job1");
            draft.setDependsOnJobIds(List.of(job2.getId()));

            assertThrows(IllegalArgumentException.class, () -> service.saveJob(draft));
            assertThat(service.findJob(job1.getId()).orElseThrow().getDependsOnJobIds()).isEmpty();
        } finally {
            cleanup(service, dir);
        }
    }

    @Test
    void deletingAPredecessorDropsItFromItsDependents() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-deps-delete");
        JobSchedulerService service = newService(dir, new ConcurrentHashMap<>());
        try {
            ScheduledJob job1 = store(service, "job1");
            ScheduledJob job2 = store(service, "job2");
            ScheduledJob job3 = store(service, "job3", job1, job2);

            assertThat(service.findDependentJobs(job1.getId())).containsExactly(job3);
            service.deleteJob(job1.getId());

            assertThat(service.findJob(job3.getId()).orElseThrow().getDependsOnJobIds()).containsExactly(job2.getId());
            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();
            assertThat(reloaded.findJob(job3.getId()).orElseThrow().getDependsOnJobIds()).containsExactly(job2.getId());
        } finally {
            cleanup(service, dir);
        }
    }

    private static ScheduledJob store(JobSchedulerService service, String name, ScheduledJob... predecessors) throws Exception {
        ScheduledJob job = new ScheduledJob();
        job.setName(name);
        job.getSchedule().setEnabled(false);
        job.setDependsOnJobIds(java.util.Arrays.stream(predecessors).map(ScheduledJob::getId).toList());
        service.saveJob(job);
        return job;
    }

    private static ScheduledJob chain(ScheduledJob job, JobSchedulerService service) throws Exception {
        job.setDependencyMode(JobDependencyMode.CHAIN);
        service.saveJob(job);
        return job;
    }

    /** Runs whose outcome is looked up by job name; unknown names succeed. */
    private static JobSchedulerService newService(Path dir, Map<String, JobRunStatus> results) {
        JobSchedulerService.JobRunner runner = (job, runId) -> switch (results.getOrDefault(job.getName(), JobRunStatus.SUCCESS)) {
            case FAILED -> JobExecutionOutcome.failed("failed", 1, null, null, null);
            case BLOCKED -> JobExecutionOutcome.blocked("blocked", null);
            case CANCELLED -> JobExecutionOutcome.cancelled("cancelled", null);
            default -> JobExecutionOutcome.success("ok", null, null, null);
        };
        return new JobSchedulerService(new JobSchedulerRepository(dir), new JobScheduleCalculator(), runner, new TickingClock());
    }

    private static void runAndWait(JobSchedulerService service, ScheduledJob job, int expectedRuns) throws InterruptedException {
        service.runJobNow(job.getId());
        awaitJournal(service, job, expectedRuns);
    }

    private static void awaitJournal(JobSchedulerService service, ScheduledJob job, int expectedRuns) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (service.getJournalForJob(job.getId()).size() >= expectedRuns && !service.isJobActive(job.getId())) {
                return;
            }
            Thread.sleep(10);
        }
        throw new AssertionError("Job " + job.getName() + " did not reach " + expectedRuns + " run(s) in time");
    }

    /** Gives follow-up runs a chance to start, then waits until none is active. */
    private static void settle(JobSchedulerService service) throws InterruptedException {
        Thread.sleep(200);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (service.hasActiveJobs() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
    }

    private static void cleanup(JobSchedulerService service, Path dir) throws Exception {
        service.shutdownSchedulerThreads(false);
        try (var files = Files.list(dir)) {
            for (Path file : files.toList()) {
                Files.deleteIfExists(file);
            }
        }
        Files.deleteIfExists(dir);
    }

    /** Advances one second per reading, so every journal timestamp is distinct and ordered. */
    private static final class TickingClock extends Clock {
        private final AtomicLong seconds = new AtomicLong(Instant.parse("2026-05-04T08:00:00Z").getEpochSecond());

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Europe/Berlin");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochSecond(seconds.getAndIncrement());
        }
    }
}
