package de.kortty.ai.huggingface;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;

/** Resumable, checksum-verifying downloader for single and multipart GGUF models. */
public final class HuggingFaceModelDownloader {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(15);
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofHours(6);
    private static final int MAX_REDIRECTS = 5;
    private static final int BUFFER_SIZE = 128 * 1024;
    private static final Consumer<HuggingFaceDownloadProgress> NO_PROGRESS = ignored -> { };
    private static final ConcurrentHashMap<Path, ReentrantLock> JVM_DOWNLOAD_LOCKS =
        new ConcurrentHashMap<>();

    private final HttpClient httpClient;
    private final HuggingFaceTokenProvider tokenProvider;
    private final Executor executor;
    private final VerifiedFileActivator fileActivator;

    public HuggingFaceModelDownloader(HuggingFaceTokenProvider tokenProvider) {
        this(HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build(), tokenProvider, ForkJoinPool.commonPool());
    }

    public HuggingFaceModelDownloader(
        HttpClient httpClient,
        HuggingFaceTokenProvider tokenProvider,
        Executor executor
    ) {
        this(httpClient, tokenProvider, executor, HuggingFaceModelDownloader::atomicReplace);
    }

    HuggingFaceModelDownloader(
        HttpClient httpClient,
        HuggingFaceTokenProvider tokenProvider,
        Executor executor,
        VerifiedFileActivator fileActivator
    ) {
        this.httpClient = Objects.requireNonNull(httpClient, "httpClient");
        this.tokenProvider = Objects.requireNonNull(tokenProvider, "tokenProvider");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.fileActivator = Objects.requireNonNull(fileActivator, "fileActivator");
    }

    public HuggingFaceDownloadTask downloadAsync(
        HuggingFaceDownloadPlan plan,
        Consumer<HuggingFaceDownloadProgress> progressListener
    ) {
        HuggingFaceDownloadController controller = new HuggingFaceDownloadController();
        CompletableFuture<HuggingFaceDownloadResult> completion = CompletableFuture.supplyAsync(() -> {
            try {
                return download(plan, controller, progressListener);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new CompletionException(e);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, executor);
        return new HuggingFaceDownloadTask(controller, completion);
    }

    public HuggingFaceDownloadResult download(
        HuggingFaceDownloadPlan plan,
        HuggingFaceDownloadController controller,
        Consumer<HuggingFaceDownloadProgress> progressListener
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(controller, "controller");
        Consumer<HuggingFaceDownloadProgress> progress = progressListener == null
            ? NO_PROGRESS : progressListener;
        Instant started = Instant.now();
        Files.createDirectories(plan.targetDirectory());
        Path root = plan.targetDirectory().toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("Model download directory must not be a symbolic link.");
        }

        try (DownloadLock ignored = acquireDownloadLock(root, plan, controller)) {
            long totalBytes = plan.totalBytes();
            progress.accept(snapshot(
                HuggingFaceDownloadProgress.Phase.CHECKING_SPACE,
                null, 0, plan.files().size(), 0, totalBytes, 0, null));
            checkDiskSpace(root, remainingBytes(plan, root));

            List<Path> installed = new ArrayList<>();
            long completedBytes = 0;
            long downloadedBytes = 0;
            long resumedBytes = 0;
            for (int index = 0; index < plan.files().size(); index++) {
                HuggingFaceModelFile file = plan.files().get(index);
                if (controller.isCancelled()) {
                    progress.accept(snapshot(
                        HuggingFaceDownloadProgress.Phase.CANCELLED,
                        file.path(), index + 1, plan.files().size(), completedBytes, totalBytes, 0, null));
                    throw new CancellationException("GGUF download cancelled.");
                }
                Path target = safeTarget(root, file.path());
                rejectSymlinkPath(root, target);
                Files.createDirectories(target.getParent());
                rejectSymlinkPath(root, target);

                if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                    && Files.size(target) == file.size()
                    && file.sha256().equals(sha256(target))) {
                    completedBytes += file.size();
                    installed.add(target);
                    progress.accept(snapshot(
                        HuggingFaceDownloadProgress.Phase.COMPLETE,
                        file.path(), index + 1, plan.files().size(), completedBytes, totalBytes, 0, Duration.ZERO));
                    continue;
                }

                TransferResult transfer = downloadFile(
                    plan,
                    file,
                    target,
                    index,
                    completedBytes,
                    totalBytes,
                    controller,
                    progress,
                    started);
                completedBytes += file.size();
                downloadedBytes += transfer.downloadedBytes();
                resumedBytes += transfer.resumedBytes();
                installed.add(target);
            }
            progress.accept(snapshot(
                HuggingFaceDownloadProgress.Phase.COMPLETE,
                installed.isEmpty() ? null : installed.get(installed.size() - 1).getFileName().toString(),
                installed.size(), installed.size(), totalBytes, totalBytes, 0, Duration.ZERO));
            return new HuggingFaceDownloadResult(
                installed,
                downloadedBytes,
                resumedBytes,
                Duration.between(started, Instant.now()));
        }
    }

    private TransferResult downloadFile(
        HuggingFaceDownloadPlan plan,
        HuggingFaceModelFile file,
        Path target,
        int fileIndex,
        long completedBeforeFile,
        long totalBytes,
        HuggingFaceDownloadController controller,
        Consumer<HuggingFaceDownloadProgress> progress,
        Instant started
    ) throws IOException, InterruptedException {
        Path partial = target.resolveSibling(target.getFileName() + ".part");
        Path metadata = target.resolveSibling(target.getFileName() + ".part.meta");
        if (Files.isSymbolicLink(partial) || Files.isSymbolicLink(metadata)) {
            throw new IOException("GGUF resume files must not be symbolic links.");
        }
        ResumeMetadata resume = readResumeMetadata(metadata);
        long existing = Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS) ? Files.size(partial) : 0;
        if (existing < 0 || existing > file.size() || !resume.matches(plan, file)) {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(metadata);
            existing = 0;
            resume = ResumeMetadata.empty();
        }

        if (existing == file.size()) {
            return verifyAndActivate(
                file, target, partial, metadata, existing, 0,
                fileIndex, plan.files().size(), completedBeforeFile, totalBytes, progress);
        }

        long requestedOffset = existing;
        requireSecureDownloadUri(file.downloadUri());
        HttpResponse<InputStream> response = sendFollowingRedirects(
            file.downloadUri(), requestedOffset, resume.etag());
        int status = response.statusCode();
        if (status == 416 && existing == file.size()) {
            response.body().close();
            return verifyAndActivate(
                file, target, partial, metadata, existing, 0,
                fileIndex, plan.files().size(), completedBeforeFile, totalBytes, progress);
        }
        if (status < 200 || status >= 300) {
            response.body().close();
            throw new IOException("GGUF download failed with HTTP " + status + " for " + file.path() + ".");
        }

        boolean append = existing > 0 && status == 206;
        if (append) {
            validateContentRange(response, existing, file.size());
        } else if (existing > 0) {
            existing = 0;
        }
        String etag = response.headers().firstValue("ETag").orElse(resume.etag());
        writeResumeMetadata(metadata, ResumeMetadata.forFile(plan, file, etag));

        long downloadedThisRun = 0;
        long offset = existing;
        StandardOpenOption[] options = append
            ? new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND}
            : new StandardOpenOption[] {StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING};
        try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(partial, options)) {
            byte[] buffer = new byte[BUFFER_SIZE];
            while (true) {
                if (controller.isPaused()) {
                    progress.accept(progressForTransfer(
                        HuggingFaceDownloadProgress.Phase.PAUSED,
                        file,
                        fileIndex,
                        plan.files().size(),
                        completedBeforeFile + offset,
                        totalBytes,
                        downloadedThisRun,
                        started));
                }
                if (!controller.awaitPermission()) {
                    progress.accept(snapshot(
                        HuggingFaceDownloadProgress.Phase.CANCELLED,
                        file.path(), fileIndex + 1, plan.files().size(),
                        completedBeforeFile + offset, totalBytes, 0, null));
                    throw new CancellationException("GGUF download cancelled.");
                }
                int count = input.read(buffer);
                if (count < 0) {
                    break;
                }
                output.write(buffer, 0, count);
                offset += count;
                downloadedThisRun += count;
                if (offset > file.size()) {
                    throw new IOException("GGUF response exceeded the advertised file size for " + file.path() + ".");
                }
                progress.accept(progressForTransfer(
                    HuggingFaceDownloadProgress.Phase.DOWNLOADING,
                    file,
                    fileIndex,
                    plan.files().size(),
                    completedBeforeFile + offset,
                    totalBytes,
                    downloadedThisRun,
                    started));
            }
        }
        if (offset != file.size()) {
            throw new IOException(
                "Incomplete GGUF download for " + file.path() + ": expected " + file.size() + ", got " + offset + ".");
        }
        return verifyAndActivate(
            file, target, partial, metadata, append ? requestedOffset : 0, downloadedThisRun,
            fileIndex, plan.files().size(), completedBeforeFile, totalBytes, progress);
    }

    private TransferResult verifyAndActivate(
        HuggingFaceModelFile file,
        Path target,
        Path partial,
        Path metadata,
        long resumedBytes,
        long downloadedBytes,
        int fileIndex,
        int fileCount,
        long completedBeforeFile,
        long totalBytes,
        Consumer<HuggingFaceDownloadProgress> progress
    ) throws IOException {
        progress.accept(snapshot(
            HuggingFaceDownloadProgress.Phase.VERIFYING,
            file.path(), fileIndex + 1, fileCount,
            completedBeforeFile + file.size(), totalBytes, 0, null));
        String actual = sha256(partial);
        if (!file.sha256().equals(actual)) {
            Files.deleteIfExists(partial);
            Files.deleteIfExists(metadata);
            throw new IOException("SHA-256 mismatch for " + file.path() + ".");
        }
        fileActivator.activate(partial, target);
        Files.deleteIfExists(metadata);
        progress.accept(snapshot(
            HuggingFaceDownloadProgress.Phase.COMPLETE,
            file.path(), fileIndex + 1, fileCount,
            completedBeforeFile + file.size(), totalBytes, 0, Duration.ZERO));
        return new TransferResult(downloadedBytes, resumedBytes);
    }

    private static DownloadLock acquireDownloadLock(
        Path root,
        HuggingFaceDownloadPlan plan,
        HuggingFaceDownloadController controller
    ) throws IOException, InterruptedException {
        String identity = plan.modelId() + "\n" + plan.revision();
        Path lockPath = root.resolve(".kortty-download-" + sha256(identity) + ".lock");
        if (Files.isSymbolicLink(lockPath)) {
            throw new IOException("GGUF model download lock must not be a symbolic link.");
        }
        ReentrantLock jvmLock = JVM_DOWNLOAD_LOCKS.computeIfAbsent(lockPath, ignored -> new ReentrantLock(true));
        while (!jvmLock.tryLock(100, TimeUnit.MILLISECONDS)) {
            if (controller.isCancelled()) {
                throw new CancellationException("GGUF download cancelled while waiting for another installer.");
            }
        }
        FileChannel channel = null;
        try {
            channel = FileChannel.open(
                lockPath,
                StandardOpenOption.CREATE,
                StandardOpenOption.WRITE,
                LinkOption.NOFOLLOW_LINKS);
            while (true) {
                if (controller.isCancelled()) {
                    throw new CancellationException("GGUF download cancelled while waiting for another installer.");
                }
                try {
                    FileLock fileLock = channel.tryLock();
                    if (fileLock != null) {
                        return new DownloadLock(lockPath, jvmLock, channel, fileLock);
                    }
                } catch (OverlappingFileLockException ignored) {
                    // Another downloader in this JVM may own the same OS-level lock through a
                    // separately opened channel. Polling also makes cancellation responsive.
                }
                Thread.sleep(100L);
            }
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (channel != null) {
                try {
                    channel.close();
                } catch (IOException closeFailure) {
                    e.addSuppressed(closeFailure);
                }
            }
            jvmLock.unlock();
            throw e;
        }
    }

    private static void atomicReplace(Path verifiedPartial, Path target) throws IOException {
        try {
            Files.move(
                verifiedPartial,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            // Both paths are deliberately siblings. If that filesystem cannot replace atomically,
            // retain the verified .part and, critically, leave the previous working GGUF untouched.
            throw new IOException(
                "The model filesystem does not support safe atomic GGUF activation; the previous model was preserved.",
                e);
        }
    }

    private HttpResponse<InputStream> sendFollowingRedirects(
        URI initialUri,
        long offset,
        String etag
    ) throws IOException, InterruptedException {
        URI current = initialUri;
        for (int redirects = 0; redirects <= MAX_REDIRECTS; redirects++) {
            HttpRequest.Builder request = HttpRequest.newBuilder(current)
                .timeout(DOWNLOAD_TIMEOUT)
                .header("Accept", "application/octet-stream")
                .header("User-Agent", "korTTY-GGUF-Downloader")
                .GET();
            if (offset > 0) {
                request.header("Range", "bytes=" + offset + "-");
                if (etag != null && !etag.isBlank()) {
                    request.header("If-Range", etag);
                }
            }
            if (isHuggingFaceHost(current.getHost())) {
                tokenProvider.token().filter(token -> !token.isBlank())
                    .ifPresent(token -> request.header("Authorization", "Bearer " + token));
            }
            HttpResponse<InputStream> response = httpClient.send(
                request.build(), HttpResponse.BodyHandlers.ofInputStream());
            if (!isRedirect(response.statusCode())) {
                return response;
            }
            String location = response.headers().firstValue("Location").orElse(null);
            response.body().close();
            if (location == null || redirects == MAX_REDIRECTS) {
                throw new IOException("Too many or malformed redirects while downloading GGUF file.");
            }
            URI next = current.resolve(location);
            if (!("https".equalsIgnoreCase(next.getScheme())
                || "http".equalsIgnoreCase(next.getScheme()) && isLoopback(next.getHost()))) {
                throw new IOException("Refusing unsafe GGUF redirect.");
            }
            current = next;
        }
        throw new IOException("Too many redirects while downloading GGUF file.");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean isLoopback(String host) {
        return "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host) || "::1".equals(host);
    }

    private static boolean isHuggingFaceHost(String host) {
        return host != null && ("huggingface.co".equalsIgnoreCase(host)
            || host.toLowerCase(Locale.ROOT).endsWith(".huggingface.co"));
    }

    private static void requireSecureDownloadUri(URI uri) throws IOException {
        if (uri == null || !("https".equalsIgnoreCase(uri.getScheme())
            || "http".equalsIgnoreCase(uri.getScheme()) && isLoopback(uri.getHost()))) {
            throw new IOException("GGUF download URL must use HTTPS.");
        }
    }

    private static void validateContentRange(HttpResponse<?> response, long expectedStart, long expectedTotal)
        throws IOException {
        String contentRange = response.headers().firstValue("Content-Range").orElse("");
        if (!contentRange.matches("bytes " + expectedStart + "-[0-9]+/" + expectedTotal)) {
            throw new IOException("Server returned an invalid Content-Range for resumed GGUF download.");
        }
    }

    private static Path safeTarget(Path root, String repositoryPath) throws IOException {
        Path relative;
        try {
            relative = Path.of(repositoryPath.replace('/', java.io.File.separatorChar));
        } catch (RuntimeException e) {
            throw new IOException("Invalid GGUF repository path.", e);
        }
        if (relative.isAbsolute()) {
            throw new IOException("Absolute GGUF repository paths are not allowed.");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            throw new IOException("GGUF repository path escapes the model directory.");
        }
        return target;
    }

    private static void rejectSymlinkPath(Path root, Path target) throws IOException {
        Path current = root;
        Path relative = root.relativize(target);
        for (Path part : relative) {
            current = current.resolve(part);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("GGUF download path contains a symbolic link.");
            }
        }
    }

    private static void checkDiskSpace(Path targetDirectory, long remainingBytes) throws IOException {
        FileStore store = Files.getFileStore(targetDirectory);
        if (remainingBytes > 0 && store.getUsableSpace() < remainingBytes) {
            throw new IOException(
                "Not enough disk space for GGUF download: " + remainingBytes + " bytes are still required.");
        }
    }

    private static long remainingBytes(HuggingFaceDownloadPlan plan, Path root) throws IOException {
        long remaining = 0;
        for (HuggingFaceModelFile file : plan.files()) {
            Path target = safeTarget(root, file.path());
            if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)
                && Files.size(target) == file.size()
                && file.sha256().equals(sha256(target))) {
                continue;
            }
            Path partial = target.resolveSibling(target.getFileName() + ".part");
            Path metadata = target.resolveSibling(target.getFileName() + ".part.meta");
            ResumeMetadata resume = readResumeMetadata(metadata);
            long existing = resume.matches(plan, file)
                && Files.isRegularFile(partial, LinkOption.NOFOLLOW_LINKS)
                ? Math.min(file.size(), Files.size(partial)) : 0;
            remaining = Math.addExact(remaining, file.size() - existing);
        }
        return remaining;
    }

    private static ResumeMetadata readResumeMetadata(Path metadata) {
        if (!Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)) {
            return ResumeMetadata.empty();
        }
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(metadata)) {
            properties.load(input);
            return new ResumeMetadata(
                properties.getProperty("modelId"),
                properties.getProperty("revision"),
                properties.getProperty("path"),
                Long.parseLong(properties.getProperty("size", "-1")),
                properties.getProperty("sha256"),
                properties.getProperty("uri"),
                properties.getProperty("etag"));
        } catch (IOException | RuntimeException ignored) {
            return ResumeMetadata.empty();
        }
    }

    private static void writeResumeMetadata(Path metadata, ResumeMetadata value) throws IOException {
        Properties properties = new Properties();
        properties.setProperty("modelId", value.modelId());
        properties.setProperty("revision", value.revision());
        properties.setProperty("path", value.path());
        properties.setProperty("size", Long.toString(value.size()));
        properties.setProperty("sha256", value.sha256());
        properties.setProperty("uri", value.uri());
        if (value.etag() != null && !value.etag().isBlank()) {
            properties.setProperty("etag", value.etag());
        }
        try (OutputStream output = Files.newOutputStream(
            metadata, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            properties.store(output, "korTTY resumable Hugging Face download");
        }
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
        try (InputStream raw = Files.newInputStream(path);
             DigestInputStream input = new DigestInputStream(raw, digest)) {
            input.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
        return HexFormat.of().formatHex(digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .toLowerCase(Locale.ROOT);
    }

    private static HuggingFaceDownloadProgress progressForTransfer(
        HuggingFaceDownloadProgress.Phase phase,
        HuggingFaceModelFile file,
        int fileIndex,
        int fileCount,
        long completed,
        long total,
        long downloadedThisRun,
        Instant started
    ) {
        long elapsedMillis = Math.max(1, Duration.between(started, Instant.now()).toMillis());
        long bytesPerSecond = downloadedThisRun <= 0 ? 0 : downloadedThisRun * 1000 / elapsedMillis;
        Duration eta = bytesPerSecond <= 0
            ? null : Duration.ofSeconds(Math.max(0, (total - completed) / bytesPerSecond));
        return snapshot(
            phase, file.path(), fileIndex + 1, fileCount, completed, total, bytesPerSecond, eta);
    }

    private static HuggingFaceDownloadProgress snapshot(
        HuggingFaceDownloadProgress.Phase phase,
        String file,
        int fileIndex,
        int fileCount,
        long downloaded,
        long total,
        long bytesPerSecond,
        Duration eta
    ) {
        return new HuggingFaceDownloadProgress(
            phase, file, fileIndex, fileCount, downloaded, total, bytesPerSecond, eta);
    }

    private record TransferResult(long downloadedBytes, long resumedBytes) {
    }

    @FunctionalInterface
    interface VerifiedFileActivator {
        void activate(Path verifiedPartial, Path target) throws IOException;
    }

    private static final class DownloadLock implements AutoCloseable {
        private final Path path;
        private final ReentrantLock jvmLock;
        private final FileChannel channel;
        private final FileLock fileLock;
        private boolean closed;

        private DownloadLock(
            Path path,
            ReentrantLock jvmLock,
            FileChannel channel,
            FileLock fileLock
        ) {
            this.path = path;
            this.jvmLock = jvmLock;
            this.channel = channel;
            this.fileLock = fileLock;
        }

        @Override
        public void close() throws IOException {
            if (closed) {
                return;
            }
            closed = true;
            IOException failure = null;
            try {
                fileLock.release();
            } catch (IOException e) {
                failure = e;
            }
            try {
                channel.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                } else {
                    failure.addSuppressed(e);
                }
            } finally {
                jvmLock.unlock();
                if (!jvmLock.hasQueuedThreads()) {
                    JVM_DOWNLOAD_LOCKS.remove(path, jvmLock);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    private record ResumeMetadata(
        String modelId,
        String revision,
        String path,
        long size,
        String sha256,
        String uri,
        String etag
    ) {
        static ResumeMetadata empty() {
            return new ResumeMetadata(null, null, null, -1, null, null, null);
        }

        static ResumeMetadata forFile(
            HuggingFaceDownloadPlan plan,
            HuggingFaceModelFile file,
            String etag
        ) {
            return new ResumeMetadata(
                plan.modelId(), plan.revision(), file.path(), file.size(), file.sha256(),
                file.downloadUri().toString(), etag);
        }

        boolean matches(HuggingFaceDownloadPlan plan, HuggingFaceModelFile file) {
            return Objects.equals(modelId, plan.modelId())
                && Objects.equals(revision, plan.revision())
                && Objects.equals(path, file.path())
                && size == file.size()
                && Objects.equals(sha256, file.sha256())
                && Objects.equals(uri, file.downloadUri().toString());
        }
    }
}
