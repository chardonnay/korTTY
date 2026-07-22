package de.kortty.jobscheduler;

import de.kortty.KorTTYApplication;
import de.kortty.core.SSHKeyManager;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.model.SSHKey;
import de.kortty.security.EncryptionService;
import de.kortty.security.PasswordVault;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.client.session.ClientSession.ClientSessionEvent;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.common.SftpConstants;
import org.apache.sshd.sftp.common.SftpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class JobSchedulerRemoteSession implements RemoteCommandExecutor, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(JobSchedulerRemoteSession.class);
    private static final Duration COMMAND_OPEN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_WAIT_TIMEOUT = Duration.ofHours(1);
    private static final long COMMAND_POLL_MILLIS = 250L;

    private final KorTTYApplication app;
    private final ServerConnection connection;
    private final PinnedHostKey pinnedHostKey;
    private final char[] masterPassword;
    private final boolean hostKeyVerificationDisabled;

    private SshClient client;
    private ClientSession session;
    private SftpClient sftpClient;
    private String password;
    private Path authenticatedPrivateKeyPath;
    private String authenticatedPrivateKeyPassphrase;

    public JobSchedulerRemoteSession(
        KorTTYApplication app,
        ServerConnection connection,
        PinnedHostKey pinnedHostKey,
        char[] masterPassword) {

        this(app, connection, pinnedHostKey, masterPassword, false);
    }

    public JobSchedulerRemoteSession(
        KorTTYApplication app,
        ServerConnection connection,
        PinnedHostKey pinnedHostKey,
        char[] masterPassword,
        boolean hostKeyVerificationDisabled) {

        this.app = app;
        this.connection = connection;
        this.pinnedHostKey = pinnedHostKey;
        this.masterPassword = masterPassword != null ? masterPassword.clone() : null;
        this.hostKeyVerificationDisabled = hostKeyVerificationDisabled;
    }

    public static PinnedHostKey probeHostKey(ServerConnection connection) throws Exception {
        if (connection == null) {
            throw new IllegalArgumentException("Connection is required.");
        }
        if (connection.getProtocol() != ConnectionProtocol.SSH_TCP) {
            throw new JobBlockedException("JobScheduler supports only SSH TCP connections.");
        }
        if (connection.getHost() == null || connection.getHost().isBlank()) {
            throw new JobBlockedException("Server host is missing.");
        }
        if (connection.getUsername() == null || connection.getUsername().isBlank()) {
            throw new JobBlockedException("Server username is missing.");
        }
        HostKeyCapture capture = new HostKeyCapture();
        SshClient probeClient = SshClient.setUpDefaultClient();
        probeClient.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
            try {
                capture.capture(serverKey);
            } catch (Exception e) {
                throw new IllegalStateException("Could not calculate host key fingerprint.", e);
            }
            return true;
        });
        probeClient.start();
        try {
            int timeoutSeconds = Math.max(1, connection.getConnectionTimeoutSeconds());
            try {
                try (ClientSession probeSession = probeClient
                    .connect(connection.getUsername(), connection.getHost(), connection.getPort())
                    .verify(Duration.ofSeconds(timeoutSeconds))
                    .getSession()) {
                    var events = probeSession.waitFor(
                        EnumSet.of(ClientSessionEvent.WAIT_AUTH, ClientSessionEvent.AUTHED, ClientSessionEvent.CLOSED),
                        Duration.ofSeconds(timeoutSeconds));
                    if (events.contains(ClientSessionEvent.TIMEOUT)) {
                        throw new IOException("Timed out while waiting for SSH key exchange.");
                    }
                    capture.capture(probeSession.getServerKey());
                }
            } catch (Exception e) {
                logger.warn("Could not probe scheduler host key for {}:{}",
                    connection.getHost(), connection.getPort(), e);
                throw new IOException(
                    "Could not read SSH host key from " + connection.getHost() + ":" + connection.getPort()
                        + " (" + safeThrowableMessage(e) + ")",
                    e);
            }
        } finally {
            probeClient.stop();
        }
        if (capture.fingerprint == null) {
            throw new IOException("Could not read SSH host key fingerprint from "
                + connection.getHost() + ":" + connection.getPort() + ".");
        }
        PinnedHostKey hostKey = new PinnedHostKey();
        hostKey.setConnectionId(connection.getId());
        hostKey.setHost(connection.getHost());
        hostKey.setPort(connection.getPort());
        hostKey.setAlgorithm(capture.algorithm);
        hostKey.setFingerprintSha256(capture.fingerprint);
        hostKey.setPublicKeyLine(capture.publicKeyLine);
        return hostKey;
    }

    private static String safeThrowableMessage(Throwable error) {
        if (error == null) {
            return "unknown error";
        }
        if (error.getMessage() != null && !error.getMessage().isBlank()) {
            return error.getMessage().trim();
        }
        Throwable cause = error.getCause();
        if (cause != null && cause.getMessage() != null && !cause.getMessage().isBlank()) {
            return cause.getMessage().trim();
        }
        return error.getClass().getSimpleName();
    }

    public static String fingerprintSha256(PublicKey publicKey) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(publicKey.getEncoded());
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(hash);
    }

    public void connect() throws Exception {
        // Headless job runs must respect the enterprise server policy like interactive connects.
        de.kortty.policy.ServerAccessPolicy.firstBlockedTarget(connection).ifPresent(target -> {
            throw new de.kortty.policy.PolicyRestrictionException(
                "Connection to " + target + " is blocked by your organization's policy");
        });
        validateConnection();
        client = SshClient.setUpDefaultClient();
        client.setUserAuthFactories(buildUserAuthFactories(connection));
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
            if (hostKeyVerificationDisabled) {
                logger.warn("Scheduler host key verification disabled for {}:{}",
                    connection.getHost(), connection.getPort());
                return true;
            }
            try {
                String actual = fingerprintSha256(serverKey);
                boolean matched = pinnedHostKey.getFingerprintSha256() != null
                    && pinnedHostKey.getFingerprintSha256().equals(actual);
                if (!matched) {
                    logger.warn("Scheduler host key mismatch for {}:{} expected={} actual={}",
                        connection.getHost(), connection.getPort(), pinnedHostKey.getFingerprintSha256(), actual);
                }
                return matched;
            } catch (Exception e) {
                logger.warn("Could not verify scheduler host key", e);
                return false;
            }
        });
        client.setUserInteraction(new SchedulerUserInteraction());
        client.setKeyIdentityProvider(null);
        client.start();

        int timeoutSeconds = Math.max(1, connection.getConnectionTimeoutSeconds());
        session = client.connect(connection.getUsername(), connection.getHost(), connection.getPort())
            .verify(Duration.ofSeconds(timeoutSeconds))
            .getSession();
        session.setKeyIdentityProvider(null);

        if (connection.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
            authenticateWithKey();
        } else {
            password = resolveConnectionPassword();
            session.addPasswordIdentity(password != null ? password : "");
        }
        session.auth().verify(Duration.ofSeconds(timeoutSeconds));
    }

    @Override
    public CommandResult execute(String command) throws Exception {
        return execute(command, null);
    }

    @Override
    public CommandResult execute(String command, String stdin) throws Exception {
        return execute(command, stdin, null);
    }

    /**
     * Executes one command with an optional cooperative cancellation check (polled alongside the
     * thread-interrupt check). Used by headless AI-swarm agents whose cancellation is flag-driven.
     */
    public CommandResult execute(String command, String stdin, java.util.function.BooleanSupplier cancelled)
        throws Exception {
        ensureConnected();
        try (ChannelExec channel = session.createExecChannel(command)) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.setErr(stderr);
            if (stdin != null) {
                channel.setIn(new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)));
            }
            channel.open().verify(COMMAND_OPEN_TIMEOUT);
            boolean timedOut = waitForCommand(channel, cancelled);
            int exitCode = !timedOut && channel.getExitStatus() != null ? channel.getExitStatus() : -1;
            return new CommandResult(
                exitCode,
                stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8));
        }
    }

    private boolean waitForCommand(ChannelExec channel, java.util.function.BooleanSupplier cancelled) throws Exception {
        long deadlineNanos = System.nanoTime() + COMMAND_WAIT_TIMEOUT.toNanos();
        while (true) {
            if (Thread.currentThread().isInterrupted() || (cancelled != null && cancelled.getAsBoolean())) {
                channel.close(false);
                throw new IOException("JobScheduler command cancelled.");
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                channel.close(false);
                return true;
            }
            long waitMillis = Math.min(
                COMMAND_POLL_MILLIS,
                Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), waitMillis);
            if (events.contains(ClientChannelEvent.CLOSED)) {
                return false;
            }
        }
    }

    public void connectSftp() throws Exception {
        ensureConnected();
        if (sftpClient == null) {
            sftpClient = SftpClientFactory.instance().createSftpClient(session);
        }
    }

    public void upload(Path localPath, String remotePath) throws Exception {
        connectSftp();
        if (Files.isDirectory(localPath)) {
            mkdirs(remotePath);
            try (var stream = Files.list(localPath)) {
                for (Path child : stream.toList()) {
                    upload(child, appendRemote(remotePath, child.getFileName().toString()));
                }
            }
            return;
        }
        mkdirs(parentRemote(remotePath));
        try (var in = Files.newInputStream(localPath);
             var out = sftpClient.write(remotePath, EnumSet.of(
                 SftpClient.OpenMode.Write,
                 SftpClient.OpenMode.Create,
                 SftpClient.OpenMode.Truncate))) {
            in.transferTo(out);
        }
    }

    public void download(String remotePath, Path localPath) throws Exception {
        connectSftp();
        SftpClient.Attributes attrs = sftpClient.stat(remotePath);
        if (attrs.isDirectory()) {
            Files.createDirectories(localPath);
            for (SftpClient.DirEntry entry : sftpClient.readDir(remotePath)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                download(appendRemote(remotePath, name), localPath.resolve(name));
            }
            return;
        }
        if (localPath.getParent() != null) {
            Files.createDirectories(localPath.getParent());
        }
        try (var in = sftpClient.read(remotePath);
             var out = Files.newOutputStream(localPath)) {
            in.transferTo(out);
        }
    }

    public void deleteRemote(String remotePath) throws Exception {
        connectSftp();
        SftpClient.Attributes attrs = sftpClient.stat(remotePath);
        if (attrs.isDirectory()) {
            for (SftpClient.DirEntry entry : sftpClient.readDir(remotePath)) {
                String name = entry.getFilename();
                if (".".equals(name) || "..".equals(name)) {
                    continue;
                }
                deleteRemote(appendRemote(remotePath, name));
            }
            sftpClient.rmdir(remotePath);
            return;
        }
        sftpClient.remove(remotePath);
    }

    public void renameRemote(String source, String destination) throws Exception {
        connectSftp();
        sftpClient.rename(source, destination);
    }

    public void mkdirs(String remotePath) throws Exception {
        connectSftp();
        if (remotePath == null || remotePath.isBlank() || "/".equals(remotePath)) {
            return;
        }
        String normalized = remotePath.startsWith("/") ? remotePath : "/" + remotePath;
        StringBuilder current = new StringBuilder();
        for (String part : normalized.split("/")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            current.append('/').append(part);
            try {
                sftpClient.stat(current.toString());
            } catch (SftpException e) {
                if (e.getStatus() != SftpConstants.SSH_FX_NO_SUCH_FILE) {
                    throw e;
                }
                try {
                    sftpClient.mkdir(current.toString());
                } catch (SftpException mkdirException) {
                    if (mkdirException.getStatus() != SftpConstants.SSH_FX_FILE_ALREADY_EXISTS) {
                        throw mkdirException;
                    }
                }
            }
        }
    }

    public void chmodRemote(String remotePath, String permissions) throws Exception {
        connectSftp();
        int parsed = Integer.parseInt(permissions, 8);
        SftpClient.Attributes attrs = sftpClient.stat(remotePath);
        attrs.setPermissions(parsed);
        sftpClient.setStat(remotePath, attrs);
    }

    public String canonicalPath(String remotePath) throws Exception {
        connectSftp();
        return sftpClient.canonicalPath(remotePath == null || remotePath.isBlank() ? "." : remotePath);
    }

    public List<RemoteDirectoryEntry> listDirectories(String remotePath) throws Exception {
        connectSftp();
        String basePath = canonicalPath(remotePath);
        List<RemoteDirectoryEntry> directories = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftpClient.readDir(basePath)) {
            String name = entry != null ? entry.getFilename() : null;
            if (name == null || ".".equals(name) || "..".equals(name)) {
                continue;
            }
            if (entry.getAttributes() != null && entry.getAttributes().isDirectory()) {
                directories.add(new RemoteDirectoryEntry(name, appendRemote(basePath, name)));
            }
        }
        directories.sort(Comparator.comparing(RemoteDirectoryEntry::name, String.CASE_INSENSITIVE_ORDER));
        return directories;
    }

    public String tempRemotePath(String suffix) {
        String safeSuffix = suffix != null && !suffix.isBlank() ? suffix.replaceAll("[^A-Za-z0-9_.-]", "_") : "file";
        return "/tmp/kortty-job-" + UUID.randomUUID() + "-" + safeSuffix;
    }

    public Optional<String> getPassword() {
        return Optional.ofNullable(password);
    }

    public ExternalSshAuthMaterial externalSshAuthMaterial() {
        return new ExternalSshAuthMaterial(
            connection.getAuthMethod(),
            Optional.ofNullable(password),
            Optional.ofNullable(authenticatedPrivateKeyPath),
            Optional.ofNullable(authenticatedPrivateKeyPassphrase));
    }

    public record ExternalSshAuthMaterial(
        AuthMethod authMethod,
        Optional<String> password,
        Optional<Path> privateKeyPath,
        Optional<String> privateKeyPassphrase) {
    }

    public record RemoteDirectoryEntry(String name, String path) {
        @Override
        public String toString() {
            return name;
        }
    }

    @Override
    public void close() {
        closeQuietly(sftpClient);
        closeQuietly(session);
        if (client != null) {
            try {
                client.stop();
            } catch (Exception e) {
                logger.debug("Could not stop scheduler SSH client", e);
            }
        }
    }

    private void validateConnection() throws JobBlockedException {
        if (connection == null) {
            throw new JobBlockedException("No server connection is configured for this job.");
        }
        if (connection.getProtocol() != ConnectionProtocol.SSH_TCP) {
            throw new JobBlockedException("JobScheduler supports only SSH TCP connections.");
        }
        if (!hostKeyVerificationDisabled
            && (pinnedHostKey == null || pinnedHostKey.getFingerprintSha256() == null)) {
            throw new JobBlockedException("Host key pinning is required before this job can run.");
        }
        if (masterPassword == null && jobNeedsConnectionSecret()) {
            throw new JobBlockedException("Master password is locked; required connection secrets are unavailable.");
        }
    }

    private boolean jobNeedsConnectionSecret() {
        return connection.getAuthMethod() != AuthMethod.PUBLIC_KEY
            || connection.getSshKeyId() != null
            || connection.getPrivateKeyPassphrase() != null;
    }

    public boolean isConnected() {
        return session != null && session.isOpen();
    }

    private void ensureConnected() {
        if (session == null || !session.isOpen()) {
            throw new IllegalStateException("SSH session is not connected.");
        }
    }

    private String resolveConnectionPassword() throws Exception {
        if (connection.getCredentialId() != null && app.getCredentialManager() != null) {
            var credential = app.getCredentialManager().findCredentialById(connection.getCredentialId());
            if (credential.isPresent()) {
                return app.getCredentialManager().getPassword(credential.get(), masterPassword);
            }
        }
        PasswordVault vault = new PasswordVault(new EncryptionService(), masterPassword);
        return vault.retrievePassword(connection);
    }

    private void authenticateWithKey() throws Exception {
        String keyPath = null;
        String passphrase = null;
        SSHKeyManager keyManager = app.getSSHKeyManager();
        if (connection.getSshKeyId() != null && keyManager != null) {
            Optional<SSHKey> key = keyManager.findKeyById(connection.getSshKeyId());
            if (key.isPresent()) {
                keyPath = keyManager.getEffectiveKeyPath(key.get());
                passphrase = masterPassword != null ? keyManager.getPassphrase(key.get(), masterPassword) : null;
            }
        }
        if (keyPath == null || keyPath.isBlank()) {
            keyPath = connection.getPrivateKeyPath();
        }
        if (keyPath == null || keyPath.isBlank()) {
            throw new JobBlockedException("No SSH key path is configured for this job target.");
        }
        if (passphrase == null && connection.getPrivateKeyPassphrase() != null && masterPassword != null) {
            passphrase = new EncryptionService().decryptPassword(connection.getPrivateKeyPassphrase(), masterPassword);
        }

        Path effectiveKeyPath = createTemporaryKeyFileIfNeeded(keyPath);
        authenticatedPrivateKeyPath = effectiveKeyPath;
        authenticatedPrivateKeyPassphrase = passphrase;
        FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(effectiveKeyPath);
        if (passphrase != null && !passphrase.isEmpty()) {
            String finalPassphrase = passphrase;
            keyPairProvider.setPasswordFinder((sess, path, retryIndex) -> finalPassphrase);
        }
        Iterable<java.security.KeyPair> keyPairs = keyPairProvider.loadKeys(session);
        int count = 0;
        for (java.security.KeyPair keyPair : keyPairs) {
            session.addPublicKeyIdentity(keyPair);
            count++;
        }
        if (count == 0) {
            throw new JobBlockedException("No key pairs were found in the configured SSH key.");
        }
    }

    private Path createTemporaryKeyFileIfNeeded(String keyPath) throws Exception {
        if (!keyPath.startsWith("TEMPORARY:")) {
            Path path = Path.of(keyPath);
            if (!Files.exists(path)) {
                throw new JobBlockedException("SSH key file does not exist: " + keyPath);
            }
            return path;
        }
        String content = keyPath.substring("TEMPORARY:".length());
        if (!content.endsWith("\n")) {
            content = content + "\n";
        }
        Path tempFile = Files.createTempFile("kortty_scheduler_key_", ".key");
        Files.writeString(tempFile, content, StandardCharsets.UTF_8);
        try {
            Files.setPosixFilePermissions(tempFile, java.util.Set.of(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
        } catch (UnsupportedOperationException ignored) {
            tempFile.toFile().setReadable(false, false);
            tempFile.toFile().setReadable(true, true);
            tempFile.toFile().setWritable(false, false);
            tempFile.toFile().setWritable(true, true);
        }
        tempFile.toFile().deleteOnExit();
        return tempFile;
    }

    private List<UserAuthFactory> buildUserAuthFactories(ServerConnection connection) {
        if (connection.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
            return List.of(
                new UserAuthPublicKeyFactory(),
                new UserAuthKeyboardInteractiveFactory(),
                new UserAuthPasswordFactory()
            );
        }
        return List.of(
            new UserAuthPasswordFactory(),
            new UserAuthKeyboardInteractiveFactory(),
            new UserAuthPublicKeyFactory()
        );
    }

    private String appendRemote(String base, String name) {
        if (base == null || base.isBlank() || "/".equals(base)) {
            return "/" + name;
        }
        return base.endsWith("/") ? base + name : base + "/" + name;
    }

    private String parentRemote(String remotePath) {
        if (remotePath == null || remotePath.isBlank()) {
            return "/";
        }
        int index = remotePath.lastIndexOf('/');
        if (index <= 0) {
            return "/";
        }
        return remotePath.substring(0, index);
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (Exception e) {
            logger.debug("Could not close scheduler resource", e);
        }
    }

    public record CommandResult(int exitCode, String stdout, String stderr) {
        public boolean isSuccess() {
            return exitCode == 0;
        }
    }

    private static final class HostKeyCapture {
        private volatile String algorithm;
        private volatile String fingerprint;
        private volatile String publicKeyLine;

        private void capture(PublicKey serverKey) throws Exception {
            if (serverKey == null) {
                return;
            }
            algorithm = serverKey.getAlgorithm();
            fingerprint = fingerprintSha256(serverKey);
            publicKeyLine = PublicKeyEntry.toString(serverKey);
        }
    }

    private final class SchedulerUserInteraction implements org.apache.sshd.client.auth.keyboard.UserInteraction {
        @Override
        public boolean isInteractionAllowed(org.apache.sshd.client.session.ClientSession session) {
            return true;
        }

        @Override
        public String[] interactive(
            org.apache.sshd.client.session.ClientSession session,
            String name,
            String instruction,
            String lang,
            String[] prompt,
            boolean[] echo) {

            if (prompt == null) {
                return new String[0];
            }
            String[] responses = new String[prompt.length];
            for (int i = 0; i < prompt.length; i++) {
                String promptText = prompt[i] != null ? prompt[i].toLowerCase() : "";
                if (promptText.contains("password")) {
                    responses[i] = password != null ? password : "";
                } else if (promptText.contains("reason")) {
                    responses[i] = getLastAccessReason();
                } else {
                    responses[i] = "";
                }
            }
            return responses;
        }

        @Override
        public String getUpdatedPassword(org.apache.sshd.client.session.ClientSession session, String prompt, String lang) {
            return null;
        }

        private String getLastAccessReason() {
            try {
                if (app.getGlobalSettingsManager() != null && app.getGlobalSettingsManager().getSettings() != null) {
                    List<String> history = app.getGlobalSettingsManager().getSettings().getAccessReasonHistory();
                    if (history != null && !history.isEmpty()) {
                        return history.get(0);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not read scheduler access-reason history", e);
            }
            return "";
        }
    }
}
