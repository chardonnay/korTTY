package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.TemporarySSHKeyManager;
import de.kortty.core.TemporarySshKeyMaterial;
import de.kortty.model.AuthMethod;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SSHKey;
import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.model.TemporarySSHKey;
import de.kortty.policy.ServerAccessPolicy;
import de.kortty.security.PasswordVault;
import de.kortty.security.MasterPasswordManager;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Works out how korTTY signs in to a saved connection before a tab is opened for it: the one
 * sign-in flow behind the Connection Manager's Connect and a tab's Duplicate, and the one later
 * entry points (reopening a closed tab, the command palette, session restore) reuse.
 *
 * <p>In this order it
 * <ol>
 *   <li>refuses a target or jump host that the enterprise server policy blocks, before anything is
 *       asked ({@link Status#BLOCKED});</li>
 *   <li>applies the teamwork default authentication to a shared connection that names neither a
 *       credential nor an SSH key;</li>
 *   <li>reuses a temporary SSH key that is still valid, and asks for a new one when it expired
 *       ({@link Status#NEEDS_TEMP_KEY}) — also for a shared connection set to use a temporary key,
 *       and for one whose key the locked vault still keeps encrypted;</li>
 *   <li>opens a local shell and SSH key auth without a password;</li>
 *   <li>uses the stored password (credential store first, then the connection's own encrypted
 *       password), asks to unlock the vault when that password is in the locked vault
 *       ({@link Status#NEEDS_UNLOCK}), and asks for a password otherwise
 *       ({@link Status#NEEDS_PASSWORD}).</li>
 * </ol>
 *
 * <p>A non-interactive {@link #resolve(ServerConnection, boolean) resolve} only classifies: it never
 * shows a dialog and never renews an expired key, so a restore can open what is {@link Status#READY}
 * and collect the rest. An interactive one goes through {@link Prompts} for what is missing and
 * still returns the step's status when the user cancels it.
 *
 * <p>Everything the resolver reads goes through {@link Seams} and everything it asks through
 * {@link Prompts}, so the decisions are unit-testable without the application or a stage; the
 * application's seams are {@link #forApplication(KorTTYApplication)}.
 */
final class ConnectionAuthResolver {

    private static final Logger logger = LoggerFactory.getLogger(ConnectionAuthResolver.class);

    /** How far sign-in for a connection got. */
    enum Status {
        /** Ready to open: a local shell, SSH key auth, a stored or entered password, or a valid temporary key. */
        READY,
        /** A password login with no stored password; the user has to enter one. */
        NEEDS_PASSWORD,
        /** The temporary SSH key expired (or a shared connection is set to use one); a new key is needed. */
        NEEDS_TEMP_KEY,
        /** The stored password is in the master-password vault, which is locked. */
        NEEDS_UNLOCK,
        /** The enterprise server policy blocks the target or its jump host. */
        BLOCKED,
        /** There is no connection: the id is unknown (an unsaved Quick Connect session, a deleted connection). */
        MISSING
    }

    /**
     * The outcome of a {@link #resolve}.
     *
     * @param connection    the connection to open, with teamwork default authentication applied; the
     *                      original connection for {@link Status#BLOCKED}, {@code null} for
     *                      {@link Status#MISSING}
     * @param password      the password to log in with, or {@code null} when none is needed
     * @param temporaryKey  the temporary SSH key to log in with, or {@code null}
     * @param blockedTarget the blocked {@code host:port} for {@link Status#BLOCKED}, otherwise {@code null}
     */
    record Resolution(Status status, @Nullable ServerConnection connection, @Nullable String password,
                      @Nullable TemporarySSHKey temporaryKey, @Nullable String blockedTarget) {

        Resolution {
            Objects.requireNonNull(status, "status");
        }

        static Resolution ready(ServerConnection connection, @Nullable String password,
                                @Nullable TemporarySSHKey temporaryKey) {
            return new Resolution(Status.READY, connection, password, temporaryKey, null);
        }

        static Resolution needs(Status status, ServerConnection connection) {
            return new Resolution(status, connection, null, null, null);
        }

        static Resolution blocked(ServerConnection connection, String target) {
            return new Resolution(Status.BLOCKED, connection, null, null, target);
        }

        static Resolution missing() {
            return new Resolution(Status.MISSING, null, null, null, null);
        }

        boolean isReady() {
            return status == Status.READY;
        }

        /** Never prints the password or the key. */
        @Override
        public String toString() {
            return "Resolution[status=" + status
                + ", password=" + (password == null ? "none" : "***")
                + ", temporaryKey=" + (temporaryKey == null ? "none" : "***")
                + (blockedTarget != null ? ", blockedTarget=" + blockedTarget : "")
                + "]";
        }
    }

    /**
     * The teamwork default authentication from Settings → Teamwork, with the credential and the SSH
     * key already looked up: {@code credentialId} and {@code sshKeyId} are set only when they name an
     * existing entry.
     *
     * @param credentialUsername the default credential's username, if it has one
     * @param sshKeyPath         the default SSH key's effective file path
     * @param username           the optional default username for SSH and temporary keys
     * @param useTemporaryKey    whether shared connections without auth use a temporary SSH key
     */
    record TeamworkDefaults(@Nullable String credentialId, @Nullable String credentialUsername,
                            @Nullable String sshKeyId, @Nullable String sshKeyPath,
                            @Nullable String username, boolean useTemporaryKey) {

        static final TeamworkDefaults NONE = new TeamworkDefaults(null, null, null, null, null, false);
    }

    /** The credential store, as far as signing in needs it. */
    interface Credentials {
        /** Whether {@code credentialId} names a credential that holds a password or a password command. */
        boolean holdsPassword(String credentialId);

        /** The credential's password (decrypted, or the output of its command), or {@code null}. */
        @Nullable String password(String credentialId) throws Exception;
    }

    /** The master-password vault. */
    interface Vault {
        /** Whether a master password exists but has not been entered in this session. */
        boolean isLocked();

        /** The connection's own stored password, decrypted, or {@code null} when it has none. */
        @Nullable String password(ServerConnection connection);

        /**
         * Whether korTTY loaded {@code connection} while the vault was locked and therefore cleared its
         * temporary SSH key in memory: the key stays stored encrypted, but the connection has none to
         * sign in with.
         */
        default boolean holdsLockedTemporaryKey(ServerConnection connection) {
            return false;
        }
    }

    /**
     * What the resolver reads.
     *
     * @param policy        the first policy-blocked target of a connection, as {@code host:port}
     * @param connections   a saved connection by id, or {@code null}
     * @param teamwork      the teamwork default authentication, read only for a shared connection
     * @param temporaryKeys the registered temporary SSH key for the key text, or {@code null}
     */
    record Seams(Function<ServerConnection, Optional<String>> policy,
                 Function<String, ServerConnection> connections,
                 Supplier<TeamworkDefaults> teamwork,
                 Function<String, TemporarySSHKey> temporaryKeys,
                 Credentials credentials,
                 Vault vault) {

        Seams {
            Objects.requireNonNull(policy, "policy");
            Objects.requireNonNull(connections, "connections");
            Objects.requireNonNull(teamwork, "teamwork");
            Objects.requireNonNull(temporaryKeys, "temporaryKeys");
            Objects.requireNonNull(credentials, "credentials");
            Objects.requireNonNull(vault, "vault");
        }
    }

    /** What an interactive resolve asks the user. Each method returns {@code null} / {@code false} on Cancel. */
    interface Prompts {
        /** Asks for the connection's password. */
        @Nullable String password(ServerConnection connection);

        /** Asks for a new temporary SSH key and registers it. */
        @Nullable TemporarySSHKey temporaryKey(ServerConnection connection);

        /** Offers to unlock the vault that holds the connection's password; {@code true} once it is open. */
        boolean unlockVault(ServerConnection connection);
    }

    /** Prompts that ask nothing: every question counts as cancelled. */
    static final Prompts NO_PROMPTS = new Prompts() {
        @Override
        public @Nullable String password(ServerConnection connection) {
            return null;
        }

        @Override
        public @Nullable TemporarySSHKey temporaryKey(ServerConnection connection) {
            return null;
        }

        @Override
        public boolean unlockVault(ServerConnection connection) {
            return false;
        }
    };

    private final Seams seams;
    private final Prompts prompts;

    ConnectionAuthResolver(Seams seams, Prompts prompts) {
        this.seams = Objects.requireNonNull(seams, "seams");
        this.prompts = Objects.requireNonNull(prompts, "prompts");
    }

    /** {@link #resolve(ServerConnection, boolean)} for the saved connection with {@code connectionId}. */
    Resolution resolveById(@Nullable String connectionId, boolean interactive) {
        ServerConnection stored = connectionId != null ? seams.connections().apply(connectionId) : null;
        return resolve(stored, interactive);
    }

    /**
     * How to sign in to {@code connection}. Interactive, it asks for what is missing — after the
     * policy check, never before — and returns {@link Status#READY} with what the user entered, or
     * the status of the step the user cancelled.
     */
    Resolution resolve(@Nullable ServerConnection connection, boolean interactive) {
        Resolution resolution = classify(connection);
        if (!interactive) {
            return resolution;
        }
        if (resolution.status() == Status.NEEDS_UNLOCK) {
            if (prompts.unlockVault(resolution.connection())) {
                resolution = classify(connection);
            } else {
                // The user chose to type the password rather than open the vault.
                resolution = Resolution.needs(Status.NEEDS_PASSWORD, resolution.connection());
            }
        }
        return switch (resolution.status()) {
            case NEEDS_TEMP_KEY -> {
                TemporarySSHKey key = prompts.temporaryKey(resolution.connection());
                yield key != null ? Resolution.ready(resolution.connection(), null, key) : resolution;
            }
            case NEEDS_PASSWORD -> {
                String password = prompts.password(resolution.connection());
                yield password != null && !password.isEmpty()
                    ? Resolution.ready(resolution.connection(), password, null)
                    : resolution;
            }
            default -> resolution;
        };
    }

    private Resolution classify(@Nullable ServerConnection original) {
        if (original == null) {
            return Resolution.missing();
        }
        Optional<String> blocked = seams.policy().apply(original);
        if (blocked.isPresent()) {
            return Resolution.blocked(original, blocked.get());
        }
        TeamworkDefaults teamwork = original.isTeamworkConnection() ? seams.teamwork().get() : TeamworkDefaults.NONE;
        ServerConnection connection = applyTeamworkDefaults(original, teamwork);

        String keyContent = connection.getTemporaryKeyContent();
        if (keyContent != null && !keyContent.trim().isEmpty()) {
            TemporarySSHKey key = seams.temporaryKeys().apply(keyContent);
            if (key != null && key.isValid()) {
                logger.info("Using the registered temporary SSH key (valid for {} more seconds)", key.getRemainingSeconds());
                return Resolution.ready(connection, null, key);
            }
            return Resolution.needs(Status.NEEDS_TEMP_KEY, connection);
        }
        if (seams.vault().holdsLockedTemporaryKey(original)) {
            // Loaded with the vault locked: the key was cleared in memory and stays encrypted on disk
            // until the vault is unlocked, so there is no key to sign in with now; like an expired key,
            // it needs a new one. Otherwise the connection would look like key authentication
            // without a key and fail on the server.
            return Resolution.needs(Status.NEEDS_TEMP_KEY, connection);
        }
        if (connection.isTeamworkConnection() && connection.getCredentialId() == null
                && connection.getSshKeyId() == null && teamwork.useTemporaryKey()) {
            return Resolution.needs(Status.NEEDS_TEMP_KEY, connection);
        }
        // A local shell runs a local process with no authentication; SSH key auth needs no password.
        if (connection.isLocalShell() || connection.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
            return Resolution.ready(connection, null, null);
        }
        if (hasStoredPassword(connection)) {
            if (seams.vault().isLocked()) {
                return Resolution.needs(Status.NEEDS_UNLOCK, connection);
            }
            String password;
            try {
                password = storedPassword(connection);
            } catch (RuntimeException e) {
                logger.warn("The stored password could not be read; asking for it instead: {}", e.getMessage());
                password = null;
            }
            if (password != null && !password.isEmpty()) {
                return Resolution.ready(connection, password, null);
            }
        }
        return Resolution.needs(Status.NEEDS_PASSWORD, connection);
    }

    /** Whether a password for {@code connection} is stored, readable or not (credential store or vault). */
    boolean hasStoredPassword(ServerConnection connection) {
        String credentialId = connection.getCredentialId();
        if (credentialId != null && seams.credentials().holdsPassword(credentialId)) {
            return true;
        }
        String encrypted = connection.getEncryptedPassword();
        return encrypted != null && !encrypted.isBlank();
    }

    /**
     * The stored password of {@code connection}: the credential store first (so a password changed
     * there applies at once), then the connection's own encrypted password; {@code null} when none
     * is stored. A credential that cannot be read falls back to the connection's password; the
     * vault's own failure propagates.
     */
    @Nullable String storedPassword(ServerConnection connection) {
        String credentialId = connection.getCredentialId();
        if (credentialId != null) {
            try {
                String password = seams.credentials().password(credentialId);
                if (password != null) {
                    return password;
                }
            } catch (Exception e) {
                logger.warn("Failed to retrieve password from credential store: {}", e.getMessage());
            }
        }
        return seams.vault().password(connection);
    }

    /**
     * For a teamwork connection that names neither a credential nor an SSH key, a copy with the
     * teamwork default filled in: the default credential (and its username), else the default SSH
     * key (and the default username), else — with "temporary SSH key" — just the default username.
     * Every other connection is returned as it is; the shared instance is never changed.
     */
    static ServerConnection applyTeamworkDefaults(ServerConnection connection, TeamworkDefaults defaults) {
        if (!connection.isTeamworkConnection()
                || connection.getCredentialId() != null || connection.getSshKeyId() != null) {
            return connection;
        }
        if (defaults.credentialId() != null) {
            ServerConnection copy = ServerConnection.copyForAuth(connection);
            copy.setCredentialId(defaults.credentialId());
            String credentialUsername = defaults.credentialUsername();
            if (credentialUsername != null && !credentialUsername.isBlank()) {
                copy.setUsername(credentialUsername.trim());
            }
            copy.setAuthMethod(AuthMethod.PASSWORD);
            copy.setSshKeyId(null);
            copy.setPrivateKeyPath(null);
            return copy;
        }
        if (defaults.sshKeyId() != null) {
            ServerConnection copy = ServerConnection.copyForAuth(connection);
            copy.setSshKeyId(defaults.sshKeyId());
            copy.setAuthMethod(AuthMethod.PUBLIC_KEY);
            copy.setPrivateKeyPath(defaults.sshKeyPath());
            copy.setCredentialId(null);
            applyDefaultUsername(copy, defaults.username());
            return copy;
        }
        if (defaults.useTemporaryKey()) {
            // A defensive copy, so the key a new temporary key sets never reaches the shared instance.
            ServerConnection copy = ServerConnection.copyForAuth(connection);
            applyDefaultUsername(copy, defaults.username());
            return copy;
        }
        return connection;
    }

    private static void applyDefaultUsername(ServerConnection copy, @Nullable String username) {
        if (username != null && !username.isBlank()) {
            copy.setUsername(username.trim());
        }
    }

    /**
     * A copy of {@code connection} to keep for later — the history of closed tabs, a session
     * snapshot — without its temporary SSH key: key text, lifetime and permanence are cleared and a
     * {@code TEMPORARY:} key path is dropped, so keeping or reopening it can never put an expired key
     * back to use without asking. Encrypted password and credential references stay; they are
     * encrypted and needed to find the password again.
     *
     * @return the copy, or {@code null} for {@code null}
     */
    static @Nullable ServerConnection sanitize(@Nullable ServerConnection connection) {
        if (connection == null) {
            return null;
        }
        ServerConnection copy = ServerConnection.copyForAuth(connection);
        copy.setTemporaryKeyContent(null);
        copy.setTemporaryKeyExpirationMinutes(null);
        copy.setTemporaryKeyPermanent(false);
        if (TemporarySshKeyMaterial.isTemporaryKeyPath(copy.getPrivateKeyPath())) {
            copy.setPrivateKeyPath(null);
        }
        return copy;
    }

    /** Whether {@code connection} carries temporary SSH key material that {@link #sanitize} removes. */
    static boolean carriesTemporaryKey(@Nullable ServerConnection connection) {
        if (connection == null) {
            return false;
        }
        String content = connection.getTemporaryKeyContent();
        return (content != null && !content.isBlank())
            || TemporarySshKeyMaterial.isTemporaryKeyPath(connection.getPrivateKeyPath());
    }

    /** The running application's seams: server policy, saved connections, teamwork settings, keys and vault. */
    static Seams forApplication(KorTTYApplication app) {
        Objects.requireNonNull(app, "app");
        return new Seams(
            ServerAccessPolicy::firstBlockedTarget,
            id -> app.getConfigManager().getConnectionById(id),
            () -> teamworkDefaults(app),
            content -> TemporarySSHKeyManager.getInstance().getTemporaryKey(content),
            new Credentials() {
                @Override
                public boolean holdsPassword(String credentialId) {
                    CredentialManager credentials = app.getCredentialManager();
                    if (credentials == null) {
                        return false;
                    }
                    return credentials.findCredentialById(credentialId)
                        .map(ConnectionAuthResolver::holdsPassword)
                        .orElse(false);
                }

                @Override
                public @Nullable String password(String credentialId) throws Exception {
                    CredentialManager credentials = app.getCredentialManager();
                    Optional<StoredCredential> credential = credentials.findCredentialById(credentialId);
                    if (credential.isEmpty()) {
                        return null;
                    }
                    return credentials.getPassword(credential.get(), app.getMasterPasswordManager().getMasterPassword());
                }
            },
            new Vault() {
                @Override
                public boolean isLocked() {
                    return VaultUnlockSupport.isLocked(app.getMasterPasswordManager());
                }

                @Override
                public @Nullable String password(ServerConnection connection) {
                    MasterPasswordManager passwords = app.getMasterPasswordManager();
                    return new PasswordVault(passwords.getEncryptionService(), passwords.getMasterPassword())
                        .retrievePassword(connection);
                }

                @Override
                public boolean holdsLockedTemporaryKey(ServerConnection connection) {
                    ConfigurationManager config = app.getConfigManager();
                    return config != null && config.hasLockedTemporaryKey(connection.getId());
                }
            });
    }

    private static boolean holdsPassword(StoredCredential credential) {
        String secret = credential.getPasswordType() == StoredCredential.PasswordType.EXTERNAL_COMMAND
            ? credential.getEncryptedExternalCommand()
            : credential.getEncryptedPassword();
        return secret != null && !secret.isBlank();
    }

    private static TeamworkDefaults teamworkDefaults(KorTTYApplication app) {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        String credentialId = null;
        String credentialUsername = null;
        CredentialManager credentials = app.getCredentialManager();
        if (settings.getTeamworkDefaultCredentialId() != null && credentials != null) {
            Optional<StoredCredential> credential = credentials.findCredentialById(settings.getTeamworkDefaultCredentialId());
            if (credential.isPresent()) {
                credentialId = credential.get().getId();
                credentialUsername = credential.get().getUsername();
            }
        }
        String sshKeyId = null;
        String sshKeyPath = null;
        SSHKeyManager keys = app.getSSHKeyManager();
        if (settings.getTeamworkDefaultSshKeyId() != null && keys != null) {
            Optional<SSHKey> key = keys.findKeyById(settings.getTeamworkDefaultSshKeyId());
            if (key.isPresent()) {
                sshKeyId = key.get().getId();
                sshKeyPath = keys.getEffectiveKeyPath(key.get());
            }
        }
        return new TeamworkDefaults(credentialId, credentialUsername, sshKeyId, sshKeyPath,
            settings.getTeamworkDefaultUsername(), settings.getTeamworkUseTemporaryKey());
    }
}
