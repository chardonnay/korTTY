package de.kortty.core;

import de.kortty.KorTTYApplication;
import de.kortty.model.ServerConnection;
import de.kortty.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.stage.Window;
import org.apache.sshd.client.keyverifier.ServerKeyVerifier;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.apache.sshd.common.digest.BuiltinDigests;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.PublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared trust-on-first-use verifier for interactive SSH and SFTP connections.
 *
 * <p>Trust is deliberately keyed by the normalized host name and port rather than by a saved
 * connection id. That means the terminal, SFTP manager and duplicate connection profiles all see
 * the same pin. A changed key is never offered as an automatic replacement: it is rejected and
 * must be resolved by removing or correcting the persisted pin outside the connection attempt.</p>
 */
public final class SshHostKeyTrustManager {

    static final String STORE_FILE_NAME = "ssh-host-keys.properties";
    private static final String STORE_FORMAT = "1";
    private static final Logger logger = LoggerFactory.getLogger(SshHostKeyTrustManager.class);
    private static final ConcurrentHashMap<Path, Object> PROCESS_STORE_LOCKS = new ConcurrentHashMap<>();

    private final Path storeFile;
    private final Path lockFile;
    private final HostKeyPrompt prompt;
    private final Object stateLock = new Object();
    private final ConcurrentHashMap<Endpoint, PendingDecision> pendingDecisions = new ConcurrentHashMap<>();

    public SshHostKeyTrustManager(Path storeFile, HostKeyPrompt prompt) {
        this.storeFile = Objects.requireNonNull(storeFile, "storeFile").toAbsolutePath().normalize();
        this.lockFile = this.storeFile.resolveSibling(this.storeFile.getFileName() + ".lock");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
    }

    /** Returns the process-wide verifier used by all interactive SSH transports. */
    public static SshHostKeyTrustManager shared() {
        return SharedHolder.INSTANCE;
    }

    /** Creates an Apache MINA verifier bound to the configured, user-visible endpoint. */
    public ConnectionVerifier verifierFor(ServerConnection connection) {
        Objects.requireNonNull(connection, "connection");
        String host = connection.getHost();
        int port = effectivePort(connection.getPort());
        return new ConnectionVerifier(this, host, port);
    }

    boolean verify(String host, int port, PublicKey serverKey) {
        Endpoint endpoint;
        HostKeyDetails offered;
        try {
            endpoint = new Endpoint(normalizeHost(host), effectivePort(port));
            offered = describe(endpoint, serverKey);
        } catch (Exception e) {
            logger.error("Could not describe SSH host key for {}:{}", host, port, e);
            warnFailure(new HostKeyVerificationFailure(safeHost(host), effectivePort(port), safeMessage(e)));
            return false;
        }

        HostKeyPin existing;
        try {
            existing = findPin(endpoint);
        } catch (IOException e) {
            logger.error("Could not load SSH host-key trust store {}", storeFile, e);
            warnFailure(new HostKeyVerificationFailure(endpoint.host(), endpoint.port(), safeMessage(e)));
            return false;
        }

        if (existing != null) {
            return acceptIfMatching(existing, offered);
        }

        PendingDecision ownDecision = new PendingDecision();
        PendingDecision pending = pendingDecisions.putIfAbsent(endpoint, ownDecision);
        if (pending == null) {
            pending = ownDecision;
            try {
                boolean accepted = prompt.confirmFirstUse(offered);
                if (!accepted) {
                    pending.complete(Decision.REJECTED);
                    logger.info("User rejected first-use SSH host key for {}:{}", endpoint.host(), endpoint.port());
                } else {
                    persistFirstPin(offered);
                    pending.complete(Decision.TRUSTED);
                    logger.info("Pinned SSH host key for {}:{} ({})",
                        endpoint.host(), endpoint.port(), offered.fingerprintSha256());
                }
            } catch (Exception e) {
                logger.error("Could not persist SSH host key for {}:{}", endpoint.host(), endpoint.port(), e);
                pending.fail(e);
                warnFailure(new HostKeyVerificationFailure(endpoint.host(), endpoint.port(), safeMessage(e)));
            } finally {
                pendingDecisions.remove(endpoint, ownDecision);
            }
        }

        Decision decision;
        try {
            decision = pending.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while waiting for SSH host-key confirmation for {}:{}",
                endpoint.host(), endpoint.port());
            return false;
        } catch (ExecutionException e) {
            logger.warn("SSH host-key confirmation failed for {}:{}",
                endpoint.host(), endpoint.port(), e.getCause());
            return false;
        }

        if (decision != Decision.TRUSTED) {
            return false;
        }

        // Always compare again after the shared decision. Two simultaneous handshakes for the same
        // endpoint may have offered different keys; only the exact key that was persisted may pass.
        try {
            HostKeyPin trusted = findPin(endpoint);
            return trusted != null && acceptIfMatching(trusted, offered);
        } catch (IOException e) {
            logger.error("Could not re-read SSH host-key trust state for {}:{}",
                endpoint.host(), endpoint.port(), e);
            warnFailure(new HostKeyVerificationFailure(endpoint.host(), endpoint.port(), safeMessage(e)));
            return false;
        }
    }

    private boolean acceptIfMatching(HostKeyPin trusted, HostKeyDetails offered) {
        if (trusted.fingerprintSha256().equals(offered.fingerprintSha256())) {
            logger.debug("SSH host key matched pin for {}:{} ({})",
                offered.host(), offered.port(), offered.fingerprintSha256());
            return true;
        }
        logger.error("SSH host key mismatch for {}:{} expected={} actual={}",
            offered.host(), offered.port(), trusted.fingerprintSha256(), offered.fingerprintSha256());
        try {
            prompt.warnMismatch(new HostKeyMismatch(
                offered.host(),
                offered.port(),
                trusted.algorithm(),
                trusted.fingerprintSha256(),
                offered.algorithm(),
                offered.fingerprintSha256()));
        } catch (RuntimeException e) {
            logger.warn("Could not display SSH host-key mismatch warning", e);
        }
        return false;
    }

    private HostKeyPin findPin(Endpoint endpoint) throws IOException {
        synchronized (stateLock) {
            return readPins().get(endpoint);
        }
    }

    private void persistFirstPin(HostKeyDetails offered) throws IOException {
        Endpoint endpoint = new Endpoint(offered.host(), offered.port());
        Path parent = storeFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Object processStoreLock = PROCESS_STORE_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
        synchronized (processStoreLock) {
            try (FileChannel lockChannel = FileChannel.open(
                    lockFile,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE);
                 FileLock ignored = lockChannel.lock()) {

                synchronized (stateLock) {
                    // Reload while holding the cross-process lock so pins written by another korTTY
                    // process are merged instead of being lost by our atomic replacement.
                    Map<Endpoint, HostKeyPin> currentPins = readPins();
                    HostKeyPin existing = currentPins.get(endpoint);
                    if (existing != null) {
                        if (!existing.fingerprintSha256().equals(offered.fingerprintSha256())) {
                            throw new IOException(
                                "Another SSH host key was pinned for this endpoint while confirmation was open.");
                        }
                        return;
                    }

                    HostKeyPin pin = new HostKeyPin(
                        endpoint,
                        offered.algorithm(),
                        offered.fingerprintSha256(),
                        offered.publicKeyLine(),
                        Instant.now().toString());
                    Map<Endpoint, HostKeyPin> updated = new HashMap<>(currentPins);
                    updated.put(endpoint, pin);
                    writePins(updated);
                }
            }
        }
    }

    private Map<Endpoint, HostKeyPin> readPins() throws IOException {
        if (!Files.exists(storeFile)) {
            return Map.of();
        }

        try {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(storeFile, StandardCharsets.UTF_8)) {
                properties.load(reader);
            }
            if (!STORE_FORMAT.equals(properties.getProperty("format"))) {
                throw new IOException("Unsupported or missing SSH host-key store format.");
            }
            int count = parseEntryCount(properties.getProperty("entry.count"));
            Map<Endpoint, HostKeyPin> loadedPins = new HashMap<>();
            Set<String> expectedProperties = new HashSet<>();
            expectedProperties.add("format");
            expectedProperties.add("entry.count");
            for (int i = 0; i < count; i++) {
                String prefix = "entry." + i + ".";
                String hostProperty = prefix + "host";
                String portProperty = prefix + "port";
                String algorithmProperty = prefix + "algorithm";
                String fingerprintProperty = prefix + "fingerprintSha256";
                String publicKeyProperty = prefix + "publicKeyLine";
                String trustedAtProperty = prefix + "trustedAt";
                expectedProperties.addAll(List.of(
                    hostProperty,
                    portProperty,
                    algorithmProperty,
                    fingerprintProperty,
                    publicKeyProperty,
                    trustedAtProperty));

                String host = normalizeHost(required(properties, hostProperty));
                int port = parsePort(required(properties, portProperty));
                Endpoint endpoint = new Endpoint(host, port);
                HostKeyPin pin = new HostKeyPin(
                    endpoint,
                    required(properties, algorithmProperty),
                    required(properties, fingerprintProperty),
                    required(properties, publicKeyProperty),
                    required(properties, trustedAtProperty));
                validatePinConsistency(pin);
                HostKeyPin duplicate = loadedPins.put(endpoint, pin);
                if (duplicate != null) {
                    throw new IOException("Duplicate SSH host-key entry for " + host + ":" + port + ".");
                }
            }
            if (!properties.stringPropertyNames().equals(expectedProperties)) {
                throw new IOException("SSH host-key trust store contains unexpected or uncounted properties.");
            }
            return Map.copyOf(loadedPins);
        } catch (RuntimeException e) {
            throw new IOException("Malformed SSH host-key trust store.", e);
        }
    }

    private void writePins(Map<Endpoint, HostKeyPin> updated) throws IOException {
        Path parent = storeFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        properties.setProperty("format", STORE_FORMAT);
        List<HostKeyPin> ordered = new ArrayList<>(updated.values());
        ordered.sort(Comparator.comparing((HostKeyPin pin) -> pin.endpoint().host())
            .thenComparingInt(pin -> pin.endpoint().port()));
        properties.setProperty("entry.count", Integer.toString(ordered.size()));
        for (int i = 0; i < ordered.size(); i++) {
            HostKeyPin pin = ordered.get(i);
            String prefix = "entry." + i + ".";
            properties.setProperty(prefix + "host", pin.endpoint().host());
            properties.setProperty(prefix + "port", Integer.toString(pin.endpoint().port()));
            properties.setProperty(prefix + "algorithm", pin.algorithm());
            properties.setProperty(prefix + "fingerprintSha256", pin.fingerprintSha256());
            properties.setProperty(prefix + "publicKeyLine", pin.publicKeyLine());
            properties.setProperty(prefix + "trustedAt", pin.trustedAt());
        }
        StringWriter writer = new StringWriter();
        properties.store(writer, "korTTY SSH host keys - verify unexpected changes before editing");
        AtomicFileWriter.writeStringAtomically(storeFile, writer.toString());
    }

    private void warnFailure(HostKeyVerificationFailure failure) {
        try {
            prompt.warnVerificationFailure(failure);
        } catch (RuntimeException promptFailure) {
            logger.warn("Could not display SSH host-key verification failure", promptFailure);
        }
    }

    static String fingerprintSha256(PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "publicKey");
        return KeyUtils.getFingerPrint(BuiltinDigests.sha256, publicKey);
    }

    private static void validatePinConsistency(HostKeyPin pin) throws IOException {
        try {
            PublicKeyEntry entry = PublicKeyEntry.parsePublicKeyEntry(pin.publicKeyLine());
            PublicKey publicKey = entry.resolvePublicKey(null, Map.of(), PublicKeyEntryResolver.FAILING);
            String canonicalLine = PublicKeyEntry.toString(publicKey);
            int separator = canonicalLine.indexOf(' ');
            String algorithm = separator > 0
                ? canonicalLine.substring(0, separator)
                : publicKey.getAlgorithm();
            String fingerprint = fingerprintSha256(publicKey);
            if (!pin.algorithm().equals(algorithm)
                    || !pin.fingerprintSha256().equals(fingerprint)
                    || !pin.publicKeyLine().equals(canonicalLine)) {
                throw new IOException("Inconsistent SSH host-key entry for "
                    + pin.endpoint().host() + ":" + pin.endpoint().port() + ".");
            }
            Instant.parse(pin.trustedAt());
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Invalid SSH host-key entry for "
                + pin.endpoint().host() + ":" + pin.endpoint().port() + ".", e);
        }
    }

    private static HostKeyDetails describe(Endpoint endpoint, PublicKey publicKey) {
        Objects.requireNonNull(publicKey, "serverKey");
        String publicKeyLine = PublicKeyEntry.toString(publicKey);
        int separator = publicKeyLine.indexOf(' ');
        String algorithm = separator > 0 ? publicKeyLine.substring(0, separator) : publicKey.getAlgorithm();
        return new HostKeyDetails(
            endpoint.host(), endpoint.port(), algorithm, fingerprintSha256(publicKey), publicKeyLine);
    }

    private static int parseEntryCount(String value) throws IOException {
        try {
            int count = Integer.parseInt(value);
            if (count < 1 || count > 100_000) {
                throw new IOException("Invalid SSH host-key entry count.");
            }
            return count;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid SSH host-key entry count.", e);
        }
    }

    private static int parsePort(String value) throws IOException {
        try {
            int port = Integer.parseInt(value);
            if (port < 1 || port > 65_535) {
                throw new IOException("Invalid SSH host-key port.");
            }
            return port;
        } catch (NumberFormatException e) {
            throw new IOException("Invalid SSH host-key port.", e);
        }
    }

    private static String required(Properties properties, String key) throws IOException {
        String value = properties.getProperty(key);
        if (value == null || value.isBlank()) {
            throw new IOException("Missing SSH host-key property: " + key);
        }
        return value.trim();
    }

    static String normalizeHost(String host) {
        String normalized = host != null ? host.trim() : "";
        if (normalized.startsWith("[") && normalized.endsWith("]") && normalized.length() > 2) {
            normalized = normalized.substring(1, normalized.length() - 1);
        }
        while (normalized.endsWith(".") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        normalized = normalized.toLowerCase(Locale.ROOT);
        if (normalized.isBlank()) {
            throw new IllegalArgumentException("SSH host is missing.");
        }
        return normalized;
    }

    private static int effectivePort(int port) {
        return port > 0 ? port : 22;
    }

    private static String safeHost(String host) {
        String value = host != null ? host.trim() : "";
        return value.isEmpty() ? "?" : value;
    }

    private static String safeMessage(Throwable failure) {
        if (failure == null) {
            return "Unknown error";
        }
        String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message.trim();
    }

    public interface HostKeyPrompt {
        boolean confirmFirstUse(HostKeyDetails details);

        void warnMismatch(HostKeyMismatch mismatch);

        void warnVerificationFailure(HostKeyVerificationFailure failure);
    }

    public record HostKeyDetails(
        String host,
        int port,
        String algorithm,
        String fingerprintSha256,
        String publicKeyLine) {
    }

    public record HostKeyMismatch(
        String host,
        int port,
        String expectedAlgorithm,
        String expectedFingerprintSha256,
        String offeredAlgorithm,
        String offeredFingerprintSha256) {
    }

    public record HostKeyVerificationFailure(String host, int port, String cause) {
    }

    /**
     * Per-handshake adapter which lets callers classify a refused host key as non-retriable.
     * Retrying TOFU rejection or a key mismatch would only repeat security dialogs and cannot heal
     * the connection without an explicit trust decision.
     */
    public static final class ConnectionVerifier implements ServerKeyVerifier {
        private final SshHostKeyTrustManager trustManager;
        private final String host;
        private final int port;
        private final AtomicBoolean rejected = new AtomicBoolean();

        private ConnectionVerifier(SshHostKeyTrustManager trustManager, String host, int port) {
            this.trustManager = trustManager;
            this.host = host;
            this.port = port;
        }

        @Override
        public boolean verifyServerKey(
            org.apache.sshd.client.session.ClientSession clientSession,
            java.net.SocketAddress remoteAddress,
            PublicKey serverKey) {

            boolean accepted = trustManager.verify(host, port, serverKey);
            if (!accepted) {
                rejected.set(true);
            }
            return accepted;
        }

        public boolean wasRejected() {
            return rejected.get();
        }
    }

    /** JavaFX prompt implementation that never blocks the FX thread waiting for itself. */
    public static final class JavaFxHostKeyPrompt implements HostKeyPrompt {

        @Override
        public boolean confirmFirstUse(HostKeyDetails details) {
            try {
                return runOnFxThreadAndWait(() -> {
                    Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                    alert.setTitle(text("ssh.hostKey.firstUse.title", "Unknown SSH host key"));
                    alert.setHeaderText(text("ssh.hostKey.firstUse.header",
                        "The authenticity of {0}:{1} cannot be established.", details.host(), details.port()));
                    alert.setContentText(text("ssh.hostKey.firstUse.message",
                        "Verify this fingerprint with the server administrator before trusting it.\n\n"
                            + "Algorithm: {0}\nSHA-256 fingerprint: {1}",
                        details.algorithm(), details.fingerprintSha256()));
                    alert.getButtonTypes().setAll(ButtonType.NO, ButtonType.YES);
                    prepareAlert(alert);
                    ((Button) alert.getDialogPane().lookupButton(ButtonType.NO)).setDefaultButton(true);
                    ((Button) alert.getDialogPane().lookupButton(ButtonType.YES)).setDefaultButton(false);
                    Optional<ButtonType> result = alert.showAndWait();
                    return result.isPresent() && result.get() == ButtonType.YES;
                });
            } catch (Exception e) {
                logger.error("Could not display first-use SSH host-key confirmation", e);
                return false;
            }
        }

        @Override
        public void warnMismatch(HostKeyMismatch mismatch) {
            try {
                runOnFxThreadAndWait(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(text("ssh.hostKey.mismatch.title", "SSH host key changed"));
                    alert.setHeaderText(text("ssh.hostKey.mismatch.header",
                        "Connection to {0}:{1} was blocked.", mismatch.host(), mismatch.port()));
                    alert.setContentText(text("ssh.hostKey.mismatch.message",
                        "The server presented a different host key. This may indicate a man-in-the-middle attack. "
                            + "Verify the change before updating the trusted key.\n\nExpected: {0}\nReceived: {1}",
                        mismatch.expectedFingerprintSha256(), mismatch.offeredFingerprintSha256()));
                    prepareAlert(alert);
                    alert.showAndWait();
                    return null;
                });
            } catch (Exception e) {
                logger.error("Could not display SSH host-key mismatch warning", e);
            }
        }

        @Override
        public void warnVerificationFailure(HostKeyVerificationFailure failure) {
            try {
                runOnFxThreadAndWait(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(text("ssh.hostKey.verificationFailed.title", "SSH host-key verification failed"));
                    alert.setHeaderText(text("ssh.hostKey.verificationFailed.header",
                        "Connection to {0}:{1} was blocked.", failure.host(), failure.port()));
                    alert.setContentText(text("ssh.hostKey.verificationFailed.message",
                        "The SSH host key could not be verified safely: {0}", failure.cause()));
                    prepareAlert(alert);
                    alert.showAndWait();
                    return null;
                });
            } catch (Exception e) {
                logger.error("Could not display SSH host-key verification failure", e);
            }
        }

        private static void prepareAlert(Alert alert) {
            alert.getDialogPane().setPrefWidth(680);
            Window owner = Window.getWindows().stream()
                .filter(Window::isShowing)
                .filter(Window::isFocused)
                .findFirst()
                .orElse(null);
            if (owner != null) {
                alert.initOwner(owner);
            }
        }

        private static <T> T runOnFxThreadAndWait(java.util.concurrent.Callable<T> action) throws Exception {
            if (Platform.isFxApplicationThread()) {
                return action.call();
            }
            FutureTask<T> task = new FutureTask<>(action);
            Platform.runLater(task);
            try {
                return task.get();
            } catch (InterruptedException e) {
                task.cancel(false);
                Thread.currentThread().interrupt();
                throw e;
            } catch (ExecutionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof Exception exception) {
                    throw exception;
                }
                throw new RuntimeException(cause);
            }
        }

        private static String text(String key, String fallback, Object... args) {
            String localized = I18n.get(key, args);
            if (localized == null || localized.equals(key)) {
                localized = fallback;
                for (int i = 0; i < args.length; i++) {
                    localized = localized.replace("{" + i + "}", String.valueOf(args[i]));
                }
            }
            return localized;
        }
    }

    private record Endpoint(String host, int port) {
    }

    private record HostKeyPin(
        Endpoint endpoint,
        String algorithm,
        String fingerprintSha256,
        String publicKeyLine,
        String trustedAt) {
    }

    private enum Decision {
        TRUSTED,
        REJECTED
    }

    private static final class PendingDecision {
        private final CompletableFuture<Decision> future = new CompletableFuture<>();

        private void complete(Decision decision) {
            future.complete(decision);
        }

        private void fail(Throwable failure) {
            future.completeExceptionally(failure);
        }

        private Decision await() throws InterruptedException, ExecutionException {
            return future.get();
        }
    }

    private static final class SharedHolder {
        private static final SshHostKeyTrustManager INSTANCE = new SshHostKeyTrustManager(
            KorTTYApplication.getConfigDirectory().resolve(STORE_FILE_NAME),
            new JavaFxHostKeyPrompt());
    }
}
