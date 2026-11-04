package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalCommandState;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Periodic AI summarization of session journals: cuts the capture log since the last summarized
 * sequence at command boundaries ({@link SessionJournalCommandBlocks}), groups finished commands
 * into windows (configurable line limit, 0 = fill the model context via a token budget), asks the
 * AI for a compact journal entry per window and appends it to the journal document. A command is
 * never split over two entries: one larger than a window is condensed — its start, the lines with
 * errors and warnings, its end — or, in chunked mode, read in parts that end in one entry. A
 * command still running waits for its prompt; after {@link #RUNNING_CARD_AFTER_MILLIS} it gets an
 * entry that says so, replaced once it ends. Runs a closing pass (the last commands, session
 * wrap-up, optional AI title) when a session ends.
 *
 * <p>The scheduler only owns a timer while at least one journal is live (App Nap friendly).
 * All AI calls run sequentially on one worker with a hard timeout. Without an available AI
 * (no profile, policy deny, disabled) the summarizer degrades to RAW entries so the timeline
 * stays usable.</p>
 */
public class SessionJournalSummarizer {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalSummarizer.class);

    private static final long TICK_SECONDS = 30;
    private static final int MIN_NEW_LINES = 3;
    private static final long MIN_QUIET_MILLIS = 30_000;
    private static final int MAX_CONSECUTIVE_FAILURES = 3;
    private static final int FAILURE_BACKOFF_INTERVALS = 2;
    private static final long AI_CALL_TIMEOUT_SECONDS = 120;
    private static final long CLOSE_WAIT_MILLIS = 15_000;
    private static final int LINE_CHAR_CAP = 400;
    private static final int EXCERPT_LINES = 5;
    private static final int EXCERPT_LINE_CHARS = 200;
    private static final int TITLE_MAX_CHARS = 80;
    private static final int SESSION_SUMMARY_MAX_ENTRIES = 100;
    /** Per entry in the wrap-up prompt; a window summary is at most five sentences, well below. */
    private static final int ENTRY_LINE_MAX_CHARS = 1500;
    private static final String HIDDEN_INPUT_MARKER = "(hidden input)";
    private static final String SCREENSHOT_MARKER = "[screenshot attached]";
    /** A command running this long gets an entry that says so, refreshed at this pace until it ends. */
    static final long RUNNING_CARD_AFTER_MILLIS = TimeUnit.MINUTES.toMillis(15);
    /** How a condensed window shares its lines: the start, notable lines from the middle, the rest the end. */
    private static final double HEAD_SHARE = 0.3;
    private static final double NOTABLE_SHARE = 0.3;
    /** Lines from the middle of a long output that a condensed window keeps: errors, warnings, refusals. */
    private static final Pattern NOTABLE_LINE = Pattern.compile(
        "(?iu)\\b(?:errors?|fail(?:ed|ure)?|fatal|warn(?:ing)?|denied|refused|exception|traceback|abort(?:ed)?"
            + "|panic|segmentation fault|cannot|can't|unable|not found|timed? ?out|fehler|warnung|fehlgeschlagen"
            + "|verweigert|nicht gefunden|abgebrochen|erreur|échec|errore|fallito|erro|falha|fout|mislukt|greška)\\b");
    /** Output lines kept before each reply typed into a running program: the question it answered. */
    private static final int REPLY_CONTEXT_LINES = 8;
    private static final Pattern EXIT_STATUS_LINE = Pattern.compile("\\[exit status -?\\d+]");

    private final SessionJournalService service;
    private final Supplier<GlobalSettings> settingsSupplier;
    private final SessionJournalAiSupport.AiInvoker aiInvoker;
    private final Map<SessionJournalSession, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean tickRunning = new AtomicBoolean();
    private final ExecutorService aiCallExecutor = Executors.newSingleThreadExecutor(
        daemonThreadFactory("SessionJournal-AiCall"));
    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor(
        daemonThreadFactory("SessionJournal-Summarizer"));
    /**
     * Close passes of automation run journals get their own worker, so a 20-server job queuing
     * twenty summaries never delays the summaries of the terminal the user is working in.
     */
    private final ExecutorService automationExecutor = Executors.newSingleThreadExecutor(
        daemonThreadFactory("SessionJournal-Automation"));
    /** Journals whose automation close pass has not finished yet; retention must not touch them. */
    private final java.util.Set<Path> pendingClosePasses = ConcurrentHashMap.newKeySet();
    /** The invoker of the pass running on this thread when it differs from the default one. */
    private final ThreadLocal<SessionJournalAiSupport.AiInvoker> invokerOverride = new ThreadLocal<>();
    /** One pass per journal at a time: the closing pass waits for a periodic pass still at work. */
    private final Map<Path, Object> directoryLocks = new ConcurrentHashMap<>();
    /** Runs a closing pass for a journal whose session just closed; null = on this summarizer's worker. */
    private volatile Consumer<Path> closePassRunner;
    private ScheduledExecutorService scheduler;
    /** Command name → snippet name of a script the snippet manager knows; null when unknown. */
    private volatile java.util.function.Function<String, String> snippetLookup =
        SessionJournalSummarizer::applicationSnippetLookup;

    /** Per-session summarization progress; {@code lastSummarizedSeq < 0} means "load from doc". */
    private static final class SessionState {
        volatile long lastSummarizedSeq = -1;
        volatile long lastProcessedMillis = System.currentTimeMillis();
        volatile int consecutiveFailures;
        volatile int backoffRemaining;
        volatile boolean failurePlaceholderWritten;
        /** Set for the pass that runs when the session closes — the journal's last chance. */
        volatile boolean closingSession;
        /** The session is closing: a periodic pass stops after its current window and leaves the rest. */
        volatile boolean stopRequested;
        /** Where the command with a "still running" entry starts, and when that entry was written. */
        volatile long runningCardSeq = -1;
        volatile long runningCardMillis;
        final AtomicBoolean busy = new AtomicBoolean();
    }

    record Window(
        long startSeq,
        long endSeq,
        OffsetDateTime fromTime,
        OffsetDateTime toTime,
        List<String> inputLines,
        List<String> outputLines,
        int omittedInputLines,
        int omittedOutputLines,
        String firstCommand,
        /** The window's full, uncapped log text: what a summary's identifiers are checked against. */
        String evidence,
        /** Lines left out of the middle of one long command's output; 0 when none were. */
        int leftOutLines,
        /** Null when its commands finished; RUNNING or ENDED_WITH_SESSION for the last one. */
        SessionJournalCommandState commandState,
        /** One long command read in consecutive parts, one AI call each; empty for a single call. */
        List<Window> parts) {

        Window {
            parts = parts != null ? List.copyOf(parts) : List.of();
        }

        Window withState(SessionJournalCommandState state) {
            return new Window(startSeq, endSeq, fromTime, toTime, inputLines, outputLines, omittedInputLines,
                omittedOutputLines, firstCommand, evidence, leftOutLines, state, parts);
        }
    }

    /**
     * What one pass makes of the log after the last summarized sequence: the windows of finished
     * commands, and the last command when it still runs (never while the session ended).
     */
    record Cut(List<Window> windows, Window running) {
    }

    /** A rendered stream cut to fit: the kept lines, with markers where lines were left out. */
    record Condensed(List<String> lines, int leftOut) {
    }

    public SessionJournalSummarizer(SessionJournalService service) {
        this(service, SessionJournalSummarizer::applicationSettings, SessionJournalAiSupport.applicationInvoker());
    }

    SessionJournalSummarizer(
            SessionJournalService service,
            Supplier<GlobalSettings> settingsSupplier,
            SessionJournalAiSupport.AiInvoker aiInvoker) {
        this.service = service;
        this.settingsSupplier = settingsSupplier;
        this.aiInvoker = aiInvoker;
    }

    /** Test seam for the snippet-manager lookup behind the command tooltips. */
    void setSnippetLookup(java.util.function.Function<String, String> snippetLookup) {
        this.snippetLookup = snippetLookup != null ? snippetLookup : command -> null;
    }

    private static String applicationSnippetLookup(String command) {
        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
        if (app == null || app.getSnippetManager() == null) {
            return null;
        }
        return SessionJournalCommandInfos.findSnippetName(app.getSnippetManager().getAllSnippets(), command);
    }

    private static GlobalSettings applicationSettings() {
        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
        return app != null && app.getGlobalSettingsManager() != null
            ? app.getGlobalSettingsManager().getSettings()
            : null;
    }

    // ==== lifecycle ====

    /** Registers a live capture session for periodic summarization. */
    public void register(SessionJournalSession session) {
        if (session == null) {
            return;
        }
        sessions.putIfAbsent(session, new SessionState());
        ensureSchedulerStarted();
    }

    public void unregister(SessionJournalSession session) {
        if (session != null) {
            sessions.remove(session);
            stopSchedulerIfIdle();
        }
    }

    /**
     * Schedules the closing pass (final window, session wrap-up, optional AI title) for a session
     * that is about to close. Non-blocking: the pass runs on the summarizer worker against the
     * journal directory once the session has fully closed.
     */
    public void onSessionClosing(SessionJournalSession session) {
        if (session == null) {
            return;
        }
        SessionState state = sessions.remove(session);
        stopSchedulerIfIdle();
        if (state != null) {
            state.stopRequested = true; // a periodic pass at work hands over after its current window
        }
        final Path directory = session.getDirectory();
        Consumer<Path> runner = closePassRunner;
        if (runner != null) {
            runner.accept(directory);
            return;
        }
        final SessionState closeState = new SessionState();
        closeState.closingSession = true;
        final boolean aiEnabled = session.isAiSummariesEnabled();
        workExecutor.submit(() -> runClosePass(directory, closeState, aiEnabled));
    }

    /**
     * Hands the closing passes of closed sessions to {@code runner} — the application queues them in
     * its background job, where the journal manager shows them. It must end in
     * {@link #finishClosedJournal}. Null runs them on this summarizer's own worker.
     */
    public void setClosePassRunner(Consumer<Path> runner) {
        this.closePassRunner = runner;
    }

    /**
     * The closing pass of a journal whose session ended — closed, or left behind when korTTY quit
     * or crashed: its last commands, the wrap-up and the AI title, with {@code invoker}. Journals
     * recorded without AI summaries get raw entries. Clears the journal's finish marker.
     */
    public CompletableFuture<Void> finishClosedJournal(Path directory, SessionJournalAiSupport.AiInvoker invoker) {
        boolean aiEnabled;
        try {
            aiEnabled = !service.loadDocument(directory).getMeta().isAiSummariesOff();
        } catch (IOException e) {
            return CompletableFuture.failedFuture(e);
        }
        return summarizeClosedJournal(directory, invoker, aiEnabled);
    }

    /** Manual "summarize now" for a live session; completes when the pass finished. */
    public CompletableFuture<Void> summarizeNow(SessionJournalSession session) {
        SessionState state = sessions.get(session);
        if (state == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> maybeProcess(session, state, true), workExecutor);
    }

    /**
     * Runs the full summarization (windows, wrap-up, optional AI title) for an already-closed
     * journal — the backfill path for journals recorded while summaries were off or no model was
     * reachable. A fresh state loads the persisted {@code lastSummarizedSeq}, so an interrupted
     * backfill resumes instead of duplicating entries.
     */
    public CompletableFuture<Void> backfillClosedJournal(Path directory) {
        return CompletableFuture.runAsync(
            () -> runClosePass(directory, new SessionState(), true), workExecutor);
    }

    /**
     * Runs the closing pass (windows and session summary) for a closed automation run journal on
     * the automation worker, with its own AI profile. {@code aiEnabled=false} writes raw entries
     * only and never calls the AI. The journal counts as pending until the returned future
     * completes; see {@link #isPending(Path)}.
     */
    public CompletableFuture<Void> summarizeClosedJournal(
            Path directory, SessionJournalAiSupport.AiInvoker invoker, boolean aiEnabled) {
        Path key = directory.toAbsolutePath().normalize();
        pendingClosePasses.add(key);
        try {
            return CompletableFuture.runAsync(() -> {
                invokerOverride.set(invoker);
                try {
                    SessionState state = new SessionState();
                    // the journal's last pass: a failed AI call must still leave raw activity
                    state.closingSession = true;
                    runClosePass(directory, state, aiEnabled);
                } finally {
                    invokerOverride.remove();
                    pendingClosePasses.remove(key);
                }
            }, automationExecutor);
        } catch (java.util.concurrent.RejectedExecutionException e) {
            pendingClosePasses.remove(key);
            return CompletableFuture.failedFuture(e);
        }
    }

    /**
     * Evaluates a closed journal again with {@code invoker} — e.g. another profile after a poor
     * result or a broken AI connection: the previous AI summaries and session summary are removed
     * and the whole capture log is summarized afresh. If the AI fails midway the windows are kept
     * as raw entries, so the timeline is never left empty.
     */
    public CompletableFuture<Void> reevaluateClosedJournal(Path directory, SessionJournalAiSupport.AiInvoker invoker) {
        try {
            service.resetAiEvaluation(directory);
        } catch (Exception e) {
            return CompletableFuture.failedFuture(e);
        }
        return summarizeClosedJournal(directory, invoker, true);
    }

    /** True while an automation close pass for this journal is queued or running. */
    public boolean isPending(Path directory) {
        return directory != null && pendingClosePasses.contains(directory.toAbsolutePath().normalize());
    }

    /** The invoker of the journal profile, which the periodic and closing passes of live sessions use. */
    SessionJournalAiSupport.AiInvoker journalInvoker() {
        return aiInvoker;
    }

    private SessionJournalAiSupport.AiInvoker invoker() {
        SessionJournalAiSupport.AiInvoker override = invokerOverride.get();
        return override != null ? override : aiInvoker;
    }

    /** Stops all executors on application shutdown. */
    public synchronized void stop() {
        sessions.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        workExecutor.shutdown();
        automationExecutor.shutdown();
        aiCallExecutor.shutdown();
    }

    private synchronized void ensureSchedulerStarted() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(daemonThreadFactory("SessionJournal-Tick"));
        scheduler.scheduleWithFixedDelay(this::tickSafely, TICK_SECONDS, TICK_SECONDS, TimeUnit.SECONDS);
    }

    private synchronized void stopSchedulerIfIdle() {
        if (sessions.isEmpty() && scheduler != null) {
            scheduler.shutdown();
            scheduler = null;
        }
    }

    private void tickSafely() {
        if (!tickRunning.compareAndSet(false, true)) {
            return; // a slow AI call may exceed the tick; never overlap
        }
        try {
            for (Map.Entry<SessionJournalSession, SessionState> entry : sessions.entrySet()) {
                if (entry.getKey().isActive()) {
                    maybeProcess(entry.getKey(), entry.getValue(), false);
                }
            }
        } catch (Exception e) {
            logger.warn("Session journal summarizer tick failed: {}", e.getMessage());
        } finally {
            tickRunning.set(false);
        }
    }

    // ==== periodic processing ====

    private void maybeProcess(SessionJournalSession session, SessionState state, boolean force) {
        if (!state.busy.compareAndSet(false, true)) {
            return;
        }
        try {
            GlobalSettings settings = settingsSupplier.get();
            long now = System.currentTimeMillis();
            if (!force) {
                long intervalMillis = effectiveIntervalMillis(session, settings);
                if (now - state.lastProcessedMillis < intervalMillis) {
                    return;
                }
                if (state.backoffRemaining > 0) {
                    state.backoffRemaining--;
                    state.lastProcessedMillis = now;
                    return;
                }
            }
            state.lastProcessedMillis = now;
            processWindows(session.getDirectory(), state, session.isAiSummariesEnabled(), settings, force, session);
        } catch (Exception e) {
            logger.warn("Session journal summarization failed for {}: {}",
                session.getDirectory().getFileName(), e.getMessage());
        } finally {
            state.busy.set(false);
        }
    }

    private long effectiveIntervalMillis(SessionJournalSession session, GlobalSettings settings) {
        int minutes = session.getSummaryIntervalMinutesOverride() > 0
            ? session.getSummaryIntervalMinutesOverride()
            : (settings != null ? settings.getSessionJournalSummarizeIntervalMinutes() : 5);
        return TimeUnit.MINUTES.toMillis(Math.max(1, minutes));
    }

    /**
     * Reads everything after the last summarized sequence, cuts it at command boundaries into
     * windows and turns each into a journal entry. Progress advances only on success, so failed
     * windows retry on the next pass. A command still running waits; after
     * {@link #RUNNING_CARD_AFTER_MILLIS}, or when the user asks for a summary now, it gets an entry
     * that says it still runs, without advancing.
     */
    private void processWindows(
            Path directory,
            SessionState state,
            boolean sessionAiEnabled,
            GlobalSettings settings,
            boolean finalPass,
            SessionJournalSession liveSessionOrNull) throws Exception {
        synchronized (lockFor(directory)) {
            processWindowsLocked(directory, state, sessionAiEnabled, settings, finalPass, liveSessionOrNull);
        }
    }

    private void processWindowsLocked(
            Path directory,
            SessionState state,
            boolean sessionAiEnabled,
            GlobalSettings settings,
            boolean finalPass,
            SessionJournalSession liveSessionOrNull) throws Exception {
        SessionJournalDocument document = service.loadDocument(directory);
        if (state.lastSummarizedSeq < 0) {
            state.lastSummarizedSeq = document.getMeta().getLastSummarizedSeq();
        }
        List<SessionJournalLogEntry> newEntries = service.readLogAfter(directory, state.lastSummarizedSeq);
        int contentLines = filterContentEntries(newEntries).size();
        if (contentLines == 0) {
            return;
        }
        if (!finalPass && liveSessionOrNull != null
                && contentLines < MIN_NEW_LINES
                && System.currentTimeMillis() - liveSessionOrNull.getLastActivityMillis() < MIN_QUIET_MILLIS) {
            return; // a half-typed command is not worth a journal entry yet
        }

        boolean aiAvailable = sessionAiEnabled
            && (settings == null || settings.isSessionJournalAiSummariesEnabled())
            && invoker().isAvailable();
        String languageCode = document.getMeta().getAppLanguageCode();

        Cut cut = cutWindows(newEntries, settings, liveSessionOrNull == null);
        String loginUser = document.getMeta().getUsername();
        for (Window window : cut.windows()) {
            if (state.stopRequested && !finalPass) {
                return; // the session closed: its closing pass takes over from here
            }
            if (isIdle(window)) {
                // Nothing was typed and nothing but the prompt was printed: no entry, but the
                // range counts as done so it never comes back as "no activity".
                dropRunningEntries(directory, window, null);
                advanceProgress(directory, state, window);
                continue;
            }
            if (aiAvailable && invoker().isCancelled()) {
                // The run was cancelled: no further call. The window stays visible as raw activity
                // and counts as done, so the journal is complete and a later re-evaluation redoes it.
                writeRawEntry(directory, window, loginUser);
                advanceProgress(directory, state, window);
                continue;
            }
            boolean ok = aiAvailable
                ? summarizeWindowWithAi(directory, document, window, languageCode)
                : writeRawEntry(directory, window, loginUser);
            if (!ok && aiAvailable && finalPass && state.closingSession) {
                // The closing pass is the last chance: a failed AI call must not leave the journal's
                // timeline empty, so keep the window as a raw activity entry. Progress is NOT
                // advanced, so "Catch up summaries" still finds the journal and summarizes it later.
                logger.info("AI summary failed in the closing pass of {}; recording raw activity instead",
                    directory.getFileName());
                writeRawEntry(directory, window, loginUser);
                handleFailure(directory, state);
                continue;
            }
            if (ok) {
                advanceProgress(directory, state, window);
            } else {
                handleFailure(directory, state);
                return;
            }
        }
        Window running = cut.running();
        if (running != null && !isIdle(running) && runningEntryDue(state, running, finalPass)) {
            boolean ok = aiAvailable
                ? summarizeWindowWithAi(directory, document, running, languageCode)
                : writeRawEntry(directory, running, loginUser);
            if (ok) {
                state.runningCardSeq = running.startSeq();
                state.runningCardMillis = System.currentTimeMillis();
            }
        }
    }

    /**
     * Whether a command still running gets its "still running" entry now: when it has run for
     * {@link #RUNNING_CARD_AFTER_MILLIS} and its entry is that old (or missing), or when the user
     * asked for a summary now ({@code forced}).
     */
    private static boolean runningEntryDue(SessionState state, Window running, boolean forced) {
        if (forced) {
            return true;
        }
        long now = System.currentTimeMillis();
        long started = running.fromTime() != null ? running.fromTime().toInstant().toEpochMilli() : now;
        if (now - started < RUNNING_CARD_AFTER_MILLIS) {
            return false;
        }
        return state.runningCardSeq != running.startSeq() || now - state.runningCardMillis >= RUNNING_CARD_AFTER_MILLIS;
    }

    private Object lockFor(Path directory) {
        return directoryLocks.computeIfAbsent(directory.toAbsolutePath().normalize(), key -> new Object());
    }

    /**
     * Cuts {@code entries} at command boundaries. Finished commands are grouped into windows within
     * the line limit and the token budget; a command larger than that gets a window of its own,
     * condensed — or read in parts in chunked mode — but never split over two entries. While the
     * session lives ({@code sessionEnded} false) a command still running is held back as
     * {@link Cut#running()}; at the session's end it is summarized as far as it got.
     */
    private Cut cutWindows(List<SessionJournalLogEntry> entries, GlobalSettings settings, boolean sessionEnded) {
        int maxLines = settings != null ? settings.getSessionJournalAiMaxLines() : 100;
        int budget = promptTokenBudget(settings != null ? settings.getSessionJournalAiTokenBudget() : 130_000);
        boolean chunked = settings != null && settings.isSessionJournalAiChunkingEnabled();
        List<SessionJournalCommandBlocks.Block> blocks = new ArrayList<>(SessionJournalCommandBlocks.cut(entries));
        SessionJournalCommandBlocks.Block runningBlock = null;
        boolean endedRunning = false;
        if (!blocks.isEmpty() && !blocks.get(blocks.size() - 1).complete()) {
            if (sessionEnded) {
                endedRunning = true;
            } else {
                runningBlock = blocks.remove(blocks.size() - 1);
            }
        }
        List<Window> windows = new ArrayList<>();
        List<SessionJournalCommandBlocks.Block> group = new ArrayList<>();
        List<SessionJournalLogEntry> groupContent = new ArrayList<>();
        int groupInput = 0;
        int groupOutput = 0;
        int groupTokens = 0;
        for (SessionJournalCommandBlocks.Block block : blocks) {
            List<SessionJournalLogEntry> content = contentOf(block);
            int input = 0;
            int tokens = 0;
            for (SessionJournalLogEntry entry : content) {
                input += entry.kind() == SessionJournalLogEntry.Kind.IN ? 1 : 0;
                tokens += AiTokenCounter.estimateTokens(renderLine(entry));
            }
            int output = content.size() - input;
            if (!fits(input, output, tokens, maxLines, budget)) {
                if (!group.isEmpty()) {
                    windows.add(groupWindow(group, groupContent));
                    group = new ArrayList<>();
                    groupContent = new ArrayList<>();
                    groupInput = groupOutput = groupTokens = 0;
                }
                windows.add(chunked
                    ? partedWindow(block, content, maxLines, budget)
                    : condensedWindow(block, content, maxLines, budget));
                continue;
            }
            if (!group.isEmpty() && !fits(groupInput + input, groupOutput + output, groupTokens + tokens, maxLines, budget)) {
                windows.add(groupWindow(group, groupContent));
                group = new ArrayList<>();
                groupContent = new ArrayList<>();
                groupInput = groupOutput = groupTokens = 0;
            }
            group.add(block);
            groupContent.addAll(content);
            groupInput += input;
            groupOutput += output;
            groupTokens += tokens;
        }
        if (!group.isEmpty()) {
            windows.add(groupWindow(group, groupContent));
        }
        if (endedRunning && !windows.isEmpty()) {
            int last = windows.size() - 1;
            windows.set(last, windows.get(last).withState(SessionJournalCommandState.ENDED_WITH_SESSION));
        }
        Window running = runningBlock != null
            ? condensedWindow(runningBlock, contentOf(runningBlock), maxLines, budget)
                .withState(SessionJournalCommandState.RUNNING)
            : null;
        return new Cut(windows, running);
    }

    private static boolean fits(int input, int output, int tokens, int maxLines, int budget) {
        boolean linesFit = maxLines <= 0 || input <= maxLines && output <= maxLines;
        return linesFit && tokens <= budget;
    }

    /**
     * The entries of {@code block} that reach a prompt: without stale partial lines and pipeline
     * notes, and without a mistyped command — the whole command when it was nothing but that.
     * A command that only showed help ({@link SessionJournalHelpCommands}) has none at all.
     */
    private static List<SessionJournalLogEntry> contentOf(SessionJournalCommandBlocks.Block block) {
        List<SessionJournalLogEntry> content =
            SessionJournalTypoFilter.withoutNotFoundAttempts(filterContentEntries(block.entries()));
        if (block.command() != null && SessionJournalHelpCommands.isHelpOnly(block.command(), content.stream()
                .filter(entry -> entry.kind() != SessionJournalLogEntry.Kind.IN)
                .map(SessionJournalLogEntry::text)
                .toList())) {
            return List.of();
        }
        if (block.command() != null
                && content.stream().noneMatch(entry -> entry.kind() == SessionJournalLogEntry.Kind.IN)) {
            // the command was a typo: its exit status says nothing either
            content = content.stream().filter(entry -> entry.kind() != SessionJournalLogEntry.Kind.NOTE).toList();
        }
        return content;
    }

    /** The window of commands that fit together, covering their whole log range. */
    private Window groupWindow(List<SessionJournalCommandBlocks.Block> group, List<SessionJournalLogEntry> content) {
        List<String> inputLines = new ArrayList<>();
        List<String> outputLines = new ArrayList<>();
        split(content, inputLines, outputLines);
        SessionJournalCommandBlocks.Block first = group.get(0);
        SessionJournalCommandBlocks.Block last = group.get(group.size() - 1);
        return new Window(first.startSeq(), last.endSeq(), first.startTime(), endTime(last),
            inputLines, outputLines, 0, 0, firstCommandOf(group, inputLines, outputLines),
            evidenceOf(content), 0, null, List.of());
    }

    /**
     * One command too large for a window, as one prompt: all it was typed with and, of its output,
     * the start, the notable lines from the middle and the end — the parts that tell what happened.
     */
    private Window condensedWindow(SessionJournalCommandBlocks.Block block, List<SessionJournalLogEntry> content,
                                   int maxLines, int budget) {
        List<String> inputLines = new ArrayList<>();
        List<String> outputLines = new ArrayList<>();
        split(content, inputLines, outputLines);
        Condensed input = condense(inputLines, maxLines, budget / 4, List.of());
        int inputTokens = 0;
        for (String line : input.lines()) {
            inputTokens += AiTokenCounter.estimateTokens(line);
        }
        Condensed output = condense(outputLines, maxLines, Math.max(500, budget - inputTokens), replyContext(content));
        return new Window(block.startSeq(), block.endSeq(), block.startTime(), endTime(block),
            input.lines(), output.lines(), input.leftOut(), 0,
            firstCommandOf(List.of(block), inputLines, outputLines), evidenceOf(content),
            output.leftOut(), null, List.of());
    }

    /**
     * One command too large for a window, read whole in chunked mode: consecutive parts, one call
     * each, that end in one entry. The window's own lines are the condensed view, for the excerpt.
     */
    private Window partedWindow(SessionJournalCommandBlocks.Block block, List<SessionJournalLogEntry> content,
                                int maxLines, int budget) {
        Window condensed = condensedWindow(block, content, maxLines, budget);
        List<Window> parts = buildChunkedWindows(content, maxLines, budget);
        return new Window(condensed.startSeq(), condensed.endSeq(), condensed.fromTime(), condensed.toTime(),
            condensed.inputLines(), condensed.outputLines(), condensed.omittedInputLines(), 0,
            condensed.firstCommand(), condensed.evidence(), 0, null, parts);
    }

    private static OffsetDateTime endTime(SessionJournalCommandBlocks.Block block) {
        return block.entries().get(block.entries().size() - 1).timestamp();
    }

    private static void split(List<SessionJournalLogEntry> content, List<String> inputLines, List<String> outputLines) {
        for (SessionJournalLogEntry entry : content) {
            if (entry.kind() == SessionJournalLogEntry.Kind.IN) {
                inputLines.add(renderLine(entry));
            } else {
                outputLines.add(renderLine(entry));
            }
        }
    }

    /**
     * The output lines just before each line typed into the running command — what it asked, like
     * dnf's transaction summary above "Is this ok [y/N]:" — nearest first, as output line indexes.
     */
    private static List<Integer> replyContext(List<SessionJournalLogEntry> content) {
        List<Integer> anchors = new ArrayList<>();
        int outputIndex = 0;
        boolean commandSeen = false;
        for (SessionJournalLogEntry entry : content) {
            if (entry.kind() != SessionJournalLogEntry.Kind.IN) {
                outputIndex++;
                continue;
            }
            if (commandSeen) {
                for (int back = 1; back <= REPLY_CONTEXT_LINES && outputIndex - back >= 0; back++) {
                    anchors.add(outputIndex - back);
                }
            }
            commandSeen = true;
        }
        return anchors;
    }

    /**
     * {@code lines} cut to {@code maxLines} (0 = no line limit) and {@code tokenBudget}: the first
     * lines, from the middle the {@code anchors} (output before a reply) and the notable lines
     * ({@link #NOTABLE_LINE}, each text once), and the last lines, in order, with a marker where
     * lines were left out.
     */
    static Condensed condense(List<String> lines, int maxLines, int tokenBudget, List<Integer> anchors) {
        int lineCap = maxLines > 0 ? maxLines : Integer.MAX_VALUE;
        int[] tokens = new int[lines.size()];
        int total = 0;
        for (int i = 0; i < lines.size(); i++) {
            tokens[i] = AiTokenCounter.estimateTokens(lines.get(i));
            total += tokens[i];
        }
        if (lines.size() <= lineCap && total <= tokenBudget) {
            return new Condensed(List.copyOf(lines), 0);
        }
        boolean[] keep = new boolean[lines.size()];
        int head = take(keep, tokens, 0, lines.size(), 1, share(lineCap, HEAD_SHARE), (int) (tokenBudget * HEAD_SHARE));
        int tail = take(keep, tokens, lines.size() - 1, head - 1, -1,
            share(lineCap, 1 - HEAD_SHARE - NOTABLE_SHARE), (int) (tokenBudget * (1 - HEAD_SHARE - NOTABLE_SHARE)));
        int notableLines = share(lineCap, NOTABLE_SHARE);
        int notableTokens = (int) (tokenBudget * NOTABLE_SHARE);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int i : anchors) {
            if (i >= head && i <= tail && !keep[i] && notableLines > 0 && tokens[i] <= notableTokens) {
                keep[i] = true;
                seen.add(lines.get(i).strip());
                notableLines--;
                notableTokens -= tokens[i];
            }
        }
        for (int i = head; i <= tail && notableLines > 0; i++) {
            String line = lines.get(i);
            if (!keep[i] && tokens[i] <= notableTokens && NOTABLE_LINE.matcher(line).find() && seen.add(line.strip())) {
                keep[i] = true;
                notableLines--;
                notableTokens -= tokens[i];
            }
        }
        List<String> result = new ArrayList<>();
        int leftOut = 0;
        int gap = 0;
        for (int i = 0; i < lines.size(); i++) {
            if (!keep[i]) {
                gap++;
                continue;
            }
            if (gap > 0) {
                result.add(leftOutMarker(gap));
                leftOut += gap;
                gap = 0;
            }
            result.add(lines.get(i));
        }
        if (gap > 0) {
            result.add(leftOutMarker(gap));
            leftOut += gap;
        }
        return new Condensed(result, leftOut);
    }

    private static String leftOutMarker(int lines) {
        return "[... " + lines + " lines left out ...]";
    }

    private static int share(int lineCap, double share) {
        return lineCap == Integer.MAX_VALUE ? Integer.MAX_VALUE : Math.max(1, (int) (lineCap * share));
    }

    /**
     * Keeps lines from {@code from} towards {@code stop} (exclusive) while both caps allow; returns
     * the first index not taken.
     */
    private static int take(boolean[] keep, int[] tokens, int from, int stop, int step, int lineCap, int tokenCap) {
        int i = from;
        int lines = 0;
        int used = 0;
        while (i != stop && lines < lineCap && used + tokens[i] <= tokenCap) {
            keep[i] = true;
            used += tokens[i];
            lines++;
            i += step;
        }
        return i;
    }

    /**
     * The AI calls a re-evaluation of a closed journal makes, in order: one per window that is not
     * idle (with the size of its log text; a command read in parts makes one per part), then the
     * wrap-up and the AI title when they would run. {@code windowPromptChars} is empty when the AI
     * summaries are switched off.
     */
    public record ReevaluationPlan(List<Integer> windowPromptChars, boolean wrapUp, boolean title) {
        public ReevaluationPlan {
            windowPromptChars = List.copyOf(windowPromptChars);
        }

        public int calls() {
            return windowPromptChars.size() + (wrapUp ? 1 : 0) + (title ? 1 : 0);
        }
    }

    /**
     * Plans {@link #reevaluateClosedJournal}: which AI calls it will make and how large their prompts
     * are, for a progress bar and a remaining-time estimate. Reads the whole capture log the way the
     * pass will after the reset; makes no call and changes nothing.
     */
    public ReevaluationPlan planReevaluation(Path directory) throws IOException {
        return plan(directory, 0);
    }

    /** Plans {@link #finishClosedJournal}: the calls for the log after the last summarized sequence. */
    public ReevaluationPlan planFinish(Path directory) throws IOException {
        SessionJournalDocument document = service.loadDocument(directory);
        if (document.getMeta().isAiSummariesOff()) {
            return new ReevaluationPlan(List.of(), false, false);
        }
        return plan(directory, document.getMeta().getLastSummarizedSeq());
    }

    private ReevaluationPlan plan(Path directory, long afterSeq) throws IOException {
        GlobalSettings settings = settingsSupplier.get();
        if (settings != null && !settings.isSessionJournalAiSummariesEnabled()) {
            return new ReevaluationPlan(List.of(), false, false);
        }
        List<Integer> windowChars = new ArrayList<>();
        List<SessionJournalLogEntry> entries = service.readLogAfter(directory, afterSeq);
        if (!filterContentEntries(entries).isEmpty()) {
            for (Window window : cutWindows(entries, settings, true).windows()) {
                if (isIdle(window)) {
                    continue;
                }
                if (window.parts().isEmpty()) {
                    windowChars.add(promptChars(window));
                } else {
                    window.parts().forEach(part -> windowChars.add(promptChars(part)));
                }
            }
        }
        SessionJournalDocument document = service.loadDocument(directory);
        // The wrap-up needs at least one line to work from: a window summary, a note or a described screenshot.
        boolean wrapUp = !windowChars.isEmpty() || document.getEntries().stream().anyMatch(entry ->
            entry.getKind() == SessionJournalEntryKind.USER_NOTE
                || entry.getKind() == SessionJournalEntryKind.AI_SUMMARY && afterSeq > 0
                || entry.getKind() == SessionJournalEntryKind.SCREENSHOT && entry.hasAiAnalysis());
        boolean title = settings != null && settings.isSessionJournalAiTitleEnabled() && titleLooksDefault(document);
        return new ReevaluationPlan(windowChars, wrapUp, title);
    }

    private static int promptChars(Window window) {
        int chars = 0;
        for (String line : window.inputLines()) {
            chars += line.length() + 1;
        }
        for (String line : window.outputLines()) {
            chars += line.length() + 1;
        }
        return chars;
    }

    private void advanceProgress(Path directory, SessionState state, Window window) throws Exception {
        state.lastSummarizedSeq = window.endSeq();
        state.consecutiveFailures = 0;
        state.failurePlaceholderWritten = false;
        service.updateLastSummarizedSeq(directory, window.endSeq());
    }

    /**
     * Removes the "still running" entries of the commands in {@code window} — the window now
     * describes them as they ended (or found nothing to say).
     */
    private void dropRunningEntries(Path directory, Window window, String exceptId) {
        try {
            service.removeEntries(directory, entry -> entry.getCommandState() == SessionJournalCommandState.RUNNING
                && !entry.getId().equals(exceptId)
                && entry.getLogStartSeq() != null
                && entry.getLogStartSeq() >= window.startSeq() && entry.getLogStartSeq() <= window.endSeq());
        } catch (IOException e) {
            logger.warn("Could not remove the running-command entry of {}: {}", directory.getFileName(), e.getMessage());
        }
    }

    /**
     * True when the window holds no activity: no typed input (hidden input counts as typed), no
     * screenshot, and no output beyond blank lines, bare shell prompts and exit statuses. Omitted
     * lines count as activity — the window was too big to be idle.
     */
    static boolean isIdle(Window window) {
        if (window.omittedInputLines() > 0 || window.omittedOutputLines() > 0 || window.leftOutLines() > 0
                || !window.parts().isEmpty()) {
            return false;
        }
        for (String line : window.inputLines()) {
            if (line != null && !line.isBlank()) {
                return false;
            }
        }
        for (String line : window.outputLines()) {
            if (SCREENSHOT_MARKER.equals(line)
                    || !SessionJournalShellPrompts.isIdleLine(line) && !EXIT_STATUS_LINE.matcher(line).matches()) {
                return false;
            }
        }
        return true;
    }

    /**
     * OUT/SEED/IN/SCREENSHOT entries that carry journal-relevant content, plus the shell's exit
     * statuses; other NOTEs are skipped.
     */
    private static List<SessionJournalLogEntry> filterContentEntries(List<SessionJournalLogEntry> entries) {
        long lastOutputSeq = entries.stream()
            .filter(e -> e.kind() == SessionJournalLogEntry.Kind.OUT
                || e.kind() == SessionJournalLogEntry.Kind.SEED)
            .mapToLong(SessionJournalLogEntry::seq)
            .max()
            .orElse(-1);
        List<SessionJournalLogEntry> result = new ArrayList<>();
        for (SessionJournalLogEntry entry : entries) {
            switch (entry.kind()) {
                case OUT, SEED -> {
                    // Stale idle-flush partials are superseded by their completed line; the very
                    // last output line (typically the pending prompt) stays.
                    if (!entry.partial() || entry.seq() == lastOutputSeq) {
                        result.add(entry);
                    }
                }
                case IN, SCREENSHOT -> result.add(entry);
                case NOTE -> {
                    // pipeline markers are not session content; an exit status the shell reported is
                    if (SessionJournalCommandBlocks.exitStatusOf(entry) != null
                            && SessionJournalCommandBlocks.isFinishedNote(entry)) {
                        result.add(entry);
                    }
                }
            }
        }
        return result;
    }

    /** Consecutive parts of {@code content}, each within the line limit and the (prompt) token budget. */
    private List<Window> buildChunkedWindows(List<SessionJournalLogEntry> content, int maxLines, int budget) {
        List<Window> windows = new ArrayList<>();
        List<SessionJournalLogEntry> current = new ArrayList<>();
        int inputCount = 0;
        int outputCount = 0;
        int usedTokens = 0;
        for (SessionJournalLogEntry entry : content) {
            String line = renderLine(entry);
            int lineTokens = AiTokenCounter.estimateTokens(line);
            boolean lineLimitReached = maxLines > 0
                && (inputCount >= maxLines || outputCount >= maxLines);
            boolean budgetReached = !current.isEmpty() && usedTokens + lineTokens > budget;
            if (lineLimitReached || budgetReached) {
                windows.add(windowFromEntries(current));
                current = new ArrayList<>();
                inputCount = 0;
                outputCount = 0;
                usedTokens = 0;
            }
            current.add(entry);
            usedTokens += lineTokens;
            if (entry.kind() == SessionJournalLogEntry.Kind.IN) {
                inputCount++;
            } else {
                outputCount++;
            }
        }
        if (!current.isEmpty()) {
            windows.add(windowFromEntries(current));
        }
        return windows;
    }

    private Window windowFromEntries(List<SessionJournalLogEntry> entries) {
        List<String> inputLines = new ArrayList<>();
        List<String> outputLines = new ArrayList<>();
        split(entries, inputLines, outputLines);
        return new Window(
            entries.get(0).seq(),
            entries.get(entries.size() - 1).seq(),
            entries.get(0).timestamp(),
            entries.get(entries.size() - 1).timestamp(),
            inputLines,
            outputLines,
            0,
            0,
            firstCommandOf(inputLines, outputLines),
            evidenceOf(entries),
            0,
            null,
            List.of());
    }

    /** Reserve a quarter of the budget for prompt scaffolding and the model's reply. */
    private static int promptTokenBudget(int tokenBudget) {
        return Math.max(500, tokenBudget - tokenBudget / 4);
    }

    private static String renderLine(SessionJournalLogEntry entry) {
        if (entry.kind() == SessionJournalLogEntry.Kind.SCREENSHOT) {
            return SCREENSHOT_MARKER;
        }
        if (entry.kind() == SessionJournalLogEntry.Kind.NOTE) {
            Integer exitStatus = SessionJournalCommandBlocks.exitStatusOf(entry);
            return exitStatus != null ? "[exit status " + exitStatus + "]" : "";
        }
        if (entry.redacted()) {
            return HIDDEN_INPUT_MARKER;
        }
        String line = capLine(entry.text() != null ? entry.text() : "", LINE_CHAR_CAP);
        // A coalesced duplicate run: the repetition signal costs the model a few tokens instead
        // of N duplicated lines.
        return entry.repeat() > 1 ? line + " (×" + entry.repeat() + ")" : line;
    }

    /** Keeps the head and the tail of a line with a marker between them; shared with the KI-Agent chat seeding. */
    static String capLine(String line, int maxChars) {
        if (line.length() <= maxChars) {
            return line;
        }
        int half = (maxChars - 5) / 2;
        return line.substring(0, half) + " ... " + line.substring(line.length() - half);
    }

    /** Keeps whole sentences up to {@code maxChars}; a cut is marked so the model never completes it. */
    static String capAtSentence(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        String head = text.substring(0, maxChars);
        int end = Math.max(Math.max(head.lastIndexOf(". "), head.lastIndexOf("! ")), head.lastIndexOf("? "));
        if (end > maxChars / 3) {
            return head.substring(0, end + 1) + " [...]";
        }
        int space = head.lastIndexOf(' ');
        return (space > 0 ? head.substring(0, space) : head) + " [...]";
    }

    private static List<String> reversed(List<String> list) {
        List<String> result = new ArrayList<>(list);
        java.util.Collections.reverse(result);
        return result;
    }

    private static String evidenceOf(List<SessionJournalLogEntry> entries) {
        StringBuilder evidence = new StringBuilder(4096);
        for (SessionJournalLogEntry entry : entries) {
            if (entry.text() != null && !entry.redacted()) {
                evidence.append(entry.text()).append('\n');
            }
        }
        return evidence.toString();
    }

    /**
     * The first command typed in {@code blocks} that reached the prompt — not a typo or a help call
     * left out of it; else the first input or output line.
     */
    private static String firstCommandOf(List<SessionJournalCommandBlocks.Block> blocks,
                                         List<String> inputLines, List<String> outputLines) {
        for (SessionJournalCommandBlocks.Block block : blocks) {
            String command = block.command() != null ? capLine(block.command(), LINE_CHAR_CAP) : null;
            if (command != null && !command.isBlank()
                    && inputLines.stream().anyMatch(line -> line.strip().equals(command))) {
                return command;
            }
        }
        return firstCommandOf(inputLines, outputLines);
    }

    private static String firstCommandOf(List<String> inputLines, List<String> outputLines) {
        for (String line : inputLines) {
            if (!line.isBlank() && !HIDDEN_INPUT_MARKER.equals(line)) {
                return line;
            }
        }
        return !outputLines.isEmpty() ? outputLines.get(0) : "";
    }

    // ==== entry production ====

    private boolean summarizeWindowWithAi(
            Path directory, SessionJournalDocument document, Window window, String languageCode) {
        try {
            SessionJournalAiSupport.SummaryResult parsed = window.parts().isEmpty()
                ? askForSummary(directory, document, window, notesFor(window), null, languageCode)
                : summarizeInParts(directory, document, window, languageCode);
            if (parsed == null) {
                return false;
            }
            if (parsed.skip()) {
                if (window.commandState() != SessionJournalCommandState.RUNNING) {
                    dropRunningEntries(directory, window, null);
                }
                return true; // the model saw no activity: the range is done, without an entry
            }
            String loginUser = document.getMeta().getUsername();
            SessionJournalFactCheck.Result checked =
                SessionJournalFactCheck.dropUnverified(parsed.summary(), window.evidence());
            if (checked.dropped() > 0) {
                logger.info("Dropped {} AI statement(s) naming something the log of {} does not show",
                    checked.dropped(), directory.getFileName());
            }
            if (checked.text().isBlank()) {
                return writeRawEntry(directory, window, loginUser); // nothing verifiable was left
            }
            String title = SessionJournalFactCheck.verified(nullToEmpty(parsed.title()),
                    window.evidence().toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " "))
                ? parsed.title() : null;
            SessionJournalEntry entry = baseEntry(window);
            entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
            entry.setState(SessionJournalEntry.State.SUMMARIZED);
            entry.setTitle(SessionJournalAiSupport.normalizeTitle(
                title, fallbackTitle(window), TITLE_MAX_CHARS));
            entry.setText(checked.text());
            entry.setMarker(SessionJournalMarker.fromAiCategory(parsed.category()));
            entry.setRunAsUser(runAsUser(window, loginUser, parsed.runAs()));
            entry.setCommands(SessionJournalCommandInfos.from(parsed.commands(), snippetLookup));
            appendWindowEntry(directory, window, entry);
            return true;
        } catch (Exception e) {
            logger.warn("Session journal AI summary failed for {}: {}", directory.getFileName(), e.getMessage());
            return false;
        }
    }

    /** One window prompt and its parsed reply; null when the call failed or the reply was unusable. */
    private SessionJournalAiSupport.SummaryResult askForSummary(Path directory, SessionJournalDocument document,
            Window window, List<String> notes, String earlierEntry, String languageCode) throws Exception {
        String systemPrompt = SessionJournalPrompts.summarySystemPrompt(languageCode);
        String userPrompt = SessionJournalPrompts.summaryUserPrompt(
            document.getMeta().getUsername(),
            document.getMeta().getHost(),
            window.fromTime(),
            window.toTime(),
            window.omittedOutputLines(),
            window.omittedInputLines(),
            notes,
            earlierEntry,
            window.inputLines(),
            window.outputLines());
        AiExecutionResult result = executeWithTimeout(directory, systemPrompt, userPrompt);
        return SessionJournalAiSupport.parseSummaryResult(result != null ? result.content() : null);
    }

    /**
     * A command read in parts: each call gets the next part and the entry so far, and returns the
     * entry for everything up to there. The commands named along the way are kept; a part the model
     * found nothing in leaves the entry as it was.
     */
    private SessionJournalAiSupport.SummaryResult summarizeInParts(Path directory, SessionJournalDocument document,
            Window window, String languageCode) throws Exception {
        SessionJournalAiSupport.SummaryResult soFar = null;
        Map<String, SessionJournalAiSupport.CommandMention> commands = new java.util.LinkedHashMap<>();
        String runAs = null;
        int count = window.parts().size();
        for (int i = 0; i < count; i++) {
            if (i > 0 && invoker().isCancelled()) {
                return null;
            }
            List<String> notes = new ArrayList<>();
            notes.add("This is part " + (i + 1) + " of " + count + " of the output of one long command."
                + (soFar != null ? " Return one entry that covers all parts so far: keep the facts of the"
                    + " earlier entry and add what this part shows." : ""));
            if (i == count - 1) {
                notes.addAll(notesFor(window));
            }
            SessionJournalAiSupport.SummaryResult parsed = askForSummary(directory, document, window.parts().get(i),
                notes, soFar != null ? earlierEntryText(soFar) : null, languageCode);
            if (parsed == null) {
                return null;
            }
            if (parsed.skip()) {
                continue;
            }
            soFar = parsed;
            for (SessionJournalAiSupport.CommandMention command : parsed.commands()) {
                if (command.name() != null) {
                    commands.putIfAbsent(command.name().toLowerCase(java.util.Locale.ROOT), command);
                }
            }
            runAs = runAs != null ? runAs : parsed.runAs();
        }
        if (soFar == null) {
            return SessionJournalAiSupport.SummaryResult.skipped();
        }
        return new SessionJournalAiSupport.SummaryResult(soFar.title(), soFar.summary(), soFar.category(),
            soFar.keywords(), runAs, List.copyOf(commands.values()), false);
    }

    private static String earlierEntryText(SessionJournalAiSupport.SummaryResult entry) {
        return "Title: " + nullToEmpty(entry.title()) + "\nSummary: " + nullToEmpty(entry.summary())
            + "\nCategory: " + nullToEmpty(entry.category());
    }

    /** What the model must know about how {@code window} was cut. */
    private static List<String> notesFor(Window window) {
        List<String> notes = new ArrayList<>();
        if (window.leftOutLines() > 0) {
            notes.add("The excerpt is one command. " + window.leftOutLines() + " lines from the middle of its"
                + " output were left out where marked; the lines that report errors or warnings were kept.");
        }
        if (window.commandState() == SessionJournalCommandState.RUNNING) {
            notes.add("The command was still running when this excerpt was taken. Describe what it has done so"
                + " far; do not say it finished or failed unless the output shows it.");
        } else if (window.commandState() == SessionJournalCommandState.ENDED_WITH_SESSION) {
            notes.add("The session ended before the shell showed its prompt again after the last command."
                + " If its output shows no end, say that it was still running when the session ended.");
        }
        return notes;
    }

    /**
     * Appends the entry for {@code window} and removes the "still running" entries it replaces —
     * of its commands, or the older one of the same running command.
     */
    private void appendWindowEntry(Path directory, Window window, SessionJournalEntry entry) throws IOException {
        entry.setCommandState(window.commandState());
        service.appendEntry(directory, entry);
        dropRunningEntries(directory, window, entry.getId());
    }

    /**
     * The user a window's commands ran as when it is not the login user. The prompts in the output
     * decide first — they are evidence; the model's {@code runAs} only fills in when no prompt
     * was recognised, and only when it names a plausible login name.
     */
    static String runAsUser(Window window, String loginUser, String aiRunAs) {
        // the whole log of the window: a condensed one may have left the prompt after "sudo -i" out
        String fromPrompts = SessionJournalShellPrompts.switchedUser(window.evidence().lines().toList(), loginUser);
        if (fromPrompts != null) {
            return fromPrompts;
        }
        if (aiRunAs == null) {
            return null;
        }
        String candidate = aiRunAs.strip().toLowerCase(java.util.Locale.ROOT);
        String login = loginUser != null ? loginUser.strip().toLowerCase(java.util.Locale.ROOT) : "";
        return SessionJournalShellPrompts.isPlausibleUserName(candidate) && !candidate.equals(login)
            ? candidate
            : null;
    }

    private boolean writeRawEntry(Path directory, Window window, String loginUser) {
        try {
            SessionJournalEntry entry = baseEntry(window);
            entry.setRunAsUser(runAsUser(window, loginUser, null));
            entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
            entry.setState(SessionJournalEntry.State.RAW);
            entry.setTitle(SessionJournalAiSupport.normalizeTitle(
                fallbackTitle(window), i18n("journal.summary.raw.title"), TITLE_MAX_CHARS));
            entry.setText(i18n("journal.summary.raw.text"));
            appendWindowEntry(directory, window, entry);
            return true;
        } catch (Exception e) {
            logger.warn("Session journal raw entry failed for {}: {}", directory.getFileName(), e.getMessage());
            return false;
        }
    }

    private SessionJournalEntry baseEntry(Window window) {
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setLogStartSeq(window.startSeq());
        entry.setLogEndSeq(window.endSeq());
        entry.setInputExcerpt(excerpt(window.inputLines()));
        entry.setOutputExcerpt(excerpt(window.outputLines()));
        return entry;
    }

    private static List<String> excerpt(List<String> lines) {
        List<String> tail = lines.size() > EXCERPT_LINES
            ? lines.subList(lines.size() - EXCERPT_LINES, lines.size())
            : lines;
        List<String> result = new ArrayList<>(tail.size());
        for (String line : tail) {
            result.add(capLine(line, EXCERPT_LINE_CHARS));
        }
        return result;
    }

    private String fallbackTitle(Window window) {
        String candidate = window.firstCommand();
        return candidate != null && !candidate.isBlank() ? candidate : i18n("journal.summary.raw.title");
    }

    private void handleFailure(Path directory, SessionState state) {
        state.consecutiveFailures++;
        state.backoffRemaining = FAILURE_BACKOFF_INTERVALS;
        if (state.consecutiveFailures >= MAX_CONSECUTIVE_FAILURES && !state.failurePlaceholderWritten) {
            state.failurePlaceholderWritten = true;
            try {
                SessionJournalEntry entry = new SessionJournalEntry();
                entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
                entry.setState(SessionJournalEntry.State.FAILED);
                entry.setTitle(i18n("journal.summary.failed.title"));
                entry.setText(i18n("journal.summary.failed.text"));
                service.appendEntry(directory, entry);
            } catch (Exception e) {
                logger.warn("Could not write FAILED placeholder for {}: {}", directory.getFileName(), e.getMessage());
            }
        }
    }

    private AiExecutionResult executeWithTimeout(Path directory, String systemPrompt, String userPrompt)
            throws Exception {
        SessionJournalAiSupport.AiInvoker invoker = invoker();
        Future<AiExecutionResult> future = aiCallExecutor.submit(() -> invoker.execute(systemPrompt, userPrompt));
        AiExecutionResult result;
        try {
            result = future.get(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("AI call exceeded " + AI_CALL_TIMEOUT_SECONDS + "s");
        }
        recordJournalUsage(directory, result);
        return result;
    }

    /** Adds the call's tokens and cost to the journal's totals; never fails the summary. */
    private void recordJournalUsage(Path directory, AiExecutionResult result) {
        if (result == null || result.usage() == null) {
            return;
        }
        try {
            service.addAiUsage(directory, result.usage(), invoker().profile());
        } catch (Exception e) {
            logger.debug("Could not record AI usage for {}: {}", directory.getFileName(), e.getMessage());
        }
    }

    // ==== closing pass ====

    private void runClosePass(Path directory, SessionState state, boolean aiEnabled) {
        try {
            waitUntilClosed(directory);
            if (service.isLive(directory)) {
                // Still recording — a journal found at start-up just as it was created: its own end finishes it.
                logger.info("Session journal {} still records; its closing pass waits for its end", directory.getFileName());
                return;
            }
            synchronized (lockFor(directory)) {
                // a periodic pass of the closed session ends its current window before this one starts
                closePassLocked(directory, state, aiEnabled);
            }
            service.setFinishPending(directory, false);
        } catch (Exception e) {
            logger.warn("Session journal close pass failed for {}: {}", directory.getFileName(), e.getMessage());
        }
    }

    private void closePassLocked(Path directory, SessionState state, boolean aiEnabled) throws Exception {
        service.finalizeAbandoned(directory);
        dropEntriesBeyondProgress(directory);
        GlobalSettings settings = settingsSupplier.get();
        processWindowsLocked(directory, state, aiEnabled, settings, true, null);
        updateCommandCount(directory);

        boolean aiAvailable = aiEnabled
            && (settings == null || settings.isSessionJournalAiSummariesEnabled())
            && invoker().isAvailable();
        if (!aiAvailable || invoker().isCancelled()) {
            return;
        }
        SessionJournalDocument document = service.loadDocument(directory);
        List<String> entryLines = collectEntryLines(document);
        if (!entryLines.isEmpty()) {
            writeSessionSummary(directory, document, entryLines);
        }
        boolean aiTitleWanted = settings != null && settings.isSessionJournalAiTitleEnabled();
        if (aiTitleWanted && !invoker().isCancelled() && titleLooksDefault(document)) {
            writeAiTitle(directory, document, entryLines);
        }
    }

    /**
     * Removes the window entries after the progress marker — a "still running" entry, the raw
     * fallback of a closing pass whose AI failed, an entry written just before korTTY quit and its
     * progress was saved: the pass that follows writes them again.
     */
    private void dropEntriesBeyondProgress(Path directory) throws IOException {
        long done = service.loadDocument(directory).getMeta().getLastSummarizedSeq();
        service.removeEntries(directory, entry -> entry.getKind() == SessionJournalEntryKind.AI_SUMMARY
            && entry.getLogStartSeq() != null && entry.getLogStartSeq() > done);
    }

    /**
     * Counts the commands for the journal header: lines typed at a shell prompt, without mistyped
     * commands, commands that only showed help, and replies to a running program (a password, the
     * "y" for a question).
     */
    private void updateCommandCount(Path directory) {
        try {
            long commands = SessionJournalCommandBlocks.cut(service.readLogAfter(directory, 0)).stream()
                .filter(block -> block.command() != null)
                .filter(block -> contentOf(block).stream().anyMatch(entry -> entry.kind() == SessionJournalLogEntry.Kind.IN))
                .count();
            service.updateCommandCount(directory, commands);
        } catch (IOException e) {
            logger.warn("Could not count the commands of {}: {}", directory.getFileName(), e.getMessage());
        }
    }

    private void waitUntilClosed(Path directory) throws InterruptedException {
        long deadline = System.currentTimeMillis() + CLOSE_WAIT_MILLIS;
        while (service.isLive(directory) && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
    }

    private List<String> collectEntryLines(SessionJournalDocument document) {
        List<String> lines = new ArrayList<>();
        for (SessionJournalEntry entry : document.getEntries()) {
            if (entry.getKind() == SessionJournalEntryKind.SCREENSHOT) {
                // what the screenshots show is evidence too, not only what the log says
                if (entry.hasAiAnalysis()) {
                    StringBuilder shot = new StringBuilder("[Screenshot] ");
                    if (entry.getAiDescription() != null) {
                        shot.append(capAtSentence(entry.getAiDescription().replace('\n', ' '), ENTRY_LINE_MAX_CHARS));
                    }
                    if (!entry.getAiTags().isEmpty()) {
                        shot.append(" (").append(String.join(", ", entry.getAiTags())).append(')');
                    }
                    lines.add(shot.toString());
                }
                continue;
            }
            if (entry.getKind() != SessionJournalEntryKind.AI_SUMMARY
                && entry.getKind() != SessionJournalEntryKind.USER_NOTE) {
                continue;
            }
            StringBuilder sb = new StringBuilder();
            if (entry.getMarker() != SessionJournalMarker.NONE) {
                sb.append('[').append(entry.getMarker()).append("] ");
            }
            if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
                sb.append(entry.getTitle()).append(": ");
            }
            if (entry.getText() != null) {
                // Never cut out of the middle: a model completes a gap with something plausible —
                // "mit `l ... " became an invented checkip.dyndns.org instead of www.heise.de.
                sb.append(capAtSentence(entry.getText().replace('\n', ' '), ENTRY_LINE_MAX_CHARS));
            }
            if (!sb.isEmpty()) {
                lines.add(sb.toString());
            }
            if (lines.size() >= SESSION_SUMMARY_MAX_ENTRIES) {
                break;
            }
        }
        return lines;
    }

    private void writeSessionSummary(Path directory, SessionJournalDocument document, List<String> entryLines) {
        try {
            var meta = document.getMeta();
            Duration duration = meta.getDuration();
            String durationText = duration != null
                ? duration.toHours() + "h " + duration.toMinutesPart() + "m"
                : "?";
            String systemPrompt = SessionJournalPrompts.sessionSummarySystemPrompt(meta.getAppLanguageCode());
            String userPrompt = SessionJournalPrompts.sessionSummaryUserPrompt(
                meta.getUsername(), meta.getHost(), durationText,
                meta.getCommandCount(), meta.getErrorCount(), meta.getScreenshotCount(), entryLines);
            AiExecutionResult result = executeWithTimeout(directory, systemPrompt, userPrompt);
            SessionJournalAiSupport.SummaryResult parsed =
                SessionJournalAiSupport.parseSummaryResult(result != null ? result.content() : null);
            if (parsed == null) {
                return;
            }
            String evidence = keywordCorpus(document) + "\n" + logEvidence(directory);
            SessionJournalFactCheck.Result checked = SessionJournalFactCheck.dropUnverified(parsed.summary(), evidence);
            if (checked.dropped() > 0) {
                logger.info("Dropped {} wrap-up statement(s) naming something {} does not show",
                    checked.dropped(), directory.getFileName());
            }
            if (checked.text().isBlank()) {
                return; // nothing verifiable was left; better no wrap-up than an invented one
            }
            String normalizedEvidence = evidence.toLowerCase(java.util.Locale.ROOT).replaceAll("\\s+", " ");
            SessionJournalEntry entry = new SessionJournalEntry();
            entry.setKind(SessionJournalEntryKind.SESSION_SUMMARY);
            entry.setState(SessionJournalEntry.State.SUMMARIZED);
            entry.setTitle(SessionJournalAiSupport.normalizeTitle(
                SessionJournalFactCheck.verified(nullToEmpty(parsed.title()), normalizedEvidence) ? parsed.title() : null,
                i18n("journal.summary.final.title"), TITLE_MAX_CHARS));
            entry.setText(checked.text() + "\n\n" + closingLine(document));
            entry.setCommands(sessionCommands(document));
            entry.setMarker(SessionJournalMarker.fromAiCategory(parsed.category()));
            // a journal finished again (after a restart, a catch-up) keeps one wrap-up: the new one
            service.removeEntries(directory, old -> old.getKind() == SessionJournalEntryKind.SESSION_SUMMARY);
            service.appendEntry(directory, entry);
            if (!parsed.keywords().isEmpty()) {
                // Identifiers must match the journal text exactly — a model-mangled script name
                // ("serverauslastung.pl" for server_auslastung.pl) is repaired or dropped here.
                // The loaded document predates the entry appended above, so the fresh wrap-up
                // text joins the corpus explicitly — it may be the only place naming a keyword.
                List<String> keywords = SessionJournalAiSupport.reconcileKeywords(
                    parsed.keywords(), keywordCorpus(document) + "\n" + checked.text());
                if (!keywords.isEmpty()) {
                    service.updateAiKeywords(directory, keywords);
                }
            }
        } catch (Exception e) {
            logger.warn("Session journal wrap-up failed for {}: {}", directory.getFileName(), e.getMessage());
        }
    }

    /**
     * The wrap-up's last line — duration and the accounts the session ran under — written by
     * korTTY rather than the model, which can neither measure the one nor see the other reliably.
     */
    static String closingLine(SessionJournalDocument document) {
        var meta = document.getMeta();
        java.util.LinkedHashSet<String> users = new java.util.LinkedHashSet<>();
        if (meta.getUsername() != null && !meta.getUsername().isBlank()) {
            users.add(meta.getUsername().strip());
        }
        for (SessionJournalEntry entry : document.getEntries()) {
            if (entry.getRunAsUser() != null) {
                users.add(entry.getRunAsUser());
            }
        }
        String duration = durationText(meta.getDuration());
        if (users.isEmpty()) {
            return i18n("journal.summary.final.closing.noUser").replace("{0}", duration);
        }
        String key = users.size() == 1 ? "journal.summary.final.closing.one" : "journal.summary.final.closing.many";
        return i18n(key).replace("{0}", duration).replace("{1}", String.join(", ", users));
    }

    /** The command explanations of all window entries, so the wrap-up's commands get tooltips too. */
    private static List<de.kortty.model.SessionJournalCommandInfo> sessionCommands(SessionJournalDocument document) {
        Map<String, de.kortty.model.SessionJournalCommandInfo> byName = new java.util.LinkedHashMap<>();
        for (SessionJournalEntry entry : document.getEntries()) {
            if (entry.getKind() != SessionJournalEntryKind.AI_SUMMARY) {
                continue;
            }
            for (de.kortty.model.SessionJournalCommandInfo command : entry.getCommands()) {
                if (command.getName() != null) {
                    byName.putIfAbsent(command.getName().toLowerCase(java.util.Locale.ROOT),
                        new de.kortty.model.SessionJournalCommandInfo(command));
                }
            }
        }
        return new ArrayList<>(byName.values());
    }

    private static String durationText(Duration duration) {
        long minutes = duration != null ? duration.toMinutes() : 0;
        if (minutes < 1) {
            return i18n("journal.summary.final.duration.underMinute");
        }
        if (minutes < 60) {
            return minutes == 1
                ? i18n("journal.summary.final.duration.minute")
                : i18n("journal.summary.final.duration.minutes").replace("{0}", Long.toString(minutes));
        }
        return i18n("journal.summary.final.duration.hours")
            .replace("{0}", Long.toString(minutes / 60))
            .replace("{1}", Long.toString(minutes % 60));
    }

    /** The whole capture log as text, for checking the wrap-up; empty when it cannot be read. */
    private String logEvidence(Path directory) {
        try {
            return evidenceOf(service.readLogAfter(directory, 0));
        } catch (Exception e) {
            logger.debug("Could not read the log of {} for the fact check: {}", directory.getFileName(), e.getMessage());
            return "";
        }
    }

    private static String nullToEmpty(String value) {
        return value != null ? value : "";
    }

    /** Everything the keyword extraction could legitimately quote from, as one text. */
    private static String keywordCorpus(SessionJournalDocument document) {
        StringBuilder corpus = new StringBuilder(4096);
        for (SessionJournalEntry entry : document.getEntries()) {
            appendCorpusPart(corpus, entry.getTitle());
            appendCorpusPart(corpus, entry.getText());
            appendCorpusPart(corpus, entry.getAiDescription());
            appendCorpusPart(corpus, entry.getUserNote());
            if (entry.getAiTags() != null) {
                for (String tag : entry.getAiTags()) {
                    appendCorpusPart(corpus, tag);
                }
            }
        }
        appendCorpusPart(corpus, document.getMeta().getTitle());
        appendCorpusPart(corpus, document.getMeta().getDescription());
        appendCorpusPart(corpus, document.getMeta().getHost());
        appendCorpusPart(corpus, document.getMeta().getConnectionName());
        return corpus.toString();
    }

    private static void appendCorpusPart(StringBuilder corpus, String value) {
        if (value != null && !value.isBlank()) {
            corpus.append(value).append('\n');
        }
    }

    private void writeAiTitle(Path directory, SessionJournalDocument document, List<String> entryLines) {
        try {
            String systemPrompt = SessionJournalPrompts.titleSystemPrompt(document.getMeta().getAppLanguageCode());
            String userPrompt = SessionJournalPrompts.titleUserPrompt(
                document.getMeta().getConnectionName(), entryLines);
            AiExecutionResult result = executeWithTimeout(directory, systemPrompt, userPrompt);
            String content = result != null ? AiResponseSanitizer.sanitizeForDisplay(result.content()) : null;
            String title = SessionJournalAiSupport.normalizeTitle(content, null, TITLE_MAX_CHARS);
            if (title != null && !title.isBlank()) {
                service.renameJournal(directory, title);
            }
        } catch (Exception e) {
            logger.warn("Session journal AI title failed for {}: {}", directory.getFileName(), e.getMessage());
        }
    }

    /** True while the title still matches the generated "name — yyyy-MM-dd HH:mm" default. */
    private static boolean titleLooksDefault(SessionJournalDocument document) {
        String title = document.getMeta().getTitle();
        String connectionName = document.getMeta().getConnectionName();
        if (title == null || connectionName == null) {
            return true;
        }
        return title.matches(java.util.regex.Pattern.quote(connectionName)
            + " — \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}");
    }

    private static String i18n(String key) {
        try {
            return LanguageManager.getInstance().getString(key);
        } catch (Exception e) {
            return key;
        }
    }

    private static java.util.concurrent.ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
