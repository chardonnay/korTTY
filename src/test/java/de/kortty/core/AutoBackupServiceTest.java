package de.kortty.core;

import de.kortty.model.GPGKey;
import de.kortty.security.MasterPasswordManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.GlobalSettings.AutoBackupInterval;
import de.kortty.model.StoredCredential;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * The scheduled backup: due-time arithmetic, every reason a run is skipped, that a due run writes
 * an encrypted backup and advances the timestamp, and that a failed run does not.
 */
public class AutoBackupServiceTest {

    private static final ZoneId ZONE = ZoneOffset.UTC;
    private static final Instant NOW = Instant.parse("2026-03-31T10:00:00Z");

    private Path root;
    private Path configDir;
    private Path target;
    private GlobalSettingsManager manager;
    private CredentialManager credentials;
    private GPGKeyManager gpgKeys;
    private char[] masterPassword;
    private final AtomicReference<char[]> vault = new AtomicReference<>();
    private final List<AutoBackupService.Outcome> outcomes = new ArrayList<>();

    @BeforeMethod
    void setUp() throws Exception {
        root = Files.createTempDirectory("kortty-auto-backup");
        configDir = Files.createDirectories(root.resolve("config"));
        Files.writeString(configDir.resolve(MasterPasswordManager.MASTER_KEY_FILE),
            "#KorTTY Master Password\nsalt=s\nhash=h\n");
        Files.writeString(configDir.resolve("connections.xml"), "<connections/>");
        target = Files.createDirectories(root.resolve("backups"));
        manager = new GlobalSettingsManager(configDir);
        manager.load();
        masterPassword = "master-pw".toCharArray();
        vault.set(masterPassword);
        credentials = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential("backup", "user", StoredCredential.Environment.PRODUCTION);
        credentials.setPassword(credential, "backup-pw", masterPassword);
        credentials.addCredential(credential);
        gpgKeys = new GPGKeyManager(configDir);
        GlobalSettings settings = manager.getSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());
        settings.setAutoBackupDirectory(target.toString());
        settings.setAutoBackupInterval(AutoBackupInterval.WEEKLY);
        outcomes.clear();
    }

    @AfterMethod
    void tearDown() throws IOException {
        // A due run saves the settings asynchronously (settings.xml.tmp, then a move). The writer is
        // single-threaded, so waiting for one more save lets those finish before the folder goes.
        try {
            manager.saveAsync().join();
        } catch (RuntimeException ignored) {
            // the cleanup below runs either way
        }
        try (Stream<Path> files = Files.walk(root)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private AutoBackupService service() {
        return new AutoBackupService(manager,
            () -> new BackupManager(configDir, manager.getSettings()),
            () -> credentials, () -> gpgKeys, vault::get, Clock.fixed(NOW, ZONE), outcomes::add);
    }

    @Test
    void nextDueFollowsTheCalendar() {
        Instant last = Instant.parse("2026-01-31T08:00:00Z");
        assertThat(AutoBackupService.nextDue(AutoBackupInterval.DAILY, last, ZONE))
            .isEqualTo(Instant.parse("2026-02-01T08:00:00Z"));
        assertThat(AutoBackupService.nextDue(AutoBackupInterval.WEEKLY, last, ZONE))
            .isEqualTo(Instant.parse("2026-02-07T08:00:00Z"));
        // A calendar month, clamped to the shorter month's last day.
        assertThat(AutoBackupService.nextDue(AutoBackupInterval.MONTHLY, last, ZONE))
            .isEqualTo(Instant.parse("2026-02-28T08:00:00Z"));
        assertThat(AutoBackupService.nextDue(AutoBackupInterval.WEEKLY, null, ZONE)).isNull();
        assertThat(AutoBackupService.nextDue(AutoBackupInterval.OFF, last, ZONE)).isNull();
    }

    @Test
    void offReportsDisabledAndWritesNothing() {
        manager.getSettings().setAutoBackupInterval(AutoBackupInterval.OFF);
        AutoBackupService.Outcome outcome = service().runIfDue(NOW);
        assertThat(outcome.status()).isEqualTo(AutoBackupService.Status.DISABLED);
        assertThat(outcome.needsAttention()).isFalse();
        assertThat(target.toFile().list()).isEmpty();
    }

    @Test
    void withoutAFolderNothingRuns() {
        manager.getSettings().setAutoBackupDirectory(null);
        assertThat(service().runIfDue(NOW).status()).isEqualTo(AutoBackupService.Status.NO_DIRECTORY);
        assertThat(manager.getSettings().getLastAutoBackupAt()).isEqualTo(0);
    }

    @Test
    void aMissingFolderIsNeverCreated() {
        Path missing = root.resolve("not-mounted").resolve("backups");
        manager.getSettings().setAutoBackupDirectory(missing.toString());
        AutoBackupService.Outcome outcome = service().runIfDue(NOW);
        assertThat(outcome.status()).isEqualTo(AutoBackupService.Status.DIRECTORY_MISSING);
        assertThat(outcome.needsAttention()).isTrue();
        assertThat(Files.exists(missing)).isFalse();
        assertThat(Files.exists(missing.getParent())).isFalse();
    }

    @Test
    void passwordModeNeedsACredentialAndAnUnlockedVault() {
        vault.set(null);
        assertThat(service().runIfDue(NOW).status()).isEqualTo(AutoBackupService.Status.WAITING_FOR_UNLOCK);
        assertThat(target.toFile().list()).isEmpty();

        vault.set(masterPassword);
        manager.getSettings().setBackupCredentialId("no-such-credential");
        assertThat(service().runIfDue(NOW).status()).isEqualTo(AutoBackupService.Status.NO_ENCRYPTION_KEY);
        assertThat(target.toFile().list()).isEmpty();
    }

    @Test
    void gpgModeNeedsAKeyButNoVault() throws Exception {
        GlobalSettings settings = manager.getSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.GPG);
        settings.setBackupGpgKeyId("missing");
        assertThat(service().runIfDue(NOW).status()).isEqualTo(AutoBackupService.Status.NO_ENCRYPTION_KEY);

        GPGKey key = new GPGKey("t", "ABCD1234");
        gpgKeys.addKey(key);
        settings.setBackupGpgKeyId(key.getId());
        vault.set(null);
        AutoBackupService gpgService = new AutoBackupService(manager,
            () -> new BackupManager(configDir, settings, AutoBackupServiceTest::fakeEncrypt, (in, out) -> { }),
            () -> credentials, () -> gpgKeys, vault::get, Clock.fixed(NOW, ZONE), outcomes::add);
        AutoBackupService.Outcome outcome = gpgService.runIfDue(NOW);
        assertThat(outcome.status()).isEqualTo(AutoBackupService.Status.OK);
        assertThat(outcome.written().getFileName().toString()).isEqualTo("kortty-backup.zip.gpg");
    }

    @Test
    void aDueRunWritesAnEncryptedZipAndAdvancesTheTimestamp() throws Exception {
        AutoBackupService service = service();
        AutoBackupService.Outcome outcome = service.runIfDue(NOW);
        assertThat(outcome.status()).isEqualTo(AutoBackupService.Status.OK);
        assertThat(outcome.written()).isEqualTo(target.resolve("kortty-backup.zip"));
        assertThat(Files.size(outcome.written())).isGreaterThan(0L);
        assertThat(manager.getSettings().getLastAutoBackupAt()).isEqualTo(NOW.toEpochMilli());
        assertThat(outcome.nextRun()).isEqualTo(NOW.plus(java.time.Duration.ofDays(7)));

        // Encrypted: the ZIP cannot be read without the password.
        try (net.lingala.zip4j.ZipFile zip = new net.lingala.zip4j.ZipFile(outcome.written().toFile())) {
            assertThat(zip.isEncrypted()).isTrue();
        }

        // Not due again until the week is over; a later check then writes the next one.
        assertThat(service.runIfDue(NOW.plusSeconds(3600)).status()).isEqualTo(AutoBackupService.Status.NOT_DUE);
        assertThat(service.preflight().status()).isEqualTo(AutoBackupService.Status.NOT_DUE);
        Instant later = NOW.plus(java.time.Duration.ofDays(7));
        assertThat(service.runIfDue(later).status()).isEqualTo(AutoBackupService.Status.OK);
        assertThat(manager.getSettings().getLastAutoBackupAt()).isEqualTo(later.toEpochMilli());
        assertThat(Files.isDirectory(target.resolve("old-backups"))).isTrue();
    }

    @Test
    void aFailedRunKeepsTheTimestampSoTheNextCheckRetries() throws Exception {
        // The credential exists but its password cannot be decrypted with this master password.
        vault.set("wrong-pw".toCharArray());
        AutoBackupService.Outcome outcome = service().runIfDue(NOW);
        assertThat(outcome.status()).isEqualTo(AutoBackupService.Status.FAILED);
        assertThat(outcome.needsAttention()).isTrue();
        assertThat(outcome.detail()).isNotEmpty();
        assertThat(manager.getSettings().getLastAutoBackupAt()).isEqualTo(0);
        assertThat(Files.exists(target.resolve("kortty-backup.zip"))).isFalse();
    }

    @Test
    void settingsRoundTripThroughTheXmlFile() throws Exception {
        GlobalSettings settings = manager.getSettings();
        settings.setAutoBackupInterval(AutoBackupInterval.DAILY);
        settings.setAutoBackupDirectory("~/Backups/korTTY");
        settings.setLastAutoBackupAt(12345L);
        manager.save();
        GlobalSettingsManager reloaded = new GlobalSettingsManager(configDir);
        reloaded.load();
        assertThat(reloaded.getSettings().getAutoBackupInterval()).isEqualTo(AutoBackupInterval.DAILY);
        assertThat(reloaded.getSettings().getAutoBackupDirectory()).isEqualTo("~/Backups/korTTY");
        assertThat(reloaded.getSettings().getLastAutoBackupAt()).isEqualTo(12345L);
        assertThat(AutoBackupService.directory(reloaded.getSettings()))
            .isEqualTo(Path.of(System.getProperty("user.home"), "Backups", "korTTY"));
    }

    @Test
    void intervalIdsRoundTrip() {
        for (AutoBackupInterval interval : AutoBackupInterval.values()) {
            assertThat(AutoBackupInterval.parseId(interval.id())).isEqualTo(interval);
            assertThat(AutoBackupInterval.parseId(interval.id().toUpperCase())).isEqualTo(interval);
        }
        assertThat(AutoBackupInterval.parseId("hourly")).isNull();
        assertThat(AutoBackupInterval.parseId(null)).isNull();
    }

    private static void fakeEncrypt(Path input, Path output, String keyId, String publicKeyPath) throws IOException {
        try (OutputStream out = Files.newOutputStream(output)) {
            out.write(new byte[] {(byte) 0xC1, 'P', 'G', 'P'});
            Files.copy(input, out);
        }
    }
}
