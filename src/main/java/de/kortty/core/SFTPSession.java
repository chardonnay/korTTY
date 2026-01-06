package de.kortty.core;

import de.kortty.model.ServerConnection;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
        
        sftpClient = SftpClientFactory.instance().createSftpClient(session);
        
        // Initialize current directory
        try {
            currentRemotePath = sftpClient.canonicalPath(".");
        } catch (IOException e) {
            currentRemotePath = "~";
        }
        
        logger.info("SFTP connected to {}", connection.getDisplayName());
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
     * Uploads a file from local to remote.
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
    
    public ServerConnection getConnection() {
        return connection;
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
        
        java.nio.file.Path keyFilePath = java.nio.file.Paths.get(keyPath);
        if (!java.nio.file.Files.exists(keyFilePath)) {
            throw new Exception("SSH-Key-Datei existiert nicht: " + keyPath);
        }
        
        // Use passphrase from manager if available, otherwise from connection
        String passphrase = passphraseRef[0];
        if (passphrase == null) {
            passphrase = connection.getPrivateKeyPassphrase();
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
