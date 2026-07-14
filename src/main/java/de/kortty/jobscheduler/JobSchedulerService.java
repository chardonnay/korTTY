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

    private final KorTTYApplication app;
    private final JobSchedulerRepository repository;
    private final JobScheduleCalculator scheduleCalculator;
    private final JobRunner jobRunner;
    private final Clock clock;
    private final ScheduledExecutorService schedulerExecutor;
    private final ExecutorService workerExecutor;
    private final Map<String, ActiveJobControl> activeJobs = new ConcurrentHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
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
        updateNextRun(job);
        repository.upsertJob(job);
        repository.save();
        notifyListeners();
        scheduleNextTick();
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

    public void runJobNow(String jobId) {
        if (draining) {
            appendJournal(JobJournalEntry.system(JobRunStatus.BLOCKED, "Manual job start blocked.", "KorTTY is waiting for running jobs before shutdown."));
            return;
        }
        repository.findJob(jobId).ifPresent(job -> submitJob(job, "manual"));
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
                submitJob(job, "scheduled");
            }
        }
        repository.save();
        notifyListeners();
    }

    private void submitJob(ScheduledJob job, String triggerType) {
        String runId;
        String startedAt;
        ScheduledJob jobToRun;
        ActiveJobControl control;
        synchronized (drainMonitor) {
            if (draining) {
                return;
            }
            jobToRun = repository.findJob(job.getId()).orElse(null);
            if (jobToRun == null) {
                return;
            }
            runId = UUID.randomUUID().toString();
            startedAt = currentJournalTimestamp();
            ActiveJobSummary summary = new ActiveJobSummary(jobToRun.getId(), jobToRun.getName(), runId, startedAt, triggerType);
            control = new ActiveJobControl(summary);
            ActiveJobControl existing = activeJobs.putIfAbsent(jobToRun.getId(), control);
            if (existing != null) {
                return;
            }
        }
        notifyListeners();
        try {
            workerExecutor.submit(() -> runJob(jobToRun, runId, startedAt, triggerType));
        } catch (RejectedExecutionException e) {
            removeActiveJob(jobToRun.getId(), control);
            notifyListeners();
            scheduleNextTick();
            if (!draining) {
                logger.warn("JobScheduler worker rejected job {}", jobToRun.getId(), e);
            }
        }
    }

    private void runJob(ScheduledJob job, String runId, String startedAt, String triggerType) {
        ActiveJobControl control = activeJobs.get(job.getId());
        if (control != null) {
            control.setWorkerThread(Thread.currentThread());
        }
        JobExecutionOutcome outcome;
        try {
            if (control != null && control.isCancellationRequested()) {
                outcome = JobExecutionOutcome.cancelled("Job cancelled before execution.", "Cancellation was requested before the worker started.");
            } else {
                outcome = jobRunner.run(job, runId);
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
        try {
            String finishedAt = currentJournalTimestamp();
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
        } catch (Exception e) {
            logger.warn("Could not persist JobScheduler run result", e);
        } finally {
            removeActiveJob(job.getId(), control);
            notifyListeners();
            scheduleNextTick();
        }
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
        public PinnedHostKey probeHostKey(String connectionId) throws Exception {
            return delegate.probeHostKey(connectionId);
        }
    }
}
