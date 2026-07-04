package de.kortty.telemetry;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Anonymous usage statistics (Aptabase, EU region). Strictly opt-in: while
 * disabled, nothing is created, queued, or sent. All network I/O runs on the
 * single daemon worker thread; {@link #trackEvent} only builds and enqueues.
 */
public final class TelemetryService {

    public static final int CURRENT_CONSENT_VERSION = 1;

    static final int MAX_QUEUE_SIZE = 200;
    static final long FLUSH_INTERVAL_SECONDS = 15;
    static final int MAX_ERROR_SIGNATURES_PER_RUN = 20;
    static final String RUN_MARKER_FILE = "telemetry-run.marker";
    static final String SPOOL_FILE = "telemetry-spool.json";
    /** Events older than this are dropped rather than sent — bounds the offline backlog. */
    static final Duration MAX_EVENT_AGE = Duration.ofHours(72);

    private static final Logger logger = LoggerFactory.getLogger(TelemetryService.class);

    private final GlobalSettingsManager settingsManager;
    private final Path configDir;
    private final AptabaseClient client;
    private final Clock clock;
    private final TelemetrySession session;
    private final TelemetrySpool spool;

    private final ConcurrentLinkedQueue<TelemetryEvent> queue = new ConcurrentLinkedQueue<>();
    private final AtomicInteger queueSize = new AtomicInteger();
    private final AtomicBoolean extraFlushPending = new AtomicBoolean();
    private final Set<String> errorSignatures = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean active = new AtomicBoolean();

    private volatile SystemProps systemProps;
    private volatile ScheduledExecutorService scheduler;
    private volatile TelemetryLogAppender logAppender;
    private volatile boolean runMarkerOwned;

    public TelemetryService(GlobalSettingsManager settingsManager, Path configDir) {
        this(settingsManager, configDir, new AptabaseClient(), Clock.systemUTC());
    }

    TelemetryService(GlobalSettingsManager settingsManager, Path configDir, AptabaseClient client, Clock clock) {
        this.settingsManager = settingsManager;
        this.configDir = configDir;
        this.client = client;
        this.clock = clock;
        this.session = new TelemetrySession(clock);
        this.spool = new TelemetrySpool(configDir);
    }

    // ------------------------------------------------------------------
    // Lifecycle (KorTTYApplication only)
    // ------------------------------------------------------------------

    /** No-op unless the user has opted in. Safe to call more than once. */
    public synchronized void start() {
        if (active.get() || !isEnabled()) {
            return;
        }
        activate();
    }

    /** Applies the current consent state: enables or disables the pipeline. */
    public synchronized void applyEnabledState() {
        boolean enabled = isEnabled();
        if (enabled && !active.get()) {
            activate();
        } else if (!enabled && active.get()) {
            deactivate();
        }
    }

    /**
     * Re-attaches the error appender after a Logback {@code context.reset()}
     * (runs at startup and on every settings save).
     */
    public void onLoggingReconfigured() {
        TelemetryLogAppender appender = logAppender;
        if (active.get() && appender != null) {
            appender.attachToRootLogger();
        }
    }

    /** Bounded final flush; called from performShutdown() inside the watchdog budget. */
    public synchronized void shutdown(Duration timeout) {
        ScheduledExecutorService currentScheduler = scheduler;
        if (active.get() && currentScheduler != null) {
            try {
                currentScheduler.submit(this::flushSafely).get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                logger.debug("Telemetry final flush did not complete: {}", e.toString());
            }
        }
        active.set(false);
        TelemetryLogAppender.detachFromRootLogger();
        logAppender = null;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
        scheduler = null;
        deleteRunMarker();
    }

    private void activate() {
        systemProps = SystemProps.detect();
        active.set(true);
        TelemetryLogAppender appender = new TelemetryLogAppender(this);
        appender.attachToRootLogger();
        logAppender = appender;
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-telemetry");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.scheduleWithFixedDelay(
            this::flushSafely, FLUSH_INTERVAL_SECONDS, FLUSH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        String crashedVersion = consumeCrashMarker();
        writeRunMarker();
        // Re-enqueue any events a previous offline session persisted, so they are sent now.
        enqueueSpooledEvents();
        trackEvent(TelemetryEvents.APP_STARTED);
        if (crashedVersion != null) {
            trackEvent(TelemetryEvents.APP_CRASH_DETECTED, Map.of("crashed_version", crashedVersion));
        }
    }

    /** Loads persisted (offline) events from the spool into the queue, skipping stale ones. */
    private void enqueueSpooledEvents() {
        List<TelemetryEvent> spooled = spool.readAndDelete();
        int restored = 0;
        for (TelemetryEvent event : spooled) {
            if (isExpired(event) || queueSize.get() >= MAX_QUEUE_SIZE) {
                continue;
            }
            queue.add(event);
            queueSize.incrementAndGet();
            restored++;
        }
        if (restored > 0) {
            logger.debug("Telemetry: restored {} spooled event(s) from a previous offline session", restored);
        }
    }

    private void deactivate() {
        active.set(false);
        TelemetryLogAppender.detachFromRootLogger();
        logAppender = null;
        ScheduledExecutorService currentScheduler = scheduler;
        scheduler = null;
        if (currentScheduler != null) {
            currentScheduler.shutdownNow();
        }
        queue.clear();
        queueSize.set(0);
        // Opting out discards everything, including any offline backlog on disk.
        spool.delete();
        deleteRunMarker();
    }

    // ------------------------------------------------------------------
    // Tracking
    // ------------------------------------------------------------------

    public void trackEvent(String eventName) {
        trackEvent(eventName, Map.of());
    }

    /** Non-blocking and FX-thread safe: builds the event and enqueues it, nothing else. */
    public void trackEvent(String eventName, Map<String, Object> props) {
        if (!active.get() || eventName == null || eventName.isBlank()) {
            return;
        }
        TelemetryEvent event = new TelemetryEvent(
            DateTimeFormatter.ISO_INSTANT.format(clock.instant()),
            session.touchAndGetId(),
            eventName,
            sanitizeProps(props));
        offer(event);
    }

    /** Called by {@link TelemetryLogAppender}; deduplicated per signature, capped per run. */
    void trackError(String exceptionClass, String source, String loggerName) {
        if (!active.get()) {
            return;
        }
        String safeLogger = loggerName != null && !loggerName.isBlank() ? loggerName : "unknown";
        String signature = exceptionClass + "|" + source + "|" + safeLogger;
        if (errorSignatures.size() >= MAX_ERROR_SIGNATURES_PER_RUN || !errorSignatures.add(signature)) {
            return;
        }
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("exception", exceptionClass);
        props.put("source", source);
        props.put("logger", safeLogger);
        trackEvent(TelemetryEvents.APP_ERROR, props);
    }

    /** Flat map of String/Number/Boolean values; everything else is dropped. */
    static Map<String, Object> sanitizeProps(Map<String, Object> props) {
        if (props == null || props.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> sanitized = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : props.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            if (key == null || key.isBlank() || value == null) {
                continue;
            }
            if (value instanceof Boolean || value instanceof Number || value instanceof String) {
                sanitized.put(key, value);
            }
        }
        return Map.copyOf(sanitized);
    }

    private void offer(TelemetryEvent event) {
        if (queueSize.get() >= MAX_QUEUE_SIZE) {
            // Drop-newest: early events (app_started, crash) are the valuable ones.
            return;
        }
        queue.add(event);
        int size = queueSize.incrementAndGet();
        ScheduledExecutorService currentScheduler = scheduler;
        if (size >= AptabaseClient.MAX_BATCH_SIZE
                && currentScheduler != null
                && extraFlushPending.compareAndSet(false, true)) {
            try {
                currentScheduler.execute(() -> {
                    extraFlushPending.set(false);
                    flushSafely();
                });
            } catch (RejectedExecutionException e) {
                extraFlushPending.set(false);
            }
        }
    }

    // ------------------------------------------------------------------
    // Consent
    // ------------------------------------------------------------------

    public boolean isEnabled() {
        GlobalSettings settings = settingsManager.getSettings();
        return settings != null && settings.isTelemetryEnabled();
    }

    /** True while the user has never made a consent decision for the current consent text. */
    public boolean isConsentPromptNeeded() {
        GlobalSettings settings = settingsManager.getSettings();
        return settings != null && settings.getTelemetryConsentVersion() < CURRENT_CONSENT_VERSION;
    }

    /** Persists the decision (GDPR record) and applies it immediately. */
    public synchronized void recordConsent(boolean granted) {
        GlobalSettings settings = settingsManager.getSettings();
        if (settings == null) {
            return;
        }
        settings.setTelemetryEnabled(granted);
        settings.setTelemetryConsentVersion(CURRENT_CONSENT_VERSION);
        settings.setTelemetryConsentDate(DateTimeFormatter.ISO_INSTANT.format(clock.instant()));
        try {
            settingsManager.save();
        } catch (Exception e) {
            logger.warn("Failed to persist telemetry consent decision", e);
        }
        applyEnabledState();
    }

    // ------------------------------------------------------------------
    // Flushing (worker thread only)
    // ------------------------------------------------------------------

    private void flushSafely() {
        try {
            flush();
        } catch (RuntimeException e) {
            logger.debug("Telemetry flush failed: {}", e.toString());
        }
        try {
            // Mirror the unsent queue to disk so an offline backlog survives a crash/restart.
            reconcileSpool();
        } catch (RuntimeException e) {
            logger.debug("Telemetry spool reconcile failed: {}", e.toString());
        }
    }

    private void flush() {
        SystemProps currentSystemProps = systemProps;
        if (currentSystemProps == null) {
            return;
        }
        while (true) {
            List<TelemetryEvent> batch = new ArrayList<>(AptabaseClient.MAX_BATCH_SIZE);
            TelemetryEvent next;
            while (batch.size() < AptabaseClient.MAX_BATCH_SIZE && (next = queue.poll()) != null) {
                queueSize.decrementAndGet();
                if (isExpired(next)) {
                    continue; // drop stale events instead of sending or re-queuing them
                }
                batch.add(next);
            }
            if (batch.isEmpty()) {
                return;
            }
            AptabaseClient.SendResult result = client.sendBatch(batch, currentSystemProps);
            if (result == AptabaseClient.SendResult.SENT) {
                continue;
            }
            if (result == AptabaseClient.SendResult.RETRYABLE_FAILURE) {
                // Offline / transient: keep retrying on later cycles (bounded by queue cap + age),
                // so the data is cached and sent once a connection is available.
                for (TelemetryEvent event : batch) {
                    if (active.get() && !isExpired(event) && queueSize.get() < MAX_QUEUE_SIZE) {
                        event.sendAttempts++;
                        queue.add(event);
                        queueSize.incrementAndGet();
                    }
                }
            }
            // PERMANENT_FAILURE: the payload will never succeed — drop the batch.
            return;
        }
    }

    /** Keeps the on-disk spool in sync with the current (unsent) queue contents. */
    private void reconcileSpool() {
        if (!active.get()) {
            return; // deactivate() owns spool deletion when disabled
        }
        if (queueSize.get() == 0) {
            spool.delete();
            return;
        }
        List<TelemetryEvent> snapshot = new ArrayList<>();
        for (TelemetryEvent event : queue) {
            if (isExpired(event)) {
                continue;
            }
            snapshot.add(event);
            if (snapshot.size() >= MAX_QUEUE_SIZE) {
                break;
            }
        }
        if (snapshot.isEmpty()) {
            spool.delete();
        } else {
            spool.write(snapshot);
        }
    }

    /** True when the event's timestamp is older than {@link #MAX_EVENT_AGE}. */
    private boolean isExpired(TelemetryEvent event) {
        try {
            Instant timestamp = Instant.parse(event.timestamp);
            return Duration.between(timestamp, clock.instant()).compareTo(MAX_EVENT_AGE) > 0;
        } catch (RuntimeException e) {
            return false; // unparseable timestamp — keep the event
        }
    }

    // ------------------------------------------------------------------
    // Crash marker
    // ------------------------------------------------------------------

    /** Returns the crashed run's app version, or null if the previous run ended cleanly. */
    private String consumeCrashMarker() {
        Path marker = configDir.resolve(RUN_MARKER_FILE);
        try {
            if (!Files.exists(marker)) {
                return null;
            }
            List<String> lines = Files.readAllLines(marker, StandardCharsets.UTF_8);
            long pid = lines.isEmpty() ? -1L : parseLongSafe(lines.get(0));
            String version = lines.size() > 1 && !lines.get(1).isBlank() ? lines.get(1).trim() : "unknown";
            if (pid > 0 && ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)) {
                return null; // a concurrent live instance owns the marker
            }
            Files.deleteIfExists(marker);
            return version;
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not evaluate telemetry run marker: {}", e.toString());
            return null;
        }
    }

    private void writeRunMarker() {
        Path marker = configDir.resolve(RUN_MARKER_FILE);
        try {
            if (Files.exists(marker)) {
                return; // concurrent instance — do not steal its marker
            }
            SystemProps currentSystemProps = systemProps;
            String version = currentSystemProps != null ? currentSystemProps.appVersion() : "unknown";
            Files.writeString(marker,
                ProcessHandle.current().pid() + "\n" + version + "\n",
                StandardCharsets.UTF_8);
            runMarkerOwned = true;
        } catch (IOException e) {
            logger.debug("Could not write telemetry run marker: {}", e.toString());
        }
    }

    private void deleteRunMarker() {
        if (!runMarkerOwned) {
            return;
        }
        runMarkerOwned = false;
        try {
            Files.deleteIfExists(configDir.resolve(RUN_MARKER_FILE));
        } catch (IOException e) {
            logger.debug("Could not delete telemetry run marker: {}", e.toString());
        }
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return -1L;
        }
    }

    // ------------------------------------------------------------------
    // Test hooks
    // ------------------------------------------------------------------

    int queuedEventCount() {
        return queueSize.get();
    }

    boolean isActive() {
        return active.get();
    }

    /** Runs one flush+spool-reconcile synchronously (deterministic offline-path testing). */
    void flushNowForTest() {
        flushSafely();
    }
}
