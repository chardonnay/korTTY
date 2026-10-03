package de.kortty.ui;

import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.ServerAccessPolicy;
import de.kortty.ui.ConnectionAuthResolver.Resolution;
import de.kortty.ui.ConnectionAuthResolver.Status;
import de.kortty.ui.ConnectionAuthResolver.TeamworkDefaults;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static com.google.common.truth.Truth.assertThat;

/**
 * The one sign-in flow behind the Connection Manager, Duplicate and the later reopen, palette and
 * restore paths, against fake credential, vault, key and policy seams: what is ready, what still
 * needs a password, a temporary key or the vault, that the server policy refuses a target before
 * anything is asked, and that a kept connection loses its temporary key.
 */
class ConnectionAuthResolverTest {

    // ---- READY -----------------------------------------------------------------------------

    @Test
    void aLocalShellIsReadyWithoutAPasswordWhateverItsAuthMethodSays() {
        for (AuthMethod method : AuthMethod.values()) {
            Fake fake = new Fake();
            ServerConnection shell = connection("shell", ConnectionProtocol.LOCAL_SHELL, method);
            shell.setEncryptedPassword("enc:ignored");

            Resolution auth = fake.resolver(Prompts.failing()).resolve(shell, true);

            assertThat(auth.status()).isEqualTo(Status.READY);
            assertThat(auth.connection()).isSameInstanceAs(shell);
            assertThat(auth.password()).isNull();
            assertThat(auth.temporaryKey()).isNull();
            assertThat(fake.vaultReads).isEmpty();
        }
    }

    @Test
    void sshKeyAuthIsReadyWithoutAPassword() {
        Fake fake = new Fake();
        ServerConnection key = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);

        Resolution auth = fake.resolver(Prompts.failing()).resolve(key, true);

        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.password()).isNull();
        assertThat(fake.vaultReads).isEmpty();
        assertThat(fake.credentialReads).isEmpty();
    }

    @Test
    void aStoredPasswordIsReadyWithoutAPrompt() {
        for (AuthMethod method : List.of(AuthMethod.PASSWORD, AuthMethod.KEYBOARD_INTERACTIVE)) {
            Fake fake = new Fake();
            ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, method);
            web.setEncryptedPassword("enc:s3cret");

            Resolution auth = fake.resolver(Prompts.failing()).resolve(web, true);

            assertThat(auth.status()).isEqualTo(Status.READY);
            assertThat(auth.password()).isEqualTo("s3cret");
            assertThat(fake.vaultReads).containsExactly(web);
        }
    }

    @Test
    void theCredentialStoreWinsOverTheConnectionsOwnPassword() {
        Fake fake = new Fake();
        fake.credentialPasswords.put("cred-1", "from-credential");
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        web.setCredentialId("cred-1");
        web.setEncryptedPassword("enc:from-connection");

        Resolution auth = fake.resolver(Prompts.failing()).resolve(web, false);

        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.password()).isEqualTo("from-credential");
        assertThat(fake.vaultReads).isEmpty();
    }

    @Test
    void aCredentialThatCannotBeReadFallsBackToTheConnectionsPassword() {
        Fake fake = new Fake();
        fake.credentialPasswords.put("cred-1", "unused");
        fake.credentialFails = true;
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        web.setCredentialId("cred-1");
        web.setEncryptedPassword("enc:from-connection");

        Resolution auth = fake.resolver(Prompts.failing()).resolve(web, false);

        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.password()).isEqualTo("from-connection");
    }

    @Test
    void aValidRegisteredTemporaryKeyIsReadyWithThatKeyAndNoPrompt() {
        Fake fake = new Fake();
        TemporarySSHKey key = new TemporarySSHKey("KEY-TEXT", 60);
        fake.keys.put("KEY-TEXT", key);
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        web.setTemporaryKeyContent("KEY-TEXT");

        Resolution auth = fake.resolver(Prompts.failing()).resolve(web, true);

        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.temporaryKey()).isSameInstanceAs(key);
        assertThat(auth.password()).isNull();
        assertThat(fake.vaultReads).isEmpty();
    }

    // ---- NEEDS_PASSWORD ----------------------------------------------------------------------

    @Test
    void aPasswordLoginWithNothingStoredNeedsAPassword() {
        Fake fake = new Fake();
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);

        assertThat(fake.resolver(Prompts.failing()).resolve(web, false).status()).isEqualTo(Status.NEEDS_PASSWORD);
    }

    @Test
    void interactivelyTheTypedPasswordMakesItReadyAndCancelKeepsItWaiting() {
        Fake fake = new Fake();
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);

        Prompts typed = new Prompts();
        typed.password = "typed";
        Resolution auth = fake.resolver(typed).resolve(web, true);
        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.password()).isEqualTo("typed");
        assertThat(typed.asked).containsExactly("password:web");

        for (String cancelled : new String[] {null, ""}) {
            Prompts cancel = new Prompts();
            cancel.password = cancelled;
            Resolution waiting = fake.resolver(cancel).resolve(web, true);
            assertThat(waiting.status()).isEqualTo(Status.NEEDS_PASSWORD);
            assertThat(waiting.isReady()).isFalse();
            assertThat(waiting.password()).isNull();
        }
    }

    @Test
    void aStoredPasswordThatCannotBeDecryptedIsAskedForInsteadOfFailing() {
        Fake fake = new Fake();
        fake.vaultFails = true;
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        web.setEncryptedPassword("enc:broken");

        assertThat(fake.resolver(Prompts.failing()).resolve(web, false).status()).isEqualTo(Status.NEEDS_PASSWORD);
    }

    // ---- NEEDS_TEMP_KEY ----------------------------------------------------------------------

    @Test
    void anExpiredOrUnregisteredTemporaryKeyNeedsANewKeyAndIsNeverRenewedNonInteractively() {
        Fake fake = new Fake();
        fake.keys.put("EXPIRED", new TemporarySSHKey("EXPIRED", 0));
        for (String content : List.of("EXPIRED", "NEVER-REGISTERED")) {
            ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);
            web.setTemporaryKeyContent(content);

            Resolution auth = fake.resolver(Prompts.failing()).resolve(web, false);

            assertThat(auth.status()).isEqualTo(Status.NEEDS_TEMP_KEY);
            assertThat(auth.temporaryKey()).isNull();
        }
    }

    @Test
    void interactivelyAnExpiredKeyAsksForANewOne() {
        Fake fake = new Fake();
        fake.keys.put("EXPIRED", new TemporarySSHKey("EXPIRED", 0));
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);
        web.setTemporaryKeyContent("EXPIRED");

        Prompts prompts = new Prompts();
        prompts.key = new TemporarySSHKey("NEW", 30);
        Resolution auth = fake.resolver(prompts).resolve(web, true);
        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.temporaryKey()).isSameInstanceAs(prompts.key);
        assertThat(prompts.asked).containsExactly("temporaryKey:web");

        Resolution cancelled = fake.resolver(new Prompts()).resolve(web, true);
        assertThat(cancelled.status()).isEqualTo(Status.NEEDS_TEMP_KEY);
    }

    // ---- NEEDS_UNLOCK ------------------------------------------------------------------------

    @Test
    void aPasswordInTheLockedVaultNeedsTheVaultUnlockedAndIsNotRead() {
        Fake fake = new Fake();
        fake.locked = true;
        fake.credentialPasswords.put("cred-1", "from-credential");
        ServerConnection own = connection("own", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        own.setEncryptedPassword("enc:s3cret");
        ServerConnection viaCredential = connection("cred", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        viaCredential.setCredentialId("cred-1");

        for (ServerConnection connection : List.of(own, viaCredential)) {
            assertThat(fake.resolver(Prompts.failing()).resolve(connection, false).status())
                .isEqualTo(Status.NEEDS_UNLOCK);
        }
        assertThat(fake.vaultReads).isEmpty();
        assertThat(fake.credentialReads).isEmpty();
    }

    @Test
    void aLockedVaultWithNothingStoredJustAsksForThePassword() {
        Fake fake = new Fake();
        fake.locked = true;
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        web.setCredentialId("no-such-credential");

        Prompts prompts = new Prompts();
        prompts.password = "typed";
        Resolution auth = fake.resolver(prompts).resolve(web, true);

        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(prompts.asked).containsExactly("password:web");
    }

    @Test
    void unlockingTheVaultUsesTheStoredPasswordAndDecliningAsksForIt() {
        Fake fake = new Fake();
        fake.locked = true;
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        web.setEncryptedPassword("enc:s3cret");

        Prompts unlock = new Prompts();
        unlock.unlockAction = () -> fake.locked = false;
        Resolution unlocked = fake.resolver(unlock).resolve(web, true);
        assertThat(unlocked.status()).isEqualTo(Status.READY);
        assertThat(unlocked.password()).isEqualTo("s3cret");
        assertThat(unlock.asked).containsExactly("unlock:web");

        fake.locked = true;
        Prompts decline = new Prompts();
        decline.password = "typed";
        Resolution typed = fake.resolver(decline).resolve(web, true);
        assertThat(typed.status()).isEqualTo(Status.READY);
        assertThat(typed.password()).isEqualTo("typed");
        assertThat(decline.asked).containsExactly("unlock:web", "password:web").inOrder();
    }

    // ---- BLOCKED -----------------------------------------------------------------------------

    @Test
    void aBlockedTargetIsRefusedBeforeAnyPromptOrSecretLookup() {
        Fake fake = new Fake();
        fake.blockedHosts.add("vault.acme.com");
        fake.locked = true;
        List<ServerConnection> connections = new ArrayList<>();
        connections.add(connection("vault.acme.com", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));
        ServerConnection stored = connection("vault.acme.com", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        stored.setEncryptedPassword("enc:s3cret");
        connections.add(stored);
        ServerConnection expiredKey = connection("vault.acme.com", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);
        expiredKey.setTemporaryKeyContent("EXPIRED");
        connections.add(expiredKey);
        ServerConnection teamwork = teamwork(connection("vault.acme.com", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));
        connections.add(teamwork);

        for (ServerConnection connection : connections) {
            Prompts prompts = Prompts.failing();
            Resolution auth = fake.resolver(prompts).resolve(connection, true);

            assertThat(auth.status()).isEqualTo(Status.BLOCKED);
            assertThat(auth.blockedTarget()).isEqualTo("vault.acme.com:22");
            assertThat(auth.connection()).isSameInstanceAs(connection);
            assertThat(prompts.asked).isEmpty();
        }
        assertThat(fake.vaultReads).isEmpty();
        assertThat(fake.credentialReads).isEmpty();
        assertThat(fake.teamworkReads).isEqualTo(0);
    }

    @Test
    void theRealServerPolicyFitsThePolicySeam() {
        Fake fake = new Fake();
        fake.policy = connection -> ServerAccessPolicy.firstBlockedTarget(connection, EffectivePolicy.lockdown());
        ServerConnection web = connection("web.acme.com", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);

        Resolution auth = fake.resolver(Prompts.failing()).resolve(web, true);

        assertThat(auth.status()).isEqualTo(Status.BLOCKED);
        assertThat(auth.blockedTarget()).isEqualTo("web.acme.com:22");
    }

    // ---- MISSING -----------------------------------------------------------------------------

    @Test
    void anUnknownIdOrNoConnectionIsMissing() {
        Fake fake = new Fake();
        ServerConnection saved = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);
        fake.saved.put(saved.getId(), saved);
        ConnectionAuthResolver resolver = fake.resolver(Prompts.failing());

        assertThat(resolver.resolveById("deleted-or-quick-connect", true).status()).isEqualTo(Status.MISSING);
        assertThat(resolver.resolveById(null, true).status()).isEqualTo(Status.MISSING);
        Resolution none = resolver.resolve(null, true);
        assertThat(none.status()).isEqualTo(Status.MISSING);
        assertThat(none.connection()).isNull();

        Resolution known = resolver.resolveById(saved.getId(), false);
        assertThat(known.status()).isEqualTo(Status.READY);
        assertThat(known.connection()).isSameInstanceAs(saved);
    }

    // ---- Teamwork default authentication -----------------------------------------------------

    @Test
    void aSharedConnectionWithoutAuthUsesTheDefaultCredentialOnACopy() {
        Fake fake = new Fake();
        fake.teamwork = new TeamworkDefaults("cred-team", " team-user ", null, null, "ignored", false);
        fake.credentialPasswords.put("cred-team", "team-pass");
        ServerConnection shared = teamwork(connection("db", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY));
        shared.setUsername("from-file");
        shared.setPrivateKeyPath("/keys/id");

        Resolution auth = fake.resolver(Prompts.failing()).resolve(shared, true);

        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.password()).isEqualTo("team-pass");
        ServerConnection resolved = auth.connection();
        assertThat(resolved).isNotSameInstanceAs(shared);
        assertThat(resolved.getId()).isEqualTo(shared.getId());
        assertThat(resolved.getCredentialId()).isEqualTo("cred-team");
        assertThat(resolved.getUsername()).isEqualTo("team-user");
        assertThat(resolved.getAuthMethod()).isEqualTo(AuthMethod.PASSWORD);
        assertThat(resolved.getPrivateKeyPath()).isNull();
        // The shared instance in the teamwork list is never changed.
        assertThat(shared.getCredentialId()).isNull();
        assertThat(shared.getUsername()).isEqualTo("from-file");
        assertThat(shared.getAuthMethod()).isEqualTo(AuthMethod.PUBLIC_KEY);
    }

    @Test
    void aSharedConnectionWithoutAuthUsesTheDefaultSshKeyAndUsername() {
        Fake fake = new Fake();
        fake.teamwork = new TeamworkDefaults(null, null, "key-team", "/keys/team", " ops ", false);
        ServerConnection shared = teamwork(connection("db", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));

        Resolution auth = fake.resolver(Prompts.failing()).resolve(shared, true);

        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.password()).isNull();
        assertThat(auth.connection().getSshKeyId()).isEqualTo("key-team");
        assertThat(auth.connection().getPrivateKeyPath()).isEqualTo("/keys/team");
        assertThat(auth.connection().getAuthMethod()).isEqualTo(AuthMethod.PUBLIC_KEY);
        assertThat(auth.connection().getUsername()).isEqualTo("ops");
        assertThat(shared.getSshKeyId()).isNull();
    }

    @Test
    void aSharedConnectionSetToTemporaryKeysAsksForAKeyOnACopy() {
        Fake fake = new Fake();
        fake.teamwork = new TeamworkDefaults(null, null, null, null, "ops", true);
        ServerConnection shared = teamwork(connection("db", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));

        assertThat(fake.resolver(Prompts.failing()).resolve(shared, false).status()).isEqualTo(Status.NEEDS_TEMP_KEY);

        Prompts prompts = new Prompts();
        prompts.key = new TemporarySSHKey("NEW", 15);
        Resolution auth = fake.resolver(prompts).resolve(shared, true);
        assertThat(auth.status()).isEqualTo(Status.READY);
        assertThat(auth.temporaryKey()).isSameInstanceAs(prompts.key);
        assertThat(prompts.askedConnections).hasSize(1);
        assertThat(prompts.askedConnections.get(0)).isNotSameInstanceAs(shared);
        assertThat(prompts.askedConnections.get(0).getUsername()).isEqualTo("ops");
    }

    @Test
    void aSharedConnectionWithItsOwnAuthAndAPrivateConnectionKeepTheirAuth() {
        Fake fake = new Fake();
        fake.teamwork = new TeamworkDefaults("cred-team", "team-user", "key-team", "/keys/team", "ops", true);
        ServerConnection ownCredential = teamwork(connection("db", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY));
        ownCredential.setSshKeyId("own-key");

        assertThat(fake.resolver(Prompts.failing()).resolve(ownCredential, false).connection())
            .isSameInstanceAs(ownCredential);

        Fake privateFake = new Fake();
        ServerConnection mine = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);
        assertThat(privateFake.resolver(Prompts.failing()).resolve(mine, false).connection()).isSameInstanceAs(mine);
        assertThat(privateFake.teamworkReads).isEqualTo(0);
    }

    @Test
    void noTeamworkDefaultLeavesTheSharedConnectionAsItIs() {
        ServerConnection shared = teamwork(connection("db", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD));

        assertThat(ConnectionAuthResolver.applyTeamworkDefaults(shared, TeamworkDefaults.NONE)).isSameInstanceAs(shared);
    }

    // ---- Output never leaks secrets ----------------------------------------------------------

    @Test
    void aResolutionNeverPrintsThePasswordOrTheKey() {
        ServerConnection web = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);

        String text = Resolution.ready(web, "s3cret", new TemporarySSHKey("KEY-TEXT", 5)).toString();

        assertThat(text).doesNotContain("s3cret");
        assertThat(text).doesNotContain("KEY-TEXT");
        assertThat(text).contains("READY");
    }

    // ---- sanitize ----------------------------------------------------------------------------

    @Test
    void sanitizeDropsTheTemporaryKeyFromACopyAndKeepsTheRest() {
        ServerConnection tab = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);
        tab.setTemporaryKeyContent("KEY-TEXT");
        tab.setTemporaryKeyExpirationMinutes(30L);
        tab.setTemporaryKeyPermanent(true);
        tab.setPrivateKeyPath("TEMPORARY:KEY-TEXT");
        tab.setEncryptedPassword("enc:s3cret");
        tab.setCredentialId("cred-1");
        assertThat(ConnectionAuthResolver.carriesTemporaryKey(tab)).isTrue();

        ServerConnection kept = ConnectionAuthResolver.sanitize(tab);

        assertThat(kept).isNotSameInstanceAs(tab);
        assertThat(kept.getId()).isEqualTo(tab.getId());
        assertThat(kept.getHost()).isEqualTo("web");
        assertThat(kept.getTemporaryKeyContent()).isNull();
        assertThat(kept.getTemporaryKeyExpirationMinutes()).isNull();
        assertThat(kept.isTemporaryKeyPermanent()).isFalse();
        assertThat(kept.getPrivateKeyPath()).isNull();
        assertThat(kept.getEncryptedPassword()).isEqualTo("enc:s3cret");
        assertThat(kept.getCredentialId()).isEqualTo("cred-1");
        assertThat(ConnectionAuthResolver.carriesTemporaryKey(kept)).isFalse();
        // The open tab keeps its key.
        assertThat(tab.getTemporaryKeyContent()).isEqualTo("KEY-TEXT");
        assertThat(tab.getPrivateKeyPath()).isEqualTo("TEMPORARY:KEY-TEXT");
    }

    @Test
    void sanitizeKeepsAnOrdinaryKeyPathAndAcceptsNoConnection() {
        ServerConnection key = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);
        key.setPrivateKeyPath("/home/me/.ssh/id_ed25519");

        assertThat(ConnectionAuthResolver.carriesTemporaryKey(key)).isFalse();
        assertThat(ConnectionAuthResolver.sanitize(key).getPrivateKeyPath()).isEqualTo("/home/me/.ssh/id_ed25519");
        assertThat(ConnectionAuthResolver.sanitize(null)).isNull();
        assertThat(ConnectionAuthResolver.carriesTemporaryKey(null)).isFalse();
    }

    @Test
    void aSanitizedConnectionIsNeverSilentlyGivenItsOldKeyBack() {
        Fake fake = new Fake();
        TemporarySSHKey stillRegistered = new TemporarySSHKey("KEY-TEXT", 60);
        fake.keys.put("KEY-TEXT", stillRegistered);
        ServerConnection tab = connection("web", ConnectionProtocol.SSH_TCP, AuthMethod.PASSWORD);
        tab.setTemporaryKeyContent("KEY-TEXT");
        tab.setPrivateKeyPath("TEMPORARY:KEY-TEXT");

        Resolution auth = fake.resolver(Prompts.failing()).resolve(ConnectionAuthResolver.sanitize(tab), false);

        assertThat(auth.temporaryKey()).isNull();
        assertThat(auth.connection().getPrivateKeyPath()).isNull();
    }

    // ---- Fakes -------------------------------------------------------------------------------

    private static ServerConnection connection(String host, ConnectionProtocol protocol, AuthMethod method) {
        ServerConnection connection = new ServerConnection(host, host, 22, "me");
        connection.setProtocol(protocol);
        connection.setAuthMethod(method);
        return connection;
    }

    private static ServerConnection teamwork(ServerConnection connection) {
        connection.setConnectionSource(ConnectionSource.TEAMWORK);
        return connection;
    }

    /** Fake seams: policy by host, saved connections by id, keys by text, credentials and vault in maps. */
    private static final class Fake {
        final Set<String> blockedHosts = new HashSet<>();
        Function<ServerConnection, Optional<String>> policy = connection ->
            blockedHosts.contains(connection.getHost())
                ? Optional.of(connection.getHost() + ":" + connection.getPort())
                : Optional.empty();
        final Map<String, ServerConnection> saved = new HashMap<>();
        TeamworkDefaults teamwork = TeamworkDefaults.NONE;
        int teamworkReads;
        final Map<String, TemporarySSHKey> keys = new HashMap<>();
        final Map<String, String> credentialPasswords = new HashMap<>();
        boolean credentialFails;
        final List<String> credentialReads = new ArrayList<>();
        boolean locked;
        boolean vaultFails;
        final List<ServerConnection> vaultReads = new ArrayList<>();

        ConnectionAuthResolver resolver(ConnectionAuthResolver.Prompts prompts) {
            ConnectionAuthResolver.Seams seams = new ConnectionAuthResolver.Seams(
                connection -> policy.apply(connection),
                saved::get,
                () -> {
                    teamworkReads++;
                    return teamwork;
                },
                keys::get,
                new ConnectionAuthResolver.Credentials() {
                    @Override
                    public boolean holdsPassword(String credentialId) {
                        return credentialPasswords.containsKey(credentialId);
                    }

                    @Override
                    public String password(String credentialId) throws Exception {
                        credentialReads.add(credentialId);
                        if (credentialFails) {
                            throw new Exception("external command failed");
                        }
                        return credentialPasswords.get(credentialId);
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
                        if (encrypted == null || encrypted.isBlank()) {
                            return null;
                        }
                        if (vaultFails) {
                            throw new RuntimeException("Failed to decrypt password");
                        }
                        return encrypted.substring("enc:".length());
                    }
                });
            return new ConnectionAuthResolver(seams, prompts);
        }
    }

    /** Records every question; answers with the configured values. */
    private static class Prompts implements ConnectionAuthResolver.Prompts {
        final List<String> asked = new ArrayList<>();
        final List<ServerConnection> askedConnections = new ArrayList<>();
        String password;
        TemporarySSHKey key;
        Runnable unlockAction;

        /** Prompts that fail the test if anything is asked. */
        static Prompts failing() {
            return new Prompts() {
                @Override
                void ask(String what, ServerConnection connection) {
                    throw new AssertionError("nothing may be asked here, but got " + what);
                }
            };
        }

        void ask(String what, ServerConnection connection) {
            asked.add(what + ":" + connection.getHost());
            askedConnections.add(connection);
        }

        @Override
        public String password(ServerConnection connection) {
            ask("password", connection);
            return password;
        }

        @Override
        public TemporarySSHKey temporaryKey(ServerConnection connection) {
            ask("temporaryKey", connection);
            return key;
        }

        @Override
        public boolean unlockVault(ServerConnection connection) {
            ask("unlock", connection);
            if (unlockAction == null) {
                return false;
            }
            unlockAction.run();
            return true;
        }
    }
}
