package de.kortty.jobscheduler;

import de.kortty.KorTTYApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class JobSchedulerService {

    private static final Logger logger = LoggerFactory.getLogger(JobSchedulerService.class);
    private static final long WORKER_SHUTDOWN_TIMEOUT_SECONDS = 30L;

    /** {@link JobJournalEntry#getTriggerType()} values. */
    public static final String TRIGGER_MANUAL = "manual";
    public static final String TRIGGER_SCHEDULED = "scheduled";
    /** Started automatically because all chain-mode predecessors succeeded. */
    public static final String TRIGGER_DEPENDENCY = "dependency";
    /** Started by "run with successors". */
    public static final String TRIGGER_CASCADE = "cascade";

    private final KorTTYApplication app;
    private final JobSchedulerRepository repository;
    private final JobScheduleCalculator scheduleCalculator;
    private final JobRunner jobRunner;
    private final Clock clock;
    private final ScheduledExecutorService schedulerExecutor;
    private final ExecutorService workerExecutor;
    private final Map<String, ActiveJobControl> activeJobs = new ConcurrentHashMap<>();
    /** Pending members of "run with successors" cascades, keyed by job id. */
    private final Map<String, CascadeRun> cascadeMembers = new ConcurrentHashMap<>();
    /** Started cascade jobs whose result the cascade still waits for, keyed by job id. */
    private final Map<String, CascadeRun> cascadeRunning = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    private final List<JobRunEventListener> runEventListeners = new CopyOnWriteArrayList<>();
    private final Object drainMonitor = new Object();
    private final Object tickScheduleMonitor = new Object();

    private final AtomicBoolean started = new AtomicBoolean(false);
    private volatile boolean draining;
    private ScheduledFuture<?> scheduledTick;

    public JobSchedulerService(KorTTYApplication app, Path configDir) {
        this(app, new JobSchedulerRepository(configDir));
    }

    private JobSchedulerService(KorTTYApplication app, JobSchedulerRepository repository) {
        this(
            app,
            repository,
            new JobScheduleCalculator(),
            new DefaultPinningJobRunner(app, repository),
            Clock.systemDefaultZone());
    }

    public JobSchedulerService(
        JobSchedulerRepository repository,
        JobScheduleCalculator scheduleCalculator,
        JobRunner jobRunner,
        Clock clock) {

        this(null, repository, scheduleCalculator, jobRunner, clock);
    }

    private JobSchedulerService(
        KorTTYApplication app,
        JobSchedulerRepository repository,
        JobScheduleCalculator scheduleCalculator,
        JobRunner jobRunner,
        Clock clock) {

        this.app = app;
        this.repository = repository;
        this.scheduleCalculator = scheduleCalculator;
        this.jobRunner = jobRunner;
        this.clock = clock;
        this.schedulerExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "JobScheduler-Tick");
            thread.setDaemon(true);
            return thread;
        });
        this.workerExecutor = Executors.newFixedThreadPool(3, runnable -> {
            Thread thread = new Thread(runnable, "JobScheduler-Worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    public void load() throws Exception {
        repository.load();
        pruneJournalByConfiguredRetention();
        recomputeNextRuns();
        repository.save();
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        scheduleNextTick();
    }

    public void shutdownSchedulerThreads() {
        shutdownSchedulerThreads(true);
    }

    /**
     * Stops the scheduler and worker threads.
     *
     * @param saveState whether to write the in-memory jobs back to {@code job-scheduler.xml};
     *                  {@code false} after a backup import that needs a restart, so the restored
     *                  file is not overwritten with the state korTTY started with
     */
    public void shutdownSchedulerThreads(boolean saveState) {
        synchronized (drainMonitor) {
            draining = true;
        }
        cancelScheduledTick();
        schedulerExecutor.shutdownNow();
        workerExecutor.shutdown();
        try {
            if (!workerExecutor.awaitTermination(WORKER_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                List<Runnable> pendingTasks = workerExecutor.shutdownNow();
                logger.warn(
                    "Timed out waiting for JobScheduler workers to finish; interrupted {} queued worker task(s).",
                    pendingTasks.size());
            }
        } catch (InterruptedException e) {
            workerExecutor.shutdownNow();
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for JobScheduler workers to finish during shutdown", e);
        }
        if (!saveState) {
            logger.info("Not saving the JobScheduler state during shutdown (restored backup awaits a restart)");
            return;
        }
        try {
            repository.save();
        } catch (Exception e) {
            logger.warn("Could not save JobScheduler state during shutdown", e);
        }
    }

    public List<ScheduledJob> getJobs() {
        return repository.getJobs();
    }

    public Optional<ScheduledJob> findJob(String jobId) {
        return repository.findJob(jobId);
    }

    public void saveJob(ScheduledJob job) throws Exception {
        if (draining) {
            throw new IllegalStateException("JobScheduler is waiting for running jobs before shutdown.");
        }
        validateDependencies(job);
        updateNextRun(job);
        repository.upsertJob(job);
        repository.save();
        notifyListeners();
        scheduleNextTick();
    }

    /** Snapshot of the stored jobs' dependency relation. */
    public JobDependencyGraph getDependencyGraph() {
        return new JobDependencyGraph(repository.getJobs());
    }

    /** The jobs that directly depend on {@code jobId}; deleting it removes their dependency. */
    public List<ScheduledJob> findDependentJobs(String jobId) {
        return getDependencyGraph().successorsOf(jobId);
    }

    /** Whether the job's predecessors currently allow it to run (gate semantics). */
    public JobDependencyGraph.DependencyCheck checkDependencies(String jobId) {
        ScheduledJob job = repository.findJob(jobId).orElse(null);
        return JobDependencyGraph.evaluateGate(job, repository::findJob, repository::findLastFinishedEntry, clock.instant());
    }

    /** The status of the job's most recent finished run, if any. */
    public Optional<JobRunStatus> findLastFinishedStatus(String jobId) {
        return repository.findLastFinishedStatus(jobId);
    }

    /** Whether the job is currently running. */
    public boolean isJobActive(String jobId) {
        return jobId != null && activeJobs.containsKey(jobId);
    }

    private void validateDependencies(ScheduledJob job) {
        if (job == null || !job.hasDependencies()) {
            return;
        }
        List<ScheduledJob> jobs = new ArrayList<>(repository.getJobs());
        jobs.removeIf(existing -> existing.getId().equals(job.getId()));
        jobs.add(job);
        JobDependencyGraph graph = new JobDependencyGraph(jobs);
        for (String dependencyId : job.getDependsOnJobIds()) {
            if (graph.job(dependencyId).isEmpty()) {
                throw new IllegalArgumentException("Job depends on an unknown job: " + dependencyId);
            }
        }
        if (graph.ancestorsOf(job.getId()).contains(job.getId())
            || graph.wouldCreateCycle(job.getId(), job.getDependsOnJobIds())) {
            throw new IllegalArgumentException("Job dependencies would form a cycle.");
        }
    }

    public void deleteJob(String jobId) throws Exception {
        synchronized (drainMonitor) {
            if (jobId != null && activeJobs.containsKey(jobId)) {
                throw new IllegalStateException("Cannot delete a job while it is running.");
            }
            repository.deleteJob(jobId);
        }
        repository.save();
        notifyListeners();
        scheduleNextTick();
    }

    /** The stored webhook targets of job notifications. */
    public List<WebhookTarget> getWebhookTargets() {
        return repository.getWebhookTargets();
    }

    /** Adds or replaces a webhook target and saves; its URL must already be encrypted. */
    public void saveWebhookTarget(WebhookTarget target) throws Exception {
        if (target == null) {
            return;
        }
        repository.upsertWebhookTarget(target);
        repository.save();
        notifyListeners();
    }

    /** Deletes a webhook target, drops it from every job that sends to it, and saves. */
    public boolean deleteWebhookTarget(String targetId) throws Exception {
        boolean removed = repository.deleteWebhookTarget(targetId);
        if (removed) {
            repository.save();
            notifyListeners();
        }
        return removed;
    }

    public void runJobNow(String jobId) {
        if (draining) {
            appendJournal(JobJournalEntry.system(JobRunStatus.BLOCKED, "Manual job start blocked.", "KorTTY is waiting for running jobs before shutdown."));
            return;
        }
        repository.findJob(jobId).ifPresent(job -> submitJob(job, TRIGGER_MANUAL));
    }

    /**
     * Starts the job now and then every job that depends on it, directly or transitively, each as
     * soon as all its predecessors within the cascade have succeeded. Predecessors outside the
     * cascade must satisfy the usual gate check. When a job does not succeed, the cascade members
     * below it are recorded as blocked instead of being started.
     *
     * @return the cascade's job ids in start order, starting with {@code jobId}
     */
    public List<String> runJobWithSuccessors(String jobId) {
        if (draining) {
            appendJournal(JobJournalEntry.system(JobRunStatus.BLOCKED, "Manual job start blocked.", "KorTTY is waiting for running jobs before shutdown."));
            return List.of();
        }
        ScheduledJob root = repository.findJob(jobId).orElse(null);
        if (root == null) {
            return List.of();
        }
        JobDependencyGraph graph = getDependencyGraph();
        List<String> order = graph.topologicalOrder(root.getId());
        CascadeRun cascade = new CascadeRun(root.getId(), order);
        for (String memberId : order.subList(1, order.size())) {
            cascadeMembers.put(memberId, cascade);
        }
        cascadeRunning.put(root.getId(), cascade);
        if (!submitJob(root, TRIGGER_CASCADE)) {
            cascadeRunning.remove(root.getId(), cascade);
            order.subList(1, order.size()).forEach(memberId -> cascadeMembers.remove(memberId, cascade));
        }
        return order;
    }

    public List<JobJournalEntry> getJournal() {
        return repository.getJournal();
    }

    public List<JobJournalEntry> getJournalForJob(String jobId) {
        return repository.getJournalForJob(jobId);
    }

    public int deleteJournalEntries(Collection<String> entryIds) throws Exception {
        int deleted = repository.deleteJournalEntries(entryIds);
        if (deleted > 0) {
            repository.save();
            notifyListeners();
        }
        return deleted;
    }

    public int deleteJournalEntriesOlderThanDays(int retentionDays) throws Exception {
        if (retentionDays <= 0) {
            return 0;
        }
        Instant cutoff = ZonedDateTime.now(clock).minusDays(retentionDays).toInstant();
        int deleted = repository.deleteJournalEntriesOlderThan(cutoff);
        if (deleted > 0) {
            repository.save();
            notifyListeners();
        }
        return deleted;
    }

    public List<ActiveJobSummary> getActiveJobSummaries() {
        return activeJobs.values().stream()
            .map(ActiveJobControl::summary)
            .toList();
    }

    public boolean hasActiveJobs() {
        return !activeJobs.isEmpty();
    }

    /** True when at least one enabled job has an enabled schedule and a future run. */
    public boolean hasEnabledScheduledJobs() {
        ZonedDateTime now = ZonedDateTime.now(clock);
        return repository.getJobs().stream()
            .filter(ScheduledJob::isEnabled)
            .filter(job -> job.getSchedule() != null && job.getSchedule().isEnabled())
            .map(ScheduledJob::getNextRunAt)
            .map(this::parseZoned)
            .flatMap(Optional::stream)
            .anyMatch(nextRun -> nextRun.isAfter(now));
    }

    public boolean isDraining() {
        return draining;
    }

    public void beginDrainForShutdown() {
        synchronized (drainMonitor) {
            if (draining) {
                return;
            }
            draining = true;
        }
        cancelScheduledTick();
        schedulerExecutor.shutdownNow();
        appendJournal(JobJournalEntry.system(
            JobRunStatus.RUNNING,
            "KorTTY shutdown is waiting for running JobScheduler jobs.",
            getActiveJobSummaries().toString()));
        notifyListeners();
        synchronized (drainMonitor) {
            if (activeJobs.isEmpty()) {
                drainMonitor.notifyAll();
            }
        }
    }

    public void awaitDrain() throws InterruptedException {
        synchronized (drainMonitor) {
            while (!activeJobs.isEmpty()) {
                drainMonitor.wait(500);
            }
        }
    }

    public PinnedHostKey probeAndPinHostKey(String connectionId) throws Exception {
        if (!(jobRunner instanceof PinningJobRunner pinningJobRunner)) {
            throw new IllegalStateException("Host key probing is not available for this scheduler runner.");
        }
        PinnedHostKey hostKey = pinningJobRunner.probeHostKey(connectionId);
        repository.upsertPinnedHostKey(hostKey);
        repository.save();
        notifyListeners();
        return hostKey;
    }

    public Optional<PinnedHostKey> findPinnedHostKey(String connectionId) {
        return repository.findPinnedHostKey(connectionId);
    }

    public JobSchedulerRepository getRepository() {
        return repository;
    }

    public boolean cancelJob(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return false;
        }
        ActiveJobControl control = activeJobs.get(jobId);
        if (control == null) {
            return false;
        }
        control.requestCancellation();
        Thread workerThread = control.workerThread();
        if (workerThread != null) {
            workerThread.interrupt();
        }
        appendJournal(JobJournalEntry.system(
            JobRunStatus.RUNNING,
            "JobScheduler cancellation requested.",
            control.summary().jobName()));
        notifyListeners();
        return true;
    }

    public void addListener(Runnable listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    public void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /**
     * Registers a listener for finished runs. Only runs that match one of the job's notification
     * triggers are published, never while the scheduler drains for shutdown.
     */
    public void addRunEventListener(JobRunEventListener listener) {
        if (listener != null) {
            runEventListeners.add(listener);
        }
    }

    public void removeRunEventListener(JobRunEventListener listener) {
        runEventListeners.remove(listener);
    }

    /**
     * Records the result of a run notification (a skipped, blocked or failed webhook delivery) in
     * the journal and saves it. Safe from any thread; never throws.
     */
    public void appendNotificationJournal(JobJournalEntry entry) {
        if (entry != null) {
            appendJournal(entry);
        }
    }

    private void tickSafely() {
        try {
            tick();
        } catch (Exception e) {
            logger.warn("JobScheduler tick failed", e);
        } finally {
            scheduleNextTick();
        }
    }

    /**
     * Schedules one wake-up for the earliest runnable job. With no enabled future jobs the scheduler
     * owns no timer at all, allowing the JVM and macOS App Nap to remain idle.
     */
    private void scheduleNextTick() {
        synchronized (tickScheduleMonitor) {
            if (!started.get() || draining || schedulerExecutor.isShutdown()) {
                cancelScheduledTickLocked();
                return;
            }
            cancelScheduledTickLocked();
            ZonedDateTime now = ZonedDateTime.now(clock);
            Optional<ZonedDateTime> nextRun = repository.getJobs().stream()
                .filter(ScheduledJob::isEnabled)
                .filter(job -> job.getSchedule() != null && job.getSchedule().isEnabled())
                .filter(job -> !activeJobs.containsKey(job.getId()))
                .map(ScheduledJob::getNextRunAt)
                .map(this::parseZoned)
                .flatMap(Optional::stream)
                .min(ZonedDateTime::compareTo);
            if (nextRun.isEmpty()) {
                return;
            }
            long delayMillis = Math.max(
                1L,
                java.time.Duration.between(now, nextRun.get()).toMillis());
            scheduledTick = schedulerExecutor.schedule(this::tickSafely, delayMillis, TimeUnit.MILLISECONDS);
        }
    }

    private void cancelScheduledTick() {
        synchronized (tickScheduleMonitor) {
            cancelScheduledTickLocked();
        }
    }

    private void cancelScheduledTickLocked() {
        if (scheduledTick != null) {
            scheduledTick.cancel(false);
            scheduledTick = null;
        }
    }

    boolean isTickScheduled() {
        synchronized (tickScheduleMonitor) {
            return scheduledTick != null && !scheduledTick.isCancelled() && !scheduledTick.isDone();
        }
    }

    private void tick() throws Exception {
        if (draining) {
            return;
        }
        pruneJournalByConfiguredRetention();
        ZonedDateTime now = ZonedDateTime.now(clock);
        for (ScheduledJob job : repository.getJobs()) {
            if (!job.isEnabled() || !job.getSchedule().isEnabled()) {
                continue;
            }
            ZonedDateTime nextRun = parseZoned(job.getNextRunAt()).orElse(null);
            if (nextRun == null) {
                updateNextRun(job);
                repository.upsertJob(job);
                continue;
            }
            if (!nextRun.isAfter(now)) {
                submitJob(job, TRIGGER_SCHEDULED);
            }
        }
        repository.save();
        notifyListeners();
    }

    /** Test hook: submits a stored job with an explicit trigger type. */
    boolean submitJobForTest(String jobId, String triggerType) {
        return repository.findJob(jobId).map(job -> submitJob(job, triggerType)).orElse(false);
    }

    private boolean submitJob(ScheduledJob job, String triggerType) {
        String runId;
        String startedAt;
        ScheduledJob jobToRun;
        ActiveJobControl control;
        synchronized (drainMonitor) {
            if (draining) {
                return false;
            }
            jobToRun = repository.findJob(job.getId()).orElse(null);
            if (jobToRun == null) {
                return false;
            }
            runId = UUID.randomUUID().toString();
            startedAt = currentJournalTimestamp();
            ActiveJobSummary summary = new ActiveJobSummary(jobToRun.getId(), jobToRun.getName(), runId, startedAt, triggerType);
            control = new ActiveJobControl(summary);
            ActiveJobControl existing = activeJobs.putIfAbsent(jobToRun.getId(), control);
            if (existing != null) {
                return false;
            }
        }
        notifyListeners();
        try {
            workerExecutor.submit(() -> runJob(jobToRun, runId, startedAt, triggerType));
            return true;
        } catch (RejectedExecutionException e) {
            removeActiveJob(jobToRun.getId(), control);
            notifyListeners();
            scheduleNextTick();
            if (!draining) {
                logger.warn("JobScheduler worker rejected job {}", jobToRun.getId(), e);
            }
            return false;
        }
    }

    private void runJob(ScheduledJob job, String runId, String startedAt, String triggerType) {
        ActiveJobControl control = activeJobs.get(job.getId());
        if (control != null) {
            control.setWorkerThread(Thread.currentThread());
        }
        JobExecutionOutcome outcome;
        de.kortty.core.AutomationJournalRun journalRun = beginJournalRun(job, runId);
        try {
            JobDependencyGraph.DependencyCheck dependencyCheck = gateCheckFor(job, triggerType);
            if (control != null && control.isCancellationRequested()) {
                outcome = JobExecutionOutcome.cancelled("Job cancelled before execution.", "Cancellation was requested before the worker started.");
            } else if (!dependencyCheck.satisfied()) {
                outcome = JobExecutionOutcome.blocked(
                    "Job blocked: dependencies not satisfied.",
                    "Predecessor check failed: " + dependencyCheck.describe());
            } else {
                outcome = jobRunner.run(job, runId, journalRun);
                if (control != null && control.isCancellationRequested()) {
                    outcome = JobExecutionOutcome.cancelled("Job cancelled.", "Cancellation was requested by the user.");
                }
            }
        } catch (Exception e) {
            if (control != null && control.isCancellationRequested()) {
                outcome = JobExecutionOutcome.cancelled("Job cancelled.", "Cancellation interrupted the running job: " + safeMessage(e));
            } else {
                outcome = JobExecutionOutcome.failed("Job failed: " + safeMessage(e), -1, null, safeMessage(e), e.toString());
            }
        }
        JobRunEvent runEvent = null;
        try {
            String finishedAt = currentJournalTimestamp();
            JobRunStatus previousStatus = repository.findLastFinishedStatus(job.getId()).orElse(null);
            JobJournalEntry entry = new JobJournalEntry();
            entry.setJobId(job.getId());
            entry.setJobName(job.getName());
            entry.setRunId(runId);
            entry.setStatus(outcome.status());
            entry.setTriggerType(triggerType);
            entry.setStartedAt(startedAt);
            entry.setFinishedAt(finishedAt);
            entry.setExitCode(outcome.exitCode());
            entry.setSummary(outcome.summary());
            entry.setStdoutText(outcome.stdout());
            entry.setStderrText(outcome.stderr());
            entry.setDetailText(outcome.detail());
            finishJournalRun(journalRun, outcome.status(), entry);
            repository.appendJournal(entry);
            if (repository.findJob(job.getId()).isPresent()) {
                job.setLastRunAt(finishedAt);
                if (!draining) {
                    updateNextRun(job);
                }
                repository.upsertJob(job);
            } else {
                logger.info("Skipping persistence for deleted JobScheduler job {}", job.getId());
            }
            repository.save();
            runEvent = new JobRunEvent(
                job.getId(),
                job.getName(),
                outcome.status(),
                previousStatus,
                outcome.exitCode(),
                triggerType,
                outcome.summary(),
                parseZoned(finishedAt).map(ZonedDateTime::toInstant).orElse(clock.instant()));
        } catch (Exception e) {
            logger.warn("Could not persist JobScheduler run result", e);
        } finally {
            publishRunEvent(job, runEvent);
            removeActiveJob(job.getId(), control);
            notifyListeners();
            startDependents(job.getId(), outcome.status());
            scheduleNextTick();
        }
    }

    /**
     * Gate check before a run. Manual starts are confirmed in the UI and cascade members were
     * checked when the cascade released them, so only scheduled and chain-triggered runs check here.
     */
    private JobDependencyGraph.DependencyCheck gateCheckFor(ScheduledJob job, String triggerType) {
        if (!TRIGGER_SCHEDULED.equals(triggerType) && !TRIGGER_DEPENDENCY.equals(triggerType)) {
            return JobDependencyGraph.DependencyCheck.ok();
        }
        ScheduledJob current = repository.findJob(job.getId()).orElse(job);
        return JobDependencyGraph.evaluateGate(current, repository::findJob, repository::findLastFinishedEntry, clock.instant());
    }

    /**
     * After a run: advances a cascade the job belongs to and starts the chain-mode jobs whose
     * predecessors have now all succeeded. Never throws.
     */
    private void startDependents(String jobId, JobRunStatus status) {
        if (draining) {
            cascadeMembers.clear();
            cascadeRunning.clear();
            return;
        }
        try {
            JobDependencyGraph graph = getDependencyGraph();
            CascadeRun cascade = cascadeRunning.remove(jobId);
            if (cascade != null) {
                synchronized (cascade) {
                    advanceCascade(cascade, jobId, status, graph);
                }
            }
            if (status != JobRunStatus.SUCCESS) {
                return;
            }
            Instant now = clock.instant();
            for (ScheduledJob successor : graph.successorsOf(jobId)) {
                if (!successor.isEnabled()
                    || successor.getDependencyMode() != JobDependencyMode.CHAIN
                    || cascadeMembers.containsKey(successor.getId())) {
                    continue;
                }
                JobDependencyGraph.DependencyCheck check = JobDependencyGraph.evaluateChain(
                    successor, repository::findJob, repository::findLastFinishedEntry, now);
                if (check.satisfied()) {
                    submitJob(successor, TRIGGER_DEPENDENCY);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not start the jobs depending on JobScheduler job {}", jobId, e);
        }
    }

    private void advanceCascade(CascadeRun cascade, String jobId, JobRunStatus status, JobDependencyGraph graph) {
        if (status == JobRunStatus.SUCCESS) {
            cascade.succeeded().add(jobId);
        } else {
            String jobName = graph.job(jobId).map(ScheduledJob::getName).orElse(jobId);
            for (String descendantId : graph.descendantsOf(jobId)) {
                if (cascadeMembers.remove(descendantId, cascade)) {
                    recordCascadeBlocked(graph, descendantId, jobName, status);
                }
            }
            return;
        }
        Instant now = clock.instant();
        for (String memberId : cascade.order()) {
            if (cascadeMembers.get(memberId) != cascade) {
                continue;
            }
            ScheduledJob member = repository.findJob(memberId).orElse(null);
            if (member == null) {
                cascadeMembers.remove(memberId, cascade);
                continue;
            }
            boolean ready = true;
            List<String> outsidePredecessors = new ArrayList<>();
            for (String predecessorId : member.getDependsOnJobIds()) {
                if (cascade.order().contains(predecessorId)) {
                    ready &= cascade.succeeded().contains(predecessorId);
                } else {
                    outsidePredecessors.add(predecessorId);
                }
            }
            if (!ready) {
                continue;
            }
            ScheduledJob outsideView = new ScheduledJob();
            outsideView.setId(member.getId());
            outsideView.setDependsOnJobIds(outsidePredecessors);
            outsideView.setDependencyMaxAgeMinutes(member.getDependencyMaxAgeMinutes());
            JobDependencyGraph.DependencyCheck check = JobDependencyGraph.evaluateGate(
                outsideView, repository::findJob, repository::findLastFinishedEntry, now);
            cascadeMembers.remove(memberId, cascade);
            if (!check.satisfied()) {
                recordBlocked(member, "Job blocked: dependencies not satisfied.", "Predecessor check failed: " + check.describe());
                for (String descendantId : graph.descendantsOf(memberId)) {
                    if (cascadeMembers.remove(descendantId, cascade)) {
                        recordCascadeBlocked(graph, descendantId, member.getName(), JobRunStatus.BLOCKED);
                    }
                }
                continue;
            }
            cascadeRunning.put(memberId, cascade);
            if (!submitJob(member, TRIGGER_CASCADE)) {
                cascadeRunning.remove(memberId, cascade);
                for (String descendantId : graph.descendantsOf(memberId)) {
                    cascadeMembers.remove(descendantId, cascade);
                }
            }
        }
    }

    private void recordCascadeBlocked(JobDependencyGraph graph, String jobId, String predecessorName, JobRunStatus predecessorStatus) {
        graph.job(jobId).ifPresent(job -> recordBlocked(
            job,
            "Job blocked: predecessor did not succeed.",
            "Run with successors stopped because " + predecessorName + " finished " + predecessorStatus + "."));
    }

    private void recordBlocked(ScheduledJob job, String summary, String detail) {
        String now = currentJournalTimestamp();
        JobJournalEntry entry = new JobJournalEntry();
        entry.setJobId(job.getId());
        entry.setJobName(job.getName());
        entry.setRunId(UUID.randomUUID().toString());
        entry.setStatus(JobRunStatus.BLOCKED);
        entry.setTriggerType(TRIGGER_CASCADE);
        entry.setStartedAt(now);
        entry.setFinishedAt(now);
        entry.setExitCode(-1);
        entry.setSummary(summary);
        entry.setDetailText(detail);
        appendJournal(entry);
    }

    /** One "run with successors" request: its members in start order and those that succeeded. */
    private record CascadeRun(String rootId, List<String> order, Set<String> succeeded) {
        CascadeRun(String rootId, List<String> order) {
            this(rootId, List.copyOf(order), ConcurrentHashMap.newKeySet());
        }
    }

    /** Starts the run's session journals, or a no-op run when the job has them off. Never throws. */
    private de.kortty.core.AutomationJournalRun beginJournalRun(ScheduledJob job, String runId) {
        try {
            if (!job.getSessionJournal().isEnabled()) {
                return de.kortty.core.AutomationJournalRun.NONE;
            }
            return de.kortty.core.AutomationJournalRun.begin(
                new de.kortty.core.AutomationJournalRun.Source(
                    de.kortty.model.SessionJournalSourceKind.JOB,
                    job.getId(),
                    job.getName(),
                    job.getAction().getType() != null ? job.getAction().getType().name() : null),
                runId,
                job.getSessionJournal());
        } catch (Exception e) {
            logger.warn("Could not start the session journals of job {}", job.getId(), e);
            return de.kortty.core.AutomationJournalRun.NONE;
        }
    }

    /**
     * Closes the run's session journals, links the kept ones (or the identical earlier run) to
     * the run-history entry, and refreshes the dialog once their summaries are written.
     */
    private void finishJournalRun(de.kortty.core.AutomationJournalRun journalRun, JobRunStatus status, JobJournalEntry entry) {
        if (journalRun == null || !journalRun.isActive()) {
            return;
        }
        try {
            de.kortty.core.AutomationJournalRun.FinishResult result = journalRun.finish(toAutomationStatus(status));
            entry.setSessionJournalDirs(result.kept().stream().map(java.nio.file.Path::toString).toList());
            entry.setDuplicateOfJournalDirs(result.duplicateOf().stream().map(java.nio.file.Path::toString).toList());
            result.summariesDone().thenRun(this::notifyListeners);
        } catch (Exception e) {
            logger.warn("Could not finish the session journals of run {}", entry.getRunId(), e);
        }
    }

    static de.kortty.model.AutomationRunStatus toAutomationStatus(JobRunStatus status) {
        if (status == null) {
            return de.kortty.model.AutomationRunStatus.FAILED;
        }
        return switch (status) {
            case SUCCESS -> de.kortty.model.AutomationRunStatus.SUCCESS;
            case BLOCKED -> de.kortty.model.AutomationRunStatus.BLOCKED;
            case CANCELLED -> de.kortty.model.AutomationRunStatus.CANCELLED;
            case FAILED, RUNNING -> de.kortty.model.AutomationRunStatus.FAILED;
        };
    }

    private void removeActiveJob(String jobId, ActiveJobControl control) {
        boolean removed = control != null
            ? activeJobs.remove(jobId, control)
            : activeJobs.remove(jobId) != null;
        if (!removed) {
            return;
        }
        synchronized (drainMonitor) {
            if (activeJobs.isEmpty()) {
                drainMonitor.notifyAll();
            }
        }
    }

    private void recomputeNextRuns() {
        for (ScheduledJob job : repository.getJobs()) {
            updateNextRun(job);
            repository.upsertJob(job);
        }
    }

    private void pruneJournalByConfiguredRetention() {
        int retentionDays = configuredJournalRetentionDays();
        if (retentionDays <= 0) {
            return;
        }
        try {
            deleteJournalEntriesOlderThanDays(retentionDays);
        } catch (Exception e) {
            logger.warn("Could not prune JobScheduler journal entries older than {} day(s)", retentionDays, e);
        }
    }

    private int configuredJournalRetentionDays() {
        try {
            if (app == null || app.getGlobalSettingsManager() == null) {
                return 0;
            }
            return app.getGlobalSettingsManager().getSettings().getJobSchedulerJournalRetentionDays();
        } catch (Exception e) {
            logger.debug("Could not read JobScheduler journal retention setting", e);
            return 0;
        }
    }

    private void updateNextRun(ScheduledJob job) {
        if (job == null) {
            return;
        }
        if (!job.isEnabled() || job.getSchedule() == null || !job.getSchedule().isEnabled()) {
            job.setNextRunAt(null);
            return;
        }
        ZonedDateTime now = ZonedDateTime.now(clock);
        job.setNextRunAt(scheduleCalculator.nextRunAfter(job.getSchedule(), now)
            .map(ZonedDateTime::toString)
            .orElse(null));
    }

    private String currentJournalTimestamp() {
        return ZonedDateTime.now(clock).toString();
    }

    private Optional<ZonedDateTime> parseZoned(String value) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(ZonedDateTime.parse(value));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private void appendJournal(JobJournalEntry entry) {
        try {
            repository.appendJournal(entry);
            repository.save();
            notifyListeners();
        } catch (Exception e) {
            logger.warn("Could not append JobScheduler journal entry", e);
        }
    }

    /**
     * Hands a saved run to the run-event listeners on the worker thread. Nothing is published while
     * draining or for a run that matches none of the job's triggers; each listener is isolated.
     */
    private void publishRunEvent(ScheduledJob job, JobRunEvent event) {
        if (event == null || draining || runEventListeners.isEmpty()) {
            return;
        }
        JobNotificationConfig config;
        try {
            config = job.effectiveNotificationConfig();
        } catch (RuntimeException e) {
            logger.debug("Could not read the notification settings of job {}", job.getId(), e);
            return;
        }
        if (!config.matches(event)) {
            return;
        }
        for (JobRunEventListener listener : runEventListeners) {
            try {
                listener.onJobRunFinished(event);
            } catch (RuntimeException e) {
                logger.warn("JobScheduler run-event listener failed for job {}", event.jobId(), e);
            }
        }
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (Exception e) {
                logger.debug("JobScheduler listener failed", e);
            }
        }
    }

    private String safeMessage(Exception e) {
        if (e == null) {
            return "Unknown error";
        }
        return e.getMessage() != null && !e.getMessage().isBlank()
            ? e.getMessage()
            : e.getClass().getSimpleName();
    }

    @FunctionalInterface
    public interface JobRunner {
        JobExecutionOutcome run(ScheduledJob job, String runId);

        /** Runs the job and records each target into {@code journalRun}; the default records nothing. */
        default JobExecutionOutcome run(ScheduledJob job, String runId, de.kortty.core.AutomationJournalRun journalRun) {
            return run(job, runId);
        }
    }

    public interface PinningJobRunner extends JobRunner {
        PinnedHostKey probeHostKey(String connectionId) throws Exception;
    }

    private static final class ActiveJobControl {
        private final ActiveJobSummary initialSummary;
        private volatile boolean cancellationRequested;
        private volatile Thread workerThread;

        private ActiveJobControl(ActiveJobSummary initialSummary) {
            this.initialSummary = initialSummary;
        }

        private ActiveJobSummary summary() {
            return new ActiveJobSummary(
                initialSummary.jobId(),
                initialSummary.jobName(),
                initialSummary.runId(),
                initialSummary.startedAt(),
                initialSummary.triggerType(),
                cancellationRequested);
        }

        private void requestCancellation() {
            cancellationRequested = true;
        }

        private boolean isCancellationRequested() {
            return cancellationRequested;
        }

        private Thread workerThread() {
            return workerThread;
        }

        private void setWorkerThread(Thread workerThread) {
            this.workerThread = workerThread;
        }
    }

    private static final class DefaultPinningJobRunner implements PinningJobRunner {
        private final JobSchedulerJobRunner delegate;

        private DefaultPinningJobRunner(KorTTYApplication app, JobSchedulerRepository repository) {
            this.delegate = new JobSchedulerJobRunner(app, repository);
        }

        @Override
        public JobExecutionOutcome run(ScheduledJob job, String runId) {
            return delegate.run(job, runId);
        }

        @Override
        public JobExecutionOutcome run(ScheduledJob job, String runId, de.kortty.core.AutomationJournalRun journalRun) {
            return delegate.run(job, runId, journalRun);
        }

        @Override
        public PinnedHostKey probeHostKey(String connectionId) throws Exception {
            return delegate.probeHostKey(connectionId);
        }
    }
}
