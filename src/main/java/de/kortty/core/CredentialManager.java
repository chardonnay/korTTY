package de.kortty.core;

import de.kortty.core.agent.AgentCommandRunner.ShellKind;
import de.kortty.core.agent.LocalShellArgv;
import de.kortty.model.StoredCredential;
import de.kortty.platform.FlatpakSupport;
import de.kortty.security.EncryptionService;
import de.kortty.ui.I18n;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Manages stored credentials for server connections.
 */
public class CredentialManager {
    
    private static final Logger logger = LoggerFactory.getLogger(CredentialManager.class);
    private static final String CREDENTIALS_FILE = "credentials.xml";

    /** Deadline of an external password command, in seconds. */
    static final int EXTERNAL_COMMAND_TIMEOUT_SECONDS = 10;
    static final Duration EXTERNAL_COMMAND_TIMEOUT = Duration.ofSeconds(EXTERNAL_COMMAND_TIMEOUT_SECONDS);
    /** Bytes kept per output stream of an external password command; the rest is discarded. */
    static final int EXTERNAL_COMMAND_OUTPUT_LIMIT = 64 * 1024;
    /** Characters of stderr shown when an external password command fails. */
    private static final int ERROR_TEXT_LIMIT = 500;
    private static final Duration DRAIN_JOIN_TIMEOUT = Duration.ofSeconds(2);
    private static final Duration FLATPAK_TERMINATE_GRACE = Duration.ofMillis(300);
    /** Appended to a PowerShell password command so a failing native tool fails the whole run. */
    static final String POWERSHELL_EXIT_CODE_TRAILER = "\nif ($LASTEXITCODE) { exit $LASTEXITCODE }";

    private final Path configDir;
    private final List<StoredCredential> credentials = new ArrayList<>();
    
    public CredentialManager(Path configDir) {
        this.configDir = configDir;
    }
    
    /**
     * Loads credentials from configuration file
     */
    public void load() throws Exception {
        Path file = configDir.resolve(CREDENTIALS_FILE);
        if (!Files.exists(file)) {
            logger.info("No credentials file found, starting with empty list");
            return;
        }
        
        try {
            JAXBContext context = JAXBContext.newInstance(
                CredentialsWrapper.class, 
                StoredCredential.class,
                StoredCredential.Environment.class,
                StoredCredential.PasswordType.class
            );
            Unmarshaller unmarshaller = context.createUnmarshaller();
            CredentialsWrapper wrapper = (CredentialsWrapper) unmarshaller.unmarshal(file.toFile());
            
            credentials.clear();
            if (wrapper.getCredentials() != null) {
                for (StoredCredential c : wrapper.getCredentials()) {
                    if (c.getEnvironmentId() == null && c.getEnvironment() != null) {
                        c.setEnvironmentId(c.getEnvironment().name());
                    }
                    credentials.add(c);
                }
            }
            
            logger.info("Loaded {} credentials from {}", credentials.size(), file);
        } catch (Exception e) {
            logger.error("Failed to load credentials from " + file, e);
            throw e;
        }
    }
    
    /**
     * Saves credentials to configuration file
     */
    public void save() throws Exception {
        Path file = configDir.resolve(CREDENTIALS_FILE);
        
        try {
            CredentialsWrapper wrapper = new CredentialsWrapper();
            wrapper.setCredentials(new ArrayList<>(credentials));
            
            JAXBContext context = JAXBContext.newInstance(
                CredentialsWrapper.class, 
                StoredCredential.class,
                StoredCredential.Environment.class,
                StoredCredential.PasswordType.class
            );
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            
            Files.createDirectories(configDir);
            marshaller.marshal(wrapper, file.toFile());
            
            logger.info("Saved {} credentials to {}", credentials.size(), file);
        } catch (Exception e) {
            logger.error("Failed to save credentials to " + file, e);
            throw e;
        }
    }
    
    /**
     * Adds a new credential
     */
    public void addCredential(StoredCredential credential) {
        credentials.add(credential);
        logger.info("Added credential: {}", credential.getName());
    }
    
    /**
     * Removes a credential
     */
    public void removeCredential(StoredCredential credential) {
        credentials.remove(credential);
        logger.info("Removed credential: {}", credential.getName());
    }
    
    /**
     * Updates an existing credential
     */
    public void updateCredential(StoredCredential credential) {
        int index = credentials.indexOf(credential);
        if (index >= 0) {
            credentials.set(index, credential);
            logger.info("Updated credential: {}", credential.getName());
        }
    }
    
    /**
     * Gets all credentials
     */
    public List<StoredCredential> getAllCredentials() {
        return new ArrayList<>(credentials);
    }
    
    /**
     * Finds credentials matching a server and environment (by environment id).
     */
    public List<StoredCredential> findMatchingCredentials(String hostname, String environmentId) {
        return credentials.stream()
                .filter(c -> (environmentId != null && environmentId.equals(c.getEnvironmentId())) || (environmentId == null && c.getEnvironmentId() == null))
                .filter(c -> c.matchesServer(hostname))
                .collect(Collectors.toList());
    }

    /**
     * Counts credentials that use the given environment id (for preventing delete of in-use environments).
     */
    public long countCredentialsByEnvironmentId(String environmentId) {
        if (environmentId == null) return 0;
        return credentials.stream().filter(c -> environmentId.equals(c.getEnvironmentId())).count();
    }
    
    /**
     * Finds a credential by ID
     */
    public Optional<StoredCredential> findCredentialById(String id) {
        return credentials.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst();
    }
    
    /**
     * Returns the password for a credential, either by decrypting the stored password
     * or by executing an external command (depending on the credential's PasswordType).
     */
    public String getPassword(StoredCredential credential, char[] masterPassword) throws Exception {
        if (credential.getPasswordType() == StoredCredential.PasswordType.EXTERNAL_COMMAND) {
            return getPasswordFromExternalCommand(credential, masterPassword);
        }
        
        // Default: decrypt stored password
        if (credential.getEncryptedPassword() == null) {
            return null;
        }
        
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.decryptPassword(credential.getEncryptedPassword(), masterPassword);
    }
    
    /**
     * Retrieves a password by executing an external command (shell script / CLI tool).
     * The command itself is stored encrypted and decrypted before execution.
     * The command's stdout output (trimmed) is used as the password.
     * 
     * @param credential the credential with an encrypted external command
     * @param masterPassword the master password to decrypt the command
     * @return the password returned by the external command
     * @throws Exception if the command fails or times out
     */
    private String getPasswordFromExternalCommand(StoredCredential credential, char[] masterPassword) throws Exception {
        String encryptedCommand = credential.getEncryptedExternalCommand();
        if (encryptedCommand == null || encryptedCommand.isBlank()) {
            throw new Exception("No external command configured for credential: " + credential.getName());
        }
        
        // Decrypt the command
        EncryptionService encryptionService = new EncryptionService();
        String command = encryptionService.decryptPassword(encryptedCommand, masterPassword);
        
        logger.info("Executing external password command for credential: {}", credential.getName());
        return executeExternalCommand(command);
    }
    
    /**
     * Runs the password command in the platform shell ({@code /bin/sh} on macOS/Linux, PowerShell on
     * Windows, the host shell under Flatpak) and returns the first line of its stdout. The command
     * gets no stdin and is stopped, together with every process it started, after
     * {@value #EXTERNAL_COMMAND_TIMEOUT_SECONDS} seconds.
     *
     * @param command the shell command to execute
     * @return the first line of the command's stdout
     * @throws Exception if the command fails, times out, or returns empty output
     */
    public static String executeExternalCommand(String command) throws Exception {
        return executeExternalCommand(command, EXTERNAL_COMMAND_TIMEOUT);
    }

    /** {@link #executeExternalCommand(String)} with an explicit deadline (tests use a short one). */
    static String executeExternalCommand(String command, Duration timeout) throws Exception {
        return runExternalCommand(
            externalCommandArgv(command, LocalShellArgv.platformDefault(), System.getenv()), timeout);
    }

    /**
     * The argv that runs {@code command} in {@code shell}. PowerShell reports a native tool's
     * failure only when the script ends with it, so a trailer re-raises {@code $LASTEXITCODE}; the
     * trailer sits on its own line so a trailing {@code #} comment in the command cannot swallow it.
     * Under Flatpak the command is spawned on the host, where the password manager's CLI lives.
     */
    static List<String> externalCommandArgv(String command, ShellKind shell, Map<String, String> environment) {
        Objects.requireNonNull(command, "command");
        String script = shell == ShellKind.WINDOWS_POWERSHELL
            ? command + POWERSHELL_EXIT_CODE_TRAILER
            : command;
        List<String> argv = LocalShellArgv.argv(shell, script);
        // Without an explicit directory flatpak-spawn would reuse the sandbox's working directory,
        // which need not exist on the host.
        String workingDirectory = FlatpakSupport.isFlatpakEnvironment(environment)
            ? System.getProperty("user.home")
            : null;
        return FlatpakSupport.hostCommand(argv, workingDirectory, environment);
    }

    /**
     * Runs {@code argv} with stdin closed, drains stdout and stderr concurrently (each capped at
     * {@value #EXTERNAL_COMMAND_OUTPUT_LIMIT} bytes, decoded as UTF-8) and enforces {@code timeout}
     * even when a child keeps a pipe open. stdout is the password and is never logged.
     */
    static String runExternalCommand(List<String> argv, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(argv).start();
        // No interactive prompts: a tool that reads stdin gets EOF at once instead of waiting forever.
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
            // The process may already have exited.
        }

        CappedStreamDrain stdout = CappedStreamDrain.start(process.getInputStream(), "kortty-credential-command-stdout");
        CappedStreamDrain stderr = CappedStreamDrain.start(process.getErrorStream(), "kortty-credential-command-stderr");

        boolean finished;
        try {
            finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            destroyProcessTree(process);
            Thread.currentThread().interrupt();
            throw e;
        }
        if (!finished) {
            destroyProcessTree(process);
            throw new IOException(I18n.get("credential.externalCommand.error.timeout", timeoutSeconds(timeout)));
        }

        // A grandchild that inherited the pipes can keep them open after the shell has exited; never
        // wait for it longer than this.
        stdout.awaitEnd(DRAIN_JOIN_TIMEOUT);
        stderr.awaitEnd(DRAIN_JOIN_TIMEOUT);

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errorText = stderr.text().trim();
            if (errorText.length() > ERROR_TEXT_LIMIT) {
                errorText = errorText.substring(0, ERROR_TEXT_LIMIT) + "...";
            }
            throw new IOException(I18n.get("credential.externalCommand.error.failed", exitCode, errorText).trim());
        }

        String output = stdout.text().trim();
        if (output.isEmpty()) {
            throw new IOException(I18n.get("credential.externalCommand.error.empty"));
        }
        // The password is the first line only.
        return output.split("\\R", 2)[0];
    }

    /**
     * Kills the command and everything it started. The descendants are collected first, because they
     * are reparented once the shell is gone. Under Flatpak the real command runs on the host behind
     * {@code flatpak-spawn}, which forwards SIGTERM but cannot forward SIGKILL, so it is asked to
     * terminate first.
     */
    private static void destroyProcessTree(Process process) {
        List<ProcessHandle> descendants = process.descendants().toList();
        if (FlatpakSupport.isRunningInFlatpak()) {
            process.destroy();
            try {
                process.waitFor(FLATPAK_TERMINATE_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        descendants.forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
    }

    private static long timeoutSeconds(Duration timeout) {
        return Math.max(1, (timeout.toMillis() + 999) / 1000);
    }

    /**
     * Resolves {@link #getPassword} on a background daemon thread, so an external password command
     * (which may wait for Touch ID or a vault unlock) never blocks the JavaFX thread. The future
     * completes with the exception {@code getPassword} throws, unwrapped.
     */
    public CompletableFuture<String> getPasswordAsync(StoredCredential credential, char[] masterPassword) {
        CompletableFuture<String> result = new CompletableFuture<>();
        try {
            CredentialCommandExecutor.INSTANCE.execute(() -> {
                try {
                    result.complete(getPassword(credential, masterPassword));
                } catch (Throwable t) {
                    result.completeExceptionally(t);
                }
            });
        } catch (RejectedExecutionException e) {
            result.completeExceptionally(e);
        }
        return result;
    }

    /** Created on first use, so the threads only exist once a password is actually fetched. */
    private static final class CredentialCommandExecutor {
        private static final ExecutorService INSTANCE = Executors.newCachedThreadPool(runnable -> {
            Thread thread = new Thread(runnable, "kortty-credential-command");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Reads a stream to EOF on a daemon thread, keeping at most {@link #EXTERNAL_COMMAND_OUTPUT_LIMIT}
     * bytes and discarding the rest, so a chatty tool can neither block on a full pipe nor exhaust
     * memory.
     */
    private static final class CappedStreamDrain implements Runnable {
        private final InputStream stream;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final Thread thread;

        private CappedStreamDrain(InputStream stream, String threadName) {
            this.stream = stream;
            this.thread = new Thread(this, threadName);
            this.thread.setDaemon(true);
        }

        static CappedStreamDrain start(InputStream stream, String threadName) {
            CappedStreamDrain drain = new CappedStreamDrain(stream, threadName);
            drain.thread.start();
            return drain;
        }

        @Override
        public void run() {
            byte[] chunk = new byte[8192];
            try (InputStream in = stream) {
                int read;
                while ((read = in.read(chunk)) >= 0) {
                    synchronized (buffer) {
                        int room = EXTERNAL_COMMAND_OUTPUT_LIMIT - buffer.size();
                        if (room > 0) {
                            buffer.write(chunk, 0, Math.min(read, room));
                        }
                    }
                }
            } catch (IOException ignored) {
                // The process ended or was killed.
            }
        }

        void awaitEnd(Duration timeout) throws InterruptedException {
            thread.join(timeout.toMillis());
        }

        String text() {
            synchronized (buffer) {
                return buffer.toString(StandardCharsets.UTF_8);
            }
        }
    }

    /**
     * Encrypts and stores a password for a credential
     */
    public void setPassword(StoredCredential credential, String password, char[] masterPassword) throws Exception {
        EncryptionService encryptionService = new EncryptionService();
        String encrypted = encryptionService.encryptPassword(password, masterPassword);
        credential.setEncryptedPassword(encrypted);
    }
    
    /**
     * Encrypts and stores an external command for a credential
     */
    public void setExternalCommand(StoredCredential credential, String command, char[] masterPassword) throws Exception {
        EncryptionService encryptionService = new EncryptionService();
        String encrypted = encryptionService.encryptPassword(command, masterPassword);
        credential.setEncryptedExternalCommand(encrypted);
    }
    
    /**
     * Decrypts and returns the external command for a credential (for display in edit dialog)
     */
    public String getExternalCommand(StoredCredential credential, char[] masterPassword) throws Exception {
        String encrypted = credential.getEncryptedExternalCommand();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.decryptPassword(encrypted, masterPassword);
    }
    
    /**
     * JAXB wrapper for credentials list
     */
    @XmlRootElement(name = "credentials")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class CredentialsWrapper {
        @XmlElement(name = "credential")
        private List<StoredCredential> credentials;
        
        public List<StoredCredential> getCredentials() {
            return credentials;
        }
        
        public void setCredentials(List<StoredCredential> credentials) {
            this.credentials = credentials;
        }
    }
}
