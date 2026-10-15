package de.kortty.jobscheduler;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

class JobDependencyGraphTest {

    private static final Instant NOW = Instant.parse("2026-05-04T08:00:00Z");

    @Test
    void cyclesAreDetectedThroughTransitiveDependents() {
        ScheduledJob a = job("a");
        ScheduledJob b = job("b", a);
        ScheduledJob c = job("c", b);
        ScheduledJob d = job("d");
        JobDependencyGraph graph = new JobDependencyGraph(List.of(a, b, c, d));

        assertThat(graph.wouldCreateCycle(a.getId(), List.of(c.getId()))).isTrue();
        assertThat(graph.wouldCreateCycle(a.getId(), List.of(a.getId()))).isTrue();
        assertThat(graph.wouldCreateCycle(c.getId(), List.of(d.getId(), a.getId()))).isFalse();
        assertThat(graph.allowedPredecessors(a.getId())).containsExactly(d);
        assertThat(graph.descendantsOf(a.getId())).containsExactly(b.getId(), c.getId());
        assertThat(graph.ancestorsOf(c.getId())).containsExactly(b.getId(), a.getId());
        assertThat(graph.successorsOf(a.getId())).containsExactly(b);
        assertThat(graph.isConnected(d.getId())).isFalse();
    }

    @Test
    void topologicalOrderPutsEveryJobAfterItsPredecessorsInTheSet() {
        ScheduledJob a = job("a");
        ScheduledJob b = job("b", a);
        ScheduledJob c = job("c", a);
        ScheduledJob d = job("d", b, c);
        JobDependencyGraph graph = new JobDependencyGraph(List.of(d, c, b, a));

        List<String> order = graph.topologicalOrder(a.getId());

        assertThat(order).hasSize(4);
        assertThat(order.get(0)).isEqualTo(a.getId());
        assertThat(order.get(3)).isEqualTo(d.getId());
        assertThat(graph.topologicalOrder(b.getId())).containsExactly(b.getId(), d.getId()).inOrder();
    }

    @Test
    void unknownPredecessorIdsAreIgnoredByTheGraphButBlockTheGate() {
        ScheduledJob a = job("a");
        a.setDependsOnJobIds(List.of("missing"));
        JobDependencyGraph graph = new JobDependencyGraph(List.of(a));

        assertThat(graph.predecessorsOf(a.getId())).isEmpty();
        JobDependencyGraph.DependencyCheck check = JobDependencyGraph.evaluateGate(
            a, id -> Optional.empty(), id -> Optional.empty(), NOW);
        assertThat(check.satisfied()).isFalse();
        assertThat(check.blockers().get(0).reason()).isEqualTo(JobDependencyGraph.BlockReason.MISSING);
    }

    @Test
    void gateNeedsASuccessWithinTheMaximumAge() {
        ScheduledJob p1 = job("p1");
        ScheduledJob p2 = job("p2");
        ScheduledJob p3 = job("p3");
        ScheduledJob target = job("target", p1, p2, p3);
        target.setDependencyMaxAgeMinutes(60);
        Map<String, ScheduledJob> jobs = Map.of(p1.getId(), p1, p2.getId(), p2, p3.getId(), p3, target.getId(), target);
        Map<String, JobJournalEntry> last = new HashMap<>();
        last.put(p1.getId(), entry(JobRunStatus.SUCCESS, NOW.minusSeconds(30 * 60)));
        last.put(p2.getId(), entry(JobRunStatus.FAILED, NOW.minusSeconds(60)));

        JobDependencyGraph.DependencyCheck check = JobDependencyGraph.evaluateGate(
            target, id -> Optional.ofNullable(jobs.get(id)), id -> Optional.ofNullable(last.get(id)), NOW);

        assertThat(check.satisfied()).isFalse();
        assertThat(check.blockers().stream().map(JobDependencyGraph.Blocker::reason).toList())
            .containsExactly(JobDependencyGraph.BlockReason.NOT_SUCCESSFUL, JobDependencyGraph.BlockReason.NEVER_RAN);

        last.put(p2.getId(), entry(JobRunStatus.SUCCESS, NOW.minusSeconds(61 * 60)));
        last.put(p3.getId(), entry(JobRunStatus.SUCCESS, NOW));
        check = JobDependencyGraph.evaluateGate(
            target, id -> Optional.ofNullable(jobs.get(id)), id -> Optional.ofNullable(last.get(id)), NOW);
        assertThat(check.blockers()).hasSize(1);
        assertThat(check.blockers().get(0).reason()).isEqualTo(JobDependencyGraph.BlockReason.TOO_OLD);
        assertThat(check.blockers().get(0).jobName()).isEqualTo("p2");

        target.setDependencyMaxAgeMinutes(0);
        assertThat(JobDependencyGraph.evaluateGate(
            target, id -> Optional.ofNullable(jobs.get(id)), id -> Optional.ofNullable(last.get(id)), NOW).satisfied()).isTrue();
    }

    @Test
    void chainNeedsEverySuccessAfterTheJobsOwnLastRun() {
        ScheduledJob p1 = job("p1");
        ScheduledJob p2 = job("p2");
        ScheduledJob target = job("target", p1, p2);
        target.setLastRunAt(ZonedDateTime.ofInstant(NOW.minusSeconds(600), ZoneId.of("UTC")).toString());
        Map<String, ScheduledJob> jobs = Map.of(p1.getId(), p1, p2.getId(), p2);
        Map<String, JobJournalEntry> last = Map.of(
            p1.getId(), entry(JobRunStatus.SUCCESS, NOW.minusSeconds(60)),
            p2.getId(), entry(JobRunStatus.SUCCESS, NOW.minusSeconds(1200)));

        JobDependencyGraph.DependencyCheck chain = JobDependencyGraph.evaluateChain(
            target, id -> Optional.ofNullable(jobs.get(id)), id -> Optional.ofNullable(last.get(id)), NOW);
        JobDependencyGraph.DependencyCheck gate = JobDependencyGraph.evaluateGate(
            target, id -> Optional.ofNullable(jobs.get(id)), id -> Optional.ofNullable(last.get(id)), NOW);

        assertThat(gate.satisfied()).isTrue();
        assertThat(chain.satisfied()).isFalse();
        assertThat(chain.blockers().get(0).reason()).isEqualTo(JobDependencyGraph.BlockReason.NOT_SINCE_LAST_RUN);
        assertThat(chain.blockers().get(0).jobName()).isEqualTo("p2");
    }

    @Test
    void dependenciesSurviveAReloadAndOldFilesLoadWithoutThem() throws Exception {
        Path dir = Files.createTempDirectory("kortty-job-deps-xml");
        try {
            JobSchedulerRepository repository = new JobSchedulerRepository(dir);
            ScheduledJob a = job("a");
            ScheduledJob b = job("b", a);
            b.setDependencyMode(JobDependencyMode.CHAIN);
            b.setDependencyMaxAgeMinutes(90);
            repository.upsertJob(a);
            repository.upsertJob(b);
            repository.save();

            JobSchedulerRepository reloaded = new JobSchedulerRepository(dir);
            reloaded.load();
            ScheduledJob loadedB = reloaded.findJob(b.getId()).orElseThrow();
            assertThat(loadedB.getDependsOnJobIds()).containsExactly(a.getId());
            assertThat(loadedB.getDependencyMode()).isEqualTo(JobDependencyMode.CHAIN);
            assertThat(loadedB.getDependencyMaxAgeMinutes()).isEqualTo(90);
            ScheduledJob loadedA = reloaded.findJob(a.getId()).orElseThrow();
            assertThat(loadedA.getDependsOnJobIds()).isEmpty();
            assertThat(loadedA.getDependencyMode()).isEqualTo(JobDependencyMode.GATE);
            assertThat(loadedA.getDependencyMaxAgeMinutes()).isNull();

            Path file = dir.resolve(JobSchedulerRepository.FILE_NAME);
            String xml = Files.readString(file);
            assertThat(xml).contains("<dependsOn>");
            // A file written before dependencies existed, or naming a job that is gone, still loads.
            Files.writeString(file, xml.replace("<jobId>" + a.getId() + "</jobId>", "<jobId>gone</jobId>"));
            JobSchedulerRepository pruned = new JobSchedulerRepository(dir);
            pruned.load();
            assertThat(pruned.findJob(b.getId()).orElseThrow().getDependsOnJobIds()).isEmpty();
        } finally {
            try (var files = Files.list(dir)) {
                for (Path file : files.toList()) {
                    Files.deleteIfExists(file);
                }
            }
            Files.deleteIfExists(dir);
        }
    }

    static ScheduledJob job(String name, ScheduledJob... predecessors) {
        ScheduledJob job = new ScheduledJob();
        job.setName(name);
        job.setDependsOnJobIds(java.util.Arrays.stream(predecessors).map(ScheduledJob::getId).toList());
        return job;
    }

    private static JobJournalEntry entry(JobRunStatus status, Instant finishedAt) {
        JobJournalEntry entry = new JobJournalEntry();
        entry.setStatus(status);
        entry.setFinishedAt(ZonedDateTime.ofInstant(finishedAt, ZoneId.of("Europe/Berlin")).toString());
        return entry;
    }
}
