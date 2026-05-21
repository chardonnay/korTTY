package de.kortty.update;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class UpdateCheckService {

    private static final Logger logger = LoggerFactory.getLogger(UpdateCheckService.class);
    private static final long PERIODIC_SCAN_INTERVAL_HOURS = 1L;
    private static final int PERIODIC_PROMPT_THROTTLE_DAYS = 7;

    private final GlobalSettingsManager settingsManager;
    private final ReleaseClient releaseClient;
    private final UpdateAssetSelector assetSelector;
    private final Supplier<PlatformProfile> platformProfileSupplier;
    private final Supplier<String> currentVersionSupplier;
    private final Clock clock;
    private final Consumer<AvailableUpdate> automaticUpdateHandler;
    private final AtomicBoolean automaticCheckRunning = new AtomicBoolean(false);

    private ScheduledExecutorService scheduler;

    public UpdateCheckService(
        GlobalSettingsManager settingsManager,
        Consumer<AvailableUpdate> automaticUpdateHandler
    ) {
        this(
            settingsManager,
            new GitHubReleaseClient(),
            new UpdateAssetSelector(),
            PlatformProfile::current,
            () -> de.kortty.KorTTYApplication.getAppVersion(),
            Clock.systemDefaultZone(),
            automaticUpdateHandler);
    }

    UpdateCheckService(
        GlobalSettingsManager settingsManager,
        ReleaseClient releaseClient,
        UpdateAssetSelector assetSelector,
        Supplier<PlatformProfile> platformProfileSupplier,
        Supplier<String> currentVersionSupplier,
        Clock clock,
        Consumer<AvailableUpdate> automaticUpdateHandler
    ) {
        this.settingsManager = Objects.requireNonNull(settingsManager, "settingsManager");
        this.releaseClient = Objects.requireNonNull(releaseClient, "releaseClient");
        this.assetSelector = Objects.requireNonNull(assetSelector, "assetSelector");
        this.platformProfileSupplier = Objects.requireNonNull(platformProfileSupplier, "platformProfileSupplier");
        this.currentVersionSupplier = Objects.requireNonNull(currentVersionSupplier, "currentVersionSupplier");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.automaticUpdateHandler = automaticUpdateHandler != null ? automaticUpdateHandler : update -> {};
    }

    public synchronized void start() {
        stop();
        if (!settingsManager.getSettings().isUpdateChecksEnabled()) {
            logger.info("Automatic update checks are disabled");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-update-checker");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(() -> runAutomaticCheck(UpdateCheckRunType.AUTOMATIC_STARTUP));
        scheduler.scheduleWithFixedDelay(
            () -> runAutomaticCheck(UpdateCheckRunType.AUTOMATIC_PERIODIC),
            PERIODIC_SCAN_INTERVAL_HOURS,
            PERIODIC_SCAN_INTERVAL_HOURS,
            TimeUnit.HOURS);
    }

    public synchronized void restart() {
        start();
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    public UpdateCheckResult checkManually() {
        return checkForUpdate(UpdateCheckRunType.MANUAL);
    }

    void runAutomaticCheck(UpdateCheckRunType runType) {
        if (!automaticCheckRunning.compareAndSet(false, true)) {
            return;
        }
        try {
            GlobalSettings settings = settingsManager.getSettings();
            if (!settings.isUpdateChecksEnabled() || !isAutomaticCheckDue(settings)) {
                return;
            }
            UpdateCheckResult result = checkForUpdate(runType);
            if (result.status() == UpdateCheckResult.Status.UPDATE_AVAILABLE && result.update() != null) {
                recordAutomaticPrompt(result.update().versionLabel());
                automaticUpdateHandler.accept(result.update());
            }
        } finally {
            automaticCheckRunning.set(false);
        }
    }

    UpdateCheckResult checkForUpdate(UpdateCheckRunType runType) {
        try {
            Optional<UpdateVersion> currentVersion = UpdateVersion.parse(currentVersionSupplier.get());
            if (currentVersion.isEmpty()) {
                return UpdateCheckResult.failed("Current KorTTY version could not be parsed.");
            }

            UpdateRelease release = releaseClient.fetchLatestRelease();
            recordSuccessfulCheck();
            if (!release.isStableLatestRelease()) {
                return UpdateCheckResult.noUpdate("Latest release is a draft or prerelease.");
            }

            Optional<UpdateVersion> latestVersion = UpdateVersion.parse(release.tagName());
            if (latestVersion.isEmpty()) {
                return UpdateCheckResult.failed("Latest KorTTY release version could not be parsed.");
            }
            if (latestVersion.get().compareTo(currentVersion.get()) <= 0) {
                return UpdateCheckResult.noUpdate("KorTTY is up to date.");
            }

            Optional<UpdateAsset> asset = assetSelector.select(release, platformProfileSupplier.get());
            if (asset.isEmpty()) {
                return UpdateCheckResult.noCompatibleAsset("No compatible download asset was found.");
            }

            AvailableUpdate update = new AvailableUpdate(
                release,
                asset.get(),
                latestVersion.get(),
                currentVersion.get());
            if (runType != UpdateCheckRunType.MANUAL && shouldSuppressAutomaticPrompt(update, runType)) {
                return UpdateCheckResult.noUpdate("Update prompt is suppressed by user settings.");
            }
            return UpdateCheckResult.updateAvailable(update);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return UpdateCheckResult.failed("Update check was cancelled.");
        } catch (IOException e) {
            logger.debug("Update check failed", e);
            return UpdateCheckResult.failed(nonBlank(e.getMessage(), e.getClass().getSimpleName()));
        } catch (RuntimeException e) {
            logger.debug("Update check failed unexpectedly", e);
            return UpdateCheckResult.failed(nonBlank(e.getMessage(), e.getClass().getSimpleName()));
        }
    }

    public void snoozeUntilTomorrow(String version) {
        GlobalSettings settings = settingsManager.getSettings();
        settings.setSnoozedUpdateVersion(version);
        settings.setUpdateSnoozedUntilLocalDate(today().plusDays(1).toString());
        saveSettings("snoozed update " + version);
    }

    public void ignoreVersion(String version) {
        GlobalSettings settings = settingsManager.getSettings();
        settings.setIgnoredUpdateVersion(version);
        settings.setSnoozedUpdateVersion(null);
        settings.setUpdateSnoozedUntilLocalDate(null);
        saveSettings("ignored update " + version);
    }

    public void recordDownloadedVersion(String version) {
        ignoreVersion(version);
    }

    private boolean isAutomaticCheckDue(GlobalSettings settings) {
        long lastSuccessfulCheck = settings.getLastSuccessfulUpdateCheckMillis();
        if (lastSuccessfulCheck <= 0) {
            return true;
        }
        LocalDate lastCheckDate = Instant.ofEpochMilli(lastSuccessfulCheck)
            .atZone(clock.getZone())
            .toLocalDate();
        LocalDate dueDate = lastCheckDate.plusDays(settings.getUpdateCheckIntervalDays());
        return !dueDate.isAfter(today());
    }

    private boolean shouldSuppressAutomaticPrompt(AvailableUpdate update, UpdateCheckRunType runType) {
        GlobalSettings settings = settingsManager.getSettings();
        String version = update.versionLabel();
        if (version.equals(settings.getIgnoredUpdateVersion())) {
            return true;
        }
        if (version.equals(settings.getSnoozedUpdateVersion())) {
            Optional<LocalDate> snoozeDate = parseDate(settings.getUpdateSnoozedUntilLocalDate());
            if (snoozeDate.isPresent() && today().isBefore(snoozeDate.get())) {
                return true;
            }
        }
        if (!version.equals(settings.getLastAutomaticUpdatePromptVersion())) {
            return false;
        }
        Optional<LocalDate> lastPromptDate = parseDate(settings.getLastAutomaticUpdatePromptLocalDate());
        if (lastPromptDate.isEmpty()) {
            return false;
        }
        if (runType == UpdateCheckRunType.AUTOMATIC_STARTUP) {
            return !lastPromptDate.get().isBefore(today());
        }
        return lastPromptDate.get().plusDays(PERIODIC_PROMPT_THROTTLE_DAYS).isAfter(today());
    }

    private void recordAutomaticPrompt(String version) {
        GlobalSettings settings = settingsManager.getSettings();
        settings.setLastAutomaticUpdatePromptVersion(version);
        settings.setLastAutomaticUpdatePromptLocalDate(today().toString());
        saveSettings("recorded automatic update prompt " + version);
    }

    private void recordSuccessfulCheck() {
        settingsManager.getSettings().setLastSuccessfulUpdateCheckMillis(clock.millis());
        saveSettings("recorded successful update check");
    }

    private void saveSettings(String action) {
        try {
            settingsManager.save();
        } catch (Exception e) {
            logger.warn("Could not save global settings after {}: {}", action, e.getMessage());
        }
    }

    private Optional<LocalDate> parseDate(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(LocalDate.parse(text.trim()));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    private LocalDate today() {
        ZoneId zone = clock.getZone();
        return Instant.now(clock).atZone(zone).toLocalDate();
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }
}
