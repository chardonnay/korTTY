package de.kortty.core;

import de.kortty.model.AutomationJournalConfig;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Deletes automation run journals automatically so they cannot fill the disk: journals whose
 * retention ended, and the oldest runs of a source beyond its maximum number of runs or disk
 * space. Pinned journals, live journals and journals whose closing pass has not finished are
 * never touched; interactive journals are out of scope entirely.
 *
 * <p>A user setting deletes only while the policy allows deleting journals; an administrator
 * cap ({@code automation-max-*}) is enforced regardless.</p>
 */
public final class AutomationJournalRetention {

    private static final Logger logger = LoggerFactory.getLogger(AutomationJournalRetention.class);
    private static final long MB = 1024L * 1024L;
    private static final long FIRST_SWEEP_DELAY_SECONDS = 60;
    private static final long SWEEP_PERIOD_MINUTES = 60;

    /** One run of a source as the limits see it: its journals and their combined size. */
    record RunGroup(String key, OffsetDateTime startedAt, List<SessionJournalMeta> journals, long bytes, boolean pinned) {
    }

    /** Totals of one source's kept journals, for the editors and the manager. */
    public record SourceStats(int runs, int journals, long totalTokens, double cost, String currency, long bytes,
                              SessionJournalMeta newest, int newestRunJournals, long newestRunTokens,
                              double newestRunCost) {
        public static final SourceStats EMPTY = new SourceStats(0, 0, 0, 0.0, null, 0, null, 0, 0, 0.0);
    }

    private final SessionJournalService service;
    private final Supplier<GlobalSettings> settingsSupplier;
    private final BiFunction<SessionJournalSourceKind, String, AutomationJournalConfig> configLookup;
    private final Supplier<AutomationJournalPolicy> policySupplier;
    private final Predicate<Path> pending;
    private final Clock clock;
    private ScheduledExecutorService scheduler;

    /**
     * @param configLookup the current journal settings of a source (null when the source is gone)
     * @param pending      true for journals whose closing pass is still queued or running
     */
    public AutomationJournalRetention(
            SessionJournalService service,
            Supplier<GlobalSettings> settingsSupplier,
            BiFunction<SessionJournalSourceKind, String, AutomationJournalConfig> configLookup,
            Supplier<AutomationJournalPolicy> policySupplier,
            Predicate<Path> pending,
            Clock clock) {
        this.service = Objects.requireNonNull(service, "service");
        this.settingsSupplier = settingsSupplier != null ? settingsSupplier : () -> null;
        this.configLookup = configLookup != null ? configLookup : (kind, id) -> null;
        this.policySupplier = policySupplier != null ? policySupplier : () -> AutomationJournalPolicy.UNRESTRICTED;
        this.pending = pending != null ? pending : path -> false;
        this.clock = clock != null ? clock : Clock.systemDefaultZone();
    }

    /** Sweeps a minute after start and then every hour, on a daemon thread. */
    public synchronized void start() {
        if (scheduler != null) {
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "SessionJournal-Retention");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(this::sweepSafely,
            FIRST_SWEEP_DELAY_SECONDS, SWEEP_PERIOD_MINUTES * 60, TimeUnit.SECONDS);
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    private void sweepSafely() {
        try {
            int deleted = sweep();
            if (deleted > 0) {
                logger.info("Automation journal retention deleted {} journal(s)", deleted);
            }
        } catch (Exception e) {
            logger.warn("Automation journal retention sweep failed: {}", e.getMessage());
        }
    }

    /**
     * Deletes every automation journal whose retention ended, then applies each source's count
     * and storage limits. Returns the number of journals deleted.
     */
    public int sweep() throws java.io.IOException {
        GlobalSettings settings = settingsSupplier.get();
        AutomationJournalPolicy policy = policySupplier.get();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<SessionJournalMeta> automation = service.listJournals(settings).stream()
            .filter(SessionJournalMeta::isAutomation)
            .toList();
        int deleted = 0;
        Map<String, SessionJournalMeta> sources = new LinkedHashMap<>();
        for (SessionJournalMeta meta : automation) {
            sources.putIfAbsent(sourceKey(meta.getEffectiveSourceKind(), meta.getSourceId()), meta);
            if (untouchable(meta) || !isExpired(meta, policy, now)) {
                continue;
            }
            if (delete(settings, meta)) {
                deleted++;
            }
        }
        for (SessionJournalMeta sample : sources.values()) {
            AutomationJournalConfig config = configLookup.apply(sample.getEffectiveSourceKind(), sample.getSourceId());
            deleted += enforceLimits(sample.getEffectiveSourceKind(), sample.getSourceId(), config, settings, policy);
        }
        return deleted;
    }

    /** Applies the count and storage limits of one source right after one of its runs. */
    public void enforceLimits(AutomationJournalRun.Source source, AutomationJournalConfig config) {
        if (source == null) {
            return;
        }
        try {
            enforceLimits(source.kind(), source.id(), config, settingsSupplier.get(), policySupplier.get());
        } catch (Exception e) {
            logger.warn("Could not apply the journal limits of {}: {}", source.name(), e.getMessage());
        }
    }

    int enforceLimits(SessionJournalSourceKind kind, String sourceId, AutomationJournalConfig config,
                      GlobalSettings settings, AutomationJournalPolicy policy) throws java.io.IOException {
        int userRuns = config != null && policy.userDeleteAllowed() ? config.getMaxJournals() : 0;
        int userMb = config != null && policy.userDeleteAllowed() ? config.getMaxStorageMb() : 0;
        int maxRuns = AutomationJournalConfig.effectiveLimit(userRuns, policy.maxJournals());
        long maxBytes = AutomationJournalConfig.effectiveLimit(userMb, policy.maxStorageMb()) * MB;
        if (maxRuns <= 0 && maxBytes <= 0) {
            return 0;
        }
        List<RunGroup> groups = groupsOf(service.listJournals(settings), kind, sourceId);
        List<RunGroup> deletable = new ArrayList<>();
        long totalBytes = 0;
        int unpinnedRuns = 0;
        for (RunGroup group : groups) {
            totalBytes += group.bytes();
            if (!group.pinned()) {
                unpinnedRuns++;
            }
        }
        // newest first: the newest unpinned run is never deleted — the limits make room for it
        boolean newestSeen = false;
        for (RunGroup group : groups) {
            if (group.pinned()) {
                continue;
            }
            if (!newestSeen) {
                newestSeen = true;
                continue;
            }
            deletable.add(group);
        }
        int deleted = 0;
        for (int i = deletable.size() - 1; i >= 0; i--) {
            boolean overCount = maxRuns > 0 && unpinnedRuns > maxRuns;
            boolean overStorage = maxBytes > 0 && totalBytes > maxBytes;
            if (!overCount && !overStorage) {
                break;
            }
            RunGroup group = deletable.get(i);
            boolean all = true;
            for (SessionJournalMeta meta : group.journals()) {
                if (untouchable(meta) || !delete(settings, meta)) {
                    all = false;
                } else {
                    deleted++;
                }
            }
            if (all) {
                unpinnedRuns--;
                totalBytes -= group.bytes();
            }
        }
        if (maxBytes > 0 && totalBytes > maxBytes) {
            logger.info("Journals of {} still use {} MB, above the {} MB limit (pinned or running journals)",
                sourceId, totalBytes / MB, maxBytes / MB);
        }
        return deleted;
    }

    /** Runs of one source, newest first. */
    static List<RunGroup> groupsOf(List<SessionJournalMeta> all, SessionJournalSourceKind kind, String sourceId) {
        Map<String, List<SessionJournalMeta>> byRun = new LinkedHashMap<>();
        for (SessionJournalMeta meta : all) {
            if (meta.getEffectiveSourceKind() != kind || !Objects.equals(meta.getSourceId(), sourceId)) {
                continue;
            }
            String key = meta.getRunId() != null ? meta.getRunId()
                : String.valueOf(meta.getDirectory());
            byRun.computeIfAbsent(key, k -> new ArrayList<>()).add(meta);
        }
        List<RunGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<SessionJournalMeta>> entry : byRun.entrySet()) {
            List<SessionJournalMeta> journals = entry.getValue();
            OffsetDateTime started = journals.stream()
                .map(m -> m.getRunStartedAt() != null ? m.getRunStartedAt() : m.getStartedAt())
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .orElse(null);
            long bytes = journals.stream().mapToLong(AutomationJournalRetention::sizeOf).sum();
            boolean pinned = journals.stream().anyMatch(SessionJournalMeta::isPinned);
            groups.add(new RunGroup(entry.getKey(), started, List.copyOf(journals), bytes, pinned));
        }
        groups.sort(Comparator.comparing(RunGroup::startedAt,
            Comparator.nullsLast(Comparator.<OffsetDateTime>naturalOrder())).reversed());
        return groups;
    }

    /** Totals of one source's journals (tokens, cost, disk space, newest run). */
    public static SourceStats statsFor(List<SessionJournalMeta> all, SessionJournalSourceKind kind, String sourceId) {
        List<RunGroup> groups = groupsOf(all, kind, sourceId);
        if (groups.isEmpty()) {
            return SourceStats.EMPTY;
        }
        int journals = 0;
        long tokens = 0;
        double cost = 0.0;
        String currency = null;
        long bytes = 0;
        for (RunGroup group : groups) {
            bytes += group.bytes();
            for (SessionJournalMeta meta : group.journals()) {
                journals++;
                tokens += meta.getAiTotalTokens();
                cost += meta.getAiCost();
                if (currency == null) {
                    currency = meta.getAiCostCurrency();
                }
            }
        }
        RunGroup newest = groups.get(0);
        long newestTokens = newest.journals().stream().mapToLong(SessionJournalMeta::getAiTotalTokens).sum();
        double newestCost = newest.journals().stream().mapToDouble(SessionJournalMeta::getAiCost).sum();
        return new SourceStats(groups.size(), journals, tokens, cost, currency, bytes,
            newest.journals().get(0), newest.journals().size(), newestTokens, newestCost);
    }

    private boolean isExpired(SessionJournalMeta meta, AutomationJournalPolicy policy, OffsetDateTime now) {
        OffsetDateTime reference = meta.getEndedAt() != null ? meta.getEndedAt() : meta.getStartedAt();
        if (reference == null) {
            return false;
        }
        Integer cap = policy.maxRetentionDays();
        if (cap != null && cap > 0 && now.isAfter(reference.plusDays(cap))) {
            return true; // administrator cap: enforced whatever the user or the policy's delete flag says
        }
        if (!policy.userDeleteAllowed()) {
            return false;
        }
        OffsetDateTime expiresAt = meta.getExpiresAt();
        if (expiresAt == null && meta.getRunStatus() == null) {
            // crashed or unfinished run: derive the expiry from the source's current settings
            AutomationJournalConfig config = configLookup.apply(meta.getEffectiveSourceKind(), meta.getSourceId());
            expiresAt = config != null
                ? config.computeExpiry(reference, cap)
                : reference.plusDays(AutomationJournalConfig.DEFAULT_RETENTION_DAYS);
        }
        return expiresAt != null && now.isAfter(expiresAt);
    }

    private boolean untouchable(SessionJournalMeta meta) {
        return meta.isLive() || meta.isPinned() || meta.getDirectory() == null || pending.test(meta.getDirectory());
    }

    private boolean delete(GlobalSettings settings, SessionJournalMeta meta) {
        try {
            service.deleteAutomationJournal(settings, meta.getDirectory());
            return true;
        } catch (Exception e) {
            logger.warn("Could not delete automation journal {}: {}", meta.getDirectory().getFileName(), e.getMessage());
            return false;
        }
    }

    private static long sizeOf(SessionJournalMeta meta) {
        if (meta.getStorageBytes() > 0) {
            return meta.getStorageBytes();
        }
        try {
            return SessionJournalService.directorySize(meta.getDirectory());
        } catch (java.io.IOException e) {
            return 0L;
        }
    }

    private static String sourceKey(SessionJournalSourceKind kind, String sourceId) {
        return kind + "|" + sourceId;
    }
}
