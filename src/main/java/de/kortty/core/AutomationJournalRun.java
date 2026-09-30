package de.kortty.core;

import de.kortty.KorTTYApplication;
import de.kortty.model.AutomationJournalConfig;
import de.kortty.model.AutomationRunStatus;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalConfig;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The session journals of one automation run (a JobScheduler job run or an AI Swarm run): one
 * journal per target server, created lazily when the run first touches that server, and decided
 * on in {@link #finish} once the outcome is known — kept, discarded (keep mode), replaced by a
 * reference to an identical earlier run (duplicates), and summarized or not (AI mode).
 *
 * <p>{@link #NONE} is the disabled run: every call is a no-op, so callers never branch on
 * whether journals are on.</p>
 */
public final class AutomationJournalRun {

    private static final Logger logger = LoggerFactory.getLogger(AutomationJournalRun.class);
    private static final DateTimeFormatter TITLE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** What recorded the run. {@code action} is e.g. {@code COMMAND} or {@code AI_SWARM}. */
    public record Source(SessionJournalSourceKind kind, String id, String name, String action) {
        public Source {
            Objects.requireNonNull(kind, "kind");
        }
    }

    /**
     * What {@link #finish} decided.
     *
     * @param kept           journals kept (one per target that was kept)
     * @param duplicateOf    earlier journals this run's discarded duplicates refer to
     * @param discarded      journals discarded because of the keep mode
     * @param summariesDone  completes when every kept journal's closing pass has finished
     */
    public record FinishResult(List<Path> kept, List<Path> duplicateOf, int discarded,
                               CompletableFuture<Void> summariesDone) {
        public static final FinishResult EMPTY =
            new FinishResult(List.of(), List.of(), 0, CompletableFuture.completedFuture(null));
    }

    /** The run without journals. */
    public static final AutomationJournalRun NONE = new AutomationJournalRun();

    private final Source source;
    private final String runId;
    private final AutomationJournalConfig config;
    private final SessionJournalService service;
    private final SessionJournalSummarizer summarizer;
    private final Supplier<GlobalSettings> settingsSupplier;
    private final Function<String, SessionJournalAiSupport.AiInvoker> invokerFactory;
    private final AutomationJournalPolicy policy;
    private final BiConsumer<Source, AutomationJournalConfig> afterSummaries;
    private final Clock clock;
    private final OffsetDateTime startedAt;
    private final Map<String, AutomationJournalRecorder> recorders = new LinkedHashMap<>();
    private boolean finished;

    private AutomationJournalRun() {
        this.source = null;
        this.runId = null;
        this.config = null;
        this.service = null;
        this.summarizer = null;
        this.settingsSupplier = () -> null;
        this.invokerFactory = id -> null;
        this.policy = AutomationJournalPolicy.UNRESTRICTED;
        this.afterSummaries = (s, c) -> { };
        this.clock = Clock.systemDefaultZone();
        this.startedAt = null;
    }

    AutomationJournalRun(
            Source source,
            String runId,
            AutomationJournalConfig config,
            SessionJournalService service,
            SessionJournalSummarizer summarizer,
            Supplier<GlobalSettings> settingsSupplier,
            Function<String, SessionJournalAiSupport.AiInvoker> invokerFactory,
            AutomationJournalPolicy policy,
            BiConsumer<Source, AutomationJournalConfig> afterSummaries,
            Clock clock) {
        this.source = Objects.requireNonNull(source, "source");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.config = new AutomationJournalConfig(config);
        this.service = Objects.requireNonNull(service, "service");
        this.summarizer = summarizer;
        this.settingsSupplier = settingsSupplier != null ? settingsSupplier : () -> null;
        this.invokerFactory = invokerFactory != null ? invokerFactory : id -> null;
        this.policy = policy != null ? policy : AutomationJournalPolicy.UNRESTRICTED;
        this.afterSummaries = afterSummaries != null ? afterSummaries : (s, c) -> { };
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
        this.startedAt = OffsetDateTime.now(this.clock);
    }

    /**
     * Starts the journals of a run with the application's services, or returns {@link #NONE}
     * when the source has journals off, the policy forbids them, or the application is absent.
     */
    public static AutomationJournalRun begin(Source source, String runId, AutomationJournalConfig config) {
        if (source == null || runId == null || config == null || !config.isEnabled()) {
            return NONE;
        }
        AutomationJournalPolicy policy = AutomationJournalPolicy.current();
        if (!policy.allowed()) {
            return NONE;
        }
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getSessionJournalService() == null) {
            return NONE;
        }
        AutomationJournalRetention retention = app.getAutomationJournalRetention();
        return new AutomationJournalRun(
            source,
            runId,
            config,
            app.getSessionJournalService(),
            app.getSessionJournalSummarizer(),
            () -> app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null,
            SessionJournalAiSupport::automationInvoker,
            policy,
            retention != null ? retention::enforceLimits : null,
            Clock.systemDefaultZone());
    }

    /** True when this run records journals. */
    public boolean isActive() {
        return service != null;
    }

    public Source source() {
        return source;
    }

    public String runId() {
        return runId;
    }

    /**
     * The recorder for one target, creating and starting its journal on first use. Returns
     * {@link AutomationJournalRecorder#NOOP} when the run is disabled or the journal could not be
     * created — capture is best-effort.
     */
    public synchronized AutomationJournalRecorder recorderFor(ServerConnection connection) {
        if (!isActive() || finished || connection == null) {
            return AutomationJournalRecorder.NOOP;
        }
        String key = connection.getId() != null ? connection.getId() : connection.getDisplayName();
        AutomationJournalRecorder existing = recorders.get(key);
        if (existing != null) {
            return existing;
        }
        try {
            SessionJournalConfig capture = new SessionJournalConfig();
            capture.setEnabled(true);
            capture.setCaptureInput(config.isCaptureInput());
            capture.setAiSummariesEnabled(true);
            String sessionId = "run" + runId.replace("-", "");
            SessionJournalSession session = service.createSession(
                connection, sessionId, settingsSupplier.get(), List.of(), false, capture,
                meta -> describe(meta, connection));
            session.start();
            AutomationJournalRecorder recorder = new AutomationJournalRecorder(session, service, connection.getId());
            recorders.put(key, recorder);
            return recorder;
        } catch (Exception e) {
            logger.warn("Could not start the session journal for {} of {}: {}",
                connection.getHost(), source.name(), e.getMessage());
            recorders.put(key, AutomationJournalRecorder.NOOP);
            return AutomationJournalRecorder.NOOP;
        }
    }

    private void describe(SessionJournalMeta meta, ServerConnection connection) {
        meta.setSourceKind(source.kind());
        meta.setSourceId(source.id());
        meta.setSourceName(source.name());
        meta.setRunId(runId);
        meta.setRunStartedAt(startedAt);
        meta.setAutomationAction(source.action());
        meta.setAiMode(config.getAiMode());
        String target = connection.getDisplayName() != null ? connection.getDisplayName() : connection.getHost();
        String key = source.kind() == SessionJournalSourceKind.SWARM
            ? "journal.automation.title.swarm"
            : "journal.automation.title.job";
        meta.setTitle(i18n(key, source.kind() == SessionJournalSourceKind.SWARM ? "AI Swarm {0} · {1}" : "Job {0} · {1}",
            source.name() != null ? source.name() : "", target) + " · " + startedAt.format(TITLE_TIME));
    }

    /**
     * Closes every journal of the run and decides its fate from the run's outcome. Returns quickly:
     * the AI summaries run afterwards on the summarizer's automation worker.
     *
     * @param runStatus the outcome of the whole run; a target's own status (see
     *                  {@link AutomationJournalRecorder#setTargetStatus}) wins unless the run was cancelled
     */
    public FinishResult finish(AutomationRunStatus runStatus) {
        List<AutomationJournalRecorder> toFinish;
        synchronized (this) {
            if (!isActive() || finished) {
                return FinishResult.EMPTY;
            }
            finished = true;
            toFinish = new ArrayList<>(recorders.values());
        }
        List<Path> kept = new ArrayList<>();
        List<Path> duplicateOf = new ArrayList<>();
        List<CompletableFuture<Void>> summaries = new ArrayList<>();
        int discarded = 0;
        GlobalSettings settings = settingsSupplier.get();
        for (AutomationJournalRecorder recorder : toFinish) {
            if (!recorder.isRecording()) {
                continue;
            }
            Path directory = recorder.directory();
            try {
                AutomationRunStatus status = effectiveStatus(runStatus, recorder.getTargetStatus());
                recorder.session().close();
                OffsetDateTime now = OffsetDateTime.now(clock);

                if (!config.getKeepMode().keeps(status)) {
                    if (deleteForUserSetting(settings, directory, "keep mode")) {
                        discarded++;
                        continue;
                    }
                }
                String hash = recorder.contentHash();
                if (config.isDedupEnabled() && hash != null && policy.userDeleteAllowed()) {
                    Path reference = findIdenticalPreviousRun(settings, directory, recorder.connectionId(), status, hash);
                    if (reference != null && deleteForUserSetting(settings, directory, "duplicate")) {
                        service.recordDuplicateRun(reference, now, config.computeExpiry(now, policy.maxRetentionDays()));
                        duplicateOf.add(reference);
                        continue;
                    }
                }
                service.updateAutomationOutcome(
                    directory, status, config.computeExpiry(now, policy.maxRetentionDays()), hash);
                kept.add(directory);
                summaries.add(closingPass(directory, status));
            } catch (Exception e) {
                logger.warn("Could not finish the session journal {}: {}",
                    directory != null ? directory.getFileName() : "?", e.getMessage());
            }
        }
        CompletableFuture<Void> all = CompletableFuture
            .allOf(summaries.toArray(CompletableFuture[]::new))
            .handle((ignored, error) -> null);
        CompletableFuture<Void> done = all.thenRun(() -> {
            try {
                afterSummaries.accept(source, config);
            } catch (RuntimeException e) {
                logger.warn("Could not enforce the journal limits of {}: {}", source.name(), e.getMessage());
            }
        });
        return new FinishResult(List.copyOf(kept), List.copyOf(duplicateOf), discarded, done);
    }

    /** Raw entries always; AI summaries when the AI mode asks for them and AI is allowed. */
    private CompletableFuture<Void> closingPass(Path directory, AutomationRunStatus status) {
        boolean aiEnabled = policy.aiAllowed() && config.getAiMode().summarizes(status);
        CompletableFuture<Void> pass = summarizer != null
            ? summarizer.summarizeClosedJournal(directory, invokerFactory.apply(config.getAiProfileId()), aiEnabled)
            : CompletableFuture.completedFuture(null);
        return pass.handle((ignored, error) -> {
            if (error != null) {
                logger.warn("Closing pass of {} failed: {}", directory.getFileName(), error.getMessage());
            }
            try {
                service.updateStorageBytes(directory);
            } catch (Exception e) {
                logger.debug("Could not measure {}: {}", directory.getFileName(), e.getMessage());
            }
            return null;
        });
    }

    private boolean deleteForUserSetting(GlobalSettings settings, Path directory, String reason) {
        if (!policy.userDeleteAllowed()) {
            logger.info("Keeping session journal {} ({}): deleting journals is disabled by policy",
                directory.getFileName(), reason);
            return false;
        }
        try {
            service.deleteAutomationJournal(settings, directory);
            return true;
        } catch (Exception e) {
            logger.warn("Could not discard session journal {} ({}): {}", directory.getFileName(), reason, e.getMessage());
            return false;
        }
    }

    /**
     * The most recent earlier kept journal of the same source and server, when it recorded the
     * same outcome and exactly the same output; otherwise null.
     */
    private Path findIdenticalPreviousRun(
            GlobalSettings settings, Path current, String connectionId, AutomationRunStatus status, String hash)
            throws java.io.IOException {
        Path currentKey = current.toAbsolutePath().normalize();
        SessionJournalMeta previous = service.listJournals(settings).stream()
            .filter(meta -> meta.getDirectory() != null
                && !meta.getDirectory().toAbsolutePath().normalize().equals(currentKey))
            .filter(meta -> meta.getEffectiveSourceKind() == source.kind()
                && Objects.equals(meta.getSourceId(), source.id())
                && Objects.equals(meta.getConnectionId(), connectionId)
                && meta.getRunStatus() != null
                && !Objects.equals(meta.getRunId(), runId))
            .max(Comparator.comparing(SessionJournalMeta::getStartedAt,
                Comparator.nullsFirst(Comparator.naturalOrder())))
            .orElse(null);
        if (previous == null || previous.getRunStatus() != status || !hash.equals(previous.getContentHash())) {
            return null;
        }
        return previous.getDirectory();
    }

    static AutomationRunStatus effectiveStatus(AutomationRunStatus runStatus, AutomationRunStatus targetStatus) {
        AutomationRunStatus run = runStatus != null ? runStatus : AutomationRunStatus.FAILED;
        if (run == AutomationRunStatus.CANCELLED) {
            return run;
        }
        return targetStatus != null ? targetStatus : run;
    }

    private static String i18n(String key, String fallback, Object... args) {
        String value;
        try {
            value = de.kortty.ui.I18n.get(key, args);
        } catch (RuntimeException e) {
            value = null;
        }
        if (value == null || value.equals(key)) {
            return java.text.MessageFormat.format(fallback, args);
        }
        return value;
    }
}
