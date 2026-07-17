package de.kortty.ai.mlx;

import de.kortty.ai.mlx.MlxRuntimeLocator.MlxRuntimeInstallation;
import de.kortty.update.UpdateVersion;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Signature-verified installer for the embedded MLX runtime below {@code <llmDir>/mlx/runtime}.
 *
 * <p>Every installation path — local archive or channel download — is gated by the Ed25519-signed
 * {@code mlx-runtime-index-v1.json}: only an archive whose exact SHA-256 is published by a
 * non-revoked macOS/arm64 entry of the verified index is ever extracted. Extraction uses the same
 * hardening as the llama.cpp installer (entry cap, traversal rejection, expansion limit), and the
 * active pointer only switches after a bounded sanity launch of the packaged interpreter while all
 * MLX sidecars are stopped.
 */
public final class MlxRuntimePackageInstaller {

    private static final int COPY_BUFFER_SIZE = 128 * 1024;
    private static final int MAX_ZIP_ENTRIES = 60_000;
    private static final long MAX_EXTRACTED_BYTES = 8L * 1024 * 1024 * 1024;
    private static final Duration SANITY_LAUNCH_TIMEOUT = Duration.ofSeconds(90);
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_UPDATE_LOCKS = new ConcurrentHashMap<>();

    @FunctionalInterface
    public interface IndexProvider {
        MlxRuntimeIndex fetch() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    public interface PackageContentProvider {
        InputStream open(URI uri) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface SanityCheck {
        void verify(Path packageDirectory, Path pythonExecutable) throws IOException, InterruptedException;
    }

    /** Signals a safe, retryable delay while a local MLX request is still running. */
    public static final class MlxRuntimeBusyException extends IOException {
        public MlxRuntimeBusyException(String message) {
            super(message);
        }
    }

    private final Path runtimeRoot;
    private final Path packagesDirectory;
    private final Path downloadsDirectory;
    private final MlxRuntimeLocator locator;
    private final IndexProvider indexProvider;
    private final PackageContentProvider contentProvider;
    private final SanityCheck sanityCheck;
    private final BooleanSupplier platformSupported;
    private final ReentrantLock jvmUpdateLock;

    /** Standard layout below {@code <llmDir>/mlx/runtime} with the pinned signed release channel. */
    public MlxRuntimePackageInstaller(Path llmDirectory) {
        this(llmDirectory.resolve("mlx").resolve("runtime"),
            () -> {
                MlxRuntimeReleaseConfiguration configuration = MlxRuntimeReleaseConfiguration.loadDefault();
                return new MlxRuntimeIndexClient(
                    configuration.stableIndexUri(),
                    configuration.stableSignatureUri(),
                    configuration.requireTrustedPublicKey()).fetch();
            },
            new HttpPackageContentProvider(),
            MlxRuntimePackageInstaller::defaultSanityLaunch,
            MlxPlatform::isSupported);
    }

    /** Returns the installer for the application-wide {@code ~/.kortty/llm} data directory. */
    public static MlxRuntimePackageInstaller createDefault() {
        return new MlxRuntimePackageInstaller(
            Path.of(System.getProperty("user.home"), ".kortty", "llm"));
    }

    MlxRuntimePackageInstaller(
        Path runtimeRoot,
        IndexProvider indexProvider,
        PackageContentProvider contentProvider,
        SanityCheck sanityCheck,
        BooleanSupplier platformSupported
    ) {
        this.runtimeRoot = Objects.requireNonNull(runtimeRoot, "runtimeRoot").toAbsolutePath().normalize();
        this.packagesDirectory = this.runtimeRoot.resolve(MlxRuntimeLocator.PACKAGES_DIRECTORY);
        this.downloadsDirectory = this.runtimeRoot.resolve("downloads");
        this.locator = new MlxRuntimeLocator(this.runtimeRoot);
        this.indexProvider = Objects.requireNonNull(indexProvider, "indexProvider");
        this.contentProvider = Objects.requireNonNull(contentProvider, "contentProvider");
        this.sanityCheck = Objects.requireNonNull(sanityCheck, "sanityCheck");
        this.platformSupported = Objects.requireNonNull(platformSupported, "platformSupported");
        this.jvmUpdateLock = JVM_UPDATE_LOCKS.computeIfAbsent(this.runtimeRoot, ignored -> new ReentrantLock());
    }

    /** Currently active, layout-validated MLX runtime installation, if any. */
    public Optional<MlxRuntimeInstallation> active() {
        return locator.locateActive();
    }

    /**
     * Removes the active pointer and every installed MLX runtime package. Refuses while any MLX
     * sidecar is processing a request; idle sidecars are stopped first. Registered MLX models keep
     * their catalog entries and simply require a runtime reinstall before the next use.
     */
    public void uninstall(MlxRuntimeManager manager) throws IOException {
        Objects.requireNonNull(manager, "manager");
        withUpdateLock(() -> {
            stopAllSidecars(manager);
            Files.deleteIfExists(runtimeRoot.resolve(MlxRuntimeLocator.ACTIVE_POINTER_FILE));
            deleteInstalledPackages(null);
            deleteTreeQuietly(downloadsDirectory);
            return null;
        });
    }

    /**
     * Installs a locally provided zip archive, but only when its exact SHA-256 is published by a
     * non-revoked macOS/arm64 entry of the Ed25519-verified MLX stable index.
     */
    public MlxRuntimeInstallation installFromLocalPackage(MlxRuntimeManager manager, Path zipArchive)
        throws IOException, InterruptedException {
        Objects.requireNonNull(manager, "manager");
        Objects.requireNonNull(zipArchive, "zipArchive");
        requireSupportedPlatform();
        if (!Files.isRegularFile(zipArchive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The selected MLX runtime archive does not exist.");
        }
        MlxRuntimeIndex index = indexProvider.fetch();
        String archiveSha256 = sha256(zipArchive);
        MlxRuntimePackageDescriptor entry = index.packages().stream()
            .filter(MlxRuntimePackageDescriptor::matchesCurrentPlatform)
            .filter(descriptor -> descriptor.sha256().equals(archiveSha256))
            .findFirst()
            .orElseThrow(() -> new IOException(
                "This archive was not published by the signed stable MLX channel and cannot be installed."));
        if (index.isRevoked(entry)) {
            throw new IOException(
                "This MLX runtime package was revoked by the signed stable channel and cannot be installed.");
        }
        requireOsVersion(entry);
        if (Files.size(zipArchive) != entry.sizeBytes()) {
            throw new IOException("The archive size does not match its signed MLX package entry.");
        }
        return install(manager, entry, zipArchive);
    }

    /**
     * Downloads and installs the newest non-revoked macOS/arm64 package of the Ed25519-verified
     * MLX stable index. Backs the explicit "Install runtime" action.
     */
    public MlxRuntimeInstallation installFromIndex(MlxRuntimeManager manager)
        throws IOException, InterruptedException {
        Objects.requireNonNull(manager, "manager");
        requireSupportedPlatform();
        MlxRuntimeIndex index = indexProvider.fetch();
        MlxRuntimePackageDescriptor entry = index.packages().stream()
            .filter(MlxRuntimePackageDescriptor::matchesCurrentPlatform)
            .filter(descriptor -> !index.isRevoked(descriptor))
            .max(Comparator.comparingLong(MlxRuntimePackageInstaller::versionSortKey)
                .thenComparing(MlxRuntimePackageDescriptor::runtimeId))
            .orElseThrow(() -> new IOException(
                "The signed stable MLX channel contains no compatible runtime package."));
        requireOsVersion(entry);
        Files.createDirectories(downloadsDirectory);
        Path partial = downloadsDirectory.resolve(entry.installationId() + "-" + UUID.randomUUID() + ".zip.part");
        try {
            downloadAndVerify(entry, partial);
            return install(manager, entry, partial);
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    private MlxRuntimeInstallation install(
        MlxRuntimeManager manager,
        MlxRuntimePackageDescriptor entry,
        Path archive
    ) throws IOException {
        return withUpdateLock(() -> {
            // No sidecar may keep the previous interpreter or launcher loaded across the switch.
            stopAllSidecars(manager);
            Files.createDirectories(packagesDirectory);
            Path staging = packagesDirectory.resolve(".installing-" + entry.installationId() + "-" + UUID.randomUUID());
            try {
                Files.createDirectories(staging);
                extractZip(archive, staging, entry.sizeBytes());
                Path payload = payloadRoot(staging);
                if (!payload.equals(staging)) {
                    // The stable channel wraps the package content in its installation-id
                    // directory; a single wrapper directory is transparent to the layout.
                    Path unwrapped = staging.resolveSibling(staging.getFileName() + "-payload");
                    Files.move(payload, unwrapped);
                    deleteTree(staging);
                    Files.move(unwrapped, staging);
                }
                Path executable = entry.resolveExecutable(staging);
                if (!Files.isRegularFile(executable, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("The MLX runtime package does not contain " + entry.executablePath() + ".");
                }
                makeExecutable(executable);
                Path launcher = entry.resolveLauncher(staging);
                if (!Files.isRegularFile(launcher, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("The MLX runtime package does not contain " + entry.launcherPath() + ".");
                }
                // Bounded, sandboxed interpreter launch before the package can ever become active.
                try {
                    sanityCheck.verify(staging, executable);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while validating the MLX runtime package.", e);
                }

                Path destination = packagesDirectory.resolve(entry.installationId());
                deleteTree(destination);
                try {
                    Files.move(staging, destination, StandardCopyOption.ATOMIC_MOVE);
                } catch (AtomicMoveNotSupportedException ignored) {
                    Files.move(staging, destination);
                }
                writePointer(entry.installationId());
                MlxRuntimeInstallation installation = locator.locateActive()
                    .filter(active -> active.id().equals(entry.installationId()))
                    .orElseThrow(() -> new IOException(
                        "The installed MLX runtime did not pass the layout validation."));
                deleteInstalledPackages(entry.installationId());
                return installation;
            } finally {
                deleteTreeQuietly(staging);
            }
        });
    }

    private void requireSupportedPlatform() throws IOException {
        if (!platformSupported.getAsBoolean()) {
            throw new IOException("The embedded MLX runtime is available only on Apple-Silicon macOS.");
        }
    }

    private static void requireOsVersion(MlxRuntimePackageDescriptor entry) throws IOException {
        // A non-semver os.version (never the case on macOS) skips the gate instead of blocking the
        // install; the platform gate above already restricts MLX to macOS in production.
        Optional<UpdateVersion> current = UpdateVersion.parse(System.getProperty("os.version"));
        Optional<UpdateVersion> minimum = UpdateVersion.parse(entry.minimumOsVersion());
        if (current.isPresent() && minimum.isPresent() && current.get().compareTo(minimum.get()) < 0) {
            throw new IOException("This MLX runtime package requires macOS "
                + entry.minimumOsVersion() + " or newer.");
        }
    }

    private void stopAllSidecars(MlxRuntimeManager manager) throws IOException {
        for (String modelId : manager.statuses().keySet()) {
            if (!manager.stop(modelId)) {
                throw new MlxRuntimeBusyException(
                    "A local MLX model is processing a request. Retry after all local AI requests finish.");
            }
        }
    }

    private <T> T withUpdateLock(LockedOperation<T> operation) throws IOException {
        Files.createDirectories(runtimeRoot);
        jvmUpdateLock.lock();
        try {
            try (FileChannel channel = FileChannel.open(
                    runtimeRoot.resolve("update.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.run();
            }
        } finally {
            jvmUpdateLock.unlock();
        }
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    private void writePointer(String installationId) throws IOException {
        Path target = runtimeRoot.resolve(MlxRuntimeLocator.ACTIVE_POINTER_FILE);
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Files.writeString(
            partial, installationId + System.lineSeparator(), StandardCharsets.UTF_8,
            StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void deleteInstalledPackages(String keepInstallationId) throws IOException {
        if (!Files.isDirectory(packagesDirectory)) {
            return;
        }
        try (var stream = Files.list(packagesDirectory)) {
            for (Path candidate : stream.toList()) {
                String name = candidate.getFileName().toString();
                if (!name.equals(keepInstallationId) && !name.startsWith(".installing-")) {
                    deleteTree(candidate);
                }
            }
        }
    }

    private void downloadAndVerify(MlxRuntimePackageDescriptor entry, Path partial)
        throws IOException, InterruptedException {
        MessageDigest digest = sha256Digest();
        long total = 0;
        try (InputStream input = new BufferedInputStream(contentProvider.open(entry.downloadUri()));
             OutputStream output = new BufferedOutputStream(Files.newOutputStream(
                 partial, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE))) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            while (true) {
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                total = Math.addExact(total, count);
                if (total > entry.sizeBytes()) {
                    throw new IOException("The MLX runtime package exceeds its signed size.");
                }
                digest.update(buffer, 0, count);
                output.write(buffer, 0, count);
            }
        }
        if (total != entry.sizeBytes()) {
            throw new IOException("The MLX runtime package size does not match its signed entry.");
        }
        String actual = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        if (!entry.sha256().equals(actual)) {
            throw new IOException("The MLX runtime package SHA-256 does not match its signed entry.");
        }
    }

    /**
     * Returns the effective package root inside an extracted archive: the staging directory
     * itself for flat archives, or the single top-level directory when the archive wraps its
     * whole content (the stable channel packages under the installation id).
     */
    private static Path payloadRoot(Path staging) throws IOException {
        List<Path> entries;
        try (var children = Files.list(staging)) {
            entries = children.toList();
        }
        if (entries.size() == 1 && Files.isDirectory(entries.getFirst(), LinkOption.NOFOLLOW_LINKS)) {
            return entries.getFirst();
        }
        return staging;
    }

    // Same hardening as LlamaRuntimePackageInstaller.extractZip: entry cap, traversal rejection
    // and a bounded expansion factor against decompression bombs.
    private static void extractZip(Path archive, Path destination, long compressedBytes) throws IOException {
        int entries = 0;
        long extractedBytes = 0;
        long expansionLimit;
        try {
            expansionLimit = Math.min(MAX_EXTRACTED_BYTES,
                Math.max(512L * 1024 * 1024, Math.multiplyExact(compressedBytes, 30)));
        } catch (ArithmeticException ignored) {
            expansionLimit = MAX_EXTRACTED_BYTES;
        }
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(Files.newInputStream(archive)))) {
            ZipEntry entry;
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > MAX_ZIP_ENTRIES) {
                    throw new IOException("The MLX runtime package contains too many entries.");
                }
                String name = entry.getName().replace('\\', '/');
                if (name.isBlank() || name.indexOf('\0') >= 0) {
                    throw new IOException("The MLX runtime package contains an invalid path.");
                }
                Path target = destination.resolve(name).normalize();
                if (!target.startsWith(destination) || target.equals(destination)) {
                    throw new IOException(
                        "The MLX runtime package attempts to write outside its installation directory.");
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
                            throw new IOException("The MLX runtime package exceeds the safe extraction limit.");
                        }
                        output.write(buffer, 0, count);
                    }
                }
            }
        } catch (ArithmeticException e) {
            throw new IOException("The MLX runtime package extracted size overflowed.", e);
        }
    }

    private static void makeExecutable(Path executable) throws IOException {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(executable);
            permissions.add(PosixFilePermission.OWNER_EXECUTE);
            Files.setPosixFilePermissions(executable, permissions);
        } catch (UnsupportedOperationException ignored) {
            if (!executable.toFile().setExecutable(true, true)) {
                throw new IOException("Could not mark the MLX runtime interpreter executable.");
            }
        }
    }

    private static long versionSortKey(MlxRuntimePackageDescriptor descriptor) {
        // Newest mlx-lm version wins; unparsable versions sort last but stay installable when they
        // are the only entry. Ties fall back to the runtime id comparison of the caller.
        String[] parts = descriptor.mlxLmVersion().split("[.+-]");
        long key = 0;
        boolean numeric = false;
        for (int i = 0; i < 3; i++) {
            long part = 0;
            if (parts.length > i && parts[i].matches("[0-9]{1,6}")) {
                part = Long.parseLong(parts[i]);
                numeric = true;
            }
            key = key * 1_000_000L + part;
        }
        return numeric ? key : -1L;
    }

    /**
     * Bounded sanity launch of the packaged interpreter with the same sanitized environment policy
     * as the MLX sidecar: the pinned interpreter must be able to import the pinned mlx-lm.
     */
    private static void defaultSanityLaunch(Path packageDirectory, Path pythonExecutable)
        throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
            pythonExecutable.toString(), "-c", "import mlx_lm; print(mlx_lm.__version__)");
        builder.directory(packageDirectory.toFile());
        builder.redirectErrorStream(true);
        Map<String, String> environment = new HashMap<>(System.getenv());
        MlxRuntimeManager.sanitizeEnvironment(environment);
        builder.environment().clear();
        builder.environment().putAll(environment);
        Process process = builder.start();
        try {
            if (!process.waitFor(SANITY_LAUNCH_TIMEOUT.toSeconds(), TimeUnit.SECONDS)) {
                throw new IOException("The MLX runtime sanity check timed out.");
            }
            String output;
            try (InputStream stdout = process.getInputStream()) {
                // The exited process's pipe holds at most the OS pipe buffer; read a bounded tail.
                output = new String(stdout.readNBytes(16 * 1024), StandardCharsets.UTF_8).trim();
            }
            if (process.exitValue() != 0) {
                throw new IOException("The MLX runtime sanity check failed"
                    + (output.isBlank() ? "." : ": " + output));
            }
        } finally {
            process.destroyForcibly();
        }
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest = sha256Digest();
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private static MessageDigest sha256Digest() throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void deleteTreeQuietly(Path root) {
        try {
            deleteTree(root);
        } catch (IOException ignored) {
            // Stale download or staging leftovers are harmless and cleaned on a later install.
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
                .header("User-Agent", "korTTY-mlx-runtime-updater")
                .GET()
                .build();
            HttpResponse<InputStream> response = client.send(
                request, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                response.body().close();
                throw new IOException("The MLX runtime package download failed with HTTP "
                    + response.statusCode() + ".");
            }
            return response.body();
        }
    }
}
