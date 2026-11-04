package de.kortty.core;

import de.kortty.model.AiProfile;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * "Re-evaluate with AI…" as a background job owned by the application rather than by a dialog.
 *
 * <p>A local model needs minutes per journal, so a run has to outlive the window that started it:
 * closing the progress window or the journal manager leaves it running, the manager shows which
 * journals it works on and how far it is, and quitting asks first. Runs queue up and are processed
 * one after another on one thread; the summarizer makes its AI calls one at a time anyway.</p>
 *
 * <p>Progress comes from {@link SessionJournalReevaluationProgress}: every journal is planned before the
 * first call, and every AI call the summarizer and the screenshot analyzer make passes through a measuring
 * invoker, so the bar moves with each call and the remaining time follows the model in use. Cancelling
 * stops before the next call; the call in flight still finishes, and the journal keeps what is done plus
 * the rest as raw activity.</p>
 *
 * <p>Free of JavaFX: observers register a {@link Runnable}, read a {@link Snapshot} and marshal onto the FX
 * thread themselves, like {@link GuideTranslationJob}. Listeners are called on the job's thread.</p>
 */
public final class SessionJournalReevaluationJob {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalReevaluationJob.class);

    /** A journal to re-evaluate and the title to show for it. */
    public record Target(Path directory, String title) {
        public Target {
            Objects.requireNonNull(directory, "directory");
        }
    }

    /**
     * What a run does: evaluate journals afresh ("Re-evaluate with AI…"), or finish journals whose
     * session ended — closed, or left behind when korTTY quit or crashed — with the journal profile.
     */
    public enum Kind { REEVALUATE, FINISH }

    /** One "Re-evaluate" click: the journals, the profile they are evaluated with, and whether screenshots are described again. */
    public record Request(List<Target> targets, String profileId, String profileName,
                          SessionJournalAiSupport.AiInvoker invoker, boolean screenshots, Kind kind) {
        public Request {
            targets = List.copyOf(targets);
            Objects.requireNonNull(invoker, "invoker");
            kind = kind != null ? kind : Kind.REEVALUATE;
        }

        public Request(List<Target> targets, String profileId, String profileName,
                       SessionJournalAiSupport.AiInvoker invoker, boolean screenshots) {
            this(targets, profileId, profileName, invoker, screenshots, Kind.REEVALUATE);
        }
    }

    /** What the job does right now. */
    public enum Phase { IDLE, TESTING, RUNNING, CANCELLING }

    /** A journal's part in the runs: being evaluated (or finished) now, waiting for its turn, or none. */
    public enum JournalState { NONE, RUNNING, WAITING, FINISHING, FINISH_WAITING }

    /** Screenshots of one journal that could not be described. */
    public record ScreenshotIssue(String title, int failed) {
    }

    /**
     * How the last run ended. {@code testFailure} is set when the profile failed its connection test,
     * in which case nothing was changed; {@code failures} holds "title: reason" per journal that failed.
     */
    public record Outcome(String profileName, int processed, long tokens, List<String> failures,
                          List<ScreenshotIssue> screenshotIssues, String testFailure, boolean cancelled) {
        public Outcome {
            failures = List.copyOf(failures);
            screenshotIssues = List.copyOf(screenshotIssues);
        }

        public boolean testFailed() {
            return testFailure != null;
        }

        /** Something the user should read: a failed test, failed journals or undescribed screenshots. */
        public boolean hasProblems() {
            return testFailed() || !failures.isEmpty() || !screenshotIssues.isEmpty();
        }
    }

    /**
     * The job at one moment. {@code journalNumber} counts from 1 (0 while the profile is tested);
     * {@code estimate} is null while idle; {@code lastOutcome} stays until {@link #dismissOutcome()}.
     */
    public record Snapshot(Phase phase, String profileName, int journalNumber, int journalCount, String currentTitle,
                           SessionJournalReevaluationProgress.Estimate estimate, int queuedRuns, Outcome lastOutcome,
                           Kind kind) {

        public Snapshot(Phase phase, String profileName, int journalNumber, int journalCount, String currentTitle,
                        SessionJournalReevaluationProgress.Estimate estimate, int queuedRuns, Outcome lastOutcome) {
            this(phase, profileName, journalNumber, journalCount, currentTitle, estimate, queuedRuns, lastOutcome,
                Kind.REEVALUATE);
        }

        public boolean running() {
            return phase != Phase.IDLE;
        }
    }

    /** One submitted request and its own cancel switch. */
    private static final class Run {
        /** Replaced once by a finish run, when the journal titles have been read. */
        volatile Request request;
        final AtomicBoolean cancelled = new AtomicBoolean();

        Run(Request request) {
            this.request = request;
        }
    }

    private final SessionJournalService service;
    private final SessionJournalSummarizer summarizer;
    private final SessionJournalScreenshotAnalyzer screenshotAnalyzer;
    private final Clock clock;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "SessionJournal-Reevaluate");
        // Daemon: a re-evaluation must never be what keeps the application alive.
        thread.setDaemon(true);
        return thread;
    });
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** Seconds per weight unit each profile needed in its last run, so the next run estimates from the start. */
    private final Map<String, Double> secondsPerUnitByProfile = new ConcurrentHashMap<>();

    // Guarded by this.
    private final Deque<Run> waiting = new ArrayDeque<>();
    private Run current;
    private Phase phase = Phase.IDLE;
    private int journalIndex = -1;
    private SessionJournalReevaluationProgress progress;
    private Outcome lastOutcome;

    public SessionJournalReevaluationJob(SessionJournalService service, SessionJournalSummarizer summarizer,
                                         SessionJournalScreenshotAnalyzer screenshotAnalyzer, Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.summarizer = Objects.requireNonNull(summarizer, "summarizer");
        this.screenshotAnalyzer = screenshotAnalyzer;
        this.clock = clock != null ? clock : Clock.systemUTC();
    }

    /** The invoker of a re-evaluation with {@code profile}, resolved per call like every journal invoker. */
    public static SessionJournalAiSupport.AiInvoker invokerFor(AiProfile profile) {
        return SessionJournalAiSupport.profileInvoker(profile.getId());
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
     * Queues a run; it starts as soon as the runs before it are done. Journals that already are in a run
     * are left out, so one journal is never evaluated twice at the same time. Returns false when nothing
     * was left to queue.
     */
    public boolean submit(Request request) {
        if (request == null) {
            return false;
        }
        Run run;
        synchronized (this) {
            List<Target> fresh = request.targets().stream()
                .filter(target -> stateOfLocked(target.directory()) == JournalState.NONE)
                .toList();
            if (fresh.isEmpty()) {
                return false;
            }
            run = new Run(new Request(fresh, request.profileId(), request.profileName(), request.invoker(),
                request.screenshots(), request.kind()));
            waiting.addLast(run);
        }
        try {
            executor.execute(this::runNext);
        } catch (RejectedExecutionException e) {
            synchronized (this) {
                waiting.remove(run);
            }
            logger.warn("Could not queue the journal re-evaluation: {}", e.getMessage());
            return false;
        }
        notifyListeners();
        return true;
    }

    /**
     * Queues the closing pass of a journal whose session ended, with the journal profile. It runs
     * silently: the journal manager shows it, no window opens and no result waits to be read. The
     * journal keeps its finish marker until the pass is done, so one that korTTY quits on is
     * finished at the next start.
     */
    public boolean finish(Path directory) {
        if (directory == null) {
            return false;
        }
        SessionJournalAiSupport.AiInvoker invoker = summarizer.journalInvoker();
        de.kortty.model.AiProfile profile = null;
        try {
            profile = invoker.profile();
        } catch (RuntimeException e) {
            logger.debug("Could not resolve the journal profile: {}", e.getMessage());
        }
        return submit(new Request(List.of(new Target(directory, directory.getFileName().toString())),
            profile != null ? profile.getId() : null, profile != null ? profile.getName() : null, invoker, false,
            Kind.FINISH));
    }

    /**
     * Queues the closing pass of every journal korTTY left unfinished — quit or crashed on while
     * it recorded or before its closing pass ran. Reads the journals folder: call it off the FX
     * thread. Returns how many were queued.
     */
    public int finishLeftBehind(de.kortty.model.GlobalSettings settings) {
        int queued = 0;
        try {
            for (de.kortty.model.SessionJournalMeta meta : service.listJournals(settings)) {
                if (meta.isFinishPending() && !meta.isLive() && meta.getDirectory() != null
                        && finish(meta.getDirectory())) {
                    queued++;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not look for unfinished session journals: {}", e.getMessage());
        }
        if (queued > 0) {
            logger.info("Finishing {} session journal(s) left behind when korTTY last quit", queued);
        }
        return queued;
    }

    /** Cancels the run in progress and every queued one. The AI call in flight still finishes. */
    public void cancel() {
        synchronized (this) {
            waiting.clear();
            if (current != null) {
                current.cancelled.set(true);
            }
        }
        notifyListeners();
    }

    /** Forgets the last outcome, once the user has read it. */
    public void dismissOutcome() {
        synchronized (this) {
            lastOutcome = null;
        }
        notifyListeners();
    }

    /** Whether screenshots can be described again; false without a screenshot analyzer. */
    public boolean canDescribeScreenshots() {
        return screenshotAnalyzer != null;
    }

    public synchronized boolean isRunning() {
        return current != null || !waiting.isEmpty();
    }

    /** Whether a re-evaluation the user started runs or waits; finish runs do not count. */
    public synchronized boolean isReevaluating() {
        if (current != null && current.request.kind() == Kind.REEVALUATE) {
            return true;
        }
        return waiting.stream().anyMatch(run -> run.request.kind() == Kind.REEVALUATE);
    }

    /** Whether the journal in {@code directory} is being re-evaluated or waits for it. */
    public synchronized JournalState stateOf(Path directory) {
        return stateOfLocked(directory);
    }

    public synchronized Snapshot snapshot() {
        Phase shown = current == null ? (waiting.isEmpty() ? Phase.IDLE : Phase.TESTING)
            : current.cancelled.get() ? Phase.CANCELLING : phase;
        Request request = current != null ? current.request : null;
        String title = request != null && journalIndex >= 0 && journalIndex < request.targets().size()
            ? request.targets().get(journalIndex).title() : null;
        return new Snapshot(shown,
            request != null ? request.profileName() : null,
            request != null ? journalIndex + 1 : 0,
            request != null ? request.targets().size() : 0,
            title,
            progress != null && current != null ? progress.estimate() : null,
            waiting.size(),
            lastOutcome,
            request != null ? request.kind() : null);
    }

    /**
     * Stops the job thread at application shutdown. Re-evaluations are cancelled (the user was
     * asked); a finish run is not — its journal keeps the finish marker and is finished at the next start.
     */
    public void stop() {
        cancelReevaluations();
        executor.shutdownNow();
    }

    /**
     * Cancels the re-evaluations the user started, running or queued, and leaves finish runs alone:
     * what quitting korTTY stops, since an unfinished journal is finished at the next start anyway.
     */
    public void cancelReevaluations() {
        synchronized (this) {
            waiting.removeIf(run -> run.request.kind() == Kind.REEVALUATE);
            if (current != null && current.request.kind() == Kind.REEVALUATE) {
                current.cancelled.set(true);
            }
        }
        notifyListeners();
    }

    private JournalState stateOfLocked(Path directory) {
        if (directory == null) {
            return JournalState.NONE;
        }
        Path key = normalize(directory);
        if (current != null) {
            boolean finish = current.request.kind() == Kind.FINISH;
            List<Target> targets = current.request.targets();
            for (int i = 0; i < targets.size(); i++) {
                if (normalize(targets.get(i).directory()).equals(key)) {
                    if (i == journalIndex) {
                        return finish ? JournalState.FINISHING : JournalState.RUNNING;
                    }
                    if (i > journalIndex) {
                        return finish ? JournalState.FINISH_WAITING : JournalState.WAITING;
                    }
                }
            }
        }
        for (Run run : waiting) {
            for (Target target : run.request.targets()) {
                if (normalize(target.directory()).equals(key)) {
                    return run.request.kind() == Kind.FINISH ? JournalState.FINISH_WAITING : JournalState.WAITING;
                }
            }
        }
        return JournalState.NONE;
    }

    private static Path normalize(Path directory) {
        return directory.toAbsolutePath().normalize();
    }

    private void runNext() {
        Run run;
        synchronized (this) {
            run = waiting.pollFirst();
            if (run == null) {
                return; // cancelled while it waited
            }
            current = run;
            phase = Phase.TESTING;
            journalIndex = -1;
            progress = new SessionJournalReevaluationProgress(clock,
                run.request.profileId() != null ? secondsPerUnitByProfile.get(run.request.profileId()) : null);
        }
        notifyListeners();
        Outcome outcome;
        try {
            outcome = execute(run);
        } catch (RuntimeException e) {
            logger.warn("Journal re-evaluation failed: {}", e.getMessage(), e);
            outcome = new Outcome(run.request.profileName(), 0, 0, List.of(String.valueOf(e.getMessage())),
                List.of(), null, run.cancelled.get());
        }
        synchronized (this) {
            if (run.request.kind() == Kind.REEVALUATE) {
                lastOutcome = outcome; // a finish run is background housekeeping: nothing to read afterwards
            } else if (outcome.hasProblems()) {
                logger.warn("Finishing session journals: {}", outcome.failures());
            }
            current = null;
            phase = Phase.IDLE;
            journalIndex = -1;
            progress = null;
        }
        notifyListeners();
    }

    private Outcome execute(Run run) {
        if (run.request.kind() == Kind.FINISH) {
            run.request = withJournalTitles(run.request);
        }
        Request request = run.request;
        boolean finish = request.kind() == Kind.FINISH;
        SessionJournalReevaluationProgress tracker;
        synchronized (this) {
            tracker = progress;
        }
        // A finish run keeps raw entries when the profile does not answer: nothing to test first.
        SessionJournalAiPreflight.Result test = finish
            ? SessionJournalAiPreflight.Result.success(request.profileName())
            : SessionJournalAiPreflight.check(request.invoker());
        if (run.cancelled.get()) {
            // Nothing was reset yet: cancelling during the test changes nothing.
            return new Outcome(request.profileName(), 0, 0, List.of(), List.of(), null, true);
        }
        if (!test.ok()) {
            return new Outcome(request.profileName(), 0, 0, List.of(), List.of(),
                test.message() != null ? test.message() : "", false);
        }
        for (Target target : request.targets()) {
            tracker.planJournal(plan(target.directory(), request.screenshots(), finish));
        }
        synchronized (this) {
            phase = Phase.RUNNING;
        }
        notifyListeners();

        long tokensBefore = totalTokens(request.targets());
        SessionJournalAiSupport.AiInvoker measuring = new MeasuringInvoker(request.invoker(), tracker, run.cancelled,
            this::notifyListeners);
        List<String> failures = new ArrayList<>();
        List<ScreenshotIssue> screenshotIssues = new ArrayList<>();
        int done = 0;
        for (int i = 0; i < request.targets().size(); i++) {
            if (run.cancelled.get()) {
                break;
            }
            Target target = request.targets().get(i);
            synchronized (this) {
                journalIndex = i;
            }
            tracker.journalStarted(i);
            notifyListeners();
            try {
                if (finish) {
                    summarizer.finishClosedJournal(target.directory(), measuring).get();
                } else {
                    summarizer.reevaluateClosedJournal(target.directory(), measuring).get();
                }
                if (request.screenshots() && screenshotAnalyzer != null && !run.cancelled.get()) {
                    tracker.screenshotsStarted();
                    int failedShots = describeScreenshots(target.directory(), measuring, run.cancelled);
                    if (failedShots > 0) {
                        screenshotIssues.add(new ScreenshotIssue(target.title(), failedShots));
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add(target.title() + ": " + e.getMessage());
                tracker.journalFinished(i);
                done++;
                break;
            } catch (Exception e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                failures.add(target.title() + ": " + cause.getMessage());
            }
            tracker.journalFinished(i);
            done++;
            notifyListeners();
        }
        Double measured = tracker.measuredSecondsPerUnit();
        if (measured != null && request.profileId() != null) {
            secondsPerUnitByProfile.put(request.profileId(), measured);
        }
        long tokens = Math.max(0, totalTokens(request.targets()) - tokensBefore);
        return new Outcome(request.profileName(), done - failures.size(), tokens, failures, screenshotIssues, null,
            run.cancelled.get());
    }

    /** A finish request with the titles of its journals, read on the job thread rather than by whoever queued it. */
    private Request withJournalTitles(Request request) {
        List<Target> targets = new ArrayList<>();
        for (Target target : request.targets()) {
            String title = target.title();
            try {
                String stored = service.loadDocument(target.directory()).getMeta().getTitle();
                title = stored != null && !stored.isBlank() ? stored : title;
            } catch (Exception e) {
                logger.debug("Could not read the title of {}: {}", target.directory().getFileName(), e.getMessage());
            }
            targets.add(new Target(target.directory(), title));
        }
        return new Request(targets, request.profileId(), request.profileName(), request.invoker(), false, request.kind());
    }

    /** The planned calls of one journal; a journal that cannot be read counts as one window and a wrap-up. */
    private List<SessionJournalReevaluationProgress.Step> plan(Path directory, boolean screenshots, boolean finish) {
        int shots = 0;
        if (screenshots && screenshotAnalyzer != null) {
            try {
                shots = (int) service.loadDocument(directory).getEntries().stream()
                    .filter(entry -> entry.getKind() == SessionJournalEntryKind.SCREENSHOT)
                    .count();
            } catch (Exception e) {
                logger.debug("Could not count the screenshots of {}: {}", directory.getFileName(), e.getMessage());
            }
        }
        try {
            return SessionJournalReevaluationProgress.steps(
                finish ? summarizer.planFinish(directory) : summarizer.planReevaluation(directory), shots);
        } catch (Exception e) {
            logger.debug("Could not plan the re-evaluation of {}: {}", directory.getFileName(), e.getMessage());
            return SessionJournalReevaluationProgress.steps(
                new SessionJournalSummarizer.ReevaluationPlan(List.of(0), true, false), shots);
        }
    }

    /** @return the number of screenshots that could not be described */
    private int describeScreenshots(Path directory, SessionJournalAiSupport.AiInvoker invoker, AtomicBoolean cancelled)
            throws Exception {
        int failed = 0;
        for (SessionJournalEntry entry : service.loadDocument(directory).getEntries()) {
            if (cancelled.get()) {
                break;
            }
            if (entry.getKind() == SessionJournalEntryKind.SCREENSHOT
                    && !screenshotAnalyzer.analyzeNow(directory, entry.getId(), invoker)) {
                failed++;
            }
        }
        return failed;
    }

    private long totalTokens(List<Target> targets) {
        long total = 0;
        for (Target target : targets) {
            try {
                total += service.loadDocument(target.directory()).getMeta().getAiTotalTokens();
            } catch (Exception ignored) {
                // a journal that cannot be read contributes nothing
            }
        }
        return total;
    }

    private void notifyListeners() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                logger.debug("Journal re-evaluation listener failed", e);
            }
        }
    }

    /** Passes every call through to the profile and tells the progress when one starts and ends. */
    static final class MeasuringInvoker implements SessionJournalAiSupport.AiInvoker {
        private final SessionJournalAiSupport.AiInvoker delegate;
        private final SessionJournalReevaluationProgress progress;
        private final AtomicBoolean cancelled;
        private final Runnable onChange;

        MeasuringInvoker(SessionJournalAiSupport.AiInvoker delegate, SessionJournalReevaluationProgress progress,
                         AtomicBoolean cancelled, Runnable onChange) {
            this.delegate = delegate;
            this.progress = progress;
            this.cancelled = cancelled;
            this.onChange = onChange;
        }

        @Override
        public boolean isAvailable() {
            return delegate.isAvailable();
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            Object call = progress.callStarted();
            onChange.run();
            try {
                return delegate.execute(systemPrompt, userPrompt);
            } finally {
                progress.callFinished(call);
                onChange.run();
            }
        }

        @Override
        public boolean isVisionAvailable() {
            return delegate.isVisionAvailable();
        }

        @Override
        public boolean isVisionPlausible() {
            return delegate.isVisionPlausible();
        }

        @Override
        public boolean isVisionAvailableLive() {
            return delegate.isVisionAvailableLive();
        }

        @Override
        public AiExecutionResult executeVision(String systemPrompt, String userPrompt, List<AiImageInput> images)
                throws Exception {
            Object call = progress.callStarted();
            onChange.run();
            try {
                return delegate.executeVision(systemPrompt, userPrompt, images);
            } finally {
                progress.callFinished(call);
                onChange.run();
            }
        }

        @Override
        public String visionModelLabel() {
            return delegate.visionModelLabel();
        }

        @Override
        public AiProfile profile() {
            return delegate.profile();
        }

        @Override
        public void testConnection() throws Exception {
            delegate.testConnection();
        }

        @Override
        public boolean isCancelled() {
            return cancelled.get() || delegate.isCancelled();
        }
    }
}
