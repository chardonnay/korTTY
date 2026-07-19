package de.kortty.ai.runtimeupdate;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.ai.llama.LlamaRuntimeTrustGuard;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Side-by-side runtime package installer with atomic activation and automatic rollback. */
public final class LlamaRuntimePackageInstaller {

    private static final int COPY_BUFFER_SIZE = 128 * 1024;
    private static final int MAX_ZIP_ENTRIES = 20_000;
    private static final long MAX_EXTRACTED_BYTES = 8L * 1024 * 1024 * 1024;
    private static final int RETAIN_HEALTHY_INSTALLATIONS = 2;
    private static final String DESCRIPTOR_FILE = ".kortty-runtime.json";
    private static final String ACTIVE_FILE = "active-v1";
    private static final String HISTORY_FILE = "healthy-history-v1";
    private static final String BLOCKED_ACTIVE_FILE = "blocked-active-v1";
    private static final String PENDING_FIRST_LAUNCH_FILE = "pending-first-launch-v1";
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_UPDATE_LOCKS = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface PackageContentProvider {
        InputStream open(URI uri) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface RuntimeHealthCheck {
        boolean isHealthy(LlamaRuntimeInstallation installation) throws Exception;
    }

    private final Path runtimeRoot;
    private final Path packagesDirectory;
    private final Path downloadsDirectory;
    private final ReentrantLock jvmUpdateLock;
    private final PackageContentProvider contentProvider;
    private final LlamaRuntimeIndexCodec codec = new LlamaRuntimeIndexCodec();

    public LlamaRuntimePackageInstaller() {
        this(defaultRuntimeRoot());
    }

    public LlamaRuntimePackageInstaller(Path runtimeRoot) {
        this(runtimeRoot, new HttpPackageContentProvider());
    }

    public LlamaRuntimePackageInstaller(Path runtimeRoot, PackageContentProvider contentProvider) {
        this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot").toAbsolutePath().normalize();
        this.packagesDirectory = this.runtimeRoot.resolve("packages");
        this.downloadsDirectory = this.runtimeRoot.resolve("downloads");
        this.jvmUpdateLock = JVM_UPDATE_LOCKS.computeIfAbsent(this.runtimeRoot, ignored -> new ReentrantLock());
        this.contentProvider = Objects.requireNonNull(contentProvider, "contentProvider");
    }

    public static Path defaultRuntimeRoot() {
        return Path.of(System.getProperty("user.home"), ".kortty", "llm", "runtime");
    }

    /** Same runtime root and locks, but package bytes served by the given provider. */
    public LlamaRuntimePackageInstaller withContentProvider(PackageContentProvider provider) {
        return new LlamaRuntimePackageInstaller(runtimeRoot, provider);
    }

    /**
     * Removes the active pointer, the pending-first-launch state, the healthy history and every
     * installed package directory. The signed revocation denylist and the blocked-active marker
     * deliberately survive an uninstall so a withdrawn package stays blocked after reinstalling.
     */
    public void uninstallAll() throws IOException {
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                // Pointers first: even if a package directory cannot be deleted, no state is left
                // that would let the runtime manager launch it again.
                Files.deleteIfExists(runtimeRoot.resolve(ACTIVE_FILE));
                Files.deleteIfExists(runtimeRoot.resolve(PENDING_FIRST_LAUNCH_FILE));
                Files.deleteIfExists(runtimeRoot.resolve(HISTORY_FILE));
                if (Files.isDirectory(packagesDirectory)) {
                    try (var stream = Files.list(packagesDirectory)) {
                        for (Path candidate : stream.toList()) {
                            deleteTree(candidate);
                        }
                    }
                }
                deleteTreeQuietly(downloadsDirectory);
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    /** Installs and verifies a package beside the current version without changing active runtime state. */
    public LlamaRuntimeInstallation install(LlamaRuntimePackageDescriptor descriptor)
        throws IOException, InterruptedException {
        Objects.requireNonNull(descriptor, "descriptor");
        validateLocalCompatibility(descriptor);
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                ensureNotRevoked(descriptor, readRevocations());
                return installLocked(descriptor);
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    /**
     * Installs first, then activates only when the caller confirms no generation is running. A
     * failed health check restores the previous active pointer before returning.
     */
    public LlamaRuntimeActivationResult installAndActivate(
        LlamaRuntimePackageDescriptor descriptor,
        BooleanSupplier runtimeIsIdle,
        RuntimeHealthCheck healthCheck
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(runtimeIsIdle, "runtimeIsIdle");
        Objects.requireNonNull(healthCheck, "healthCheck");
        LlamaRuntimeInstallation installation = install(descriptor);
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                ensureNotRevoked(installation.descriptor(), readRevocations());
                if (LlamaRuntimeTrustGuard.isRevoked(installation.executable())) {
                    throw new IOException("Refusing to activate a quarantined llama.cpp runtime package.");
                }
                LlamaRuntimePackageIntegrity.verifyInstallation(installation);
                Optional<LlamaRuntimeInstallation> previous = activeLocked();
                Optional<PendingActivation> existingPending = pendingLocked();
                if (previous.map(value -> value.descriptor().installationId())
                    .filter(installation.descriptor().installationId()::equals).isPresent()) {
                    return new LlamaRuntimeActivationResult(
                        LlamaRuntimeActivationResult.Status.ALREADY_ACTIVE,
                        installation,
                        previous.orElse(null));
                }
                if (!runtimeIsIdle.getAsBoolean()) {
                    return new LlamaRuntimeActivationResult(
                        LlamaRuntimeActivationResult.Status.STAGED_UNTIL_IDLE,
                        installation,
                        previous.orElse(null));
                }
                boolean healthy;
                try {
                    healthy = healthCheck.isHealthy(installation);
                } catch (Exception e) {
                    deleteInstallationQuietly(installation);
                    throw new IOException(
                        "New llama.cpp runtime failed its startup health check; previous runtime restored.", e);
                }
                if (!healthy) {
                    deleteInstallationQuietly(installation);
                    return new LlamaRuntimeActivationResult(
                        LlamaRuntimeActivationResult.Status.ROLLED_BACK,
                        installation,
                        previous.orElse(null));
                }

                LlamaRuntimeInstallation rollbackBase = previous.orElse(null);
                if (existingPending.isPresent() && previous.isPresent()
                    && existingPending.get().installation().descriptor().installationId()
                        .equals(previous.get().descriptor().installationId())) {
                    rollbackBase = existingPending.get().previousInstallation();
                }
                writePending(installation, rollbackBase);
                writePointer(ACTIVE_FILE, installation.descriptor().installationId());
                Files.deleteIfExists(runtimeRoot.resolve(BLOCKED_ACTIVE_FILE));
                return new LlamaRuntimeActivationResult(
                    LlamaRuntimeActivationResult.Status.ACTIVATED,
                    installation,
                    previous.orElse(null));
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    public Optional<LlamaRuntimeInstallation> active() throws IOException {
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return activeLocked();
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    /** Package awaiting its first real GGUF-backed llama-server/API-ready start. */
    public Optional<PendingActivation> pendingActivation() throws IOException {
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return pendingLocked();
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    /** Promotes a pending package only after the real model server reached its authenticated API. */
    public Optional<LlamaRuntimeInstallation> confirmPendingFirstLaunch(Path executable) throws IOException {
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Optional<PendingActivation> pending = pendingLocked();
                if (pending.isEmpty() || !sameExecutable(pending.get().installation(), executable)) {
                    return Optional.empty();
                }
                PendingActivation activation = pending.get();
                recordHealthy(
                    activation.installation().descriptor().installationId(),
                    activation.previousInstallation());
                cleanupOldInstallations();
                Files.deleteIfExists(runtimeRoot.resolve(PENDING_FIRST_LAUNCH_FILE));
                return Optional.of(activation.installation());
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    /** Rolls a pending package back after a real model/API startup failure. */
    public Optional<PendingRollback> rollbackPendingFirstLaunch(Path executable) throws IOException {
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Optional<PendingActivation> pending = pendingLocked();
                if (pending.isEmpty() || !sameExecutable(pending.get().installation(), executable)) {
                    return Optional.empty();
                }
                return Optional.of(rollbackPendingLocked(pending.get()));
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    /**
     * Persists every withdrawal from a verified signed index and atomically deactivates a matching
     * active package. Revoked packages remain on disk only as quarantined evidence and can no
     * longer be selected, restored, reinstalled, or launched through the runtime trust guard.
     *
     * @return the package that was active and became blocked during this call, if any
     */
    public Optional<LlamaRuntimeInstallation> applyRevocations(LlamaRuntimeIndex index) throws IOException {
        Objects.requireNonNull(index, "index");
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Optional<LlamaRuntimeInstallation> rawActive = activeLockedRaw();
                Set<String> revoked = new LinkedHashSet<>(readRevocations());
                for (String value : index.revokedRuntimeIds()) {
                    if (isSafeIdentifier(value)) {
                        revoked.add(value);
                    }
                }
                for (LlamaRuntimePackageDescriptor descriptor : index.packages()) {
                    if (descriptor.revoked() || index.revokedRuntimeIds().contains(descriptor.runtimeId())
                        || index.revokedRuntimeIds().contains(descriptor.installationId())) {
                        revoked.add(descriptor.runtimeId());
                        revoked.add(descriptor.installationId());
                    }
                }

                List<LlamaRuntimeInstallation> installations = installedLocked();
                for (LlamaRuntimeInstallation installation : installations) {
                    if (index.isRevoked(installation.descriptor())
                        || isRevoked(installation.descriptor(), revoked)) {
                        revoked.add(installation.descriptor().runtimeId());
                        revoked.add(installation.descriptor().installationId());
                    }
                }
                writeRevocations(revoked);

                for (LlamaRuntimeInstallation installation : installations) {
                    if (isRevoked(installation.descriptor(), revoked)) {
                        writeRevocationMarker(installation);
                    }
                }

                Optional<LlamaRuntimeInstallation> revokedActive = rawActive.filter(
                    installation -> index.isRevoked(installation.descriptor())
                        || isRevoked(installation.descriptor(), revoked));
                if (revokedActive.isPresent()) {
                    writePointer(BLOCKED_ACTIVE_FILE, revokedActive.get().descriptor().runtimeId());
                    Files.deleteIfExists(runtimeRoot.resolve(ACTIVE_FILE));
                    Optional<PendingActivation> pending = pendingLocked();
                    if (pending.isPresent() && pending.get().installation().descriptor().installationId()
                        .equals(revokedActive.get().descriptor().installationId())) {
                        Files.deleteIfExists(runtimeRoot.resolve(PENDING_FIRST_LAUNCH_FILE));
                    }
                }
                pruneRevokedHistory(revoked);
                return revokedActive;
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    /** Runtime id most recently removed from the active pointer due to a signed withdrawal. */
    public Optional<String> blockedActiveRuntimeId() throws IOException {
        Path file = runtimeRoot.resolve(BLOCKED_ACTIVE_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 1024) {
            return Optional.empty();
        }
        String value = Files.readString(file, StandardCharsets.UTF_8).trim();
        return value.matches("llama-b[0-9]+-kortty[1-9][0-9]*") ? Optional.of(value) : Optional.empty();
    }

    /** Rolls back the currently active package to the newest different healthy package, if any. */
    public Optional<LlamaRuntimeInstallation> rollbackAfterFailedLaunch() throws IOException {
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                Optional<LlamaRuntimeInstallation> failed = activeLocked();
                String failedId = failed.map(value -> value.descriptor().installationId()).orElse(null);
                Optional<PendingActivation> pending = pendingLocked();
                if (pending.isPresent() && pending.get().installation().descriptor().installationId()
                    .equals(failedId)) {
                    return Optional.ofNullable(rollbackPendingLocked(pending.get()).restoredInstallation());
                }
                Set<String> revoked = readRevocations();
                for (String candidateId : readHistory()) {
                    if (candidateId.equals(failedId)) {
                        continue;
                    }
                    Optional<LlamaRuntimeInstallation> candidate = readInstallation(candidateId);
                    if (candidate.isPresent()
                        && !isRevoked(candidate.get().descriptor(), revoked)
                        && !LlamaRuntimeTrustGuard.isRevoked(candidate.get().executable())
                        && hasValidIntegrity(candidate.get())) {
                        writePointer(ACTIVE_FILE, candidateId);
                        Files.deleteIfExists(runtimeRoot.resolve(BLOCKED_ACTIVE_FILE));
                        failed.ifPresent(this::deleteInstallationQuietly);
                        return candidate;
                    }
                }
                Files.deleteIfExists(runtimeRoot.resolve(ACTIVE_FILE));
                failed.ifPresent(this::deleteInstallationQuietly);
                return Optional.empty();
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    public List<LlamaRuntimeInstallation> installed() throws IOException {
        return List.copyOf(installedLocked());
    }

    private List<LlamaRuntimeInstallation> installedLocked() throws IOException {
        if (!Files.isDirectory(packagesDirectory)) {
            return List.of();
        }
        List<LlamaRuntimeInstallation> result = new ArrayList<>();
        try (var stream = Files.list(packagesDirectory)) {
            for (Path directory : stream.filter(Files::isDirectory).sorted().toList()) {
                readInstallation(directory.getFileName().toString()).ifPresent(result::add);
            }
        }
        return result;
    }

    private LlamaRuntimeInstallation installLocked(LlamaRuntimePackageDescriptor descriptor)
        throws IOException, InterruptedException {
        Files.createDirectories(packagesDirectory);
        Files.createDirectories(downloadsDirectory);
        String installationId = descriptor.installationId();
        Optional<LlamaRuntimeInstallation> existing = readInstallation(installationId);
        if (existing.isPresent()) {
            if (!existing.get().descriptor().sha256().equals(descriptor.sha256())) {
                throw new IOException("Immutable runtime id already exists with a different SHA-256.");
            }
            LlamaRuntimePackageIntegrity.verifyInstallation(existing.get());
            return existing.get();
        }

        Path archive = downloadsDirectory.resolve(installationId + ".zip");
        Path partial = archive.resolveSibling(archive.getFileName() + ".part");
        Files.deleteIfExists(partial);
        try {
            downloadAndVerify(descriptor, partial);
            try {
                Files.move(partial, archive, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, archive, StandardCopyOption.REPLACE_EXISTING);
            }

            Path staging = packagesDirectory.resolve(".installing-" + installationId + "-" + UUID.randomUUID());
            try {
                Files.createDirectories(staging);
                extractZip(archive, staging, descriptor.size());
                Path executable = descriptor.resolveEntrypoint(staging);
                if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Runtime package does not contain entrypoint " + descriptor.entrypoint() + ".");
                }
                makeExecutable(executable);
                Files.writeString(
                    staging.resolve(DESCRIPTOR_FILE),
                    codec.descriptorJson(descriptor),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE);
                LlamaRuntimePackageIntegrity.seal(staging, descriptor);
                Path destination = packagesDirectory.resolve(installationId);
                try {
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(staging, destination);
                }
                LlamaRuntimeInstallation installation = requireInstallation(destination);
                LlamaRuntimePackageIntegrity.verifyInstallation(installation);
                return installation;
            } finally {
                deleteTreeQuietly(staging);
            }
        } finally {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(archive);
        }
    }

    private static void validateLocalCompatibility(LlamaRuntimePackageDescriptor descriptor) throws IOException {
        if (descriptor.revoked()) {
            throw new IOException("Refusing to install a revoked llama.cpp runtime package.");
        }
        if (descriptor.apiContractVersion() != 1) {
            throw new IOException("llama.cpp runtime API contract is not supported by this korTTY build.");
        }
        if (descriptor.platform() != LlamaRuntimePlatform.current()
            || !descriptor.architecture().equals(LlamaRuntimePackageDescriptor.currentArchitecture())) {
            throw new IOException("llama.cpp runtime package does not match this platform and architecture.");
        }
    }

    private void downloadAndVerify(LlamaRuntimePackageDescriptor descriptor, Path partial)
        throws IOException, InterruptedException {
        MessageDigest digest = sha256Digest();
        long total = 0;
        try (InputStream input = new BufferedInputStream(contentProvider.open(descriptor.downloadUri()));
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                 partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                total = Math.addExact(total, count);
                if (total > descriptor.size()) {
                    throw new IOException("Runtime package exceeds its signed size.");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (total != descriptor.size()) {
            throw new IOException("Runtime package size does not match its signed descriptor.");
        }
        String actual = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        if (!descriptor.sha256().equals(actual)) {
            throw new IOException("Runtime package SHA-256 does not match its signed descriptor.");
        }
    }

    private static void extractZip(Path archive, Path destination, long compressedBytes) throws IOException {
        int entries = 0;
        long extractedBytes = 0;
        long expansionLimit;
        try {
            expansionLimit = Math.min(MAX_EXTRACTED_BYTES, Math.max(512L * 1024 * 1024, Math.multiplyExact(compressedBytes, 30)));
        } catch (ArithmeticException ignored) {
            expansionLimit = MAX_EXTRACTED_BYTES;
        }
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new IOException("Runtime package contains too many entries.");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank() || name.indexOf('\0') >= 0) {
                    throw new IOException("Runtime package contains an invalid path.");
                }
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination) || target.equals(destination)) {
                    throw new IOException("Runtime package attempts to write outside its installation directory.");
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(target);
                    continue;
                }
                Files.createDirectories(target.getParent());
                try (OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                    target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
                    while (true) {
                        int count = zip.read(buffer);
                        if (count < 0) {
                            break;
                        }
                        extractedBytes = Math.addExact(extractedBytes, count);
                        if (extractedBytes > expansionLimit) {
                            throw new IOException("Runtime package exceeds the safe extraction limit.");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        } catch (ArithmeticException e) {
            throw new IOException("Runtime package extracted size overflow.", e);
        }
    }

    private Optional<LlamaRuntimeInstallation> activeLocked() throws IOException {
        Optional<LlamaRuntimeInstallation> installation = activeLockedRaw();
        if (installation.isPresent()
            && (isRevoked(installation.get().descriptor(), readRevocations())
                || LlamaRuntimeTrustGuard.isRevoked(installation.get().executable()))) {
            writePointer(BLOCKED_ACTIVE_FILE, installation.get().descriptor().runtimeId());
            Files.deleteIfExists(runtimeRoot.resolve(ACTIVE_FILE));
            return Optional.empty();
        }
        return installation;
    }

    private Optional<LlamaRuntimeInstallation> activeLockedRaw() throws IOException {
        Path pointer = runtimeRoot.resolve(ACTIVE_FILE);
        if (!Files.isRegularFile(pointer, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        String installationId = Files.readString(pointer, StandardCharsets.UTF_8).trim();
        return installationId.isEmpty() ? Optional.empty() : readInstallation(installationId);
    }

    private Optional<PendingActivation> pendingLocked() throws IOException {
        Path file = runtimeRoot.resolve(PENDING_FIRST_LAUNCH_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.size(file) > 4096) {
            throw new IOException("Pending llama.cpp runtime activation state is unexpectedly large.");
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        if (lines.isEmpty() || lines.size() > 2) {
            throw new IOException("Pending llama.cpp runtime activation state is malformed.");
        }
        String installationId = lines.get(0).trim();
        String previousId = lines.size() == 2 ? lines.get(1).trim() : "-";
        Optional<LlamaRuntimeInstallation> installation = readInstallation(installationId);
        Optional<LlamaRuntimeInstallation> active = activeLockedRaw();
        if (installation.isEmpty() || active.isEmpty()
            || !active.get().descriptor().installationId().equals(installationId)) {
            Files.deleteIfExists(file);
            return Optional.empty();
        }
        Set<String> revoked = readRevocations();
        if (isRevoked(installation.get().descriptor(), revoked)
            || LlamaRuntimeTrustGuard.isRevoked(installation.get().executable())) {
            return Optional.empty();
        }
        LlamaRuntimeInstallation previous = null;
        if (!previousId.equals("-") && isSafeIdentifier(previousId)) {
            Optional<LlamaRuntimeInstallation> candidate = readInstallation(previousId);
            if (candidate.isPresent() && !isRevoked(candidate.get().descriptor(), revoked)
                && !LlamaRuntimeTrustGuard.isRevoked(candidate.get().executable())) {
                previous = candidate.get();
            }
        }
        return Optional.of(new PendingActivation(installation.get(), previous));
    }

    private void writePending(
        LlamaRuntimeInstallation installation,
        LlamaRuntimeInstallation previous
    ) throws IOException {
        String previousId = previous != null ? previous.descriptor().installationId() : "-";
        writePointer(PENDING_FIRST_LAUNCH_FILE,
            installation.descriptor().installationId() + System.lineSeparator() + previousId);
    }

    private PendingRollback rollbackPendingLocked(PendingActivation pending) throws IOException {
        LlamaRuntimeInstallation failed = pending.installation();
        Set<String> revoked = readRevocations();
        LlamaRuntimeInstallation restored = pending.previousInstallation();
        if (restored == null || isRevoked(restored.descriptor(), revoked)
            || LlamaRuntimeTrustGuard.isRevoked(restored.executable())
            || !hasValidIntegrity(restored)) {
            restored = null;
            for (String candidateId : readHistory()) {
                if (candidateId.equals(failed.descriptor().installationId())) {
                    continue;
                }
                Optional<LlamaRuntimeInstallation> candidate = readInstallation(candidateId);
                if (candidate.isPresent() && !isRevoked(candidate.get().descriptor(), revoked)
                    && !LlamaRuntimeTrustGuard.isRevoked(candidate.get().executable())
                    && hasValidIntegrity(candidate.get())) {
                    restored = candidate.get();
                    break;
                }
            }
        }
        if (restored != null) {
            writePointer(ACTIVE_FILE, restored.descriptor().installationId());
            Files.deleteIfExists(runtimeRoot.resolve(BLOCKED_ACTIVE_FILE));
        } else {
            Files.deleteIfExists(runtimeRoot.resolve(ACTIVE_FILE));
        }
        Files.deleteIfExists(runtimeRoot.resolve(PENDING_FIRST_LAUNCH_FILE));
        deleteInstallationQuietly(failed);
        return new PendingRollback(failed, restored);
    }

    private static boolean sameExecutable(LlamaRuntimeInstallation installation, Path executable) {
        if (executable == null) {
            return false;
        }
        try {
            return Files.isSameFile(installation.executable(), executable);
        } catch (IOException | SecurityException ignored) {
            return installation.executable().toAbsolutePath().normalize()
                .equals(executable.toAbsolutePath().normalize());
        }
    }

    private static boolean hasValidIntegrity(LlamaRuntimeInstallation installation) {
        try {
            LlamaRuntimePackageIntegrity.verifyInstallation(installation);
            return true;
        } catch (IOException ignored) {
            return false;
        }
    }

    private Optional<LlamaRuntimeInstallation> readInstallation(String installationId) throws IOException {
        if (installationId == null || !installationId.matches("[A-Za-z0-9._-]+")) {
            return Optional.empty();
        }
        Path directory = packagesDirectory.resolve(installationId).normalize();
        if (!directory.startsWith(packagesDirectory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        Path metadata = directory.resolve(DESCRIPTOR_FILE);
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS) || Files.size(metadata) > 64 * 1024) {
            return Optional.empty();
        }
        JsonObject json;
        try {
            json = JsonParser.parseString(Files.readString(metadata, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (RuntimeException e) {
            throw new IOException("Installed runtime descriptor is malformed: " + directory, e);
        }
        LlamaRuntimePackageDescriptor descriptor = codec.parseDescriptor(json);
        if (!descriptor.installationId().equals(installationId)) {
            throw new IOException("Installed runtime descriptor does not match its directory.");
        }
        Path executable = descriptor.resolveEntrypoint(directory);
        if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        return Optional.of(new LlamaRuntimeInstallation(descriptor, directory, executable));
    }

    private LlamaRuntimeInstallation requireInstallation(Path directory) throws IOException {
        return readInstallation(directory.getFileName().toString())
            .orElseThrow(() -> new IOException("Installed runtime did not pass validation."));
    }

    private void restoreActive(Optional<LlamaRuntimeInstallation> previous) throws IOException {
        if (previous.isPresent()
            && !isRevoked(previous.get().descriptor(), readRevocations())
            && !LlamaRuntimeTrustGuard.isRevoked(previous.get().executable())) {
            writePointer(ACTIVE_FILE, previous.get().descriptor().installationId());
            Files.deleteIfExists(runtimeRoot.resolve(BLOCKED_ACTIVE_FILE));
        } else {
            Files.deleteIfExists(runtimeRoot.resolve(ACTIVE_FILE));
        }
    }

    private void writePointer(String fileName, String value) throws IOException {
        Path target = runtimeRoot.resolve(fileName);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(
            partial, value + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void recordHealthy(String installationId, LlamaRuntimeInstallation previous) throws IOException {
        Set<String> revoked = readRevocations();
        LinkedHashSet<String> history = new LinkedHashSet<>();
        history.add(installationId);
        if (previous != null && !isRevoked(previous.descriptor(), revoked)
            && !LlamaRuntimeTrustGuard.isRevoked(previous.executable())) {
            history.add(previous.descriptor().installationId());
        }
        for (String candidateId : readHistory()) {
            Optional<LlamaRuntimeInstallation> candidate = readInstallation(candidateId);
            if (candidate.isPresent() && !isRevoked(candidate.get().descriptor(), revoked)
                && !LlamaRuntimeTrustGuard.isRevoked(candidate.get().executable())) {
                history.add(candidateId);
            }
        }
        writePointer(HISTORY_FILE, String.join(System.lineSeparator(), history));
        Files.deleteIfExists(runtimeRoot.resolve(BLOCKED_ACTIVE_FILE));
    }

    private List<String> readHistory() throws IOException {
        Path file = runtimeRoot.resolve(HISTORY_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return List.of();
        }
        return Files.readAllLines(file, StandardCharsets.UTF_8).stream()
            .map(String::trim).filter(value -> value.matches("[A-Za-z0-9._-]+"))
            .distinct().toList();
    }

    private void cleanupOldInstallations() throws IOException {
        List<String> history = readHistory();
        Set<String> keep = new LinkedHashSet<>(history.stream()
            .limit(RETAIN_HEALTHY_INSTALLATIONS).toList());
        Optional<LlamaRuntimeInstallation> active = activeLocked();
        active.ifPresent(value -> keep.add(value.descriptor().installationId()));
        if (!Files.isDirectory(packagesDirectory)) {
            return;
        }
        try (var stream = Files.list(packagesDirectory)) {
            for (Path candidate : stream.filter(Files::isDirectory).toList()) {
                if (!candidate.getFileName().toString().startsWith(".installing-")
                    && !keep.contains(candidate.getFileName().toString())) {
                    deleteTreeQuietly(candidate);
                }
            }
        }
        if (history.size() > RETAIN_HEALTHY_INSTALLATIONS) {
            writePointer(HISTORY_FILE, String.join(
                System.lineSeparator(), history.subList(0, RETAIN_HEALTHY_INSTALLATIONS)));
        }
    }

    private Set<String> readRevocations() throws IOException {
        Path file = runtimeRoot.resolve(LlamaRuntimeTrustGuard.REVOCATION_LIST_FILE);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            return Set.of();
        }
        if (Files.size(file) > 256 * 1024) {
            throw new IOException("llama.cpp runtime revocation list is unexpectedly large.");
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String value = line.trim();
            if (isSafeIdentifier(value)) {
                result.add(value);
            }
        }
        return result;
    }

    private void writeRevocations(Set<String> revoked) throws IOException {
        List<String> values = revoked.stream().filter(LlamaRuntimePackageInstaller::isSafeIdentifier)
            .sorted().toList();
        if (values.isEmpty()) {
            return;
        }
        writePointer(LlamaRuntimeTrustGuard.REVOCATION_LIST_FILE,
            String.join(System.lineSeparator(), values));
    }

    private void writeRevocationMarker(LlamaRuntimeInstallation installation) throws IOException {
        Path marker = installation.directory().resolve(LlamaRuntimeTrustGuard.REVOCATION_MARKER_FILE);
        Files.writeString(marker,
            installation.descriptor().runtimeId() + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    private void pruneRevokedHistory(Set<String> revoked) throws IOException {
        List<String> allowed = new ArrayList<>();
        for (String candidateId : readHistory()) {
            Optional<LlamaRuntimeInstallation> candidate = readInstallation(candidateId);
            if (candidate.isPresent() && !isRevoked(candidate.get().descriptor(), revoked)
                && !LlamaRuntimeTrustGuard.isRevoked(candidate.get().executable())) {
                allowed.add(candidateId);
            }
        }
        if (allowed.isEmpty()) {
            Files.deleteIfExists(runtimeRoot.resolve(HISTORY_FILE));
        } else {
            writePointer(HISTORY_FILE, String.join(System.lineSeparator(), allowed));
        }
    }

    private static void ensureNotRevoked(
        LlamaRuntimePackageDescriptor descriptor,
        Set<String> revoked
    ) throws IOException {
        if (isRevoked(descriptor, revoked)) {
            throw new IOException("Refusing to install a revoked llama.cpp runtime package.");
        }
    }

    private static boolean isRevoked(LlamaRuntimePackageDescriptor descriptor, Set<String> revoked) {
        return descriptor.revoked() || revoked.contains(descriptor.runtimeId())
            || revoked.contains(descriptor.installationId());
    }

    private static boolean isSafeIdentifier(String value) {
        return value != null && value.matches("[A-Za-z0-9._-]+");
    }

    public record PendingActivation(
        LlamaRuntimeInstallation installation,
        LlamaRuntimeInstallation previousInstallation
    ) {
        public PendingActivation {
            Objects.requireNonNull(installation, "installation");
        }
    }

    public record PendingRollback(
        LlamaRuntimeInstallation failedInstallation,
        LlamaRuntimeInstallation restoredInstallation
    ) {
        public PendingRollback {
            Objects.requireNonNull(failedInstallation, "failedInstallation");
        }
    }

    private void deleteInstallationQuietly(LlamaRuntimeInstallation installation) {
        deleteTreeQuietly(installation.directory());
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((first, second) -> second.compareTo(first)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteTreeQuietly(Path root) {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((first, second) -> second.compareTo(first)).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Stale staging directories are harmless and can be cleaned on a later update.
        }
    }

    private static void makeExecutable(Path executable) throws IOException {
        if (LlamaRuntimePlatform.current() == LlamaRuntimePlatform.WINDOWS) {
            return;
        }
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(executable);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(executable, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!executable.toFile().setExecutable(true, true)) {
                throw new IOException("Could not mark llama-server executable.");
            }
        }
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
    }

    private static final class HttpPackageContentProvider implements PackageContentProvider {
        private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        @Override
        public InputStream open(URI uri) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(30))
                .header("Accept", "application/zip")
                .header("User-Agent", "korTTY-llama-runtime-updater")
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("Runtime package download failed with HTTP " + response.statusCode() + ".");
            }
            return response.body();
        }
    }
}
