package de.kortty.core;

import de.kortty.jobscheduler.JobSchedulerRepository;
import de.kortty.model.GPGKey;
import de.kortty.model.GlobalSettings;
import de.kortty.model.StoredCredential;
import de.kortty.persistence.XMLConnectionRepository;
import de.kortty.security.MasterPasswordManager;
import org.testng.annotations.Test;

import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;


public class BackupManagerTest {

    /** What the fake gpg writes in front of the plaintext: a new-format PKESK packet header. */
    private static final byte[] FAKE_PGP_PREFIX = fakePgpPrefix();

    private static byte[] fakePgpPrefix() {
        byte[] marker = "FAKEPGP".getBytes(StandardCharsets.US_ASCII);
        byte[] prefix = new byte[marker.length + 1];
        prefix[0] = (byte) 0xC1;
        System.arraycopy(marker, 0, prefix, 1, marker.length);
        return prefix;
    }

    /** Fake gpg encryption: the prefix followed by the plain ZIP; remembers the plaintext path. */
    private static final class FakeEncryptor implements SessionJournalExportProtection.GpgEncryptor {
        final List<Path> plaintexts = new ArrayList<>();
        final List<String> keyIds = new ArrayList<>();

        @Override
        public void encrypt(Path input, Path output, String keyId, String publicKeyPath) throws IOException {
            plaintexts.add(input);
            keyIds.add(keyId);
            try (OutputStream out = Files.newOutputStream(output)) {
                out.write(FAKE_PGP_PREFIX);
                Files.copy(input, out);
            }
        }
    }

    /** Fake gpg decryption: checks and strips the prefix. */
    private static final class FakeDecryptor implements BackupManager.GpgDecryptor {
        int calls;

        @Override
        public void decrypt(Path input, Path output) throws IOException {
            calls++;
            byte[] data = Files.readAllBytes(input);
            if (data.length < FAKE_PGP_PREFIX.length
                    || !Arrays.equals(Arrays.copyOf(data, FAKE_PGP_PREFIX.length), FAKE_PGP_PREFIX)) {
                throw new IOException("GPG decryption failed: no secret key");
            }
            Files.write(output, Arrays.copyOfRange(data, FAKE_PGP_PREFIX.length, data.length));
        }
    }

    /** A config directory with a master key, a connection and a copied SSH key file. */
    private static Path sampleConfig(Path root) throws IOException {
        Path configDir = Files.createDirectories(root.resolve("config"));
        Files.writeString(configDir.resolve(MasterPasswordManager.MASTER_KEY_FILE), masterKey("salt-a", "hash-a"));
        Files.writeString(configDir.resolve("connections.xml"), "<connections/>");
        Files.createDirectories(configDir.resolve("ssh-keys"));
        Files.writeString(configDir.resolve("ssh-keys/id_test"), "fake key material");
        return configDir;
    }

    private static String masterKey(String salt, String hash) {
        return "#KorTTY Master Password\n#" + LocalDateTime.now() + "\nsalt=" + salt + "\nhash=" + hash + "\n";
    }

    private record GpgSetup(GlobalSettings settings, GPGKeyManager keys) {
    }

    private static GpgSetup gpgSetup(Path configDir) {
        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.GPG);
        GPGKeyManager keys = new GPGKeyManager(configDir);
        GPGKey key = new GPGKey("t", "ABCD1234");
        keys.addKey(key);
        settings.setBackupGpgKeyId(key.getId());
        return new GpgSetup(settings, keys);
    }

    private record PasswordSetup(CredentialManager credentials, char[] masterPassword) {
    }

    /** Configures {@code settings} for a password ZIP whose password is "backup-pw". */
    private static PasswordSetup usePasswordBackup(Path configDir, GlobalSettings settings) throws Exception {
        char[] masterPassword = "master-pw".toCharArray();
        CredentialManager credentialManager = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential(
            "backup", "user", StoredCredential.Environment.PRODUCTION);
        credentialManager.setPassword(credential, "backup-pw", masterPassword);
        credentialManager.addCredential(credential);
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());
        return new PasswordSetup(credentialManager, masterPassword);
    }

    private static List<String> fileNames(Path dir) throws IOException {
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    public void gpgBackupIsWrittenAsZipGpgAndRestoresWithoutAZipPassword() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-gpg-");
        Path configDir = sampleConfig(root);
        GpgSetup gpg = gpgSetup(configDir);
        FakeEncryptor encryptor = new FakeEncryptor();
        FakeDecryptor decryptor = new FakeDecryptor();
        Path target = root.resolve("target");

        Path backup = new BackupManager(configDir, gpg.settings(), encryptor, decryptor)
            .createBackup(target, null, gpg.keys(), null);

        assertThat(backup.getFileName().toString()).isEqualTo("kortty-backup.zip.gpg");
        assertThat(BackupManager.detectBackupFormat(backup)).isEqualTo(BackupManager.BackupFormat.OPENPGP);
        assertThat(encryptor.keyIds).containsExactly("ABCD1234");
        // Neither the plaintext ZIP nor the .part file is left behind anywhere.
        assertThat(fileNames(target)).containsExactly("kortty-backup.zip.gpg");
        assertThat(Files.exists(encryptor.plaintexts.get(0))).isFalse();
        assertThat(Files.exists(encryptor.plaintexts.get(0).getParent())).isFalse();

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        int imported = new BackupManager(restoreDir, new GlobalSettings(), encryptor, decryptor)
            .importBackup(backup, null, true);

        assertThat(decryptor.calls).isEqualTo(1);
        assertThat(imported).isAtLeast(3);
        assertThat(Files.readString(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE)))
            .contains("salt=salt-a");
        assertThat(Files.readString(restoreDir.resolve("connections.xml"))).isEqualTo("<connections/>");
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_test"))).isEqualTo("fake key material");
    }

    @Test
    public void legacyGpgBackupNamedZipIsRestoredByContent() throws Exception {
        // Older versions wrote GPG backups as kortty-backup.zip; the name sent them to the ZIP
        // password prompt and every restore failed. The content decides now.
        Path root = Files.createTempDirectory("kortty-backup-gpg-legacy-");
        Path configDir = sampleConfig(root);
        GpgSetup gpg = gpgSetup(configDir);
        FakeEncryptor encryptor = new FakeEncryptor();
        FakeDecryptor decryptor = new FakeDecryptor();
        Path backup = new BackupManager(configDir, gpg.settings(), encryptor, decryptor)
            .createBackup(root.resolve("target"), null, gpg.keys(), null);
        Path legacy = Files.move(backup, backup.resolveSibling("kortty-backup.zip"));

        assertThat(BackupManager.importFormat(legacy)).isEqualTo(BackupManager.BackupFormat.OPENPGP);
        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        new BackupManager(restoreDir, new GlobalSettings(), encryptor, decryptor)
            .importBackup(legacy, null, true);

        assertThat(decryptor.calls).isEqualTo(1);
        assertThat(Files.readString(restoreDir.resolve("connections.xml"))).isEqualTo("<connections/>");
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_test"))).isEqualTo("fake key material");
    }

    @Test
    public void rotationKeepsTheGpgSuffixAndRetentionCountsGpgBackups() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-gpg-rotation-");
        Path configDir = sampleConfig(root);
        GpgSetup gpg = gpgSetup(configDir);
        gpg.settings().setMaxBackupCount(2);
        BackupManager manager = new BackupManager(configDir, gpg.settings(), new FakeEncryptor(), new FakeDecryptor());
        Path target = root.resolve("target");

        for (int i = 0; i < 4; i++) {
            manager.createBackup(target, null, gpg.keys(), null);
        }

        assertThat(fileNames(target)).containsExactly("kortty-backup.zip.gpg", "old-backups");
        List<String> rotated = fileNames(target.resolve("old-backups"));
        assertThat(rotated).hasSize(2);
        for (String name : rotated) {
            assertThat(name).startsWith("kortty-backup_");
            assertThat(name).endsWith(".zip.gpg");
        }
    }

    @Test
    public void legacyMisnamedGpgCurrentBackupIsRotatedAsZipGpg() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-gpg-misnamed-");
        Path configDir = sampleConfig(root);
        GpgSetup gpg = gpgSetup(configDir);
        BackupManager manager = new BackupManager(configDir, gpg.settings(), new FakeEncryptor(), new FakeDecryptor());
        Path target = root.resolve("target");
        Path first = manager.createBackup(target, null, gpg.keys(), null);
        Files.move(first, target.resolve("kortty-backup.zip"));

        manager.createBackup(target, null, gpg.keys(), null);

        // The legacy file is no longer "current" next to the new one, and its rotated name says GPG.
        assertThat(fileNames(target)).containsExactly("kortty-backup.zip.gpg", "old-backups");
        List<String> rotated = fileNames(target.resolve("old-backups"));
        assertThat(rotated).hasSize(1);
        assertThat(rotated.get(0)).endsWith(".zip.gpg");
    }

    @Test
    public void switchingFromPasswordToGpgRotatesThePreviousCurrentBackup() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-switch-");
        Path configDir = sampleConfig(root);
        GpgSetup gpg = gpgSetup(configDir);
        PasswordSetup password = usePasswordBackup(configDir, gpg.settings());
        BackupManager manager = new BackupManager(configDir, gpg.settings(), new FakeEncryptor(), new FakeDecryptor());
        Path target = root.resolve("target");
        manager.createBackup(target, password.credentials(), gpg.keys(), password.masterPassword());
        assertThat(fileNames(target)).containsExactly("kortty-backup.zip");

        gpg.settings().setBackupEncryptionType(GlobalSettings.BackupEncryptionType.GPG);
        manager.createBackup(target, password.credentials(), gpg.keys(), password.masterPassword());

        assertThat(fileNames(target)).containsExactly("kortty-backup.zip.gpg", "old-backups");
        List<String> rotated = fileNames(target.resolve("old-backups"));
        assertThat(rotated).hasSize(1);
        assertThat(rotated.get(0)).endsWith(".zip");
        assertThat(rotated.get(0)).doesNotContain(".gpg");
        assertThat(BackupManager.detectBackupFormat(target.resolve("old-backups").resolve(rotated.get(0))))
            .isEqualTo(BackupManager.BackupFormat.ZIP);
    }

    @Test
    public void rotationNeverOverwritesABackupRotatedInTheSameSecond() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-collision-");
        Path configDir = sampleConfig(root);
        GpgSetup gpg = gpgSetup(configDir);
        gpg.settings().setMaxBackupCount(0);
        BackupManager manager = new BackupManager(configDir, gpg.settings(), new FakeEncryptor(), new FakeDecryptor());
        Path target = root.resolve("target");
        Path oldBackups = Files.createDirectories(target.resolve("old-backups"));
        // Occupy the rotated names of this and the next seconds, as if rotated just before.
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
        LocalDateTime now = LocalDateTime.now();
        List<Path> occupied = new ArrayList<>();
        for (int second = 0; second < 3; second++) {
            Path taken = oldBackups.resolve("kortty-backup_" + now.plusSeconds(second).format(format) + ".zip.gpg");
            Files.writeString(taken, "earlier backup " + second);
            occupied.add(taken);
        }

        manager.createBackup(target, null, gpg.keys(), null);
        manager.createBackup(target, null, gpg.keys(), null);
        manager.createBackup(target, null, gpg.keys(), null);

        for (int second = 0; second < 3; second++) {
            assertThat(Files.readString(occupied.get(second))).isEqualTo("earlier backup " + second);
        }
        assertThat(fileNames(oldBackups)).hasSize(5);
    }

    @Test
    public void failedGpgEncryptionKeepsThePreviousBackupAndLeavesNoPartFile() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-gpg-failure-");
        Path configDir = sampleConfig(root);
        GpgSetup gpg = gpgSetup(configDir);
        Path target = root.resolve("target");
        Path previous = new BackupManager(configDir, gpg.settings(), new FakeEncryptor(), new FakeDecryptor())
            .createBackup(target, null, gpg.keys(), null);
        byte[] previousBytes = Files.readAllBytes(previous);
        AtomicReference<Path> plaintext = new AtomicReference<>();
        SessionJournalExportProtection.GpgEncryptor failing = (input, output, keyId, publicKeyPath) -> {
            plaintext.set(input);
            Files.writeString(output, "half written");
            throw new IOException("gpg failed (exit 2): no public key");
        };

        Exception failure = expectThrows(Exception.class, () ->
            new BackupManager(configDir, gpg.settings(), failing, new FakeDecryptor())
                .createBackup(target, null, gpg.keys(), null));

        assertThat(failure).hasMessageThat().contains("GPG encryption failed");
        assertThat(fileNames(target)).containsExactly("kortty-backup.zip.gpg");
        assertThat(Files.readAllBytes(previous)).isEqualTo(previousBytes);
        assertThat(Files.exists(plaintext.get())).isFalse();
    }

    @Test
    public void detectBackupFormatRecognisesZipOpenPgpOldNewArmoredAndUnknown() throws Exception {
        Path dir = Files.createTempDirectory("kortty-backup-format-");
        assertThat(formatOf(dir, new byte[] {'P', 'K', 3, 4, 20, 0})).isEqualTo(BackupManager.BackupFormat.ZIP);
        assertThat(formatOf(dir, new byte[] {'P', 'K', 5, 6, 0, 0})).isEqualTo(BackupManager.BackupFormat.ZIP);
        // Old packet format, public-key encrypted session key (tag 1), two length types.
        assertThat(formatOf(dir, new byte[] {(byte) 0x85, 1, 12})).isEqualTo(BackupManager.BackupFormat.OPENPGP);
        assertThat(formatOf(dir, new byte[] {(byte) 0x84, 12, 3})).isEqualTo(BackupManager.BackupFormat.OPENPGP);
        // New packet format: PKESK (tag 1) and symmetric session key (tag 3).
        assertThat(formatOf(dir, new byte[] {(byte) 0xC1, 12, 3})).isEqualTo(BackupManager.BackupFormat.OPENPGP);
        assertThat(formatOf(dir, new byte[] {(byte) 0xC3, 13, 4})).isEqualTo(BackupManager.BackupFormat.OPENPGP);
        assertThat(formatOf(dir, "-----BEGIN PGP MESSAGE-----\n\nhQEMA...".getBytes(StandardCharsets.US_ASCII)))
            .isEqualTo(BackupManager.BackupFormat.OPENPGP);
        assertThat(formatOf(dir, "just some notes".getBytes(StandardCharsets.UTF_8)))
            .isEqualTo(BackupManager.BackupFormat.UNKNOWN);
        // A literal-data packet (tag 11) is OpenPGP but not an encrypted message.
        assertThat(formatOf(dir, new byte[] {(byte) 0xCB, 5, 'b'})).isEqualTo(BackupManager.BackupFormat.UNKNOWN);
        assertThat(formatOf(dir, new byte[0])).isEqualTo(BackupManager.BackupFormat.UNKNOWN);
    }

    private static BackupManager.BackupFormat formatOf(Path dir, byte[] content) throws IOException {
        Path file = Files.createTempFile(dir, "sample", ".bin");
        Files.write(file, content);
        return BackupManager.detectBackupFormat(file);
    }

    @Test
    public void importRejectsFilesThatAreNeitherZipNorOpenPgp() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-unknown-");
        Path notes = root.resolve("kortty-backup.zip");
        Files.writeString(notes, "not a backup at all");
        FakeDecryptor decryptor = new FakeDecryptor();
        BackupManager manager = new BackupManager(root.resolve("config"), new GlobalSettings(),
            new FakeEncryptor(), decryptor);

        Exception failure = expectThrows(Exception.class, () -> manager.importBackup(notes, null, true));

        assertThat(failure).hasMessageThat().contains("neither a ZIP archive nor GPG-encrypted");
        assertThat(decryptor.calls).isEqualTo(0);

        // An unrecognised file named *.gpg is still handed to gpg, which knows more variants.
        Path gpgNamed = root.resolve("exotic.gpg");
        Files.writeString(gpgNamed, "not recognised");
        Exception gpgFailure = expectThrows(Exception.class, () -> manager.importBackup(gpgNamed, null, true));
        assertThat(decryptor.calls).isEqualTo(1);
        assertThat(gpgFailure).hasMessageThat().contains("GPG decryption failed");
        assertThat(Files.exists(root.resolve("config"))).isFalse();
    }

    @Test
    public void importOfAnEncryptedZipWithoutPasswordAsksForPassword() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-nopassword-");
        Path configDir = sampleConfig(root);
        GlobalSettings settings = new GlobalSettings();
        PasswordSetup password = usePasswordBackup(configDir, settings);
        Path backup = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), password.credentials(), null, password.masterPassword());
        Path restoreDir = Files.createDirectories(root.resolve("restore"));

        Exception failure = expectThrows(Exception.class, () ->
            new BackupManager(restoreDir, new GlobalSettings()).importBackup(backup, null, true));

        assertThat(failure).hasMessageThat().contains("Password required for password-encrypted backup");
        assertThat(fileNames(restoreDir)).isEmpty();
    }

    @Test
    public void overwriteImportOfAnotherMasterKeyReportsThatKorttyMustRestart() throws Exception {
        // The running app keeps the old salt and derived key in memory: reloading the stores
        // with it, or saving them at shutdown, would mix two keys. The caller has to know.
        Path root = Files.createTempDirectory("kortty-backup-masterkey-mismatch-");
        Path configDir = sampleConfig(root);
        GlobalSettings settings = new GlobalSettings();
        PasswordSetup password = usePasswordBackup(configDir, settings);
        Path backup = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), password.credentials(), null, password.masterPassword());
        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        Files.writeString(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE), masterKey("salt-b", "hash-b"));

        BackupManager.ImportResult result = new BackupManager(restoreDir, new GlobalSettings())
            .restoreBackup(backup, "backup-pw", true);

        assertThat(result.masterKeyReplaced()).isTrue();
        assertThat(result.filesImported()).isAtLeast(3);
        assertThat(Files.readString(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE)))
            .contains("salt=salt-a");
    }

    @Test
    public void importOfTheSameMasterKeyNeedsNoRestart() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-masterkey-same-");
        Path configDir = sampleConfig(root);
        GlobalSettings settings = new GlobalSettings();
        PasswordSetup password = usePasswordBackup(configDir, settings);
        Path backup = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), password.credentials(), null, password.masterPassword());
        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        // Same salt and hash, another timestamp comment: still the same master password.
        Files.writeString(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE),
            "#KorTTY Master Password\n#written on another day\nhash=hash-a\nsalt=salt-a\n");

        BackupManager.ImportResult result = new BackupManager(restoreDir, new GlobalSettings())
            .restoreBackup(backup, "backup-pw", true);

        assertThat(result.masterKeyReplaced()).isFalse();
    }

    @Test
    public void restoredSecretStoresAreOwnerOnlyAndNotTruncated() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-restore-modes-");
        Path configDir = sampleConfig(root);
        Files.writeString(configDir.resolve(CredentialManager.CREDENTIALS_FILE), "<credentials/>");
        Files.writeString(configDir.resolve(SSHKeyManager.SSH_KEYS_FILE), "<sshKeys/>");
        Files.writeString(configDir.resolve(JobSchedulerRepository.FILE_NAME), "<jobScheduler/>");
        Files.writeString(configDir.resolve(ThemeManager.THEMES_FILE), "<themes/>");
        GlobalSettings settings = new GlobalSettings();
        PasswordSetup password = usePasswordBackup(configDir, settings);
        Path backup = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), password.credentials(), null, password.masterPassword());
        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        // Local files an older korTTY left world-readable; the restore replaces them.
        Path localConnections = Files.writeString(restoreDir.resolve(XMLConnectionRepository.CONNECTIONS_FILE),
            "<connections><connection>local</connection></connections>");
        Path localThemes = Files.writeString(restoreDir.resolve(ThemeManager.THEMES_FILE), "<themes>local</themes>");
        if (Files.getFileAttributeView(localConnections, java.nio.file.attribute.PosixFileAttributeView.class) == null) {
            throw new org.testng.SkipException("POSIX file attributes are not supported on this platform");
        }
        Files.setPosixFilePermissions(localConnections,
            java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));
        Files.setPosixFilePermissions(localThemes, java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"));

        new BackupManager(restoreDir, new GlobalSettings()).restoreBackup(backup, "backup-pw", true);

        for (String secretStore : List.of(XMLConnectionRepository.CONNECTIONS_FILE, CredentialManager.CREDENTIALS_FILE,
                SSHKeyManager.SSH_KEYS_FILE, JobSchedulerRepository.FILE_NAME, MasterPasswordManager.MASTER_KEY_FILE)) {
            Path restored = restoreDir.resolve(secretStore);
            assertWithMessage(secretStore).that(Files.readAllBytes(restored))
                .isEqualTo(Files.readAllBytes(configDir.resolve(secretStore)));
            assertWithMessage(secretStore)
                .that(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(restored)))
                .isEqualTo("rw-------");
        }
        // The other stores keep the permissions of the file they replace.
        assertThat(Files.readString(localThemes)).isEqualTo("<themes/>");
        assertThat(java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(localThemes)))
            .isEqualTo("rw-r--r--");
        assertThat(fileNames(restoreDir).stream().filter(name -> name.endsWith(".tmp")).toList()).isEmpty();
    }

    @Test
    public void mergeImportKeepsTheLocalMasterKeyAndNeedsNoRestart() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-masterkey-merge-");
        Path configDir = sampleConfig(root);
        GlobalSettings settings = new GlobalSettings();
        PasswordSetup password = usePasswordBackup(configDir, settings);
        Path backup = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), password.credentials(), null, password.masterPassword());
        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        String localKey = masterKey("salt-b", "hash-b");
        Files.writeString(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE), localKey);

        BackupManager.ImportResult result = new BackupManager(restoreDir, new GlobalSettings())
            .restoreBackup(backup, "backup-pw", false);

        assertThat(result.masterKeyReplaced()).isFalse();
        assertThat(Files.readString(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE))).isEqualTo(localKey);
    }

    /**
     * End to end with the real gpg — only when KORTTY_GPG_E2E_RECIPIENT names a throwaway key
     * without a passphrase in a temporary GNUPGHOME (never the user's keyring).
     */
    @Test
    public void gpgBackupRoundTripsWithTheSystemGpg() throws Exception {
        String recipient = System.getenv("KORTTY_GPG_E2E_RECIPIENT");
        if (recipient == null || recipient.isBlank()) {
            throw new org.testng.SkipException("KORTTY_GPG_E2E_RECIPIENT not set");
        }
        Path root = Files.createTempDirectory("kortty-backup-gpg-e2e-");
        Path configDir = sampleConfig(root);
        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.GPG);
        GPGKeyManager keys = new GPGKeyManager(configDir);
        GPGKey key = new GPGKey("e2e", recipient);
        keys.addKey(key);
        settings.setBackupGpgKeyId(key.getId());

        Path backup = new BackupManager(configDir, settings).createBackup(root.resolve("target"), null, keys, null);

        assertThat(backup.getFileName().toString()).isEqualTo("kortty-backup.zip.gpg");
        assertThat(BackupManager.detectBackupFormat(backup)).isEqualTo(BackupManager.BackupFormat.OPENPGP);
        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        new BackupManager(restoreDir, new GlobalSettings()).importBackup(backup, null, true);
        assertThat(Files.readString(restoreDir.resolve("connections.xml"))).isEqualTo("<connections/>");
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_test"))).isEqualTo("fake key material");
    }

    @Test
    public void importRejectsZipEntriesEscapingTheExtractionDirectory() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-zipslip-");
        Path malicious = root.resolve("backup.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(malicious))) {
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("attacker content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        BackupManager manager = new BackupManager(root.resolve("config"), new GlobalSettings());

        Exception failure = expectThrows(Exception.class, () ->
            manager.importBackup(malicious, null, false));

        assertThat(failure).hasMessageThat().contains("Blocked backup ZIP entry");
        // The traversal target must not exist anywhere above the extraction directory.
        try (var walk = Files.walk(root)) {
            assertThat(walk.filter(path -> path.getFileName().toString().equals("escaped.txt"))
                .toList()).isEmpty();
        }
    }

    @Test
    public void managedBackupFilesIncludeSavedAiChats() {
        assertThat(BackupManager.managedBackupFiles()).contains("ai-chats.xml");
    }

    @Test
    public void managedBackupFilesPreserveTrustedSshHostKeys() {
        assertThat(BackupManager.managedBackupFiles()).contains("ssh-host-keys.properties");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("ssh-host-keys.properties.lock");
    }

    @Test
    public void managedBackupContentCoversSshKeyReferencesAndCopiedKeyFiles() {
        assertThat(BackupManager.managedBackupFiles()).contains("ssh-keys.xml");
        // The copied key FILES are included as a directory, like projects/.
        assertThat(BackupManager.managedBackupDirectories())
            .containsExactly("projects", "ssh-keys", SnippetAnalysisStore.DIRECTORY_NAME);
    }

    @Test
    public void managedBackupFilesIncludeTheMasterKeyFile() {
        // The list once carried "master-password-hash" — a name that never existed on disk —
        // so every backup silently omitted the master-password file and a restore onto a
        // fresh profile could not decrypt anything.
        assertThat(BackupManager.managedBackupFiles()).contains(MasterPasswordManager.MASTER_KEY_FILE);
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("master-password-hash");
        // The obfuscated auto-login password is deliberately kept out of backups.
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("master.autounlock");
    }

    @Test
    public void passwordBackupContainsTheMasterKeyFileAndRestoresIt() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-masterkey-");
        Path configDir = Files.createDirectories(root.resolve("config"));
        byte[] masterKeyBytes = "salt-and-hash".getBytes(StandardCharsets.UTF_8);
        Files.write(configDir.resolve(MasterPasswordManager.MASTER_KEY_FILE), masterKeyBytes);
        Files.writeString(configDir.resolve("connections.xml"), "<connections/>");
        Files.createDirectories(configDir.resolve("projects"));
        Files.writeString(configDir.resolve("projects/demo.kortty"), "<project/>");
        Files.createDirectories(configDir.resolve("ssh-keys"));
        Files.writeString(configDir.resolve("ssh-keys/id_test"), "fake key material");

        char[] masterPassword = "master-pw".toCharArray();
        CredentialManager credentialManager = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential(
            "backup", "user", StoredCredential.Environment.PRODUCTION);
        credentialManager.setPassword(credential, "backup-pw", masterPassword);
        credentialManager.addCredential(credential);

        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());

        Path backupZip = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), credentialManager, null, masterPassword);

        try (net.lingala.zip4j.ZipFile zip =
                 new net.lingala.zip4j.ZipFile(backupZip.toFile(), "backup-pw".toCharArray())) {
            assertThat(zip.getFileHeaders().stream()
                    .map(net.lingala.zip4j.model.FileHeader::getFileName)
                    .toList())
                .containsAtLeast(
                    MasterPasswordManager.MASTER_KEY_FILE,
                    "projects/demo.kortty",
                    "ssh-keys/id_test");
            // New backups use AES-256, not legacy ZipCrypto — the archive carries raw key files.
            assertThat(zip.getFileHeader(MasterPasswordManager.MASTER_KEY_FILE)
                    .getEncryptionMethod())
                .isEqualTo(EncryptionMethod.AES);
        }

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        int imported = new BackupManager(restoreDir, new GlobalSettings())
            .importBackup(backupZip, "backup-pw", true);

        assertThat(imported).isAtLeast(4);
        assertThat(Files.readAllBytes(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE)))
            .isEqualTo(masterKeyBytes);
        // Directly under projects/ — not nested into projects/projects/ (former restore bug).
        assertThat(Files.readString(restoreDir.resolve("projects/demo.kortty")))
            .isEqualTo("<project/>");
        assertThat(Files.exists(restoreDir.resolve("projects/projects"))).isFalse();
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_test")))
            .isEqualTo("fake key material");
    }

    @Test
    public void restoreMergesSshKeysWithoutDeletingOrOverwriting() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-keymerge-");
        Path configDir = Files.createDirectories(root.resolve("config"));
        Files.createDirectories(configDir.resolve("ssh-keys"));
        Files.writeString(configDir.resolve("ssh-keys/id_backup"), "from backup");
        Files.writeString(configDir.resolve("ssh-keys/id_shared"), "backup version");

        char[] masterPassword = "master-pw".toCharArray();
        CredentialManager credentialManager = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential(
            "backup", "user", StoredCredential.Environment.PRODUCTION);
        credentialManager.setPassword(credential, "backup-pw", masterPassword);
        credentialManager.addCredential(credential);
        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());
        Path backupZip = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), credentialManager, null, masterPassword);

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        Files.createDirectories(restoreDir.resolve("ssh-keys"));
        Files.writeString(restoreDir.resolve("ssh-keys/id_local_only"), "must survive");
        Files.writeString(restoreDir.resolve("ssh-keys/id_shared"), "local version");

        new BackupManager(restoreDir, new GlobalSettings())
            .importBackup(backupZip, "backup-pw", false);

        // Merge semantics: local-only keys survive, existing files are not overwritten
        // without the overwrite flag, and new keys from the backup arrive.
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_local_only")))
            .isEqualTo("must survive");
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_shared")))
            .isEqualTo("local version");
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_backup")))
            .isEqualTo("from backup");
    }

    @Test
    public void restoreMergesSnippetAnalysesAndTheNewerRevisionWins() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-analyses-");
        Path configDir = Files.createDirectories(root.resolve("config"));
        writeAnalyses(configDir, "backup-only", 1);
        writeAnalyses(configDir, "backup-newer", 5);
        writeAnalyses(configDir, "local-newer", 2);
        Files.writeString(configDir.resolve("snippet-analyses/stray.json.corrupt-20260101-000000"), "junk");

        char[] masterPassword = "master-pw".toCharArray();
        CredentialManager credentialManager = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential(
            "backup", "user", StoredCredential.Environment.PRODUCTION);
        credentialManager.setPassword(credential, "backup-pw", masterPassword);
        credentialManager.addCredential(credential);
        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());
        Path backupZip = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), credentialManager, null, masterPassword);

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        writeAnalyses(restoreDir, "backup-newer", 2);
        writeAnalyses(restoreDir, "local-newer", 9);
        writeAnalyses(restoreDir, "local-only", 1);

        new BackupManager(restoreDir, new GlobalSettings()).importBackup(backupZip, "backup-pw", true);

        Path analyses = restoreDir.resolve(SnippetAnalysisStore.DIRECTORY_NAME);
        assertThat(revision(analyses, "backup-only")).isEqualTo(1L);
        assertThat(revision(analyses, "backup-newer")).isEqualTo(5L);
        assertThat(revision(analyses, "local-newer")).isEqualTo(9L);
        assertThat(revision(analyses, "local-only")).isEqualTo(1L);
        // Quarantined copies are not restored as live data.
        try (var files = Files.list(analyses)) {
            assertThat(files.map(p -> p.getFileName().toString()).filter(n -> n.contains("corrupt")).toList())
                .isEmpty();
        }

        // Without the overwrite flag, existing files are kept even when the backup is newer.
        writeAnalyses(restoreDir, "backup-newer", 2);
        new BackupManager(restoreDir, new GlobalSettings()).importBackup(backupZip, "backup-pw", false);
        assertThat(revision(analyses, "backup-newer")).isEqualTo(2L);
    }

    private static void writeAnalyses(Path configDir, String snippetId, long revision) throws Exception {
        SnippetAnalysisHistory history = SnippetAnalysisHistory.empty(snippetId)
            .withNewCurrent(SnippetAnalysisTestData.simpleRecord("r" + revision, snippetId, revision), 5)
            .withRevision(revision, revision);
        Path directory = Files.createDirectories(configDir.resolve(SnippetAnalysisStore.DIRECTORY_NAME));
        Files.writeString(directory.resolve(snippetId + ".json"), SnippetAnalysisStore.toJson(history));
    }

    private static long revision(Path directory, String snippetId) {
        return SnippetAnalysisStore.readRevision(directory.resolve(snippetId + ".json")).orElse(-1L);
    }

    @Test
    public void importToleratesLegacyBackupsWithoutTheMasterKeyFile() throws Exception {
        // Backups created while the list named the non-existent file contain no master-key
        // entry at all; restoring them must keep working and simply not create the file.
        Path root = Files.createTempDirectory("kortty-backup-legacy-");
        Path legacyZip = root.resolve("kortty-backup.zip");
        try (net.lingala.zip4j.ZipFile zip =
                 new net.lingala.zip4j.ZipFile(legacyZip.toFile(), "backup-pw".toCharArray())) {
            Path connections = root.resolve("connections.xml");
            Files.writeString(connections, "<connections/>");
            ZipParameters parameters = new ZipParameters();
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
            zip.addFile(connections.toFile(), parameters);
        }

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        int imported = new BackupManager(restoreDir, new GlobalSettings())
            .importBackup(legacyZip, "backup-pw", true);

        assertThat(imported).isEqualTo(1);
        assertThat(Files.exists(restoreDir.resolve("connections.xml"))).isTrue();
        assertThat(Files.exists(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE))).isFalse();
    }

    @Test
    public void backupRestoresSnippetFoldersExecutableFlagsAndFolderAnalyses() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-snippet-folders-");
        Path configDir = Files.createDirectories(root.resolve("config"));
        SnippetManager snippets = new SnippetManager(configDir);
        String libId = snippets.ensureFolderPath("tools/lib", null);
        de.kortty.model.Snippet script = new de.kortty.model.Snippet("helper", "def x():\n    pass\n", "python");
        script.setExecutable(Boolean.FALSE);
        script.setFileName("helper_lib.py");
        snippets.addSnippet(script);
        snippets.moveSnippetsToFolder(java.util.List.of(script.getId()), libId);
        snippets.save();
        String toolsId = snippets.findFolder(libId).orElseThrow().getParentId();
        writeAnalyses(configDir, SnippetProjectAiSupport.folderKey(toolsId), 3);

        char[] masterPassword = "master-pw".toCharArray();
        CredentialManager credentialManager = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential(
            "backup", "user", StoredCredential.Environment.PRODUCTION);
        credentialManager.setPassword(credential, "backup-pw", masterPassword);
        credentialManager.addCredential(credential);
        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());
        Path backupZip = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), credentialManager, null, masterPassword);

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        new BackupManager(restoreDir, new GlobalSettings()).importBackup(backupZip, "backup-pw", true);

        SnippetManager restored = new SnippetManager(restoreDir);
        restored.load();
        de.kortty.model.Snippet copy = restored.findById(script.getId()).orElseThrow();
        assertThat(restored.folderPath(copy.getFolderId())).isEqualTo("tools/lib");
        assertThat(copy.getExecutable()).isFalse();
        assertThat(copy.getFileName()).isEqualTo("helper_lib.py");
        assertThat(revision(restoreDir.resolve(SnippetAnalysisStore.DIRECTORY_NAME),
            SnippetProjectAiSupport.folderKey(toolsId))).isEqualTo(3L);
    }

    @Test
    public void managedBackupFilesIncludeOnlyRegenerableLocalAiMetadata() {
        assertThat(BackupManager.managedBackupFiles()).containsAtLeast(
            "llm/models.xml",
            "llm/mlx-models.json",
            "rag/stores.json");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("llm/runtime");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("llm/models");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("rag/index");
    }
}
