package de.kortty.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

public class UpdateAssetDownloader {

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);
    static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    @FunctionalInterface
    public interface AssetContentProvider {
        InputStream open(URI uri) throws IOException, InterruptedException;
    }

    private final AssetContentProvider contentProvider;

    public UpdateAssetDownloader() {
        this(new HttpAssetContentProvider());
    }

    UpdateAssetDownloader(AssetContentProvider contentProvider) {
        this.contentProvider = Objects.requireNonNull(contentProvider, "contentProvider");
    }

    public Path download(UpdateAsset asset, Path downloadDirectory)
        throws IOException, InterruptedException, DownloadException {
        Objects.requireNonNull(asset, "asset");
        Objects.requireNonNull(downloadDirectory, "downloadDirectory");
        String expectedSha256 = expectedSha256(asset);
        Files.createDirectories(downloadDirectory);
        Path finalPath = uniqueTarget(downloadDirectory, safeFileName(asset.name()));
        Path partPath = finalPath.resolveSibling(finalPath.getFileName() + ".part");
        Files.deleteIfExists(partPath);

        MessageDigest digest = sha256Digest();
        try (InputStream rawInput = contentProvider.open(asset.downloadUri());
             DigestInputStream input = new DigestInputStream(rawInput, digest);
             OutputStream output = Files.newOutputStream(partPath)) {
            input.transferTo(output);
        } catch (IOException | InterruptedException e) {
            Files.deleteIfExists(partPath);
            throw e;
        }

        String actualSha256 = HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
        if (!expectedSha256.equals(actualSha256)) {
            Files.deleteIfExists(partPath);
            throw new DownloadException("SHA-256 mismatch for " + asset.name() + ".");
        }

        try {
            Files.move(partPath, finalPath, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(partPath, finalPath);
        }
        return finalPath;
    }

    private static String expectedSha256(UpdateAsset asset) throws DownloadException {
        String digest = asset.digest();
        if (digest == null || !digest.toLowerCase(Locale.ROOT).startsWith("sha256:")) {
            throw new DownloadException("Asset " + asset.name() + " has no SHA-256 digest.");
        }
        String value = digest.substring("sha256:".length()).trim().toLowerCase(Locale.ROOT);
        if (!value.matches("[0-9a-f]{64}")) {
            throw new DownloadException("Asset " + asset.name() + " has an invalid SHA-256 digest.");
        }
        return value;
    }

    private static MessageDigest sha256Digest() throws DownloadException {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new DownloadException("SHA-256 is not available in this Java runtime.", e);
        }
    }

    private static Path uniqueTarget(Path directory, String fileName) {
        Path candidate = directory.resolve(fileName);
        if (!Files.exists(candidate)) {
            return candidate;
        }
        int dot = fileName.lastIndexOf('.');
        String baseName = dot > 0 ? fileName.substring(0, dot) : fileName;
        String extension = dot > 0 ? fileName.substring(dot) : "";
        for (int i = 1; i < 10_000; i++) {
            candidate = directory.resolve(baseName + " (" + i + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return directory.resolve(baseName + "-" + System.currentTimeMillis() + extension);
    }

    private static String safeFileName(String fileName) {
        String sanitized = fileName.replace('\\', '_').replace('/', '_').trim();
        return sanitized.isBlank() ? "kortty-update" : sanitized;
    }

    private static final class HttpAssetContentProvider implements AssetContentProvider {
        private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

        @Override
        public InputStream open(URI uri) throws IOException, InterruptedException {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(uri)
                .timeout(REQUEST_TIMEOUT)
                .header("User-Agent", "KorTTY-UpdateChecker")
                .GET()
                .build();
            HttpResponse<InputStream> response = httpClient.send(
                request,
                HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                response.body().close();
                throw new IOException("Download failed with HTTP " + status);
            }
            return response.body();
        }
    }
}
