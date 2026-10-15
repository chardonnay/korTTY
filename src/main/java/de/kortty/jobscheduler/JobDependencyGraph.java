package de.kortty.jobscheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * An immutable snapshot of the "depends on" relation between scheduler jobs, plus the checks that
 * decide whether a job's predecessors allow it to run. Edges point from a predecessor to the job
 * that depends on it. Ids that name no job in the snapshot are ignored.
 */
public final class JobDependencyGraph {

    /** Why one predecessor does not satisfy a dependency. */
    public enum BlockReason {
        /** The predecessor no longer exists. */
        MISSING,
        /** The predecessor has no finished run. */
        NEVER_RAN,
        /** The predecessor's last finished run was not successful. */
        NOT_SUCCESSFUL,
        /** The predecessor's last success is older than the job's maximum age. */
        TOO_OLD,
        /** The predecessor has not succeeded again since the dependent job last ran (chain mode). */
        NOT_SINCE_LAST_RUN
    }

    public record Blocker(String jobId, String jobName, BlockReason reason, JobRunStatus lastStatus) {

        /** An English one-liner for the run journal. */
        public String describe() {
            return switch (reason) {
                case MISSING -> "predecessor " + jobId + " no longer exists";
                case NEVER_RAN -> jobName + " has never run";
                case NOT_SUCCESSFUL -> jobName + " last finished " + lastStatus;
                case TOO_OLD -> jobName + " last succeeded too long ago";
                case NOT_SINCE_LAST_RUN -> jobName + " has not succeeded since this job last ran";
            };
        }
    }

    public record DependencyCheck(boolean satisfied, List<Blocker> blockers) {

        public DependencyCheck {
            blockers = blockers != null ? List.copyOf(blockers) : List.of();
        }

        public static DependencyCheck ok() {
            return new DependencyCheck(true, List.of());
        }

        public String describe() {
            return String.join("; ", blockers.stream().map(Blocker::describe).toList());
        }
    }

    private final Map<String, ScheduledJob> jobs = new LinkedHashMap<>();
    private final Map<String, List<String>> predecessors = new HashMap<>();
    private final Map<String, List<String>> successors = new HashMap<>();

    public JobDependencyGraph(Collection<ScheduledJob> jobs) {
        if (jobs != null) {
            for (ScheduledJob job : jobs) {
                if (job != null) {
                    this.jobs.put(job.getId(), job);
                }
            }
        }
        for (ScheduledJob job : this.jobs.values()) {
            List<String> deps = new ArrayList<>();
            for (String dependencyId : job.getDependsOnJobIds()) {
                if (this.jobs.containsKey(dependencyId)) {
                    deps.add(dependencyId);
                    successors.computeIfAbsent(dependencyId, ignored -> new ArrayList<>()).add(job.getId());
                }
            }
            predecessors.put(job.getId(), deps);
        }
    }

    public Collection<ScheduledJob> jobs() {
        return List.copyOf(jobs.values());
    }

    public Optional<ScheduledJob> job(String jobId) {
        return Optional.ofNullable(jobId != null ? jobs.get(jobId) : null);
    }

    public List<ScheduledJob> predecessorsOf(String jobId) {
        return resolve(predecessors.getOrDefault(jobId, List.of()));
    }

    /** The jobs that directly depend on {@code jobId}. */
    public List<ScheduledJob> successorsOf(String jobId) {
        return resolve(successors.getOrDefault(jobId, List.of()));
    }

    /** True when at least one edge touches the job. */
    public boolean isConnected(String jobId) {
        return !predecessors.getOrDefault(jobId, List.of()).isEmpty()
            || !successors.getOrDefault(jobId, List.of()).isEmpty();
    }

    /** All jobs that depend on {@code jobId}, directly or transitively, excluding the job itself. */
    public Set<String> descendantsOf(String jobId) {
        return reach(jobId, successors);
    }

    /** All jobs {@code jobId} depends on, directly or transitively, excluding the job itself. */
    public Set<String> ancestorsOf(String jobId) {
        return reach(jobId, predecessors);
    }

    /**
     * Whether letting {@code jobId} depend on {@code candidateDependencies} would close a cycle,
     * i.e. whether the job itself or any job depending on it is among the candidates.
     */
    public boolean wouldCreateCycle(String jobId, Collection<String> candidateDependencies) {
        if (jobId == null || candidateDependencies == null || candidateDependencies.isEmpty()) {
            return false;
        }
        Set<String> forbidden = descendantsOf(jobId);
        forbidden.add(jobId);
        for (String candidate : candidateDependencies) {
            if (forbidden.contains(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** The jobs {@code jobId} may depend on without creating a cycle, in snapshot order. */
    public List<ScheduledJob> allowedPredecessors(String jobId) {
        Set<String> forbidden = descendantsOf(jobId);
        if (jobId != null) {
            forbidden.add(jobId);
        }
        return jobs.values().stream().filter(job -> !forbidden.contains(job.getId())).toList();
    }

    /**
     * {@code rootId} followed by all its descendants in an order where every job comes after each
     * of its predecessors that is part of the set.
     */
    public List<String> topologicalOrder(String rootId) {
        if (rootId == null || !jobs.containsKey(rootId)) {
            return List.of();
        }
        Set<String> members = descendantsOf(rootId);
        members.add(rootId);
        Map<String, Integer> inDegree = new HashMap<>();
        for (String member : members) {
            int degree = 0;
            for (String predecessor : predecessors.getOrDefault(member, List.of())) {
                if (members.contains(predecessor)) {
                    degree++;
                }
            }
            inDegree.put(member, degree);
        }
        List<String> order = new ArrayList<>();
        Deque<String> ready = new ArrayDeque<>();
        ready.add(rootId);
        while (!ready.isEmpty()) {
            String current = ready.poll();
            order.add(current);
            for (String successor : successors.getOrDefault(current, List.of())) {
                if (inDegree.merge(successor, -1, Integer::sum) == 0) {
                    ready.add(successor);
                }
            }
        }
        return order;
    }

    /**
     * Gate check: every predecessor's most recent finished run succeeded and, if the job sets a
     * maximum age, finished no longer ago than that.
     */
    public static DependencyCheck evaluateGate(
        ScheduledJob job,
        Function<String, Optional<ScheduledJob>> jobLookup,
        Function<String, Optional<JobJournalEntry>> lastFinishedLookup,
        Instant now) {

        return evaluate(job, jobLookup, lastFinishedLookup, now, false);
    }

    /**
     * Chain check: the gate check, plus every predecessor's success finished after the job's own
     * last run, so one round of predecessor runs starts the job exactly once.
     */
    public static DependencyCheck evaluateChain(
        ScheduledJob job,
        Function<String, Optional<ScheduledJob>> jobLookup,
        Function<String, Optional<JobJournalEntry>> lastFinishedLookup,
        Instant now) {

        return evaluate(job, jobLookup, lastFinishedLookup, now, true);
    }

    private static DependencyCheck evaluate(
        ScheduledJob job,
        Function<String, Optional<ScheduledJob>> jobLookup,
        Function<String, Optional<JobJournalEntry>> lastFinishedLookup,
        Instant now,
        boolean requireSinceLastRun) {

        if (job == null || !job.hasDependencies()) {
            return DependencyCheck.ok();
        }
        Integer maxAgeMinutes = job.getDependencyMaxAgeMinutes();
        Optional<Instant> ownLastRun = parseInstant(job.getLastRunAt());
        List<Blocker> blockers = new ArrayList<>();
        for (String predecessorId : job.getDependsOnJobIds()) {
            Optional<ScheduledJob> predecessor = jobLookup.apply(predecessorId);
            if (predecessor.isEmpty()) {
                blockers.add(new Blocker(predecessorId, predecessorId, BlockReason.MISSING, null));
                continue;
            }
            String name = predecessor.get().getName();
            Optional<JobJournalEntry> last = lastFinishedLookup.apply(predecessorId);
            if (last.isEmpty()) {
                blockers.add(new Blocker(predecessorId, name, BlockReason.NEVER_RAN, null));
                continue;
            }
            JobRunStatus status = last.get().getStatus();
            if (status != JobRunStatus.SUCCESS) {
                blockers.add(new Blocker(predecessorId, name, BlockReason.NOT_SUCCESSFUL, status));
                continue;
            }
            Optional<Instant> finishedAt = parseInstant(last.get().getFinishedAt());
            if (maxAgeMinutes != null && now != null
                && finishedAt.map(at -> Duration.between(at, now).toMinutes() > maxAgeMinutes).orElse(true)) {
                blockers.add(new Blocker(predecessorId, name, BlockReason.TOO_OLD, status));
                continue;
            }
            if (requireSinceLastRun && ownLastRun.isPresent()
                && finishedAt.map(at -> !at.isAfter(ownLastRun.get())).orElse(true)) {
                blockers.add(new Blocker(predecessorId, name, BlockReason.NOT_SINCE_LAST_RUN, status));
            }
        }
        return new DependencyCheck(blockers.isEmpty(), blockers);
    }

    static Optional<Instant> parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(value).toInstant());
        } catch (Exception e) {
            try {
                return Optional.of(Instant.parse(value));
            } catch (Exception ignored) {
                return Optional.empty();
            }
        }
    }

    private List<ScheduledJob> resolve(List<String> ids) {
        List<ScheduledJob> resolved = new ArrayList<>();
        for (String id : ids) {
            ScheduledJob job = jobs.get(id);
            if (job != null) {
                resolved.add(job);
            }
        }
        return resolved;
    }

    private static Set<String> reach(String startId, Map<String, List<String>> edges) {
        Set<String> seen = new LinkedHashSet<>();
        if (startId == null) {
            return seen;
        }
        Deque<String> pending = new ArrayDeque<>(edges.getOrDefault(startId, List.of()));
        while (!pending.isEmpty()) {
            String current = pending.poll();
            if (current.equals(startId) || !seen.add(current)) {
                continue;
            }
            pending.addAll(edges.getOrDefault(current, List.of()));
        }
        return seen;
    }
}
