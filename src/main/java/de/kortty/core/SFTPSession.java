package de.kortty.core;

import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.UserAuthFactory;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.signature.BuiltinSignatures;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.EOFException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Manages SFTP connections for file transfer.
 */
public class SFTPSession {
    
    private static final Logger logger = LoggerFactory.getLogger(SFTPSession.class);
    
    private final ServerConnection connection;
    private final String password;
    private SSHKeyManager sshKeyManager;
    private char[] masterPassword;
    
    private SshClient client;
    private ClientSession session;
    private SftpClient sftpClient;
    private String currentRemotePath = "~";
    
    public SFTPSession(ServerConnection connection, String password) {
        this.connection = connection;
        this.password = password;
    }
    
    /**
     * Sets SSHKeyManager and master password for key-based authentication.
     */
    public void setSSHKeyManager(SSHKeyManager sshKeyManager, char[] masterPassword) {
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
    }
    
    /**
     * Establishes the SFTP connection.
     */
    public void connect() throws Exception {
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
        
        client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
            logger.warn("Accepting server key from {}: {}", remoteAddress, serverKey.getAlgorithm());
            return true; // Accept all keys for now
        });
        client.start();
        
        int timeoutSeconds = connection.getConnectionTimeoutSeconds();
        if (timeoutSeconds <= 0) {
            timeoutSeconds = 15;
        }
        
        session = client.connect(connection.getUsername(), connection.getHost(), connection.getPort())
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
            throw new IOException(sftpSubsystemFailureMessage(e), e);
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

    static String sftpSubsystemFailureMessage(Throwable failure) {
        String causeMessage = safeFailureMessage(failure);
        if (isSftpSubsystemNegotiationFailure(failure)) {
            return "SFTP-Subsystem wurde nach erfolgreicher SSH-Authentifizierung vom Server abgelehnt oder geschlossen. "
                + "Es wurde keine SFTP-Version ausgehandelt. Prüfe, ob SFTP für dieses Ziel bzw. den SSH-Proxy "
                + "freigegeben ist. Technische Ursache: " + causeMessage;
        }
        return "SFTP-Subsystem konnte nach erfolgreicher SSH-Authentifizierung nicht gestartet werden: "
            + causeMessage;
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
            return "unbekannter Fehler";
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
     * Downloads a file from remote to local.
     */
    public void downloadFile(String remotePath, Path localPath) throws IOException {
        SftpClient.Attributes attrs = sftpClient.stat(remotePath);
        long size = attrs.getSize();
        
        try (java.io.InputStream in = sftpClient.read(remotePath);
             java.io.FileOutputStream out = new java.io.FileOutputStream(localPath.toFile())) {
            byte[] buffer = new byte[8192];
            long totalRead = 0;
            int bytesRead;
            while ((bytesRead = in.read(buffer)) != -1) {
                out.write(buffer, 0, bytesRead);
                totalRead += bytesRead;
            }
            logger.info("Downloaded {} bytes from {} to {}", totalRead, remotePath, localPath);
        }
    }
    
    /**
     * Downloads a file from local to remote.
     */
    public void uploadFile(Path localPath, String remotePath) throws IOException {
        long fileSize = java.nio.file.Files.size(localPath);
        byte[] fileData = java.nio.file.Files.readAllBytes(localPath);
        
        // Write file using String path with OpenMode
        try (java.io.OutputStream out = sftpClient.write(remotePath, 
                java.util.EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate))) {
            out.write(fileData);
            logger.info("Uploaded {} bytes from {} to {}", fileData.length, localPath, remotePath);
        }
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
     * Creates a directory on the remote server.
     */
    public void createDirectory(String remotePath) throws IOException {
        sftpClient.mkdir(remotePath);
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
     * Copies a file or directory on the remote server.
     */
    public void copyFile(String sourcePath, String destPath) throws IOException {
        SftpClient.Attributes attrs = sftpClient.stat(sourcePath);
        if (attrs.isDirectory()) {
            // Create destination directory
            sftpClient.mkdir(destPath);
            // Copy contents recursively
            List<SftpClient.DirEntry> entries = listFiles(sourcePath);
            for (SftpClient.DirEntry entry : entries) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                String src = sourcePath.endsWith("/") ? sourcePath + name : sourcePath + "/" + name;
                String dst = destPath.endsWith("/") ? destPath + name : destPath + "/" + name;
                copyFile(src, dst);
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
        }
    }
    
    public boolean isConnected() {
        return sftpClient != null && session != null && session.isOpen();
    }
    
    /**
     * Executes a shell command on the remote server.
     * @param command The command to execute
     * @return The command output
     * @throws Exception If the command fails
     */
    public String executeCommand(String command) throws Exception {
        if (session == null || !session.isOpen()) {
            throw new Exception("Not connected");
        }
        
        try (org.apache.sshd.client.channel.ChannelExec channel = session.createExecChannel(command)) {
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
        if (session == null || !session.isOpen()) {
            throw new Exception("Not connected");
        }
        
        try (org.apache.sshd.client.channel.ChannelExec channel = session.createExecChannel(command)) {
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
            throw new Exception("Kein SSH-Key-Pfad angegeben");
        }
        
        // Check if this is a temporary SSH key (starts with "TEMPORARY:")
        if (keyPath.startsWith("TEMPORARY:")) {
            String keyContent = keyPath.substring("TEMPORARY:".length());
            java.io.File tempFile = null;
            try {
                // Ensure key content ends with a newline - OpenSSH keys MUST end with a newline character
                String keyContentFixed = keyContent;
                if (!keyContent.endsWith("\n")) {
                    keyContentFixed = keyContent + "\n";
                    logger.debug("Added missing trailing newline to key content");
                }
                
                // Write temporary key to a temporary file
                tempFile = java.io.File.createTempFile("kortty_temp_key_", ".key");
                tempFile.deleteOnExit();
                
                // Write key content to file
                try (java.io.FileWriter writer = new java.io.FileWriter(tempFile, java.nio.charset.StandardCharsets.UTF_8)) {
                    writer.write(keyContentFixed);
                }
                
                // Set file permissions to 600 (read/write for owner only) - required by SSH
                try {
                    java.nio.file.Files.setPosixFilePermissions(
                        tempFile.toPath(),
                        java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE
                        )
                    );
                } catch (UnsupportedOperationException e) {
                    // Windows doesn't support POSIX permissions, try alternative
                    tempFile.setReadable(false, false);
                    tempFile.setReadable(true, true);
                    tempFile.setWritable(false, false);
                    tempFile.setWritable(true, true);
                }
                
                // Load key pair from temporary file using FileKeyPairProvider
                // Use setKeyIdentityProvider instead of addPublicKeyIdentity for better EdDSA support
                FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(tempFile.toPath());
                
                // Set the key identity provider on the session - this is the recommended approach
                // for FileKeyPairProvider and works better with EdDSA keys
                session.setKeyIdentityProvider(keyPairProvider);
                
                // Also verify that we can load at least one key pair
                Iterable<java.security.KeyPair> keyPairs = keyPairProvider.loadKeys(session);
                
                if (keyPairs == null) {
                    throw new Exception("Could not load temporary SSH key: keyPairs is null");
                }
                
                // Get iterator and check if it has elements
                java.util.Iterator<java.security.KeyPair> iterator = keyPairs.iterator();
                if (!iterator.hasNext()) {
                    throw new Exception("Could not parse temporary SSH key: no key pairs found");
                }
                
                // Use the first key pair for logging
                java.security.KeyPair keyPair = iterator.next();
                if (keyPair == null) {
                    throw new Exception("Could not parse temporary SSH key: keyPair is null");
                }
                
                // Verify key pair has both private and public key
                if (keyPair.getPrivate() == null || keyPair.getPublic() == null) {
                    throw new Exception("Invalid temporary SSH key: missing private or public key component");
                }
                
                // Log key details for debugging
                String keyAlgorithm = keyPair.getPublic().getAlgorithm();
                String keyFormat = keyPair.getPublic().getFormat();
                logger.info("Loaded temporary SSH key - Algorithm: {}, Format: {}, Key size: {} bytes", 
                    keyAlgorithm, keyFormat, keyPair.getPublic().getEncoded().length);
                
                // Set key identity provider AND add the key directly
                // Using both methods ensures compatibility with all SSH servers
                session.setKeyIdentityProvider(keyPairProvider);
                session.addPublicKeyIdentity(keyPair);
                
                logger.info("Set temporary SSH key identity provider and added key to session (key type: {})", keyAlgorithm);
                return;
            } catch (Exception e) {
                logger.error("Failed to load temporary SSH key from file: {}", 
                    tempFile != null ? tempFile.getAbsolutePath() : "unknown", e);
                throw new Exception("Error loading temporary SSH key: " + e.getMessage(), e);
            }
        }
        
        java.nio.file.Path keyFilePath = java.nio.file.Paths.get(keyPath);
        if (!java.nio.file.Files.exists(keyFilePath)) {
            throw new Exception("SSH-Key-Datei existiert nicht: " + keyPath);
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
                throw new Exception("Konnte SSH-Key nicht laden: " + keyPath);
            }
            
            // Add all key pairs to session
            int count = 0;
            for (java.security.KeyPair keyPair : keyPairs) {
                session.addPublicKeyIdentity(keyPair);
                count++;
            }
            
            if (count == 0) {
                throw new Exception("Keine KeyPairs in SSH-Key-Datei gefunden: " + keyPath);
            }
            
            logger.info("Added {} public key identity/identities from {}", count, keyPath);
        } catch (Exception e) {
            logger.error("Failed to load SSH key from " + keyPath, e);
            throw new Exception("SSH-Key-Authentifizierung fehlgeschlagen: " + e.getMessage(), e);
        }
    }
}
