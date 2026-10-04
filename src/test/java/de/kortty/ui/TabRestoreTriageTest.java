package de.kortty.ui;

import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionState;
import de.kortty.model.TemporarySSHKey;
import de.kortty.ui.TabRestoreTriage.Classification;
import de.kortty.ui.TabRestoreTriage.Outcome;
import de.kortty.ui.TabRestoreTriage.Reason;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How a saved project tab is sorted when the project opens, over fake credential, vault, key and
 * policy seams of {@link ConnectionAuthResolver}: what opens right away (a local shell whatever its
 * authentication field says, SSH key auth, a stored password, a valid temporary key, an existing
 * local file), what waits for the user (a password that is not stored, an expired temporary key,
 * a password in the locked vault), and what is listed only (a blocked server, a deleted connection, a
 * missing file). Nothing is ever asked, and an expired key is never renewed.
 */
class TabRestoreTriageTest {

    // ---- READY -----------------------------------------------------------------------------

    @Test
    void aSavedLocalShellOpensRightAwayWhateverItsAuthenticationFieldSays() {
        for (AuthMethod method : AuthMethod.values()) {
            Fake fake = new Fake();
            ServerConnection shell = fake.save(connection("shell", ConnectionProtocol.LOCAL_SHELL, method));

            Outcome outcome = fake.classify(terminal(shell));

            assertWithMessage("a local shell with " + method).that(outcome.classification()).isEqualTo(Classification.READY);
            assertThat(outcome.connection()).isSameInstanceAs(shell);
            assertThat(outcome.auth().password()).isNull();
            assertThat(outcome.reason()).isNull();
            assertThat(fake.vaultReads).isEmpty();
        }
    }

    @Test
    void sshKeyAuthAStoredPasswordAndAValidTemporaryKeyOpenRightAway() {
        Fake fake = new Fake();
        ServerConnection key = fake.save(connection("key", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY));
        ServerConnection stored = fake.save(connection("stored", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));
        stored.setEncryptedPassword("enc:s3cret");
        TemporarySSHKey valid = new TemporarySSHKey("VALID", 60);
        fake.keys.put("VALID", valid);
        ServerConnection temporary = fake.save(connection("temporary", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));
        temporary.setTemporaryKeyContent("VALID");

        assertThat(fake.classify(terminal(key)).isReady()).isTrue();
        Outcome withPassword = fake.classify(terminal(stored));
        assertThat(withPassword.isReady()).isTrue();
        assertThat(withPassword.auth().password()).isEqualTo("s3cret");
        Outcome withKey = fake.classify(sftp(temporary));
        assertThat(withKey.isReady()).isTrue();
        assertThat(withKey.auth().temporaryKey()).isSameInstanceAs(valid);
    }

    @Test
    void aLocalFileOpensWhileItExistsAndNeedsNoConnection() {
        Fake fake = new Fake();
        fake.files.add("/home/me/notes.txt");

        Outcome editor = fake.classify(localEditor("/home/me/notes.txt"));

        assertThat(editor).isSameInstanceAs(Outcome.LOCAL_FILE_READY);
        assertThat(editor.auth()).isNull();
        assertThat(fake.policyChecks).isEqualTo(0);
    }

    // ---- waiting for the user ----------------------------------------------------------------

    @Test
    void aPasswordThatIsNotStoredWaitsForSignIn() {
        Fake fake = new Fake();
        ServerConnection web = fake.save(connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));

        Outcome outcome = fake.classify(terminal(web));

        assertThat(outcome.classification()).isEqualTo(Classification.NEEDS_CREDENTIALS);
        assertThat(outcome.reason()).isEqualTo(Reason.PASSWORD);
        assertThat(outcome.classification().waitsForUser()).isTrue();
        assertThat(outcome.connection()).isSameInstanceAs(web);
    }

    @Test
    void anExpiredTemporaryKeyWaitsForANewOneAndIsNotRenewed() {
        Fake fake = new Fake();
        TemporarySSHKey expired = new TemporarySSHKey("EXPIRED", 0);
        fake.keys.put("EXPIRED", expired);
        ServerConnection web = fake.save(connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY));
        web.setTemporaryKeyContent("EXPIRED");

        Outcome outcome = fake.classify(remoteImage(web, "/srv/logo.png"));

        assertThat(outcome.classification()).isEqualTo(Classification.NEEDS_CREDENTIALS);
        assertThat(outcome.reason()).isEqualTo(Reason.TEMPORARY_KEY);
        assertThat(outcome.auth().temporaryKey()).isNull();
        assertThat(fake.keys.get("EXPIRED")).isSameInstanceAs(expired);
    }

    @Test
    void aPasswordInTheLockedVaultWaitsForTheUnlockWithoutReadingIt() {
        Fake fake = new Fake();
        fake.locked = true;
        ServerConnection web = fake.save(connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));
        web.setEncryptedPassword("enc:s3cret");

        Outcome outcome = fake.classify(terminal(web));

        assertThat(outcome.classification()).isEqualTo(Classification.NEEDS_UNLOCK);
        assertThat(outcome.reason()).isEqualTo(Reason.VAULT_LOCKED);
        assertThat(fake.vaultReads).isEmpty();

        // Once the vault is open, the same tab is ready with the stored password.
        fake.locked = false;
        Outcome unlocked = fake.classify(terminal(web));
        assertThat(unlocked.isReady()).isTrue();
        assertThat(unlocked.auth().password()).isEqualTo("s3cret");
    }

    // ---- listed only -------------------------------------------------------------------------

    @Test
    void aServerThePolicyBlocksIsListedWithItsTargetBeforeAnythingElseIsRead() {
        Fake fake = new Fake();
        fake.blockedHosts.add("prod");
        fake.locked = true;
        ServerConnection prod = fake.save(connection("prod", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));
        prod.setEncryptedPassword("enc:s3cret");

        Outcome outcome = fake.classify(terminal(prod));

        assertThat(outcome.classification()).isEqualTo(Classification.BLOCKED);
        assertThat(outcome.reason()).isEqualTo(Reason.BLOCKED);
        assertThat(outcome.blockedTarget()).isEqualTo("prod:22");
        assertThat(outcome.classification().waitsForUser()).isFalse();
        assertThat(fake.vaultReads).isEmpty();
    }

    @Test
    void aDeletedOrNeverSavedConnectionIsListedAsMissing() {
        Fake fake = new Fake();
        SessionState quickConnect = new SessionState("s1", "never-saved");
        SessionState withoutId = new SessionState("s2", null);

        for (SessionState state : List.of(quickConnect, withoutId, sftpState("gone"))) {
            Outcome outcome = fake.classify(state);
            assertThat(outcome.classification()).isEqualTo(Classification.MISSING);
            assertThat(outcome.reason()).isEqualTo(Reason.CONNECTION_MISSING);
            assertThat(outcome.connection()).isNull();
        }
    }

    @Test
    void aLocalFileThatIsGoneIsListedAsMissingAndAFileTabWithoutAPathHasNothingToOpen() {
        Fake fake = new Fake();

        Outcome gone = fake.classify(localEditor("/home/me/deleted.txt"));
        assertThat(gone).isSameInstanceAs(Outcome.LOCAL_FILE_MISSING);
        assertThat(gone.reason()).isEqualTo(Reason.FILE_MISSING);

        SessionState noPath = new SessionState("s", null);
        noPath.setTabType(SessionState.TabType.IMAGE_VIEWER);
        assertThat(fake.classify(noPath)).isNull();
    }

    @Test
    void theClassesOfTheResolverMapOntoTheRestoreClasses() {
        assertThat(Classification.of(ConnectionAuthResolver.Status.READY)).isEqualTo(Classification.READY);
        assertThat(Classification.of(ConnectionAuthResolver.Status.NEEDS_PASSWORD)).isEqualTo(Classification.NEEDS_CREDENTIALS);
        assertThat(Classification.of(ConnectionAuthResolver.Status.NEEDS_TEMP_KEY)).isEqualTo(Classification.NEEDS_CREDENTIALS);
        assertThat(Classification.of(ConnectionAuthResolver.Status.NEEDS_UNLOCK)).isEqualTo(Classification.NEEDS_UNLOCK);
        assertThat(Classification.of(ConnectionAuthResolver.Status.BLOCKED)).isEqualTo(Classification.BLOCKED);
        assertThat(Classification.of(ConnectionAuthResolver.Status.MISSING)).isEqualTo(Classification.MISSING);
        List<Classification> waiting = Arrays.stream(Classification.values()).filter(Classification::waitsForUser).toList();
        assertThat(waiting).containsExactly(Classification.NEEDS_UNLOCK, Classification.NEEDS_CREDENTIALS);
    }

    @Test
    void onlyTerminalSftpAndRemoteFileTabsOpenOverAConnection() {
        assertThat(TabRestoreTriage.opensOverConnection(new SessionState("s", "c"))).isTrue();
        SessionState legacy = new SessionState("s", "c");
        legacy.setTabType(null);
        assertThat(TabRestoreTriage.opensOverConnection(legacy)).isTrue();
        assertThat(TabRestoreTriage.opensOverConnection(sftpState("c"))).isTrue();
        assertThat(TabRestoreTriage.opensOverConnection(localEditor("/a"))).isFalse();
        SessionState localImage = new SessionState("s", null);
        localImage.setTabType(SessionState.TabType.IMAGE_VIEWER);
        localImage.setImageFilePath("/a.png");
        localImage.setImageIsRemote(false);
        assertThat(TabRestoreTriage.opensOverConnection(localImage)).isFalse();
        localImage.setImageIsRemote(true);
        assertThat(TabRestoreTriage.opensOverConnection(localImage)).isTrue();
    }

    @Test
    void theOutcomeNeverPrintsThePassword() {
        Fake fake = new Fake();
        ServerConnection stored = fake.save(connection("stored", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));
        stored.setEncryptedPassword("enc:s3cret");

        assertThat(fake.classify(terminal(stored)).toString()).doesNotContain("s3cret");
    }

    // ---- labels ------------------------------------------------------------------------------

    @Test
    void theBarNamesATabByItsOwnNameItsConnectionOrItsPosition() {
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        web.setName("Web 01");
        SessionState renamed = terminal(web);
        renamed.setTabTitle("deploy");

        assertThat(TabRestoreTriage.label(renamed, web, 0, TabRestoreTriageTest::texts)).isEqualTo("deploy");
        assertThat(TabRestoreTriage.label(terminal(web), web, 0, TabRestoreTriageTest::texts)).isEqualTo("Web 01");
        assertThat(TabRestoreTriage.label(new SessionState("s", "gone"), null, 2, TabRestoreTriageTest::texts))
            .isEqualTo("[session.restore.tab.terminal|3]");
        assertThat(TabRestoreTriage.label(sftpState(web.getId()), web, 0, TabRestoreTriageTest::texts))
            .isEqualTo("[session.restore.tab.sftp|Web 01]");
        assertThat(TabRestoreTriage.label(sftpState("gone"), null, 4, TabRestoreTriageTest::texts))
            .isEqualTo("[session.restore.tab.sftpUnnamed|5]");
        assertThat(TabRestoreTriage.label(localEditor("/home/me/notes.txt"), null, 0, TabRestoreTriageTest::texts))
            .isEqualTo("notes.txt");
        assertThat(TabRestoreTriage.label(remoteImage(web, "C:\\logos\\logo.png"), web, 0, TabRestoreTriageTest::texts))
            .isEqualTo("logo.png");
    }

    @Test
    void aNameFromAProjectFileIsCleanedAndShortened() {
        SessionState crafted = new SessionState("s", "gone");
        crafted.setTabTitle("evil\u001b[31m\u202Egnp.exe\n" + "x".repeat(200));

        String label = TabRestoreTriage.label(crafted, null, 0, TabRestoreTriageTest::texts);

        assertThat(label).doesNotContain("\u001b");
        assertThat(label).doesNotContain("\u202E");
        assertThat(label).doesNotContain("\n");
        assertThat(label.codePointCount(0, label.length())).isAtMost(TabRestoreTriage.MAX_LABEL_LENGTH);
    }

    @Test
    void fileNamesAreTakenFromEitherSeparator() {
        assertThat(TabRestoreTriage.fileName("/srv/www/index.html")).isEqualTo("index.html");
        assertThat(TabRestoreTriage.fileName("C:\\Users\\me\\a.txt")).isEqualTo("a.txt");
        assertThat(TabRestoreTriage.fileName("/srv/www/")).isEqualTo("www");
        assertThat(TabRestoreTriage.fileName("plain")).isEqualTo("plain");
        assertThat(TabRestoreTriage.fileName(null)).isEmpty();
    }

    // ---- helpers -----------------------------------------------------------------------------

    private static String texts(String key, Object... args) {
        StringBuilder text = new StringBuilder("[").append(key);
        for (Object arg : args) {
            text.append('|').append(arg);
        }
        return text.append(']').toString();
    }

    private static ServerConnection connection(String host, ConnectionProtocol protocol, AuthMethod method) {
        ServerConnection connection = new ServerConnection(host, host, 22, "me");
        connection.setProtocol(protocol);
        connection.setAuthMethod(method);
        return connection;
    }

    private static SessionState terminal(ServerConnection connection) {
        return new SessionState("session-" + connection.getHost(), connection.getId());
    }

    private static SessionState sftp(ServerConnection connection) {
        return sftpState(connection.getId());
    }

    private static SessionState sftpState(String connectionId) {
        SessionState state = new SessionState("sftp", connectionId);
        state.setTabType(SessionState.TabType.SFTP_MANAGER);
        return state;
    }

    private static SessionState localEditor(String path) {
        SessionState state = new SessionState("editor", null);
        state.setTabType(SessionState.TabType.FILE_EDITOR);
        state.setEditorFilePath(path);
        state.setEditorIsRemote(false);
        return state;
    }

    private static SessionState remoteImage(ServerConnection connection, String path) {
        SessionState state = new SessionState("image", connection.getId());
        state.setTabType(SessionState.TabType.IMAGE_VIEWER);
        state.setImageFilePath(path);
        state.setImageIsRemote(true);
        return state;
    }

    /** Fake seams: policy by host, saved connections by id, keys by text, vault in the connection; prompts fail. */
    private static final class Fake {
        final Set<String> blockedHosts = new HashSet<>();
        final Map<String, ServerConnection> saved = new HashMap<>();
        final Map<String, TemporarySSHKey> keys = new HashMap<>();
        final Set<String> files = new HashSet<>();
        final List<ServerConnection> vaultReads = new ArrayList<>();
        boolean locked;
        int policyChecks;

        ServerConnection save(ServerConnection connection) {
            saved.put(connection.getId(), connection);
            return connection;
        }

        Outcome classify(SessionState state) {
            return TabRestoreTriage.classify(state,
                tab -> tab.getConnectionId() != null ? saved.get(tab.getConnectionId()) : null,
                resolver(), files::contains);
        }

        ConnectionAuthResolver resolver() {
            ConnectionAuthResolver.Seams seams = new ConnectionAuthResolver.Seams(
                connection -> {
                    policyChecks++;
                    return blockedHosts.contains(connection.getHost())
                        ? Optional.of(connection.getHost() + ":" + connection.getPort())
                        : Optional.empty();
                },
                saved::get,
                () -> ConnectionAuthResolver.TeamworkDefaults.NONE,
                keys::get,
                new ConnectionAuthResolver.Credentials() {
                    @Override
                    public boolean holdsPassword(String credentialId) {
                        return false;
                    }

                    @Override
                    public String password(String credentialId) {
                        return null;
                    }
                },
                new ConnectionAuthResolver.Vault() {
                    @Override
                    public boolean isLocked() {
                        return locked;
                    }

                    @Override
                    public String password(ServerConnection connection) {
                        vaultReads.add(connection);
                        String encrypted = connection.getEncryptedPassword();
                        return encrypted == null ? null : encrypted.substring("enc:".length());
                    }
                });
            return new ConnectionAuthResolver(seams, new ConnectionAuthResolver.Prompts() {
                @Override
                public String password(ServerConnection connection) {
                    throw new AssertionError("a restore never asks for a password");
                }

                @Override
                public TemporarySSHKey temporaryKey(ServerConnection connection) {
                    throw new AssertionError("a restore never asks for a temporary key");
                }

                @Override
                public boolean unlockVault(ServerConnection connection) {
                    throw new AssertionError("a restore never offers the vault unlock");
                }
            });
        }
    }
}
