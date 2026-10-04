package de.kortty.core;

import de.kortty.core.sftp.BorrowedSessionSupplier;
import de.kortty.core.sftp.SftpChannelSource;
import de.kortty.core.sftp.SftpSubsystemUnavailableException;
import de.kortty.core.sftp.transfer.SftpStreamCopier;
import de.kortty.core.sftp.transfer.TransferCancellation;
import de.kortty.core.sftp.transfer.TransferProgressListener;
import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import de.kortty.ui.I18n;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.future.CloseFuture;
import org.apache.sshd.common.future.SshFutureListener;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.keyprovider.KeyIdentityProvider;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages SFTP connections for file transfer.
 *
 * <p>As a {@link SftpChannelSource} it either owns its SSH session (a standalone login with
 * {@link #connect()}) or borrows the session of a terminal pane ({@link #attach}). Transfer workers
 * may open further SFTP channels on it with {@link #openChannel()}.
 *
 * <p>A borrowed session never closes the terminal's {@link ClientSession}, its client or a jump
 * tunnel: {@link #close()} closes only the SFTP channels this object opened. The session is
 * re-resolved through the {@link BorrowedSessionSupplier} for every new channel, so a terminal
 * reconnect is picked up and a pane that now runs another user or host is never used. Extra
 * channels on a borrowed session (parallel transfers and remote commands) share the terminal's
 * {@code MaxSessions} budget with the shell and the agent, so at most
 * {@link #BORROWED_CHANNEL_BUDGET} of them are open at once.
 */
public class SFTPSession implements SftpChannelSource {
    
    private static final Logger logger = LoggerFactory.getLogger(SFTPSession.class);

    /**
     * Extra channels (beyond the primary SFTP channel) a borrowed session may hold open at once:
     * the terminal's shell, its agent commands and the sidebar share the server's
     * {@code MaxSessions} with them, and bulk traffic delays the terminal's liveness probe.
     */
    public static final int BORROWED_CHANNEL_BUDGET = 2;
    
    private final ServerConnection connection;
    private final String password;
    /** Null for a borrowed session: it never connects or verifies a host key itself. */
    private final SshHostKeyTrustManager hostKeyTrustManager;
    /** False when the SSH session belongs to a terminal pane. */
    private final boolean owning;
    /** Resolves the terminal pane's current session; null for an owning session. */
    private final @Nullable BorrowedSessionSupplier borrowedSupplier;
    /** A log label for a borrowed session (never a secret); null for an owning one. */
    private final @Nullable String label;
    /** Extra channels a borrowed session opened and has not seen closed yet. */
    private final Set<Object> extraChannels = ConcurrentHashMap.newKeySet();
    private final Object channelBudgetLock = new Object();
    /** Registered on a borrowed session so it can be removed again on {@link #close()}. */
    private volatile @Nullable SshFutureListener<CloseFuture> sessionCloseListener;
    private volatile SshHostKeyTrustManager.ReplacePolicy hostKeyReplacePolicy =
        SshHostKeyTrustManager.ReplacePolicy.NEVER;
    private SSHKeyManager sshKeyManager;
    private char[] masterPassword;

    private SshClient client;
    private ClientSession session;
    private SftpClient sftpClient;
    private String currentRemotePath = "~";
    /** Established bastion hop when the connection has an enabled jump server; null otherwise. */
    private JumpHostSupport.JumpTunnel jumpTunnel;
    /** Told once when the connection ends without {@link #close()} being called. */
    private volatile Runnable disconnectListener;
    /** Set by {@link #close()} before anything is closed, so a deliberate close is never reported. */
    private volatile boolean closingDeliberately;
    private final AtomicBoolean disconnectReported = new AtomicBoolean();

    public SFTPSession(ServerConnection connection, String password) {
        this(connection, password, SshHostKeyTrustManager.shared());
    }

    SFTPSession(
        ServerConnection connection,
        String password,
        SshHostKeyTrustManager hostKeyTrustManager) {

        this.connection = connection;
        this.password = password;
        this.hostKeyTrustManager = java.util.Objects.requireNonNull(hostKeyTrustManager, "hostKeyTrustManager");
        this.owning = true;
        this.borrowedSupplier = null;
        this.label = null;
    }

    private SFTPSession(ServerConnection paneConnection, BorrowedSessionSupplier supplier, @Nullable String label) {
        this.connection = Objects.requireNonNull(paneConnection, "paneConnection");
        this.password = null;
        this.hostKeyTrustManager = null;
        this.owning = false;
        this.borrowedSupplier = Objects.requireNonNull(supplier, "supplier");
        this.label = label;
    }

    /**
     * Opens SFTP on a terminal pane's SSH session instead of logging in again. The result does not
     * own the session: {@link #close()} closes only its own SFTP channels, and a disconnect of the
     * terminal's session (or of the SFTP channel alone) is reported once to the
     * {@linkplain #setDisconnectListener disconnect listener}. Network I/O: never call this on the
     * FX thread.
     *
     * @param supplier resolves the pane's current session; checked at every use
     * @param paneConnection the connection the pane runs (its own, not necessarily the tab's)
     * @param label a short log label such as {@code host:port}; never a secret
     * @throws de.kortty.policy.PolicyRestrictionException when the policy blocks the pane's target
     * @throws SftpSubsystemUnavailableException when the server or a proxy refuses the SFTP
     *     subsystem on that session; the caller may fall back to a separate login
     * @throws IOException when the pane's session is gone or no longer matches the pane
     */
    public static SFTPSession attach(BorrowedSessionSupplier supplier, ServerConnection paneConnection, String label)
            throws IOException {
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(paneConnection, "paneConnection");
        de.kortty.policy.ServerAccessPolicy.firstBlockedTarget(paneConnection).ifPresent(target -> {
            logger.warn("Blocked SFTP on a terminal session to {} by enterprise policy", target);
            throw new de.kortty.policy.PolicyRestrictionException(
                "Connection to " + target + " is blocked by your organization's policy");
        });
        SFTPSession attached = new SFTPSession(paneConnection, supplier, label);
        attached.attachToBorrowedSession();
        return attached;
    }

    private void attachToBorrowedSession() throws IOException {
        ClientSession borrowed = resolveBorrowedSession();
        try {
            sftpClient = SftpClientFactory.instance().createSftpClient(borrowed);
        } catch (IOException | RuntimeException e) {
            throw new SftpSubsystemUnavailableException(sftpSubsystemFailureMessage(e), e);
        }
        session = borrowed;
        SshFutureListener<CloseFuture> onSessionClosed = future -> reportDisconnect();
        sessionCloseListener = onSessionClosed;
        borrowed.addCloseFutureListener(onSessionClosed);
        sftpClient.getClientChannel().addCloseFutureListener(future -> reportDisconnect());
        try {
            currentRemotePath = sftpClient.canonicalPath(".");
        } catch (IOException e) {
            currentRemotePath = "~";
        }
        logger.info("SFTP attached to the terminal session of {}", describe());
    }

    /** The pane's open session right now, or an {@link IOException} with the readable message. */
    private ClientSession resolveBorrowedSession() throws IOException {
        ClientSession current = borrowedSupplier != null ? borrowedSupplier.get() : null;
        if (current == null || !current.isOpen() || current.isClosing()) {
            throw new IOException(I18n.get("terminal.sftp.sessionUnavailable"));
        }
        return current;
    }
    
    /**
     * Sets SSHKeyManager and master password for key-based authentication.
     */
    public void setSSHKeyManager(SSHKeyManager sshKeyManager, char[] masterPassword) {
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
    }

    /**
     * Hands this session the vault it needs, whatever the target's authentication method.
     *
     * <p>The master password is always set, because it also decrypts the stored jump server
     * password. The key manager is only used for a {@code PUBLIC_KEY} target that does not log in
     * with a temporary key: a managed key referenced by the connection must never take the place
     * of the temporary one.
     *
     * @param keyManager the managed SSH keys; may be {@code null}
     * @param masterPassword the vault's master password, or {@code null} while the vault is locked
     * @param temporaryKeyAuth whether this session authenticates with a temporary SSH key
     */
    public void configureVault(SSHKeyManager keyManager, char[] masterPassword, boolean temporaryKeyAuth) {
        this.masterPassword = masterPassword;
        this.sshKeyManager = !temporaryKeyAuth
                && connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY
            ? keyManager
            : null;
    }

    /**
     * Registers a callback for a connection that ends on its own: the server or the network closed
     * the SSH session or the SFTP channel. It runs at most once, on an SSHD I/O thread, and never
     * because of {@link #close()}. A network drop without a FIN only surfaces once an operation fails
     * or TCP gives up.
     *
     * @param listener the callback, or {@code null} to remove it
     */
    public void setDisconnectListener(Runnable listener) {
        this.disconnectListener = listener;
    }

    /**
     * Whether a changed host key (of the target or of its jump server) may be reviewed and replaced
     * during {@link #connect()}. Only the SFTP manager tab sets
     * {@link SshHostKeyTrustManager.ReplacePolicy#INTERACTIVE}; the default
     * {@link SshHostKeyTrustManager.ReplacePolicy#NEVER} keeps restored editors and other
     * background transfers on the plain mismatch warning.
     */
    public void setHostKeyReplacePolicy(SshHostKeyTrustManager.ReplacePolicy replacePolicy) {
        this.hostKeyReplacePolicy = replacePolicy != null ? replacePolicy : SshHostKeyTrustManager.ReplacePolicy.NEVER;
    }

    /**
     * Establishes the SFTP connection.
     */
    public void connect() throws Exception {
        if (!owning) {
            throw new IllegalStateException("A borrowed SFTP session is attached, not connected");
        }
        de.kortty.policy.ServerAccessPolicy.firstBlockedTarget(connection).ifPresent(target -> {
            logger.warn("Blocked SFTP session to {} by enterprise policy", target);
            throw new de.kortty.policy.PolicyRestrictionException(
                "Connection to " + target + " is blocked by your organization's policy");
        });
        logger.info("Connecting SFTP to {}@{}:{}",
                connection.getUsername(), connection.getHost(), connection.getPort());
        
        client = SshClient.setUpDefaultClient();
        client.setUserAuthFactories(buildUserAuthFactories(connection));
        
        // Set up keyboard-interactive handler - use last access reason from TAB connection
        client.setUserInteraction(new org.apache.sshd.client.auth.keyboard.UserInteraction() {
            @Override
            public boolean isInteractionAllowed(org.apache.sshd.client.session.ClientSession sess) {
                return true;
            }
            
            @Override
            public String[] interactive(org.apache.sshd.client.session.ClientSession sess, String name, String instruction,
                                       String lang, String[] prompt, boolean[] echo) {
                logger.info("Keyboard-interactive request: name='{}', instruction='{}'", name, instruction);
                if (prompt == null || prompt.length == 0) return new String[0];
                
                String[] responses = new String[prompt.length];
                final String[] finalResponses = responses;
                final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                
                for (int i = 0; i < prompt.length; i++) {
                    boolean isAccessReason = prompt[i] != null && prompt[i].toLowerCase().contains("reason");
                    if (isAccessReason) {
                        String lastReason = getLastAccessReason();
                        if (lastReason != null && !lastReason.trim().isEmpty()) {
                            // Use same reason as TAB - no dialog needed
                            finalResponses[i] = lastReason;
                        } else {
                            // No history - show dialog (must run on JavaFX thread)
                            final int idx = i;
                            final String promptText = prompt[i];
                            javafx.application.Platform.runLater(() -> {
                                try {
                                    javafx.scene.control.TextInputDialog dialog = new javafx.scene.control.TextInputDialog();
                                    dialog.setTitle("SSH Authentication - Access Reason");
                                    dialog.setHeaderText(instruction != null && !instruction.isEmpty() ? instruction : "Authentication Required");
                                    dialog.setContentText(promptText);
                                    java.util.Optional<String> result = dialog.showAndWait();
                                    String reason = result.orElse("");
                                    finalResponses[idx] = reason;
                                    if (reason != null && !reason.trim().isEmpty()) {
                                        try {
                                            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
                                            if (gsm != null && gsm.getSettings() != null) {
                                                gsm.getSettings().addAccessReason(reason);
                                                gsm.save();
                                            }
                                        } catch (Exception e) {
                                            logger.warn("Could not save access reason: {}", e.getMessage());
                                        }
                                    }
                                } catch (Exception e) {
                                    logger.error("Error showing access reason dialog: {}", e.getMessage());
                                    finalResponses[idx] = "";
                                } finally {
                                    latch.countDown();
                                }
                            });
                            try {
                                latch.await(5, java.util.concurrent.TimeUnit.MINUTES);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        }
                    } else {
                        finalResponses[i] = "";
                    }
                }
                return responses;
            }
            
            @Override
            public String getUpdatedPassword(org.apache.sshd.client.session.ClientSession sess, String prompt, String lang) {
                return null;
            }
        });
        
        // Note: EdDSA signature support is automatically enabled when the eddsa dependency
        // is on the classpath. The client will detect and use EdDSA signatures automatically.
        
        SshHostKeyTrustManager.ReplacePolicy replacePolicy = hostKeyReplacePolicy;
        client.setServerKeyVerifier(hostKeyTrustManager.verifierFor(
            connection, HostKeyCheckPolicy.resolveFromSettings(connection), replacePolicy));
        client.start();
        
        int timeoutSeconds = connection.getConnectionTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 15;
        }
        
        // With an enabled jump server, hop first: authenticate to the bastion with its own
        // credentials and open a loopback forward to the target. The verifier set above was built
        // for the target's real host:port, so the target's key is still pinned under its real name.
        String connectHost = connection.getHost();
        int connectPort = connection.getPort();
        if (JumpHostSupport.isActive(connection)) {
            jumpTunnel = JumpHostSupport.open(
                connection, hostKeyTrustManager, masterPassword, Duration.ofSeconds(timeoutSeconds),
                replacePolicy);
            connectHost = jumpTunnel.localHost();
            connectPort = jumpTunnel.localPort();
            // Log raw host:port rather than connection.getDisplayName(): the latter can fall back to
            // "username@host", and CodeQL's coarse sensitive-data heuristic treats any getter on
            // ServerConnection as tainted once the class holds an encryptedPassword field. Host/port
            // carry no credential and give the same diagnostic value.
            logger.info("SFTP connecting to {}:{} via jump server {}:{}",
                connection.getHost(), connection.getPort(),
                connection.getJumpServer().getHost(), connection.getJumpServer().getPort());
        }

        // Once the tunnel is open, a failure connecting or authenticating to the target must not
        // leak it — the caller may not reach close(). Close the tunnel on the way out and rethrow.
        try {
            session = client.connect(connection.getUsername(), connectHost, connectPort)
                    .verify(Duration.ofSeconds(timeoutSeconds))
                    .getSession();

            // Authenticate
            if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                authenticateWithKey();
            } else {
                session.addPasswordIdentity(password);
            }
            session.auth().verify(Duration.ofSeconds(timeoutSeconds));

            try {
                sftpClient = SftpClientFactory.instance().createSftpClient(session);
            } catch (IOException | RuntimeException e) {
                throw new SftpSubsystemUnavailableException(sftpSubsystemFailureMessage(e), e);
            }
            // Both: the server can end the SFTP channel alone and keep the SSH session open.
            session.addCloseFutureListener(future -> reportDisconnect());
            sftpClient.getClientChannel().addCloseFutureListener(future -> reportDisconnect());
        } catch (Exception e) {
            if (jumpTunnel != null) {
                jumpTunnel.close();
                jumpTunnel = null;
            }
            throw e;
        }
        
        // Initialize current directory
        try {
            currentRemotePath = sftpClient.canonicalPath(".");
        } catch (IOException e) {
            currentRemotePath = "~";
        }
        
        logger.info("SFTP connected to {}", connection.getDisplayName());
    }

    static List<UserAuthFactory> buildUserAuthFactories(ServerConnection connection) {
        if (connection != null && connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
            // CyberArk requires keyboard-interactive after public-key auth for access-reason prompts.
            return java.util.List.of(
                new UserAuthPublicKeyFactory(),
                new UserAuthKeyboardInteractiveFactory(),
                new UserAuthPasswordFactory()
            );
        }
        // For password logins we must explicitly include password auth. Without it, servers that do
        // not offer keyboard-interactive password prompts fail with "No more authentication methods available".
        return java.util.List.of(
            new UserAuthPasswordFactory(),
            new UserAuthKeyboardInteractiveFactory(),
            new UserAuthPublicKeyFactory()
        );
    }

    private void reportDisconnect() {
        Runnable listener = disconnectListener;
        if (closingDeliberately || listener == null || !disconnectReported.compareAndSet(false, true)) {
            return;
        }
        logger.info("SFTP connection to {}:{} ended", connection.getHost(), connection.getPort());
        try {
            listener.run();
        } catch (RuntimeException e) {
            logger.warn("SFTP disconnect listener failed", e);
        }
    }

    static String sftpSubsystemFailureMessage(Throwable failure) {
        String causeMessage = safeFailureMessage(failure);
        if (isSftpSubsystemNegotiationFailure(failure)) {
            return I18n.get("sftp.error.subsystemRejected", causeMessage);
        }
        return I18n.get("sftp.error.subsystemStartFailed", causeMessage);
    }

    static boolean isSftpSubsystemNegotiationFailure(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof EOFException) {
                return true;
            }
            String message = current.getMessage();
            String normalizedMessage = message != null ? message.toLowerCase(Locale.ROOT) : null;
            if (message != null
                    && (normalizedMessage.contains("channel closing")
                    || normalizedMessage.contains("closed before version negotiated")
                    || normalizedMessage.contains("eofexception")
                    || normalizedMessage.contains("subsystem request failed"))) {
                return true;
            }
        }
        return false;
    }

    private static String safeFailureMessage(Throwable failure) {
        if (failure == null) {
            return I18n.get("sftp.error.unknownCause");
        }
        String message = failure.getMessage();
        if (message == null || message.isBlank()) {
            message = failure.getClass().getSimpleName();
        }
        return message;
    }
    
    /**
     * Lists files in a directory.
     */
    public List<SftpClient.DirEntry> listFiles(String remotePath) throws IOException {
        java.util.List<SftpClient.DirEntry> result = new java.util.ArrayList<>();
        Iterable<SftpClient.DirEntry> entries = sftpClient.readDir(remotePath);
        if (entries != null) {
            for (SftpClient.DirEntry entry : entries) {
                result.add(entry);
            }
        }
        return result;
    }
    
    /**
     * Gets file attributes.
     */
    public SftpClient.Attributes getAttributes(String remotePath) throws IOException {
        return sftpClient.stat(remotePath);
    }
    
    /**
     * Downloads a file from remote to local, replacing a local file of that name.
     *
     * <p>The copy is pipelined (see {@link SftpStreamCopier}).
     */
    public void downloadFile(String remotePath, Path localPath) throws IOException {
        // Stat first, as before: a missing remote file must not leave an empty local one behind.
        sftpClient.stat(remotePath);
        try (OutputStream out = Files.newOutputStream(localPath)) {
            long bytes = SftpStreamCopier.download(sftpClient, remotePath, out, 0,
                TransferProgressListener.NONE, TransferCancellation.create());
            logger.info("Downloaded {} bytes from {} to {}", bytes, remotePath, localPath);
        }
    }

    /**
     * Downloads {@code remotePath} into a new local file {@code localPath} and can be stopped
     * mid-file: {@code cancel} is checked after every buffer of the pipelined copy (on the shared
     * channel the cancel is cooperative; the channel itself stays open). The local file must not
     * exist yet, and a symbolic link there is never followed. A missing remote file creates nothing,
     * and a cancelled or failed download deletes what it wrote, so no partial file is left behind.
     *
     * @throws java.nio.file.FileAlreadyExistsException when {@code localPath} exists
     * @throws de.kortty.core.sftp.transfer.TransferCancelledException when {@code cancel} stopped it
     */
    public void downloadNewFile(String remotePath, Path localPath, TransferCancellation cancel) throws IOException {
        SftpClient client = primaryClient();
        cancel.throwIfCancelled();
        // Stat first: a missing remote file must not leave an empty local one behind.
        client.stat(remotePath);
        // CREATE_NEW refuses any existing entry, a symbolic link included, so the cleanup below
        // only ever deletes the file this call created.
        Files.newByteChannel(localPath, java.nio.file.StandardOpenOption.CREATE_NEW,
            java.nio.file.StandardOpenOption.WRITE).close();
        boolean complete = false;
        try {
            long bytes = SftpStreamCopier.download(client, remotePath, localPath, 0,
                TransferProgressListener.NONE, cancel);
            complete = true;
            logger.info("Downloaded {} bytes from {} to {}", bytes, remotePath, localPath);
        } finally {
            if (!complete) {
                try {
                    Files.deleteIfExists(localPath);
                } catch (IOException e) {
                    logger.debug("Could not delete the unfinished download {}", localPath, e);
                }
            }
        }
    }

    /**
     * Uploads a local file to {@code remotePath}, replacing a remote file of that name.
     *
     * <p>The file is streamed and pipelined (see {@link SftpStreamCopier}), so its size is limited
     * neither by the heap nor by the 2 GB maximum of a Java array.
     */
    public void uploadFile(Path localPath, String remotePath) throws IOException {
        long bytes = SftpStreamCopier.upload(sftpClient, remotePath, localPath, 0, SftpStreamCopier.REPLACE,
            TransferProgressListener.NONE, TransferCancellation.create());
        logger.info("Uploaded {} bytes from {} to {}", bytes, localPath, remotePath);
    }

    /**
     * Downloads a file and returns its content as byte array.
     */
    public byte[] downloadFileBytes(String remotePath) throws IOException {
        try (java.io.InputStream in = sftpClient.read(remotePath);
             java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
            }
            logger.info("Downloaded {} bytes from {}", out.size(), remotePath);
            return out.toByteArray();
        }
    }
    
    /**
     * Uploads a file from byte array to remote.
     */
    public void uploadFileBytes(byte[] data, String remotePath) throws IOException {
        try (java.io.OutputStream out = sftpClient.write(remotePath, 
                java.util.EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate))) {
            out.write(data);
            logger.info("Uploaded {} bytes to {}", data.length, remotePath);
        }
    }
    
    /**
     * Creates a directory on the remote server; fails when anything already exists there.
     */
    public void createDirectory(String remotePath) throws IOException {
        sftpClient.mkdir(remotePath);
    }

    /**
     * Creates a directory unless it already exists, so uploading a folder again merges into it.
     *
     * @throws IOException when something that is not a directory is in the way, or the server refuses
     */
    public void createDirectoryIfMissing(String remotePath) throws IOException {
        RemotePathSupport.ensureDirectory(sftpClient, remotePath);
    }

    /**
     * Creates a directory and every missing parent; existing directories are kept.
     */
    public void createDirectories(String remotePath) throws IOException {
        RemotePathSupport.mkdirs(sftpClient, remotePath);
    }
    
    /**
     * Deletes a file on the remote server.
     */
    public void deleteFile(String remotePath) throws IOException {
        SftpClient.Attributes attrs = sftpClient.stat(remotePath);
        if (attrs.isDirectory()) {
            sftpClient.rmdir(remotePath);
        } else {
            sftpClient.remove(remotePath);
        }
    }
    
    /**
     * Copies a file or directory on the remote server. A folder copied onto an existing folder
     * merges into it; a file of the same name is replaced.
     *
     * @throws IOException also when {@code destPath} is {@code sourcePath} itself or lies inside it
     */
    public void copyFile(String sourcePath, String destPath) throws IOException {
        requireTargetOutsideSource(sourcePath, destPath);
        copyTree(sourcePath, destPath);
    }

    private void copyTree(String sourcePath, String destPath) throws IOException {
        SftpClient.Attributes attrs = sftpClient.stat(sourcePath);
        if (attrs.isDirectory()) {
            // Copying onto an existing folder merges into it
            RemotePathSupport.ensureDirectory(sftpClient, destPath);
            // Copy contents recursively
            List<SftpClient.DirEntry> entries = listFiles(sourcePath);
            for (SftpClient.DirEntry entry : entries) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                copyTree(RemotePathSupport.appendRemotePath(sourcePath, name),
                    RemotePathSupport.appendRemotePath(destPath, name));
            }
        } else {
            // Copy file
            try (java.io.InputStream in = sftpClient.read(sourcePath);
                 java.io.OutputStream out = sftpClient.write(destPath, 
                         java.util.EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate))) {
                byte[] buffer = new byte[8192];
                int bytesRead;
                while ((bytesRead = in.read(buffer)) != -1) {
                    out.write(buffer, 0, bytesRead);
                }
            }
        }
    }

    /**
     * Refuses a copy onto the source itself or into a folder inside it. Onto itself, opening the
     * target with truncate empties every file before it is read, and since a folder copy merges into
     * an existing folder, that would wipe a whole tree; into itself, the copy never ends. Both paths
     * are compared after the server resolved them, so {@code ..}, {@code .} and symbolic links count.
     */
    private void requireTargetOutsideSource(String sourcePath, String destPath) throws IOException {
        String dest = withoutTrailingSlashes(destPath.trim());
        String source = resolvedPath(sourcePath);
        // A target that does not exist yet: its folder resolved by the server, plus its name.
        String target = RemotePathSupport.exists(sftpClient, dest)
            ? resolvedPath(dest)
            : RemotePathSupport.appendRemotePath(
                resolvedPath(RemotePathSupport.parentRemotePath(dest)), remoteName(dest));
        if (isSameOrInside(target, source)) {
            throw new IOException(I18n.get("sftp.error.copyIntoItself", sourcePath, destPath));
        }
    }

    /** Whether {@code target} is {@code source} or lies below it; both absolute and normalized. */
    static boolean isSameOrInside(String target, String source) {
        if (target.equals(source)) {
            return true;
        }
        return target.startsWith(source.endsWith("/") ? source : source + "/");
    }

    /** The server's canonical form of {@code remotePath}; the textual normal form if it cannot say. */
    private String resolvedPath(String remotePath) {
        try {
            String canonical = sftpClient.canonicalPath(remotePath);
            if (canonical != null && canonical.startsWith("/")) {
                return RemotePathSupport.normalizeAbsolutePath(canonical);
            }
        } catch (IOException e) {
            logger.debug("Could not resolve remote path {}: {}", remotePath, e.getMessage());
        }
        return RemotePathSupport.normalizeAbsolutePath(remotePath);
    }

    private static String remoteName(String remotePath) {
        int slash = remotePath.lastIndexOf('/');
        return slash >= 0 ? remotePath.substring(slash + 1) : remotePath;
    }

    private static String withoutTrailingSlashes(String remotePath) {
        String path = remotePath;
        while (path.length() > 1 && path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    /**
     * Renames a file on the remote server.
     */
    public void renameFile(String oldPath, String newPath) throws IOException {
        sftpClient.rename(oldPath, newPath);
    }
    
    /**
     * Sets file permissions (chmod) on the remote server.
     * @param remotePath Path to the file or directory
     * @param permissions Permissions in octal format (e.g., 0755) or symbolic (e.g., "rwxr-xr-x")
     */
    public void setPermissions(String remotePath, String permissions) throws IOException {
        SftpClient.Attributes attrs = sftpClient.stat(remotePath);
        
        // Parse permissions - support both octal (0755) and symbolic (rwxr-xr-x)
        int perms = parsePermissions(permissions, attrs);
        
        attrs.setPermissions(perms);
        sftpClient.setStat(remotePath, attrs);
        
        logger.info("Set permissions {} ({}) on {}", permissions, String.format("%04o", perms), remotePath);
    }
    
    /**
     * Gets file permissions as octal string.
     */
    public String getPermissions(String remotePath) throws IOException {
        SftpClient.Attributes attrs = sftpClient.stat(remotePath);
        int perms = attrs.getPermissions();
        return String.format("%04o", perms);
    }
    
    /**
     * Parses permissions string (octal or symbolic) to integer.
     */
    private int parsePermissions(String permissions, SftpClient.Attributes currentAttrs) {
        permissions = permissions.trim();
        
        // If it's already a number, parse as octal
        if (permissions.matches("^[0-7]+$")) {
            return Integer.parseInt(permissions, 8);
        }
        
        // Parse symbolic format (rwxr-xr-x)
        if (permissions.length() == 9 || permissions.length() == 10) {
            int perms = 0;
            String permStr = permissions.length() == 10 ? permissions.substring(1) : permissions;
            
            // Owner permissions
            if (permStr.charAt(0) == 'r') perms |= 0400;
            if (permStr.charAt(1) == 'w') perms |= 0200;
            if (permStr.charAt(2) == 'x') perms |= 0100;
            
            // Group permissions
            if (permStr.charAt(3) == 'r') perms |= 0040;
            if (permStr.charAt(4) == 'w') perms |= 0020;
            if (permStr.charAt(5) == 'x') perms |= 0010;
            
            // Other permissions
            if (permStr.charAt(6) == 'r') perms |= 0004;
            if (permStr.charAt(7) == 'w') perms |= 0002;
            if (permStr.charAt(8) == 'x') perms |= 0001;
            
            return perms;
        }
        
        // Default: use current permissions
        return currentAttrs.getPermissions();
    }
    
    /**
     * Gets the current working directory.
     */
    public String getCurrentDirectory() throws IOException {
        if (currentRemotePath == null || currentRemotePath.equals("~")) {
            currentRemotePath = sftpClient.canonicalPath(".");
        }
        return currentRemotePath;
    }
    
    /**
     * Changes the current working directory.
     */
    public void changeDirectory(String remotePath) throws IOException {
        // Verify the path exists and is a directory
        SftpClient.Attributes attrs = sftpClient.stat(remotePath);
        if (!attrs.isDirectory()) {
            throw new IOException("Path is not a directory: " + remotePath);
        }
        // Update current path by resolving it
        currentRemotePath = sftpClient.canonicalPath(remotePath);
    }
    
    /**
     * Closes the SFTP connection.
     */
    public void close() {
        // First, so the close futures below never report this as a lost connection.
        closingDeliberately = true;
        if (!owning) {
            closeBorrowed();
            return;
        }
        try {
            if (sftpClient != null) {
                sftpClient.close();
            }
            if (session != null) {
                session.close();
            }
            if (client != null) {
                client.stop();
            }
            logger.info("SFTP connection closed");
        } catch (Exception e) {
            logger.error("Error closing SFTP connection", e);
        } finally {
            if (jumpTunnel != null) {
                jumpTunnel.close();
                jumpTunnel = null;
            }
        }
    }
    
    /** Closes this object's own channels; the terminal's session, client and jump tunnel stay open. */
    private void closeBorrowed() {
        for (Object open : List.copyOf(extraChannels)) {
            if (!(open instanceof java.io.Closeable channel)) {
                continue; // a budget placeholder
            }
            try {
                channel.close();
            } catch (IOException | RuntimeException e) {
                logger.debug("Closing an SFTP channel on a terminal session failed: {}", e.getMessage());
            }
        }
        extraChannels.clear();
        try {
            if (sftpClient != null) {
                sftpClient.close();
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Closing the SFTP channel on a terminal session failed: {}", e.getMessage());
        }
        ClientSession borrowed = session;
        SshFutureListener<CloseFuture> listener = sessionCloseListener;
        if (borrowed != null && listener != null) {
            // The terminal's session outlives this object: do not leave a listener behind on it.
            borrowed.removeCloseFutureListener(listener);
            sessionCloseListener = null;
        }
        logger.info("SFTP on the terminal session of {} closed", describe());
    }

    public boolean isConnected() {
        SftpClient client = sftpClient;
        ClientSession current = session;
        return client != null && client.isOpen() && current != null && current.isOpen();
    }

    @Override
    public SftpClient primaryClient() {
        SftpClient client = sftpClient;
        if (client == null) {
            throw new IllegalStateException("SFTP session is not connected");
        }
        return client;
    }

    /** Opens another SFTP channel on this session's SSH connection; the caller closes it. */
    @Override
    public SftpClient openChannel() throws IOException {
        if (!owning) {
            return openBorrowedChannel();
        }
        ClientSession current = session;
        if (current == null || !current.isOpen()) {
            throw new IOException("SFTP session is not connected");
        }
        return SftpClientFactory.instance().createSftpClient(current);
    }

    /**
     * An extra SFTP channel on the pane's current session, within {@link #BORROWED_CHANNEL_BUDGET}.
     * A refusal over budget is an {@link IOException}, which the transfer pool treats like a server
     * that refuses a channel: it shrinks and carries on with what it has.
     */
    private SftpClient openBorrowedChannel() throws IOException {
        if (closingDeliberately) {
            throw new IOException(I18n.get("terminal.sftp.sessionUnavailable"));
        }
        ClientSession current = resolveBorrowedSession();
        BudgetSlot slot = reserveBorrowedChannel();
        try {
            SftpClient extra;
            try {
                extra = SftpClientFactory.instance().createSftpClient(current);
            } catch (IOException | RuntimeException e) {
                throw new SftpSubsystemUnavailableException(sftpSubsystemFailureMessage(e), e);
            }
            slot.bind(extra);
            extra.getClientChannel().addCloseFutureListener(future -> extraChannels.remove(extra));
            if (!extra.isOpen()) {
                extraChannels.remove(extra);
            }
            return extra;
        } finally {
            slot.releaseIfUnbound();
        }
    }

    /** How many extra channels this borrowed session holds open now; for tests. */
    int openExtraChannelCount() {
        return extraChannels.size();
    }

    /** A reserved place in the borrowed channel budget until a channel takes it over. */
    private final class BudgetSlot {
        /** A fresh identity per reservation, so two reservations never collapse into one entry. */
        private final Object placeholder = new Object();
        private boolean bound;

        BudgetSlot() {
            extraChannels.add(placeholder);
        }

        void bind(java.io.Closeable channel) {
            extraChannels.add(channel);
            extraChannels.remove(placeholder);
            bound = true;
        }

        void releaseIfUnbound() {
            if (!bound) {
                extraChannels.remove(placeholder);
            }
        }
    }

    private BudgetSlot reserveBorrowedChannel() throws IOException {
        synchronized (channelBudgetLock) {
            if (extraChannels.size() >= BORROWED_CHANNEL_BUDGET) {
                throw new IOException(I18n.get("sftp.error.channelBudget", BORROWED_CHANNEL_BUDGET));
            }
            return new BudgetSlot();
        }
    }

    /**
     * The session remote commands run on: this object's own, or for a borrowed one the pane's
     * current session. Null when there is none.
     */
    private @Nullable ClientSession commandSession() {
        if (owning) {
            ClientSession current = session;
            return current != null && current.isOpen() ? current : null;
        }
        ClientSession current = borrowedSupplier != null ? borrowedSupplier.get() : null;
        return current != null && current.isOpen() && !current.isClosing() ? current : null;
    }

    /**
     * Opens an exec channel for {@code command}. On a borrowed session it takes a place in the
     * channel budget until it is closed.
     */
    private ChannelExec openExecChannel(String command) throws Exception {
        ClientSession current = commandSession();
        if (current == null) {
            throw new Exception("Not connected");
        }
        if (owning) {
            return current.createExecChannel(command);
        }
        BudgetSlot slot = reserveBorrowedChannel();
        try {
            ChannelExec channel = current.createExecChannel(command);
            slot.bind(channel);
            channel.addCloseFutureListener(future -> extraChannels.remove(channel));
            return channel;
        } finally {
            slot.releaseIfUnbound();
        }
    }

    @Override
    public boolean isOpen() {
        return isConnected();
    }

    @Override
    public boolean ownsSession() {
        return owning;
    }

    @Override
    public String describe() {
        if (label != null && !label.isBlank()) {
            return label;
        }
        // host:port only, like the connect log: no getter that could carry a user name.
        return connection == null ? "sftp" : connection.getHost() + ":" + connection.getPort();
    }
    
    /**
     * Executes a shell command on the remote server.
     * @param command The command to execute
     * @return The command output
     * @throws Exception If the command fails
     */
    public String executeCommand(String command) throws Exception {
        try (ChannelExec channel = openExecChannel(command)) {
            java.io.ByteArrayOutputStream stdout = new java.io.ByteArrayOutputStream();
            java.io.ByteArrayOutputStream stderr = new java.io.ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.setErr(stderr);
            
            channel.open().verify(Duration.ofSeconds(30));
            channel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 
                    Duration.ofMinutes(30).toMillis());
            
            int exitStatus = channel.getExitStatus() != null ? channel.getExitStatus() : -1;
            String output = stdout.toString(java.nio.charset.StandardCharsets.UTF_8);
            String error = stderr.toString(java.nio.charset.StandardCharsets.UTF_8);
            
            if (exitStatus != 0) {
                logger.warn("Command '{}' exited with status {}: {}", command, exitStatus, error);
                throw new Exception("Command failed with exit code " + exitStatus + ": " + error);
            }
            
            return output;
        }
    }
    
    /**
     * Executes a shell command asynchronously and provides progress updates.
     * @param command The command to execute
     * @param outputConsumer Consumer that receives output line by line
     * @return A CommandResult containing exit status and any error output
     * @throws Exception If the command fails to execute
     */
    public CommandResult executeCommandWithProgress(String command, java.util.function.Consumer<String> outputConsumer) throws Exception {
        try (ChannelExec channel = openExecChannel(command)) {
            java.io.PipedInputStream stdoutPipedIn = new java.io.PipedInputStream();
            java.io.PipedOutputStream stdoutPipedOut = new java.io.PipedOutputStream(stdoutPipedIn);
            java.io.ByteArrayOutputStream stderrStream = new java.io.ByteArrayOutputStream();
            
            channel.setOut(stdoutPipedOut);
            channel.setErr(stderrStream);
            
            channel.open().verify(Duration.ofSeconds(30));
            
            // Read stdout in a separate thread
            Thread readerThread = new Thread(() -> {
                try (java.io.BufferedReader reader = new java.io.BufferedReader(
                        new java.io.InputStreamReader(stdoutPipedIn, java.nio.charset.StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (outputConsumer != null) {
                            outputConsumer.accept(line);
                        }
                    }
                } catch (Exception e) {
                    logger.debug("Reader thread ended: {}", e.getMessage());
                }
            }, "SSH-Command-Reader");
            readerThread.setDaemon(true);
            readerThread.start();
            
            channel.waitFor(java.util.EnumSet.of(org.apache.sshd.client.channel.ClientChannelEvent.CLOSED), 
                    Duration.ofHours(1).toMillis());
            
            // Wait for reader thread to finish
            readerThread.join(5000);
            
            int exitCode = channel.getExitStatus() != null ? channel.getExitStatus() : -1;
            String stderr = stderrStream.toString(java.nio.charset.StandardCharsets.UTF_8);
            
            return new CommandResult(exitCode, stderr);
        }
    }
    
    /**
     * Result of a command execution containing exit code and stderr.
     */
    public static class CommandResult {
        private final int exitCode;
        private final String stderr;
        
        public CommandResult(int exitCode, String stderr) {
            this.exitCode = exitCode;
            this.stderr = stderr;
        }
        
        public int getExitCode() { return exitCode; }
        public String getStderr() { return stderr; }
        public boolean isSuccess() { return exitCode == 0; }
    }
    
    public ServerConnection getConnection() {
        return connection;
    }
    
    /**
     * Gets the last access reason used (e.g. for CyberArk).
     * Uses the most recent entry from GlobalSettings - the one the user entered for the TAB connection.
     */
    private String getLastAccessReason() {
        try {
            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (gsm != null && gsm.getSettings() != null) {
                java.util.List<String> history = gsm.getSettings().getAccessReasonHistory();
                if (history != null && !history.isEmpty()) {
                    return history.get(0);
                }
            }
        } catch (Exception e) {
            logger.warn("Could not get last access reason: {}", e.getMessage());
        }
        return null;
    }
    
    /**
     * Authenticates using a private key file.
     */
    private void authenticateWithKey() throws Exception {
        String[] keyPathRef = new String[1];
        String[] passphraseRef = new String[1];
        
        // Try to get key from SSHKeyManager if sshKeyId is set
        if (connection.getSshKeyId() != null && sshKeyManager != null && masterPassword != null) {
            try {
                sshKeyManager.findKeyById(connection.getSshKeyId()).ifPresent(key -> {
                    try {
                        keyPathRef[0] = sshKeyManager.getEffectiveKeyPath(key);
                        passphraseRef[0] = sshKeyManager.getPassphrase(key, masterPassword);
                    } catch (Exception e) {
                        logger.error("Failed to get key from SSHKeyManager", e);
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to find key by ID", e);
            }
        }
        
        // Fallback to connection's key path if not found in manager
        String keyPath = keyPathRef[0];
        if (keyPath == null || keyPath.trim().isEmpty()) {
            keyPath = connection.getPrivateKeyPath();
        }
        
        if (keyPath == null || keyPath.trim().isEmpty()) {
            throw new Exception(I18n.get("sftp.error.noKeyPath"));
        }
        
        // A temporary SSH key is parsed in memory and never written to disk.
        if (TemporarySshKeyMaterial.isTemporaryKeyPath(keyPath)) {
            try {
                List<java.security.KeyPair> keyPairs = TemporarySshKeyMaterial.load(session, keyPath);
                // The session-level provider holds the parsed pairs in memory (no file to re-read)
                // and, being non-null, keeps the client-level default keys out of authentication.
                session.setKeyIdentityProvider(KeyIdentityProvider.wrapKeyPairs(keyPairs));
                session.addPublicKeyIdentity(keyPairs.get(0));
                logger.info("Using temporary SSH key (algorithm: {})", keyPairs.get(0).getPublic().getAlgorithm());
                return;
            } catch (Exception e) {
                logger.error("Failed to load temporary SSH key", e);
                throw new Exception("Error loading temporary SSH key: " + e.getMessage(), e);
            }
        }
        
        java.nio.file.Path keyFilePath = java.nio.file.Paths.get(keyPath);
        if (!java.nio.file.Files.exists(keyFilePath)) {
            throw new Exception(I18n.get("sftp.error.keyFileMissing", keyPath));
        }
        
        // Use passphrase from manager if available, otherwise from connection (decrypt only; never use plain)
        String passphrase = passphraseRef[0];
        if (passphrase == null) {
            String stored = connection.getPrivateKeyPassphrase();
            if (stored != null && !stored.isBlank() && masterPassword != null) {
                try {
                    EncryptionService encryptionService = new EncryptionService();
                    passphrase = encryptionService.decryptPassword(stored, masterPassword);
                } catch (Exception e) {
                    logger.debug("Could not decrypt stored key passphrase", e);
                }
            }
        }
        
        try {
            // Load key pair from file using FileKeyPairProvider
            FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(keyFilePath);
            
            // Set passphrase if provided
            if (passphrase != null && !passphrase.isEmpty()) {
                final String finalPassphrase = passphrase;
                keyPairProvider.setPasswordFinder((sess, path, retryIndex) -> finalPassphrase);
            }
            
            // Load the key pair
            Iterable<java.security.KeyPair> keyPairs = keyPairProvider.loadKeys(session);
            
            if (keyPairs == null) {
                throw new Exception(I18n.get("sftp.error.keyLoadFailed", keyPath));
            }
            
            // Add all key pairs to session
            int count = 0;
            for (java.security.KeyPair keyPair : keyPairs) {
                session.addPublicKeyIdentity(keyPair);
                count++;
            }
            
            if (count == 0) {
                throw new Exception(I18n.get("sftp.error.noKeyPairs", keyPath));
            }
            
            logger.info("Added {} public key identity/identities from {}", count, keyPath);
        } catch (Exception e) {
            logger.error("Failed to load SSH key from " + keyPath, e);
            throw new Exception(I18n.get("sftp.error.keyAuthFailed", safeFailureMessage(e)), e);
        }
    }
}
