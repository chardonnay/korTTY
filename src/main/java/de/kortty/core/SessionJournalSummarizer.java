package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import java.util.function.Supplier;

/**
 * Periodic AI summarization of session journals: windows the capture log since the last
 * summarized sequence (configurable line limit, 0 = fill the model context via a token budget,
 * optional chunked processing of the whole backlog), asks the AI for a compact journal entry,
 * and appends it to the journal document. Runs a closing pass (final window, session wrap-up,
 * optional AI title) when a session ends.
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
    private static final String HIDDEN_INPUT_MARKER = "(hidden input)";
    private static final String SCREENSHOT_MARKER = "[screenshot attached]";

    private final SessionJournalService service;
    private final Supplier<GlobalSettings> settingsSupplier;
    private final SessionJournalAiSupport.AiInvoker aiInvoker;
    private final Map<SessionJournalSession, SessionState> sessions = new ConcurrentHashMap<>();
    private final AtomicBoolean tickRunning = new AtomicBoolean();
    private final ExecutorService aiCallExecutor = Executors.newSingleThreadExecutor(
        daemonThreadFactory("SessionJournal-AiCall"));
    private final ExecutorService workExecutor = Executors.newSingleThreadExecutor(
        daemonThreadFactory("SessionJournal-Summarizer"));
    private ScheduledExecutorService scheduler;

    /** Per-session summarization progress; {@code lastSummarizedSeq < 0} means "load from doc". */
    private static final class SessionState {
        volatile long lastSummarizedSeq = -1;
        volatile long lastProcessedMillis = System.currentTimeMillis();
        volatile int consecutiveFailures;
        volatile int backoffRemaining;
        volatile boolean failurePlaceholderWritten;
        final AtomicBoolean busy = new AtomicBoolean();
    }

    private record Window(
        long startSeq,
        long endSeq,
        OffsetDateTime fromTime,
        OffsetDateTime toTime,
        List<String> inputLines,
        List<String> outputLines,
        int omittedInputLines,
        int omittedOutputLines,
        String firstCommand) {
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
        final SessionState closeState = state != null ? state : new SessionState();
        final Path directory = session.getDirectory();
        final boolean aiEnabled = session.isAiSummariesEnabled();
        workExecutor.submit(() -> runClosePass(directory, closeState, aiEnabled));
    }

    /** Manual "summarize now" for a live session; completes when the pass finished. */
    public CompletableFuture<Void> summarizeNow(SessionJournalSession session) {
        SessionState state = sessions.get(session);
        if (state == null) {
            return CompletableFuture.completedFuture(null);
        }
        return CompletableFuture.runAsync(() -> maybeProcess(session, state, true), workExecutor);
    }

    /** Stops all executors on application shutdown. */
    public synchronized void stop() {
        sessions.clear();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        workExecutor.shutdown();
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
     * Reads everything after the last summarized sequence, cuts it into one (newest window) or
     * many (chunked mode) windows and turns each into a journal entry. Progress advances only on
     * success, so failed windows retry on the next pass.
     */
    private void processWindows(
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
        List<SessionJournalLogEntry> content = filterContentEntries(newEntries);
        if (content.isEmpty()) {
            return;
        }
        if (!finalPass && liveSessionOrNull != null
                && content.size() < MIN_NEW_LINES
                && System.currentTimeMillis() - liveSessionOrNull.getLastActivityMillis() < MIN_QUIET_MILLIS) {
            return; // a half-typed command is not worth a journal entry yet
        }

        int maxLines = settings != null ? settings.getSessionJournalAiMaxLines() : 100;
        int tokenBudget = settings != null ? settings.getSessionJournalAiTokenBudget() : 130_000;
        boolean chunked = settings != null && settings.isSessionJournalAiChunkingEnabled();
        boolean aiAvailable = sessionAiEnabled
            && (settings == null || settings.isSessionJournalAiSummariesEnabled())
            && aiInvoker.isAvailable();
        String languageCode = document.getMeta().getAppLanguageCode();

        List<Window> windows = chunked
            ? buildChunkedWindows(content, maxLines, tokenBudget)
            : List.of(buildNewestWindow(content, maxLines, tokenBudget));
        for (Window window : windows) {
            boolean ok = aiAvailable
                ? summarizeWindowWithAi(directory, document, window, languageCode)
                : writeRawEntry(directory, window);
            if (ok) {
                state.lastSummarizedSeq = window.endSeq();
                state.consecutiveFailures = 0;
                state.failurePlaceholderWritten = false;
                service.updateLastSummarizedSeq(directory, window.endSeq());
            } else {
                handleFailure(directory, state);
                break;
            }
        }
    }

    /** OUT/SEED/IN/SCREENSHOT entries that carry journal-relevant content; NOTEs are skipped. */
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
                    // pipeline markers are not session content
                }
            }
        }
        return result;
    }

    /**
     * Newest-window mode: the entry covers the FULL new range, but only the newest lines (per
     * stream, capped by line limit and token budget) reach the prompt; older overflow is noted.
     */
    private Window buildNewestWindow(List<SessionJournalLogEntry> content, int maxLines, int tokenBudget) {
        int effectiveBudget = promptTokenBudget(tokenBudget);
        List<String> inputReversed = new ArrayList<>();
        List<String> outputReversed = new ArrayList<>();
        int totalInput = 0;
        int totalOutput = 0;
        int usedTokens = 0;
        for (int i = content.size() - 1; i >= 0; i--) {
            SessionJournalLogEntry entry = content.get(i);
            boolean isInput = entry.kind() == SessionJournalLogEntry.Kind.IN;
            if (isInput) {
                totalInput++;
            } else {
                totalOutput++;
            }
            String line = renderLine(entry);
            int lineTokens = AiTokenCounter.estimateTokens(line);
            List<String> target = isInput ? inputReversed : outputReversed;
            boolean lineLimitReached = maxLines > 0 && target.size() >= maxLines;
            boolean budgetReached = usedTokens + lineTokens > effectiveBudget;
            if (lineLimitReached || budgetReached) {
                continue; // keep counting omitted lines but stop collecting for this stream
            }
            target.add(line);
            usedTokens += lineTokens;
        }
        List<String> inputLines = reversed(inputReversed);
        List<String> outputLines = reversed(outputReversed);
        return new Window(
            content.get(0).seq(),
            content.get(content.size() - 1).seq(),
            content.get(0).timestamp(),
            content.get(content.size() - 1).timestamp(),
            inputLines,
            outputLines,
            totalInput - inputLines.size(),
            totalOutput - outputLines.size(),
            firstCommandOf(inputLines, outputLines));
    }

    /**
     * Chunked mode (power users): the whole backlog is cut into consecutive windows of the
     * configured size and each window becomes its own prompt and entry — nothing is dropped.
     */
    private List<Window> buildChunkedWindows(List<SessionJournalLogEntry> content, int maxLines, int tokenBudget) {
        int effectiveBudget = promptTokenBudget(tokenBudget);
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
            boolean budgetReached = !current.isEmpty() && usedTokens + lineTokens > effectiveBudget;
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
        for (SessionJournalLogEntry entry : entries) {
            if (entry.kind() == SessionJournalLogEntry.Kind.IN) {
                inputLines.add(renderLine(entry));
            } else {
                outputLines.add(renderLine(entry));
            }
        }
        return new Window(
            entries.get(0).seq(),
            entries.get(entries.size() - 1).seq(),
            entries.get(0).timestamp(),
            entries.get(entries.size() - 1).timestamp(),
            inputLines,
            outputLines,
            0,
            0,
            firstCommandOf(inputLines, outputLines));
    }

    /** Reserve a quarter of the budget for prompt scaffolding and the model's reply. */
    private static int promptTokenBudget(int tokenBudget) {
        return Math.max(500, tokenBudget - tokenBudget / 4);
    }

    private static String renderLine(SessionJournalLogEntry entry) {
        if (entry.kind() == SessionJournalLogEntry.Kind.SCREENSHOT) {
            return SCREENSHOT_MARKER;
        }
        if (entry.redacted()) {
            return HIDDEN_INPUT_MARKER;
        }
        return capLine(entry.text() != null ? entry.text() : "", LINE_CHAR_CAP);
    }

    private static String capLine(String line, int maxChars) {
        if (line.length() <= maxChars) {
            return line;
        }
        int half = (maxChars - 5) / 2;
        return line.substring(0, half) + " ... " + line.substring(line.length() - half);
    }

    private static List<String> reversed(List<String> list) {
        List<String> result = new ArrayList<>(list);
        java.util.Collections.reverse(result);
        return result;
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
            String systemPrompt = SessionJournalPrompts.summarySystemPrompt(languageCode);
            String userPrompt = SessionJournalPrompts.summaryUserPrompt(
                document.getMeta().getUsername(),
                document.getMeta().getHost(),
                window.fromTime(),
                window.toTime(),
                window.omittedOutputLines(),
                window.omittedInputLines(),
                window.inputLines(),
                window.outputLines());
            AiExecutionResult result = executeWithTimeout(systemPrompt, userPrompt);
            SessionJournalAiSupport.SummaryResult parsed =
                SessionJournalAiSupport.parseSummaryResult(result != null ? result.content() : null);
            if (parsed == null) {
                return false;
            }
            SessionJournalEntry entry = baseEntry(window);
            entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
            entry.setState(SessionJournalEntry.State.SUMMARIZED);
            entry.setTitle(SessionJournalAiSupport.normalizeTitle(
                parsed.title(), fallbackTitle(window), TITLE_MAX_CHARS));
            entry.setText(parsed.summary());
            entry.setMarker(SessionJournalMarker.fromAiCategory(parsed.category()));
            service.appendEntry(directory, entry);
            return true;
        } catch (Exception e) {
            logger.warn("Session journal AI summary failed for {}: {}", directory.getFileName(), e.getMessage());
            return false;
        }
    }

    private boolean writeRawEntry(Path directory, Window window) {
        try {
            SessionJournalEntry entry = baseEntry(window);
            entry.setKind(SessionJournalEntryKind.AI_SUMMARY);
            entry.setState(SessionJournalEntry.State.RAW);
            entry.setTitle(SessionJournalAiSupport.normalizeTitle(
                fallbackTitle(window), i18n("journal.summary.raw.title"), TITLE_MAX_CHARS));
            entry.setText(i18n("journal.summary.raw.text"));
            service.appendEntry(directory, entry);
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

    private AiExecutionResult executeWithTimeout(String systemPrompt, String userPrompt) throws Exception {
        Future<AiExecutionResult> future = aiCallExecutor.submit(() -> aiInvoker.execute(systemPrompt, userPrompt));
        try {
            return future.get(AI_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("AI call exceeded " + AI_CALL_TIMEOUT_SECONDS + "s");
        }
    }

    // ==== closing pass ====

    private void runClosePass(Path directory, SessionState state, boolean aiEnabled) {
        try {
            waitUntilClosed(directory);
            GlobalSettings settings = settingsSupplier.get();
            processWindows(directory, state, aiEnabled, settings, true, null);

            boolean aiAvailable = aiEnabled
                && (settings == null || settings.isSessionJournalAiSummariesEnabled())
                && aiInvoker.isAvailable();
            if (!aiAvailable) {
                return;
            }
            SessionJournalDocument document = service.loadDocument(directory);
            List<String> entryLines = collectEntryLines(document);
            if (!entryLines.isEmpty()) {
                writeSessionSummary(directory, document, entryLines);
            }
            boolean aiTitleWanted = settings != null && settings.isSessionJournalAiTitleEnabled();
            if (aiTitleWanted && titleLooksDefault(document)) {
                writeAiTitle(directory, document, entryLines);
            }
        } catch (Exception e) {
            logger.warn("Session journal close pass failed for {}: {}", directory.getFileName(), e.getMessage());
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
                sb.append(capLine(entry.getText().replace('\n', ' '), 300));
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
            AiExecutionResult result = executeWithTimeout(systemPrompt, userPrompt);
            SessionJournalAiSupport.SummaryResult parsed =
                SessionJournalAiSupport.parseSummaryResult(result != null ? result.content() : null);
            if (parsed == null) {
                return;
            }
            SessionJournalEntry entry = new SessionJournalEntry();
            entry.setKind(SessionJournalEntryKind.SESSION_SUMMARY);
            entry.setState(SessionJournalEntry.State.SUMMARIZED);
            entry.setTitle(SessionJournalAiSupport.normalizeTitle(
                parsed.title(), i18n("journal.summary.final.title"), TITLE_MAX_CHARS));
            entry.setText(parsed.summary());
            entry.setMarker(SessionJournalMarker.fromAiCategory(parsed.category()));
            service.appendEntry(directory, entry);
        } catch (Exception e) {
            logger.warn("Session journal wrap-up failed for {}: {}", directory.getFileName(), e.getMessage());
        }
    }

    private void writeAiTitle(Path directory, SessionJournalDocument document, List<String> entryLines) {
        try {
            String systemPrompt = SessionJournalPrompts.titleSystemPrompt(document.getMeta().getAppLanguageCode());
            String userPrompt = SessionJournalPrompts.titleUserPrompt(
                document.getMeta().getConnectionName(), entryLines);
            AiExecutionResult result = executeWithTimeout(systemPrompt, userPrompt);
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
