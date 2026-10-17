package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.GlobalSettings.AutoBackupInterval;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Writes a backup on a schedule (Settings → Backup → Automatic backup). One daemon thread checks
 * shortly after startup and then every hour whether a backup is due; the check is timestamp-based
 * ({@link GlobalSettings#getLastAutoBackupAt()}), so a run missed while the computer slept or
 * korTTY was closed happens at the next check instead of silently waiting another interval.
 *
 * <p>Nothing runs unless the user chose an <em>existing</em> folder: the folder is never created,
 * because the configured path may sit on a drive that is not mounted right now, and a folder
 * created in its place would receive every backup from then on. The backup itself is always
 * encrypted by {@link BackupManager}; when the chosen mode cannot run (no credential, no GPG key,
 * or password mode while the vault is locked) the run is skipped with a reason the Backup tab
 * shows, and the next hourly check retries.</p>
 */
public final class AutoBackupService {

    private static final Logger logger = LoggerFactory.getLogger(AutoBackupService.class);

    /** How long after startup the first check runs; gives the vault time to be unlocked. */
    static final long STARTUP_DELAY_SECONDS = 120;
    static final long CHECK_INTERVAL_MINUTES = 60;

    /** Why a check did or did not write a backup. */
    public enum Status {
        /** The interval is {@link AutoBackupInterval#OFF}. */
        DISABLED,
        /** The last backup is recent enough. */
        NOT_DUE,
        /** No folder chosen yet. */
        NO_DIRECTORY,
        /** The chosen folder does not exist (or is not a folder) right now. */
        DIRECTORY_MISSING,
        /** Password mode without a credential, or GPG mode without a key. */
        NO_ENCRYPTION_KEY,
        /** Password mode needs the master password, and the vault is locked. */
        WAITING_FOR_UNLOCK,
        /** Written. */
        OK,
        /** {@link BackupManager} threw. */
        FAILED
    }

    /**
     * @param status  what happened
     * @param written the backup file, for {@link Status#OK}
     * @param nextRun when the next backup is due, when a schedule is active
     * @param detail  the failure message, for {@link Status#FAILED}
     */
    public record Outcome(Status status, Path written, Instant nextRun, String detail) {
        static Outcome of(Status status, Instant nextRun) {
            return new Outcome(status, null, nextRun, null);
        }

        /** The run was skipped for a reason the user has to resolve (not just "not yet"). */
        public boolean needsAttention() {
            return status == Status.DIRECTORY_MISSING || status == Status.NO_ENCRYPTION_KEY
                || status == Status.WAITING_FOR_UNLOCK || status == Status.FAILED;
        }
    }

    private final GlobalSettingsManager settingsManager;
    private final Supplier<BackupManager> backupManager;
    private final Supplier<CredentialManager> credentials;
    private final Supplier<GPGKeyManager> gpgKeys;
    /** The master password while the vault is unlocked, null while it is locked. */
    private final Supplier<char[]> unlockedMasterPassword;
    private final Clock clock;
    private final Consumer<Outcome> onOutcome;
    private final AtomicBoolean running = new AtomicBoolean();
    private ScheduledExecutorService scheduler;
    private volatile Outcome lastOutcome;

    public AutoBackupService(GlobalSettingsManager settingsManager,
                             Supplier<BackupManager> backupManager,
                             Supplier<CredentialManager> credentials,
                             Supplier<GPGKeyManager> gpgKeys,
                             Supplier<char[]> unlockedMasterPassword,
                             Clock clock,
                             Consumer<Outcome> onOutcome) {
        this.settingsManager = Objects.requireNonNull(settingsManager, "settingsManager");
        this.backupManager = Objects.requireNonNull(backupManager, "backupManager");
        this.credentials = Objects.requireNonNull(credentials, "credentials");
        this.gpgKeys = Objects.requireNonNull(gpgKeys, "gpgKeys");
        this.unlockedMasterPassword = Objects.requireNonNull(unlockedMasterPassword, "unlockedMasterPassword");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.onOutcome = onOutcome != null ? onOutcome : outcome -> { };
    }

    /** Starts (or restarts) the checks; a no-op schedule when the interval is OFF. */
    public synchronized void start() {
        stop();
        GlobalSettings settings = settingsManager.getSettings();
        if (settings.getAutoBackupInterval() == AutoBackupInterval.OFF) {
            logger.info("Automatic backups are off");
            return;
        }
        scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-auto-backup");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.schedule(this::check, STARTUP_DELAY_SECONDS, TimeUnit.SECONDS);
        scheduler.scheduleWithFixedDelay(this::check,
            STARTUP_DELAY_SECONDS + TimeUnit.MINUTES.toSeconds(CHECK_INTERVAL_MINUTES),
            TimeUnit.MINUTES.toSeconds(CHECK_INTERVAL_MINUTES), TimeUnit.SECONDS);
        logger.info("Automatic backups: {} into {}", settings.getAutoBackupInterval().id(),
            settings.getAutoBackupDirectory());
    }

    /** After the settings were saved: picks up a changed interval or folder. */
    public synchronized void restart() {
        start();
    }

    public synchronized void stop() {
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
    }

    /** The most recent check's outcome, or null before the first one; for the Backup tab. */
    public Outcome lastOutcome() {
        return lastOutcome;
    }

    private void check() {
        try {
            Outcome outcome = runIfDue(clock.instant());
            if (outcome.status() != Status.NOT_DUE || lastOutcome == null) {
                lastOutcome = outcome;
            }
            onOutcome.accept(outcome);
        } catch (RuntimeException unexpected) {
            logger.warn("Automatic backup check failed", unexpected);
        }
    }

    /**
     * Everything that can be answered without writing: whether a backup could run right now, and
     * if not, why. {@link Status#NOT_DUE} when it could but is not due yet.
     */
    public Outcome preflight() {
        return evaluate(clock.instant(), false);
    }

    /** Writes a backup when one is due and possible; otherwise reports why not. */
    Outcome runIfDue(Instant now) {
        return evaluate(now, true);
    }

    private Outcome evaluate(Instant now, boolean write) {
        GlobalSettings settings = settingsManager.getSettings();
        AutoBackupInterval interval = settings.getAutoBackupInterval();
        if (interval == AutoBackupInterval.OFF) {
            return Outcome.of(Status.DISABLED, null);
        }
        Instant nextRun = nextDue(interval, settings.getLastAutoBackupAt() > 0
            ? Instant.ofEpochMilli(settings.getLastAutoBackupAt()) : null, clock.getZone());
        Path directory = directory(settings);
        if (directory == null) {
            return Outcome.of(Status.NO_DIRECTORY, nextRun);
        }
        if (!Files.isDirectory(directory)) {
            return Outcome.of(Status.DIRECTORY_MISSING, nextRun);
        }
        Status encryption = encryptionReadiness(settings);
        if (encryption != null) {
            return Outcome.of(encryption, nextRun);
        }
        if (nextRun != null && now.isBefore(nextRun)) {
            return Outcome.of(Status.NOT_DUE, nextRun);
        }
        if (!write) {
            return Outcome.of(Status.NOT_DUE, nextRun);
        }
        return write(settings, directory, now, interval);
    }

    private Outcome write(GlobalSettings settings, Path directory, Instant now, AutoBackupInterval interval) {
        if (!running.compareAndSet(false, true)) {
            return Outcome.of(Status.NOT_DUE, nextDue(interval, now, clock.getZone()));
        }
        try {
            BackupManager manager = backupManager.get();
            if (manager == null) {
                return new Outcome(Status.FAILED, null, null, "backup manager not ready");
            }
            Path written = manager.createBackup(directory, credentials.get(), gpgKeys.get(), unlockedMasterPassword.get());
            settings.setLastAutoBackupAt(now.toEpochMilli());
            settingsManager.saveAsync();
            logger.info("Automatic backup written: {}", written);
            return new Outcome(Status.OK, written, nextDue(interval, now, clock.getZone()), null);
        } catch (Exception failure) {
            logger.warn("Automatic backup into {} failed", directory, failure);
            return new Outcome(Status.FAILED, null, nextDue(interval, now, clock.getZone()),
                failure.getMessage() != null ? failure.getMessage() : failure.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    /** Null when the chosen mode has what it needs; otherwise the status that names what is missing. */
    private Status encryptionReadiness(GlobalSettings settings) {
        if (settings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.GPG) {
            GPGKeyManager keys = gpgKeys.get();
            String keyId = settings.getBackupGpgKeyId();
            boolean present = keyId != null && keys != null
                && keys.getAllKeys().stream().anyMatch(key -> keyId.equals(key.getId()));
            return present ? null : Status.NO_ENCRYPTION_KEY;
        }
        CredentialManager manager = credentials.get();
        String credentialId = settings.getBackupCredentialId();
        if (credentialId == null || manager == null || manager.findCredentialById(credentialId).isEmpty()) {
            return Status.NO_ENCRYPTION_KEY;
        }
        return unlockedMasterPassword.get() == null ? Status.WAITING_FOR_UNLOCK : null;
    }

    /** The configured folder as a path ({@code ~/} expanded), or null when none is set or it is unparsable. */
    static Path directory(GlobalSettings settings) {
        String configured = settings.getAutoBackupDirectory();
        if (configured == null || configured.isBlank()) {
            return null;
        }
        String expanded = configured.trim();
        if ("~".equals(expanded) || expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        try {
            return Path.of(expanded);
        } catch (InvalidPathException invalid) {
            return null;
        }
    }

    /**
     * When the next backup is due: a day, a week or a calendar month after {@code last}; null when
     * no backup was ever written (so the first one runs at the first check).
     */
    static Instant nextDue(AutoBackupInterval interval, Instant last, ZoneId zone) {
        if (last == null || interval == AutoBackupInterval.OFF) {
            return null;
        }
        ZonedDateTime from = last.atZone(zone);
        return switch (interval) {
            case DAILY -> from.plusDays(1).toInstant();
            case WEEKLY -> from.plusWeeks(1).toInstant();
            case MONTHLY -> from.plusMonths(1).toInstant();
            case OFF -> null;
        };
    }
}
