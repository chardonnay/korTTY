package de.kortty.core;

import de.kortty.KorTTYApplication;
import de.kortty.model.ServerConnection;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.PolicyRestrictionException;
import de.kortty.policy.PolicyUiSupport;
import de.kortty.ui.I18n;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
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
import java.util.function.BooleanSupplier;

/**
 * Shared trust-on-first-use verifier for interactive SSH and SFTP connections.
 *
 * <p>Trust is deliberately keyed by the normalized host name and port rather than by a saved
 * connection id. That means the terminal, SFTP manager and duplicate connection profiles all see
 * the same pin.</p>
 *
 * <p>A changed key is never replaced automatically. It is rejected, and the user can replace the
 * pin during the connection attempt only when three conditions hold together: the verifier was
 * built with {@link ReplacePolicy#INTERACTIVE} (a connect the user started, never a background or
 * scheduled one), the enterprise policy does not lock pins ({@code enforce-host-key-check}), and
 * the user reviews both fingerprints and explicitly confirms the replacement. The replacement is a
 * compare-and-swap against the exact fingerprint the user saw, and it is logged at WARN with both
 * fingerprints. Outside a connection attempt, pins can be listed and removed through
 * {@link #listTrustedKeys()} and {@link #removePin(String, int, String)}.</p>
 */
public final class SshHostKeyTrustManager {

    static final String STORE_FILE_NAME = "ssh-host-keys.properties";
    private static final String STORE_FORMAT = "1";
    private static final Logger logger = LoggerFactory.getLogger(SshHostKeyTrustManager.class);
    private static final ConcurrentHashMap<Path, Object> PROCESS_STORE_LOCKS = new ConcurrentHashMap<>();
    private static final Comparator<HostKeyPin> PIN_ORDER =
        Comparator.comparing((HostKeyPin pin) -> pin.endpoint().host())
            .thenComparingInt(pin -> pin.endpoint().port());

    private final Path storeFile;
    private final Path lockFile;
    private final HostKeyPrompt prompt;
    private final BooleanSupplier pinChangesLocked;
    private final Object stateLock = new Object();
    private final ConcurrentHashMap<Endpoint, PendingDecision> pendingDecisions = new ConcurrentHashMap<>();

    /**
     * Creates a trust manager whose pin changes (remove, replace) are locked while the enterprise
     * policy enforces host-key checking.
     */
    public SshHostKeyTrustManager(Path storeFile, HostKeyPrompt prompt) {
        this(storeFile, prompt, () -> PolicyManager.effective().enforceHostKeyCheck());
    }

    /**
     * @param pinChangesLocked answers whether removing or replacing a trusted key is forbidden
     *        right now; first-use pinning is never affected by it
     */
    public SshHostKeyTrustManager(Path storeFile, HostKeyPrompt prompt, BooleanSupplier pinChangesLocked) {
        this.storeFile = Objects.requireNonNull(storeFile, "storeFile").toAbsolutePath().normalize();
        this.lockFile = this.storeFile.resolveSibling(this.storeFile.getFileName() + ".lock");
        this.prompt = Objects.requireNonNull(prompt, "prompt");
        this.pinChangesLocked = Objects.requireNonNull(pinChangesLocked, "pinChangesLocked");
    }

    /** Returns the process-wide verifier used by all interactive SSH transports. */
    public static SshHostKeyTrustManager shared() {
        return SharedHolder.INSTANCE;
    }

    /**
     * Creates a STRICT (trust-on-first-use) verifier bound to the connection's user-visible
     * endpoint. A changed key is never offered for replacement ({@link ReplacePolicy#NEVER}).
     */
    public ConnectionVerifier verifierFor(ServerConnection connection) {
        return verifierFor(connection, HostKeyCheckMode.STRICT, ReplacePolicy.NEVER);
    }

    /**
     * Creates a verifier for the connection's endpoint using the given {@link HostKeyCheckMode}.
     * A changed key is never offered for replacement ({@link ReplacePolicy#NEVER}).
     */
    public ConnectionVerifier verifierFor(ServerConnection connection, HostKeyCheckMode mode) {
        return verifierFor(connection, mode, ReplacePolicy.NEVER);
    }

    /**
     * Creates a verifier for the connection's endpoint. Pass {@link ReplacePolicy#INTERACTIVE} only
     * for a connect the user started and is watching; every unattended caller keeps
     * {@link ReplacePolicy#NEVER}, so its thread never waits on a replacement dialog.
     */
    public ConnectionVerifier verifierFor(
            ServerConnection connection, HostKeyCheckMode mode, ReplacePolicy replacePolicy) {
        Objects.requireNonNull(connection, "connection");
        String host = connection.getHost();
        int port = effectivePort(connection.getPort());
        return new ConnectionVerifier(
            this,
            host,
            port,
            mode != null ? mode : HostKeyCheckMode.STRICT,
            replacePolicy != null ? replacePolicy : ReplacePolicy.NEVER);
    }

    /**
     * Whether trusted keys may currently not be removed or replaced, because the enterprise policy
     * enforces host-key checking. Fails closed: an error while asking counts as locked.
     */
    public boolean isPinManagementLocked() {
        try {
            return pinChangesLocked.getAsBoolean();
        } catch (RuntimeException e) {
            logger.warn("Could not determine whether SSH host-key pin changes are locked; treating them as locked", e);
            return true;
        }
    }

    /**
     * All trusted host keys, sorted by host and then port. A malformed store throws instead of
     * returning a partial list, so a caller cannot mistake it for an empty one.
     */
    public List<TrustedHostKey> listTrustedKeys() throws IOException {
        Map<Endpoint, HostKeyPin> pins;
        synchronized (stateLock) {
            pins = readPins();
        }
        return pins.values().stream()
            .sorted(PIN_ORDER)
            .map(pin -> new TrustedHostKey(
                pin.endpoint().host(),
                pin.endpoint().port(),
                pin.algorithm(),
                pin.fingerprintSha256(),
                pin.trustedAt()))
            .toList();
    }

    /**
     * Removes the trusted key for {@code host:port}, but only while it still has the fingerprint
     * the caller showed to the user (compare-and-delete). The next connection to that endpoint
     * shows the first-use prompt again.
     *
     * @return {@code true} when the pin was removed; {@code false} when there is no pin for the
     *         endpoint or it has meanwhile changed to another key
     * @throws PolicyRestrictionException when the enterprise policy locks pin changes
     */
    public boolean removePin(String host, int port, String expectedFingerprintSha256) throws IOException {
        Objects.requireNonNull(expectedFingerprintSha256, "expectedFingerprintSha256");
        if (isPinManagementLocked()) {
            throw new PolicyRestrictionException(
                "Trusted SSH host keys cannot be removed while the organization enforces host-key checking.");
        }
        Endpoint endpoint = new Endpoint(normalizeHost(host), effectivePort(port));
        boolean removed = mutateStore(currentPins -> {
            HostKeyPin existing = currentPins.get(endpoint);
            if (existing == null || !existing.fingerprintSha256().equals(expectedFingerprintSha256)) {
                return Mutation.unchanged(false);
            }
            Map<Endpoint, HostKeyPin> updated = new HashMap<>(currentPins);
            updated.remove(endpoint);
            return Mutation.write(updated, true);
        });
        if (removed) {
            logger.info("Removed trusted SSH host key for {}:{} ({})",
                endpoint.host(), endpoint.port(), expectedFingerprintSha256);
        } else {
            logger.info("Did not remove SSH host key for {}:{}: it is no longer pinned with {}",
                endpoint.host(), endpoint.port(), expectedFingerprintSha256);
        }
        return removed;
    }

    boolean verify(String host, int port, PublicKey serverKey) {
        return verify(host, port, serverKey, HostKeyCheckMode.STRICT);
    }

    boolean verify(String host, int port, PublicKey serverKey, HostKeyCheckMode mode) {
        return verify(host, port, serverKey, mode, ReplacePolicy.NEVER);
    }

    boolean verify(
            String host, int port, PublicKey serverKey, HostKeyCheckMode mode, ReplacePolicy replacePolicy) {
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
            return acceptOrResolveMismatch(
                endpoint, existing, offered, replacePolicy == ReplacePolicy.INTERACTIVE);
        }

        // accept-new: a host with no existing pin is trusted and pinned WITHOUT a prompt. A changed
        // key still fails, because that path (existing != null) is handled above and is unaffected.
        if (mode == HostKeyCheckMode.ACCEPT_NEW) {
            try {
                persistFirstPin(offered);
                logger.info("Accepted new SSH host key for {}:{} without prompt (host-key checking relaxed) ({})",
                    endpoint.host(), endpoint.port(), offered.fingerprintSha256());
                return true;
            } catch (Exception e) {
                logger.error("Could not persist accepted SSH host key for {}:{}",
                    endpoint.host(), endpoint.port(), e);
                warnFailure(new HostKeyVerificationFailure(endpoint.host(), endpoint.port(), safeMessage(e)));
                return false;
            }
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
        // A replacement is never offered here: a first-use race must not turn into a key change.
        try {
            HostKeyPin trusted = findPin(endpoint);
            return trusted != null && acceptOrResolveMismatch(endpoint, trusted, offered, false);
        } catch (IOException e) {
            logger.error("Could not re-read SSH host-key trust state for {}:{}",
                endpoint.host(), endpoint.port(), e);
            warnFailure(new HostKeyVerificationFailure(endpoint.host(), endpoint.port(), safeMessage(e)));
            return false;
        }
    }

    /**
     * Accepts {@code offered} when it matches {@code trusted}. Otherwise the connection is blocked,
     * and the user may replace the pin only when {@code offerReplacement} is set, the policy does
     * not lock pins, and the prompt answers {@link HostKeyPrompt.MismatchResolution#REPLACE}.
     */
    private boolean acceptOrResolveMismatch(
            Endpoint endpoint, HostKeyPin trusted, HostKeyDetails offered, boolean offerReplacement) {
        if (trusted.fingerprintSha256().equals(offered.fingerprintSha256())) {
            logger.debug("SSH host key matched pin for {}:{} ({})",
                offered.host(), offered.port(), offered.fingerprintSha256());
            return true;
        }

        // Re-read before prompting: a parallel window may already have replaced the pin with this
        // exact key after the user confirmed it there, and that decision must not be asked twice.
        HostKeyPin current;
        try {
            current = findPin(endpoint);
        } catch (IOException e) {
            logger.error("Could not re-read SSH host-key trust state for {}:{}",
                endpoint.host(), endpoint.port(), e);
            warnFailure(new HostKeyVerificationFailure(endpoint.host(), endpoint.port(), safeMessage(e)));
            return false;
        }
        if (current != null && current.fingerprintSha256().equals(offered.fingerprintSha256())) {
            logger.info("SSH host key for {}:{} was meanwhile trusted in another window ({})",
                endpoint.host(), endpoint.port(), offered.fingerprintSha256());
            return true;
        }
        HostKeyPin expected = current != null ? current : trusted;

        logger.error("SSH host key mismatch for {}:{} expected={} actual={}",
            offered.host(), offered.port(), expected.fingerprintSha256(), offered.fingerprintSha256());
        HostKeyMismatch mismatch = new HostKeyMismatch(
            offered.host(),
            offered.port(),
            expected.algorithm(),
            expected.fingerprintSha256(),
            offered.algorithm(),
            offered.fingerprintSha256());
        // A pin that vanished meanwhile is not replaced either: the next connect asks as first use.
        boolean allowed = offerReplacement && current != null && !isPinManagementLocked();
        HostKeyPrompt.MismatchResolution resolution;
        try {
            resolution = prompt.resolveMismatch(mismatch, allowed);
        } catch (RuntimeException e) {
            logger.warn("Could not display SSH host-key mismatch warning", e);
            return false;
        }
        if (!allowed || resolution != HostKeyPrompt.MismatchResolution.REPLACE) {
            return false;
        }

        try {
            if (replacePin(endpoint, expected.fingerprintSha256(), offered)) {
                logger.warn("Replaced trusted SSH host key for {}:{} after explicit user confirmation: "
                        + "old={} {} new={} {}",
                    endpoint.host(), endpoint.port(),
                    expected.algorithm(), expected.fingerprintSha256(),
                    offered.algorithm(), offered.fingerprintSha256());
                return true;
            }
        } catch (IOException | RuntimeException e) {
            logger.error("Could not replace trusted SSH host key for {}:{}", endpoint.host(), endpoint.port(), e);
            warnFailure(new HostKeyVerificationFailure(endpoint.host(), endpoint.port(), safeMessage(e)));
            return false;
        }
        logger.warn("Did not replace SSH host key for {}:{}: the trusted key changed while the "
                + "confirmation was open (expected={} offered={})",
            endpoint.host(), endpoint.port(), expected.fingerprintSha256(), offered.fingerprintSha256());
        try {
            prompt.warnReplacementConflict(mismatch);
        } catch (RuntimeException e) {
            logger.warn("Could not display SSH host-key replacement conflict", e);
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
        mutateStore(currentPins -> {
            HostKeyPin existing = currentPins.get(endpoint);
            if (existing != null) {
                if (!existing.fingerprintSha256().equals(offered.fingerprintSha256())) {
                    throw new IOException(
                        "Another SSH host key was pinned for this endpoint while confirmation was open.");
                }
                return Mutation.unchanged(Boolean.FALSE);
            }
            Map<Endpoint, HostKeyPin> updated = new HashMap<>(currentPins);
            updated.put(endpoint, newPin(endpoint, offered));
            return Mutation.write(updated, Boolean.TRUE);
        });
    }

    /**
     * Compare-and-swap of a pin: replaces it with {@code offered} only while it still has
     * {@code expectedOldFingerprint}, the key the user saw in the confirmation.
     *
     * @return {@code true} when the pin now holds {@code offered} (also when a parallel
     *         confirmation already stored exactly this key); {@code false} when the pin vanished
     *         or changed to a third key
     */
    private boolean replacePin(Endpoint endpoint, String expectedOldFingerprint, HostKeyDetails offered)
            throws IOException {
        if (isPinManagementLocked()) {
            throw new PolicyRestrictionException(
                "Trusted SSH host keys cannot be replaced while the organization enforces host-key checking.");
        }
        return mutateStore(currentPins -> {
            HostKeyPin existing = currentPins.get(endpoint);
            if (existing == null) {
                return Mutation.unchanged(false);
            }
            if (existing.fingerprintSha256().equals(offered.fingerprintSha256())) {
                return Mutation.unchanged(true);
            }
            if (!existing.fingerprintSha256().equals(expectedOldFingerprint)) {
                return Mutation.unchanged(false);
            }
            Map<Endpoint, HostKeyPin> updated = new HashMap<>(currentPins);
            updated.put(endpoint, newPin(endpoint, offered));
            return Mutation.write(updated, true);
        });
    }

    private static HostKeyPin newPin(Endpoint endpoint, HostKeyDetails offered) {
        return new HostKeyPin(
            endpoint,
            offered.algorithm(),
            offered.fingerprintSha256(),
            offered.publicKeyLine(),
            Instant.now().toString());
    }

    /**
     * Runs one read-modify-write of the store under the process monitor, the cross-process file
     * lock and the instance state lock, in that order. The store is reloaded while the locks are
     * held, so pins written by another korTTY process are merged instead of being lost by the
     * atomic replacement.
     */
    private <T> T mutateStore(StoreMutation<T> mutation) throws IOException {
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
                    Mutation<T> outcome = mutation.apply(readPins());
                    if (outcome.updatedPins() != null) {
                        writePins(outcome.updatedPins());
                    }
                    return outcome.result();
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
        if (updated.isEmpty()) {
            // A missing file is the valid empty store, while entry.count=0 would be rejected as
            // malformed and fail every later connection closed.
            Files.deleteIfExists(storeFile);
            return;
        }
        Path parent = storeFile.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Properties properties = new Properties();
        properties.setProperty("format", STORE_FORMAT);
        List<HostKeyPin> ordered = new ArrayList<>(updated.values());
        ordered.sort(PIN_ORDER);
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

        /**
         * Shows a changed host key and asks how to continue. The connection stays blocked unless
         * this returns {@link MismatchResolution#REPLACE} while {@code replacementAllowed} is set;
         * a {@code REPLACE} answer without that permission is ignored. The default only warns,
         * which keeps every prompt that predates replacement on the plain hard block.
         *
         * @param replacementAllowed whether the user may replace the trusted key from here: false
         *        for unattended connections, while the policy locks pins, and for a first-use race
         */
        default MismatchResolution resolveMismatch(HostKeyMismatch mismatch, boolean replacementAllowed) {
            warnMismatch(mismatch);
            return MismatchResolution.KEEP_BLOCKED;
        }

        /**
         * Reports that a confirmed replacement was not stored because the trusted key was changed
         * or removed elsewhere while the confirmation was open. The connection stays blocked.
         */
        default void warnReplacementConflict(HostKeyMismatch mismatch) {
            warnVerificationFailure(new HostKeyVerificationFailure(
                mismatch.host(),
                mismatch.port(),
                "The trusted host key was changed or removed while the replacement was being confirmed."));
        }

        /** What the user chose in the changed-key prompt. */
        enum MismatchResolution {
            /** Keep the trusted key and block the connection (the default answer). */
            KEEP_BLOCKED,
            /** Replace the trusted key with the offered one and continue the connection. */
            REPLACE
        }
    }

    /**
     * Whether a verifier may offer to replace a changed key. Only connects the user started and is
     * watching are {@link #INTERACTIVE}; background, restored and scheduled work stays
     * {@link #NEVER}, so it gets the plain mismatch warning and never waits on a review dialog.
     */
    public enum ReplacePolicy {
        INTERACTIVE,
        NEVER
    }

    /** A trusted host key as listed by {@link #listTrustedKeys()}. */
    public record TrustedHostKey(
        String host,
        int port,
        String algorithm,
        String fingerprintSha256,
        String trustedAt) {
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
        private final HostKeyCheckMode mode;
        private final ReplacePolicy replacePolicy;
        private final AtomicBoolean rejected = new AtomicBoolean();

        private ConnectionVerifier(
                SshHostKeyTrustManager trustManager,
                String host,
                int port,
                HostKeyCheckMode mode,
                ReplacePolicy replacePolicy) {
            this.trustManager = trustManager;
            this.host = host;
            this.port = port;
            this.mode = mode;
            this.replacePolicy = replacePolicy;
        }

        @Override
        public boolean verifyServerKey(
            org.apache.sshd.client.session.ClientSession clientSession,
            java.net.SocketAddress remoteAddress,
            PublicKey serverKey) {

            boolean accepted = trustManager.verify(host, port, serverKey, mode, replacePolicy);
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
            resolveMismatch(mismatch, false);
        }

        /**
         * Shows the changed-key alert. Close is the default; when {@code replacementAllowed} it adds
         * "Review and Replace…", which opens a second confirmation that only a ticked verification
         * checkbox enables. Anything other than that explicit confirmation keeps the block.
         */
        @Override
        public MismatchResolution resolveMismatch(HostKeyMismatch mismatch, boolean replacementAllowed) {
            try {
                return runOnFxThreadAndWait(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(text("ssh.hostKey.mismatch.title", "SSH host key changed"));
                    alert.setHeaderText(text("ssh.hostKey.mismatch.header",
                        "Connection to {0}:{1} was blocked.", mismatch.host(), mismatch.port()));
                    String content = text("ssh.hostKey.mismatch.message",
                        "The server presented a different host key. This may indicate a man-in-the-middle attack. "
                            + "Verify the new fingerprint with the server administrator before replacing the "
                            + "trusted key.\n\nExpected: {0}\nReceived: {1}",
                        mismatch.expectedFingerprintSha256(), mismatch.offeredFingerprintSha256());
                    if (!replacementAllowed) {
                        content += "\n\n" + replacementUnavailableNote();
                    }
                    alert.setContentText(content);

                    ButtonType review = new ButtonType(
                        text("ssh.hostKey.mismatch.reviewReplace", "Review and Replace…"),
                        ButtonBar.ButtonData.OTHER);
                    if (replacementAllowed) {
                        alert.getButtonTypes().setAll(review, ButtonType.CLOSE);
                    }
                    prepareAlert(alert);
                    if (replacementAllowed) {
                        ((Button) alert.getDialogPane().lookupButton(review)).setDefaultButton(false);
                        ((Button) alert.getDialogPane().lookupButton(ButtonType.CLOSE)).setDefaultButton(true);
                    }
                    Optional<ButtonType> result = alert.showAndWait();
                    if (replacementAllowed && result.isPresent() && result.get() == review
                            && confirmReplacement(mismatch, alert.getOwner())) {
                        return MismatchResolution.REPLACE;
                    }
                    return MismatchResolution.KEEP_BLOCKED;
                });
            } catch (Exception e) {
                logger.error("Could not display SSH host-key mismatch warning", e);
                return MismatchResolution.KEEP_BLOCKED;
            }
        }

        @Override
        public void warnReplacementConflict(HostKeyMismatch mismatch) {
            try {
                runOnFxThreadAndWait(() -> {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(text("ssh.hostKey.mismatch.title", "SSH host key changed"));
                    alert.setHeaderText(text("ssh.hostKey.mismatch.header",
                        "Connection to {0}:{1} was blocked.", mismatch.host(), mismatch.port()));
                    alert.setContentText(text("ssh.hostKey.replace.failed",
                        "The trusted key was not replaced, because it was changed or removed in another window "
                            + "while you were confirming. Reconnect to review the current key."));
                    prepareAlert(alert);
                    alert.showAndWait();
                    return null;
                });
            } catch (Exception e) {
                logger.error("Could not display SSH host-key replacement conflict", e);
            }
        }

        /** Why the alert offers no replacement: the organization's policy, or this connection. */
        private static String replacementUnavailableNote() {
            if (PolicyManager.effective().enforceHostKeyCheck()) {
                return text("ssh.hostKey.mismatch.policyLocked",
                    "Your organization enforces host-key checking, so the trusted key cannot be replaced in "
                        + "korTTY. Ask your administrator to update it.")
                    + "\n" + PolicyUiSupport.managedByOrganizationText();
            }
            return text("ssh.hostKey.mismatch.knownHostsHint",
                "This connection cannot replace the trusted key. Once you have verified the new fingerprint, "
                    + "remove the old key under Configuration › Security › Known Hosts… and reconnect.");
        }

        /**
         * Second step of a replacement: shows both keys side by side. Cancel is the default button,
         * so Enter never confirms, and the replace button stays disabled until the user ticks that
         * the new fingerprint was verified with the server administrator.
         */
        private static boolean confirmReplacement(HostKeyMismatch mismatch, Window owner) {
            Alert alert = new Alert(Alert.AlertType.WARNING);
            alert.setTitle(text("ssh.hostKey.replace.title", "Replace Trusted SSH Host Key"));
            alert.setHeaderText(text("ssh.hostKey.replace.header",
                "Replace the trusted key for {0}:{1}?", mismatch.host(), mismatch.port()));

            Label message = new Label(text("ssh.hostKey.replace.message",
                "Compare the new fingerprint with the one the server administrator gives you over a trusted "
                    + "channel. Replace the key only if both match exactly."));
            message.setWrapText(true);

            GridPane keys = new GridPane();
            keys.setHgap(10);
            keys.setVgap(6);
            ColumnConstraints labelColumn = new ColumnConstraints();
            labelColumn.setHgrow(Priority.NEVER);
            ColumnConstraints valueColumn = new ColumnConstraints();
            valueColumn.setHgrow(Priority.ALWAYS);
            keys.getColumnConstraints().setAll(labelColumn, valueColumn);
            addKeyRow(keys, 0, text("ssh.hostKey.replace.currentKey", "Currently trusted:"),
                mismatch.expectedAlgorithm() + "  " + mismatch.expectedFingerprintSha256());
            addKeyRow(keys, 1, text("ssh.hostKey.replace.newKey", "New key:"),
                mismatch.offeredAlgorithm() + "  " + mismatch.offeredFingerprintSha256());

            CheckBox verified = new CheckBox(text("ssh.hostKey.replace.verifiedCheck",
                "I have verified the new fingerprint with the server administrator"));
            verified.setWrapText(true);

            VBox content = new VBox(12, message, keys, verified);
            content.setPadding(new Insets(4, 0, 0, 0));
            alert.getDialogPane().setContent(content);

            ButtonType replace = new ButtonType(
                text("ssh.hostKey.replace.confirmButton", "Replace Key and Connect"),
                ButtonBar.ButtonData.OK_DONE);
            alert.getButtonTypes().setAll(replace, ButtonType.CANCEL);
            // Right after the changed-key alert closed, its owner may not have regained focus yet,
            // so reuse that owner instead of looking for the focused window again.
            if (owner != null) {
                alert.getDialogPane().setPrefWidth(680);
                alert.initOwner(owner);
            } else {
                prepareAlert(alert);
            }
            Button replaceButton = (Button) alert.getDialogPane().lookupButton(replace);
            replaceButton.setDefaultButton(false);
            replaceButton.disableProperty().bind(verified.selectedProperty().not());
            ((Button) alert.getDialogPane().lookupButton(ButtonType.CANCEL)).setDefaultButton(true);

            Optional<ButtonType> result = alert.showAndWait();
            return result.isPresent() && result.get() == replace && verified.isSelected();
        }

        private static void addKeyRow(GridPane grid, int row, String label, String value) {
            Label name = new Label(label);
            name.setMinWidth(Label.USE_PREF_SIZE);
            // A read-only field rather than a label, so the fingerprint can be selected and copied
            // for comparison.
            TextField field = new TextField(value);
            field.setEditable(false);
            field.setFocusTraversable(false);
            field.setStyle("-fx-font-family: monospace;");
            grid.add(name, 0, row);
            grid.add(field, 1, row);
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

    /** One read-modify-write step of {@link #mutateStore}; it sees the freshly reloaded pins. */
    @FunctionalInterface
    private interface StoreMutation<T> {
        Mutation<T> apply(Map<Endpoint, HostKeyPin> currentPins) throws IOException;
    }

    /** The outcome of a {@link StoreMutation}: the pins to write ({@code null}: none) and a result. */
    private record Mutation<T>(Map<Endpoint, HostKeyPin> updatedPins, T result) {
        static <T> Mutation<T> unchanged(T result) {
            return new Mutation<>(null, result);
        }

        static <T> Mutation<T> write(Map<Endpoint, HostKeyPin> updatedPins, T result) {
            return new Mutation<>(Objects.requireNonNull(updatedPins, "updatedPins"), result);
        }
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
