package de.kortty.ai.huggingface;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class HuggingFaceModelDownloaderTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void cancellationLeavesPartAndNextRunUsesRangeAndIfRange() throws Exception {
        byte[] content = new byte[2 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 31);
        }
        List<String> ranges = new ArrayList<>();
        List<String> ifRanges = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.gguf", exchange -> serveRange(exchange, content, ranges, ifRanges));
        server.start();
        Path directory = Files.createTempDirectory("kortty-hf-resume");
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.gguf");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                "model-Q4_K_M.gguf", content.length, sha256(content), uri, "Q4_K_M", 1, 1);
            HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
                "owner/model", REVISION, directory, List.of(file));
            HuggingFaceModelDownloader downloader = new HuggingFaceModelDownloader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HuggingFaceTokenProvider.fixed("hf_test"),
                Executors.newSingleThreadExecutor());
            HuggingFaceDownloadController cancelled = new HuggingFaceDownloadController();
            List<HuggingFaceDownloadProgress> snapshots = new ArrayList<>();

            expectThrows(CancellationException.class, () -> downloader.download(
                plan,
                cancelled,
                progress -> {
                    snapshots.add(progress);
                    if (progress.phase() == HuggingFaceDownloadProgress.Phase.DOWNLOADING
                        && progress.downloadedBytes() >= 256 * 1024) {
                        cancelled.cancel();
                    }
                }));

            Path partial = directory.resolve("model-Q4_K_M.gguf.part");
            assertThat(Files.size(partial)).isGreaterThan(0L);
            assertThat(Files.size(partial)).isLessThan((long) content.length);
            List<HuggingFaceDownloadProgress> cancelledSnapshots = snapshots.stream()
                .filter(progress -> progress.phase() == HuggingFaceDownloadProgress.Phase.CANCELLED)
                .toList();
            assertThat(cancelledSnapshots).hasSize(1);
            assertThat(cancelledSnapshots.get(0).elapsed()).isNotNull();
            assertThat(cancelledSnapshots.get(0).bytesPerSecond()).isEqualTo(0L);
            assertThat(cancelledSnapshots.get(0).estimatedRemaining()).isNull();

            HuggingFaceDownloadResult result = downloader.download(
                plan, new HuggingFaceDownloadController(), ignored -> { });

            assertThat(result.resumedBytes()).isGreaterThan(0L);
            assertThat(ranges.stream().anyMatch(value -> value != null && value.startsWith("bytes="))).isTrue();
            assertThat(ifRanges).contains("\"kortty-test-etag\"");
            assertThat(Files.readAllBytes(result.files().get(0))).isEqualTo(content);
            assertThat(Files.exists(partial)).isFalse();
        } finally {
            server.stop(0);
            deleteTree(directory);
        }
    }

    @Test
    void pauseReportsCleanStateAndDownloadResumes() throws Exception {
        byte[] content = new byte[2 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 17);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.gguf", exchange ->
            serveRange(exchange, content, new ArrayList<>(), new ArrayList<>()));
        server.start();
        Path directory = Files.createTempDirectory("kortty-hf-pause");
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.gguf");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                "model.gguf", content.length, sha256(content), uri, "Q4_K_M", 1, 1);
            HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
                "owner/model", REVISION, directory, List.of(file));
            HuggingFaceModelDownloader downloader = new HuggingFaceModelDownloader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HuggingFaceTokenProvider.fixed(null),
                Runnable::run);
            HuggingFaceDownloadController controller = new HuggingFaceDownloadController();
            List<HuggingFaceDownloadProgress> snapshots = new CopyOnWriteArrayList<>();
            AtomicBoolean pauseRequested = new AtomicBoolean();
            CountDownLatch pauseReported = new CountDownLatch(1);

            CompletableFuture<HuggingFaceDownloadResult> completion = CompletableFuture.supplyAsync(() -> {
                try {
                    return downloader.download(plan, controller, progress -> {
                        snapshots.add(progress);
                        if (progress.phase() == HuggingFaceDownloadProgress.Phase.DOWNLOADING
                            && pauseRequested.compareAndSet(false, true)) {
                            controller.pause();
                        } else if (progress.phase() == HuggingFaceDownloadProgress.Phase.PAUSED) {
                            pauseReported.countDown();
                        }
                    });
                } catch (IOException | InterruptedException e) {
                    throw new CompletionException(e);
                }
            });

            assertThat(pauseReported.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(completion.isDone()).isFalse();
            Thread.sleep(100L);
            controller.resume();
            HuggingFaceDownloadResult result = completion.get(5, TimeUnit.SECONDS);

            HuggingFaceDownloadProgress paused = snapshots.stream()
                .filter(progress -> progress.phase() == HuggingFaceDownloadProgress.Phase.PAUSED)
                .findFirst()
                .orElseThrow();
            assertThat(paused.elapsed()).isNotNull();
            assertThat(paused.bytesPerSecond()).isEqualTo(0L);
            assertThat(paused.estimatedRemaining()).isNull();
            assertThat(snapshots.stream().anyMatch(progress ->
                progress.phase() == HuggingFaceDownloadProgress.Phase.DOWNLOADING
                    && progress.elapsed() != null
                    && progress.bytesPerSecond() > 0
                    && progress.estimatedRemaining() != null)).isTrue();
            assertThat(Files.readAllBytes(result.files().get(0))).isEqualTo(content);
        } finally {
            server.stop(0);
            deleteTree(directory);
        }
    }

    @Test
    void cancellationDuringVerificationKeepsPartialAndSkipsActivation() throws Exception {
        byte[] content = new byte[2 * 1024 * 1024];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) (i * 23);
        }
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.gguf", exchange ->
            serveRange(exchange, content, new ArrayList<>(), new ArrayList<>()));
        server.start();
        Path directory = Files.createTempDirectory("kortty-hf-cancel-verification");
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.gguf");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                "model.gguf", content.length, sha256(content), uri, "Q4_K_M", 1, 1);
            HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
                "owner/model", REVISION, directory, List.of(file));
            HuggingFaceModelDownloader downloader = new HuggingFaceModelDownloader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HuggingFaceTokenProvider.fixed(null),
                Runnable::run);
            HuggingFaceDownloadController controller = new HuggingFaceDownloadController();
            List<HuggingFaceDownloadProgress> snapshots = new ArrayList<>();

            expectThrows(CancellationException.class, () -> downloader.download(
                plan,
                controller,
                progress -> {
                    snapshots.add(progress);
                    if (progress.phase() == HuggingFaceDownloadProgress.Phase.VERIFYING) {
                        controller.cancel();
                    }
                }));

            assertThat(Files.exists(directory.resolve("model.gguf"))).isFalse();
            assertThat(Files.readAllBytes(directory.resolve("model.gguf.part"))).isEqualTo(content);
            assertThat(snapshots.stream()
                .filter(progress -> progress.phase() == HuggingFaceDownloadProgress.Phase.CANCELLED)
                .count()).isEqualTo(1L);
            assertThat(snapshots.stream().noneMatch(progress ->
                progress.phase() == HuggingFaceDownloadProgress.Phase.COMPLETE)).isTrue();
        } finally {
            server.stop(0);
            deleteTree(directory);
        }
    }

    @Test
    void activationWinsOverCancellationThatArrivesAfterAtomicCommitStarts() throws Exception {
        byte[] content = "verified GGUF ready to commit".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.gguf", exchange ->
            serveRange(exchange, content, new ArrayList<>(), new ArrayList<>()));
        server.start();
        Path directory = Files.createTempDirectory("kortty-hf-cancel-activation-race");
        var downloadExecutor = Executors.newSingleThreadExecutor();
        var cancellationExecutor = Executors.newSingleThreadExecutor();
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.gguf");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                "model.gguf", content.length, sha256(content), uri, "Q4_K_M", 1, 1);
            HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
                "owner/model", REVISION, directory, List.of(file));
            HuggingFaceDownloadController controller = new HuggingFaceDownloadController();
            CountDownLatch activationStarted = new CountDownLatch(1);
            CountDownLatch allowActivation = new CountDownLatch(1);
            List<HuggingFaceDownloadProgress> snapshots = new CopyOnWriteArrayList<>();
            HuggingFaceModelDownloader downloader = new HuggingFaceModelDownloader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HuggingFaceTokenProvider.fixed(null),
                Runnable::run,
                (partial, destination) -> {
                    activationStarted.countDown();
                    try {
                        assertThat(allowActivation.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IOException("Interrupted while simulating activation", e);
                    }
                    Files.move(partial, destination);
                });

            CompletableFuture<HuggingFaceDownloadResult> completion = CompletableFuture.supplyAsync(() -> {
                try {
                    return downloader.download(plan, controller, snapshots::add);
                } catch (IOException | InterruptedException e) {
                    throw new CompletionException(e);
                }
            }, downloadExecutor);

            assertThat(activationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            CompletableFuture<Void> cancellation = CompletableFuture.runAsync(controller::cancel, cancellationExecutor);
            Thread.sleep(100L);
            assertThat(cancellation.isDone()).isFalse();
            allowActivation.countDown();

            HuggingFaceDownloadResult result = completion.get(5, TimeUnit.SECONDS);
            cancellation.get(5, TimeUnit.SECONDS);
            assertThat(Files.readAllBytes(result.files().get(0))).isEqualTo(content);
            assertThat(snapshots.stream().anyMatch(progress ->
                progress.phase() == HuggingFaceDownloadProgress.Phase.COMPLETE)).isTrue();
            assertThat(snapshots.stream().noneMatch(progress ->
                progress.phase() == HuggingFaceDownloadProgress.Phase.CANCELLED)).isTrue();
        } finally {
            downloadExecutor.shutdownNow();
            cancellationExecutor.shutdownNow();
            server.stop(0);
            deleteTree(directory);
        }
    }

    @Test
    void transferMetricsStayCumulativeAcrossShardsAndExcludeInactiveTime() {
        AtomicLong nanoTime = new AtomicLong();
        HuggingFaceModelDownloader.TransferMetrics metrics =
            new HuggingFaceModelDownloader.TransferMetrics(nanoTime::get);

        long firstShardStarted = metrics.nanoTime();
        nanoTime.addAndGet(Duration.ofSeconds(1).toNanos());
        metrics.recordTransfer(1_024, firstShardStarted);
        assertThat(metrics.bytesPerSecond()).isEqualTo(1_024L);
        assertThat(metrics.estimatedRemaining(1_024, 3_072)).isEqualTo(Duration.ofSeconds(2));

        nanoTime.addAndGet(Duration.ofMinutes(1).toNanos());
        assertThat(metrics.elapsed()).isEqualTo(Duration.ofSeconds(61));
        assertThat(metrics.bytesPerSecond()).isEqualTo(1_024L);

        long secondShardStarted = metrics.nanoTime();
        nanoTime.addAndGet(Duration.ofSeconds(1).toNanos());
        metrics.recordTransfer(1_024, secondShardStarted);
        assertThat(metrics.elapsed()).isEqualTo(Duration.ofSeconds(62));
        assertThat(metrics.bytesPerSecond()).isEqualTo(1_024L);
        assertThat(metrics.estimatedRemaining(2_048, 3_072)).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void rejectsIncompleteMultipartPlan() {
        HuggingFaceModelFile first = new HuggingFaceModelFile(
            "model-Q4_K_M-00001-of-00002.gguf", 1,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            URI.create("https://example.test/model"), "Q4_K_M", 1, 2);

        expectThrows(IllegalArgumentException.class, () -> new HuggingFaceDownloadPlan(
            "owner/model", REVISION, Path.of("build/model"), List.of(first)));
    }

    @Test
    void mlxPlanAcceptsSinglePartConfigFilesNextToCompleteShardSet() {
        HuggingFaceModelFile config = new HuggingFaceModelFile(
            "config.json", 12, null, URI.create("https://example.test/config"), "4BIT", 1, 1,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        HuggingFaceModelFile firstShard = new HuggingFaceModelFile(
            "model-00001-of-00002.safetensors", 10,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            URI.create("https://example.test/s1"), "4BIT", 1, 2);
        HuggingFaceModelFile secondShard = new HuggingFaceModelFile(
            "model-00002-of-00002.safetensors", 10,
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            URI.create("https://example.test/s2"), "4BIT", 2, 2);

        HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
            "mlx-community/model-4bit", REVISION, Path.of("build/model"),
            List.of(config, firstShard, secondShard));
        assertThat(plan.totalBytes()).isEqualTo(32);

        expectThrows(IllegalArgumentException.class, () -> new HuggingFaceDownloadPlan(
            "mlx-community/model-4bit", REVISION, Path.of("build/model"),
            List.of(config, firstShard)));
    }

    @Test
    void verifiesNonLfsFilesAgainstTheirGitBlobSha1() throws Exception {
        byte[] content = "{\"model_type\":\"qwen3\"}".getBytes(StandardCharsets.UTF_8);
        String blobSha1 = gitBlobSha1(content);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/config.json", exchange ->
            serveRange(exchange, content, new ArrayList<>(), new ArrayList<>()));
        server.start();
        Path directory = Files.createTempDirectory("kortty-hf-blob-sha1");
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/config.json");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                "config.json", content.length, null, uri, "4BIT", 1, 1, blobSha1);
            HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
                "mlx-community/model-4bit", REVISION, directory, List.of(file));

            HuggingFaceDownloadResult result = new HuggingFaceModelDownloader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HuggingFaceTokenProvider.fixed(null),
                Runnable::run)
                .download(plan, new HuggingFaceDownloadController(), progress -> { });

            assertThat(Files.readAllBytes(result.files().get(0))).isEqualTo(content);
        } finally {
            server.stop(0);
            deleteTree(directory);
        }
    }

    @Test
    void rejectsNonLfsFileWhoseGitBlobSha1DoesNotMatch() throws Exception {
        byte[] content = "tampered".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/config.json", exchange ->
            serveRange(exchange, content, new ArrayList<>(), new ArrayList<>()));
        server.start();
        Path directory = Files.createTempDirectory("kortty-hf-blob-sha1-mismatch");
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/config.json");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                "config.json", content.length, null, uri, "4BIT", 1, 1,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
            HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
                "mlx-community/model-4bit", REVISION, directory, List.of(file));

            Exception failure = expectThrows(IOException.class, () -> new HuggingFaceModelDownloader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HuggingFaceTokenProvider.fixed(null),
                Runnable::run)
                .download(plan, new HuggingFaceDownloadController(), progress -> { }));

            assertThat(failure).hasMessageThat().contains("digest mismatch");
            assertThat(Files.exists(directory.resolve("config.json"))).isFalse();
        } finally {
            server.stop(0);
            deleteTree(directory);
        }
    }

    private static String gitBlobSha1(byte[] content) throws Exception {
        var digest = java.security.MessageDigest.getInstance("SHA-1");
        digest.update(("blob " + content.length + "\0").getBytes(StandardCharsets.US_ASCII));
        digest.update(content);
        return java.util.HexFormat.of().formatHex(digest.digest());
    }

    @Test
    void failedAtomicActivationPreservesPreviousModelAndVerifiedPartial() throws Exception {
        byte[] content = "new verified GGUF".getBytes(StandardCharsets.UTF_8);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.gguf", exchange ->
            serveRange(exchange, content, new ArrayList<>(), new ArrayList<>()));
        server.start();
        Path directory = Files.createTempDirectory("kortty-hf-atomic-preserve");
        Path target = directory.resolve("model.gguf");
        byte[] previous = "previous working GGUF".getBytes(StandardCharsets.UTF_8);
        Files.write(target, previous);
        try {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.gguf");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                target.getFileName().toString(), content.length, sha256(content), uri, "Q4_K_M", 1, 1);
            HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
                "owner/model", REVISION, directory, List.of(file));
            HuggingFaceModelDownloader downloader = new HuggingFaceModelDownloader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HuggingFaceTokenProvider.fixed(null),
                Runnable::run,
                (partial, destination) -> {
                    throw new AtomicMoveNotSupportedException(
                        partial.toString(), destination.toString(), "simulated unsupported filesystem");
                });

            IOException error = expectThrows(IOException.class, () -> downloader.download(
                plan, new HuggingFaceDownloadController(), ignored -> { }));

            assertThat(error).hasMessageThat().contains("simulated unsupported filesystem");
            assertThat(Files.readAllBytes(target)).isEqualTo(previous);
            assertThat(Files.readAllBytes(directory.resolve("model.gguf.part"))).isEqualTo(content);
            assertThat(Files.exists(directory.resolve("model.gguf.part.meta"))).isTrue();
        } finally {
            server.stop(0);
            deleteTree(directory);
        }
    }

    @Test
    void waitsForCrossProcessModelLockBeforeOpeningDownloadConnection() throws Exception {
        byte[] content = "locked verified GGUF".getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/model.gguf", exchange -> {
            requests.incrementAndGet();
            serveRange(exchange, content, new ArrayList<>(), new ArrayList<>());
        });
        server.start();
        Path directory = Files.createTempDirectory("kortty-hf-process-lock");
        String identity = "owner/model\n" + REVISION;
        Path lockPath = directory.resolve(
            ".kortty-download-" + sha256(identity.getBytes(StandardCharsets.UTF_8)) + ".lock");
        try (FileChannel channel = FileChannel.open(
                 lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             FileLock externalProcessLock = channel.lock()) {
            URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/model.gguf");
            HuggingFaceModelFile file = new HuggingFaceModelFile(
                "model.gguf", content.length, sha256(content), uri, "Q4_K_M", 1, 1);
            HuggingFaceDownloadPlan plan = new HuggingFaceDownloadPlan(
                "owner/model", REVISION, directory, List.of(file));
            HuggingFaceModelDownloader downloader = new HuggingFaceModelDownloader(
                HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build(),
                HuggingFaceTokenProvider.fixed(null),
                Runnable::run);
            CompletableFuture<HuggingFaceDownloadResult> completion = CompletableFuture.supplyAsync(() -> {
                try {
                    return downloader.download(plan, new HuggingFaceDownloadController(), ignored -> { });
                } catch (IOException | InterruptedException e) {
                    throw new CompletionException(e);
                }
            });

            Thread.sleep(250L);
            assertThat(completion.isDone()).isFalse();
            assertThat(requests.get()).isEqualTo(0);

            externalProcessLock.release();
            HuggingFaceDownloadResult result = completion.get(5, TimeUnit.SECONDS);
            assertThat(Files.readAllBytes(result.files().get(0))).isEqualTo(content);
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
            deleteTree(directory);
        }
    }

    private static void serveRange(
        HttpExchange exchange,
        byte[] content,
        List<String> ranges,
        List<String> ifRanges
    ) throws IOException {
        String range = exchange.getRequestHeaders().getFirst("Range");
        String ifRange = exchange.getRequestHeaders().getFirst("If-Range");
        synchronized (ranges) {
            ranges.add(range);
            ifRanges.add(ifRange);
        }
        int offset = 0;
        if (range != null && range.matches("bytes=[0-9]+-")) {
            offset = Integer.parseInt(range.substring("bytes=".length(), range.length() - 1));
            exchange.getResponseHeaders().add(
                "Content-Range", "bytes " + offset + "-" + (content.length - 1) + "/" + content.length);
        }
        exchange.getResponseHeaders().add("ETag", "\"kortty-test-etag\"");
        int status = offset > 0 ? 206 : 200;
        exchange.sendResponseHeaders(status, content.length - offset);
        try (var output = exchange.getResponseBody()) {
            int position = offset;
            while (position < content.length) {
                int count = Math.min(16 * 1024, content.length - position);
                output.write(content, position, count);
                output.flush();
                position += count;
            }
        } catch (IOException ignored) {
            // Expected when the cooperative cancellation closes the first response body.
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void deleteTree(Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
