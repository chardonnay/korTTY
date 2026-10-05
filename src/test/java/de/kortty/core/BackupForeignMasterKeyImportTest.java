package de.kortty.core;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import de.kortty.jobscheduler.JobSchedulerRepository;
import de.kortty.jobscheduler.SudoCredential;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SSHKey;
import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.persistence.XMLConnectionRepository;
import de.kortty.security.EncryptionService;
import de.kortty.security.MasterPasswordManager;
import de.kortty.security.MasterPasswordReEncryptor;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * A merge import of a backup made under another master password: the backup's secrets are
 * re-encrypted with the current master password, or — without the backup's password — the files
 * holding them are not imported. Two real master keys (A for the backup, B for this installation).
 */
public class BackupForeignMasterKeyImportTest {

    private static final EncryptionService ENC = new EncryptionService();
    private static final String PW_A = "backup-master-A";
    private static final String PW_B = "local-master-B";
    private static final String ZIP_PASSWORD = "zip-pw";
    private static final String TEMP_KEY = "-----BEGIN OPENSSH PRIVATE KEY-----\nfake-temporary-key\n-----END OPENSSH PRIVATE KEY-----";

    /** A backup made by installation A, plus the credential id of the secret it carries. */
    private record SourceBackup(Path backup, String credentialId, String foreignCredentialId) {
    }

    /** Installation A with one secret in every secret-bearing store, backed up as a password ZIP. */
    private static SourceBackup backupOfInstallationA(Path root, boolean withForeignSecret) throws Exception {
        Path a = Files.createDirectories(root.resolve("a"));
        MasterPasswordManager mpm = new MasterPasswordManager(a);
        mpm.setupPassword(PW_A.toCharArray());
        char[] pw = PW_A.toCharArray();

        ServerConnection connection = new ServerConnection("web", "host.example", 22, "admin");
        connection.setEncryptedPassword(ENC.encryptPassword("conn-secret", pw));
        connection.setTemporaryKeyContent(TEMP_KEY);
        XMLConnectionRepository.writeConnections(List.of(connection),
            a.resolve(XMLConnectionRepository.CONNECTIONS_FILE), mpm.getDerivedKey());

        CredentialManager credentials = new CredentialManager(a);
        StoredCredential stored = new StoredCredential("db", "root", StoredCredential.Environment.PRODUCTION);
        credentials.setPassword(stored, "cred-secret", pw);
        credentials.addCredential(stored);
        StoredCredential zip = new StoredCredential("backup", "user", StoredCredential.Environment.PRODUCTION);
        credentials.setPassword(zip, ZIP_PASSWORD, pw);
        credentials.addCredential(zip);
        String foreignId = null;
        if (withForeignSecret) {
            // Already undecryptable in installation A (encrypted under a third password).
            StoredCredential foreign = new StoredCredential("stale", "x", StoredCredential.Environment.PRODUCTION);
            foreign.setEncryptedPassword(ENC.encryptPassword("stale", "third-password".toCharArray()));
            credentials.addCredential(foreign);
            foreignId = foreign.getId();
        }
        credentials.save();

        SSHKeyManager keys = new SSHKeyManager(a);
        SSHKey key = new SSHKey("id", "/keys/id_ed25519");
        key.setEncryptedPassphrase(ENC.encryptPassword("key-pass", pw));
        keys.addKey(key);
        keys.save();

        JobSchedulerRepository jobs = new JobSchedulerRepository(a);
        SudoCredential sudo = new SudoCredential();
        sudo.setId("sudo-1");
        sudo.setEncryptedPassword(ENC.encryptPassword("sudo-secret", pw));
        jobs.upsertSudoCredential(sudo);
        jobs.save();

        Files.writeString(a.resolve(ThemeManager.THEMES_FILE), "<themes/>");

        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(zip.getId());
        Path backup = new BackupManager(a, settings).createBackup(root.resolve("target"), credentials, null, pw);
        return new SourceBackup(backup, stored.getId(), foreignId);
    }

    /** Installation B: its own master password, no stores yet (a merge copies them all). */
    private static Path installationB(Path root) throws Exception {
        Path b = Files.createDirectories(root.resolve("b"));
        new MasterPasswordManager(b).setupPassword(PW_B.toCharArray());
        return b;
    }

    private static SecretKey derivedKey(Path configDir, String password) throws Exception {
        MasterPasswordManager mpm = new MasterPasswordManager(configDir);
        assertThat(mpm.verifyPassword(password.toCharArray())).isTrue();
        return mpm.getDerivedKey();
    }

    /** A prompt that answers with the given passwords in turn (null = cancel) and records attempts. */
    private static final class ScriptedPrompt implements BackupManager.BackupMasterPasswordPrompt {
        final List<String> answers;
        final List<Integer> attempts = new ArrayList<>();
        final List<List<String>> files = new ArrayList<>();

        ScriptedPrompt(String... answers) {
            this.answers = new ArrayList<>(java.util.Arrays.asList(answers));
        }

        @Override
        public char[] requestBackupMasterPassword(int attempt, List<String> secretFiles) {
            attempts.add(attempt);
            files.add(secretFiles);
            String answer = answers.isEmpty() ? null : answers.remove(0);
            return answer == null ? null : answer.toCharArray();
        }
    }

    @Test
    public void mergeImportReEncryptsEverySecretWithTheCurrentMasterPassword() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-merge-");
        SourceBackup source = backupOfInstallationA(root, false);
        Path b = installationB(root);
        String localKey = Files.readString(b.resolve(MasterPasswordManager.MASTER_KEY_FILE));
        ScriptedPrompt prompt = new ScriptedPrompt(PW_A);

        ch.qos.logback.classic.Logger[] loggers = {
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BackupManager.class),
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(BackupSecretReKeyer.class),
            (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(MasterPasswordReEncryptor.class)};
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        List<Level> previous = new ArrayList<>();
        for (ch.qos.logback.classic.Logger logger : loggers) {
            previous.add(logger.getLevel());
            logger.setLevel(Level.DEBUG);
            logger.addAppender(logs);
        }
        BackupManager.ImportResult result;
        try {
            result = new BackupManager(b, new GlobalSettings())
                .restoreBackup(source.backup(), ZIP_PASSWORD, false, PW_B.toCharArray(), prompt);
        } finally {
            for (int i = 0; i < loggers.length; i++) {
                loggers[i].detachAppender(logs);
                loggers[i].setLevel(previous.get(i));
            }
        }

        assertThat(prompt.attempts).containsExactly(1);
        assertThat(prompt.files.get(0)).containsAtLeast(XMLConnectionRepository.CONNECTIONS_FILE,
            CredentialManager.CREDENTIALS_FILE, SSHKeyManager.SSH_KEYS_FILE, JobSchedulerRepository.FILE_NAME);
        assertThat(result.masterKeyReplaced()).isFalse();
        assertThat(result.skippedSecretFiles()).isEmpty();
        assertThat(result.secretsCleared()).isEqualTo(0);
        // conn password, 2 credentials, key passphrase, sudo password
        assertThat(result.secretsReEncrypted()).isEqualTo(5);
        assertThat(Files.readString(b.resolve(MasterPasswordManager.MASTER_KEY_FILE))).isEqualTo(localKey);
        assertThat(Files.exists(b.resolve(ThemeManager.THEMES_FILE))).isTrue();

        char[] pwB = PW_B.toCharArray();
        List<ServerConnection> connections = XMLConnectionRepository.readConnections(
            b.resolve(XMLConnectionRepository.CONNECTIONS_FILE), derivedKey(b, PW_B));
        assertThat(ENC.decryptPassword(connections.get(0).getEncryptedPassword(), pwB)).isEqualTo("conn-secret");
        assertThat(connections.get(0).getTemporaryKeyContent()).isEqualTo(TEMP_KEY);
        expectThrows(Exception.class,
            () -> ENC.decryptPassword(connections.get(0).getEncryptedPassword(), PW_A.toCharArray()));

        CredentialManager credentials = new CredentialManager(b);
        credentials.load();
        StoredCredential stored = credentials.getAllCredentials().stream()
            .filter(c -> c.getId().equals(source.credentialId())).findFirst().orElseThrow();
        assertThat(credentials.getPassword(stored, pwB)).isEqualTo("cred-secret");

        SSHKeyManager keys = new SSHKeyManager(b);
        keys.load();
        assertThat(ENC.decryptPassword(keys.getAllKeys().get(0).getEncryptedPassphrase(), pwB)).isEqualTo("key-pass");

        JobSchedulerRepository jobs = new JobSchedulerRepository(b);
        jobs.load();
        assertThat(ENC.decryptPassword(jobs.getSudoCredentials().get(0).getEncryptedPassword(), pwB))
            .isEqualTo("sudo-secret");

        for (ILoggingEvent event : logs.list) {
            assertThat(event.getFormattedMessage()).doesNotContain(PW_A);
            assertThat(event.getFormattedMessage()).doesNotContain(PW_B);
        }
    }

    @Test
    public void cancellingThePromptImportsNoSecretBearingFile() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-cancel-");
        SourceBackup source = backupOfInstallationA(root, false);
        Path b = installationB(root);
        ScriptedPrompt prompt = new ScriptedPrompt((String) null);

        BackupManager.ImportResult result = new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, false, PW_B.toCharArray(), prompt);

        assertThat(prompt.attempts).containsExactly(1);
        assertNoSecretFileImported(b, result);
    }

    @Test
    public void threeWrongPasswordsImportNoSecretBearingFile() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-wrong-");
        SourceBackup source = backupOfInstallationA(root, false);
        Path b = installationB(root);
        // The current password is not the backup's either.
        ScriptedPrompt prompt = new ScriptedPrompt("nope", PW_B, "", PW_A);

        BackupManager.ImportResult result = new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, false, PW_B.toCharArray(), prompt);

        assertThat(prompt.attempts).containsExactly(1, 2, 3).inOrder();
        assertNoSecretFileImported(b, result);
    }

    @Test
    public void aWrongPasswordCanBeCorrected() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-retry-");
        SourceBackup source = backupOfInstallationA(root, false);
        Path b = installationB(root);
        ScriptedPrompt prompt = new ScriptedPrompt("typo", PW_A);

        BackupManager.ImportResult result = new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, false, PW_B.toCharArray(), prompt);

        assertThat(prompt.attempts).containsExactly(1, 2).inOrder();
        assertThat(result.skippedSecretFiles()).isEmpty();
        CredentialManager credentials = new CredentialManager(b);
        credentials.load();
        StoredCredential stored = credentials.getAllCredentials().stream()
            .filter(c -> c.getId().equals(source.credentialId())).findFirst().orElseThrow();
        assertThat(credentials.getPassword(stored, PW_B.toCharArray())).isEqualTo("cred-secret");
    }

    @Test
    public void aSecretTheBackupPasswordCannotDecryptIsClearedNotImportedAsForeignCiphertext() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-stale-");
        SourceBackup source = backupOfInstallationA(root, true);
        Path b = installationB(root);

        BackupManager.ImportResult result = new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, false, PW_B.toCharArray(), new ScriptedPrompt(PW_A));

        assertThat(result.secretsCleared()).isEqualTo(1);
        CredentialManager credentials = new CredentialManager(b);
        credentials.load();
        StoredCredential stale = credentials.getAllCredentials().stream()
            .filter(c -> c.getId().equals(source.foreignCredentialId())).findFirst().orElseThrow();
        assertThat(stale.getEncryptedPassword()).isNull();
        StoredCredential stored = credentials.getAllCredentials().stream()
            .filter(c -> c.getId().equals(source.credentialId())).findFirst().orElseThrow();
        assertThat(credentials.getPassword(stored, PW_B.toCharArray())).isEqualTo("cred-secret");
    }

    @Test
    public void withoutAPromptOrAnUnlockedPasswordNoSecretBearingFileIsImported() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-noprompt-");
        SourceBackup source = backupOfInstallationA(root, false);
        Path b = installationB(root);

        BackupManager.ImportResult legacy = new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, false);
        assertNoSecretFileImported(b, legacy);

        ScriptedPrompt prompt = new ScriptedPrompt(PW_A);
        BackupManager.ImportResult locked = new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, false, null, prompt);
        assertThat(prompt.attempts).isEmpty();
        assertNoSecretFileImported(b, locked);
    }

    @Test
    public void localFilesAreNeverTouchedAndOnlyMissingSecretFilesNeedThePassword() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-partial-");
        SourceBackup source = backupOfInstallationA(root, false);
        Path b = installationB(root);
        String localConnections = "<connections/>";
        Files.writeString(b.resolve(XMLConnectionRepository.CONNECTIONS_FILE), localConnections);
        ScriptedPrompt prompt = new ScriptedPrompt(PW_A);

        new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, false, PW_B.toCharArray(), prompt);

        assertThat(prompt.files.get(0)).doesNotContain(XMLConnectionRepository.CONNECTIONS_FILE);
        assertThat(Files.readString(b.resolve(XMLConnectionRepository.CONNECTIONS_FILE))).isEqualTo(localConnections);
    }

    @Test
    public void theSameMasterKeyNeverAsks() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-same-");
        SourceBackup source = backupOfInstallationA(root, false);
        Path b = Files.createDirectories(root.resolve("b"));
        Files.copy(root.resolve("a").resolve(MasterPasswordManager.MASTER_KEY_FILE),
            b.resolve(MasterPasswordManager.MASTER_KEY_FILE));
        ScriptedPrompt prompt = new ScriptedPrompt(PW_A);

        BackupManager.ImportResult result = new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, false, PW_A.toCharArray(), prompt);

        assertThat(prompt.attempts).isEmpty();
        assertThat(result.skippedSecretFiles()).isEmpty();
        assertThat(Files.readAllBytes(b.resolve(CredentialManager.CREDENTIALS_FILE)))
            .isEqualTo(Files.readAllBytes(root.resolve("a").resolve(CredentialManager.CREDENTIALS_FILE)));
    }

    @Test
    public void anOverwriteImportOfAnotherMasterKeyStillRestartsWithoutAsking() throws Exception {
        Path root = Files.createTempDirectory("kortty-foreign-key-overwrite-");
        SourceBackup source = backupOfInstallationA(root, false);
        Path b = installationB(root);
        ScriptedPrompt prompt = new ScriptedPrompt(PW_A);

        BackupManager.ImportResult result = new BackupManager(b, new GlobalSettings())
            .restoreBackup(source.backup(), ZIP_PASSWORD, true, PW_B.toCharArray(), prompt);

        assertThat(result.masterKeyReplaced()).isTrue();
        assertThat(prompt.attempts).isEmpty();
        // The backup's files arrive unchanged together with the backup's master key.
        assertThat(Files.readAllBytes(b.resolve(CredentialManager.CREDENTIALS_FILE)))
            .isEqualTo(Files.readAllBytes(root.resolve("a").resolve(CredentialManager.CREDENTIALS_FILE)));
    }

    @Test
    public void theImportMenuPassesTheCurrentPasswordAndAMaskedPrompt() throws Exception {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/MainWindow.java"))
            .replace("\r\n", "\n");
        int restore = source.indexOf("app.getBackupManager().restoreBackup(");
        assertThat(restore).isAtLeast(0);
        String call = source.substring(restore, source.indexOf(");", restore));
        assertThat(call).contains("currentCopy");
        assertThat(call).contains("MainWindow.this::askBackupMasterPassword");
        int dialog = source.indexOf("private char[] showBackupMasterPasswordDialog(");
        assertThat(dialog).isAtLeast(0);
        String body = source.substring(dialog, source.indexOf("private void showBackupImportError(", dialog));
        assertThat(body).contains("new PasswordField()");
        assertThat(body).doesNotContain("logger.");
    }

    private static void assertNoSecretFileImported(Path b, BackupManager.ImportResult result) {
        assertThat(result.skippedSecretFiles()).containsAtLeast(XMLConnectionRepository.CONNECTIONS_FILE,
            CredentialManager.CREDENTIALS_FILE, SSHKeyManager.SSH_KEYS_FILE, JobSchedulerRepository.FILE_NAME);
        assertThat(result.secretsReEncrypted()).isEqualTo(0);
        for (String name : result.skippedSecretFiles()) {
            assertThat(Files.exists(b.resolve(name))).isFalse();
        }
        // The files without secrets are still imported, the local master key stays.
        assertThat(Files.exists(b.resolve(ThemeManager.THEMES_FILE))).isTrue();
        assertThat(result.masterKeyReplaced()).isFalse();
    }
}
