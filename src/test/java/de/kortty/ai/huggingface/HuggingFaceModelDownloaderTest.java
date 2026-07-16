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
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

            expectThrows(CancellationException.class, () -> downloader.download(
                plan,
                cancelled,
                progress -> {
                    if (progress.phase() == HuggingFaceDownloadProgress.Phase.DOWNLOADING
                        && progress.downloadedBytes() >= 256 * 1024) {
                        cancelled.cancel();
                    }
                }));

            Path partial = directory.resolve("model-Q4_K_M.gguf.part");
            assertThat(Files.size(partial)).isGreaterThan(0L);
            assertThat(Files.size(partial)).isLessThan((long) content.length);

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
    void rejectsIncompleteMultipartPlan() {
        HuggingFaceModelFile first = new HuggingFaceModelFile(
            "model-Q4_K_M-00001-of-00002.gguf", 1,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            URI.create("https://example.test/model"), "Q4_K_M", 1, 2);

        expectThrows(IllegalArgumentException.class, () -> new HuggingFaceDownloadPlan(
            "owner/model", REVISION, Path.of("build/model"), List.of(first)));
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
