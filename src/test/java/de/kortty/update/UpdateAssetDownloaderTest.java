package de.kortty.update;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class UpdateAssetDownloaderTest {

    @Test
    void downloadsAssetAndVerifiesSha256() throws Exception {
        byte[] content = "installer".getBytes(StandardCharsets.UTF_8);
        UpdateAsset asset = asset("korTTY-Windows-2.3.0-x86_64.msi", content);
        Path dir = Files.createTempDirectory("kortty-update-download");
        try {
            UpdateAssetDownloader downloader = new UpdateAssetDownloader(
                uri -> new ByteArrayInputStream(content));

            Path downloaded = downloader.download(asset, dir);

            assertThat(downloaded.getFileName().toString()).isEqualTo(asset.name());
            assertThat(Files.readString(downloaded)).isEqualTo("installer");
            assertThat(Files.exists(downloaded.resolveSibling(downloaded.getFileName() + ".part"))).isFalse();
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void createsUniqueFileNameWhenTargetExists() throws Exception {
        byte[] content = "installer".getBytes(StandardCharsets.UTF_8);
        UpdateAsset asset = asset("korTTY-Windows-2.3.0-x86_64.msi", content);
        Path dir = Files.createTempDirectory("kortty-update-download-collision");
        try {
            Files.writeString(dir.resolve(asset.name()), "existing");
            UpdateAssetDownloader downloader = new UpdateAssetDownloader(
                uri -> new ByteArrayInputStream(content));

            Path downloaded = downloader.download(asset, dir);

            assertThat(downloaded.getFileName().toString()).isEqualTo("korTTY-Windows-2.3.0-x86_64 (1).msi");
            assertThat(Files.readString(dir.resolve(asset.name()))).isEqualTo("existing");
        } finally {
            deleteDirectory(dir);
        }
    }

    @Test
    void deletesPartialFileWhenSha256DoesNotMatch() throws Exception {
        byte[] content = "installer".getBytes(StandardCharsets.UTF_8);
        UpdateAsset asset = new UpdateAsset(
            "korTTY-Windows-2.3.0-x86_64.msi",
            URI.create("https://example.test/update.msi"),
            content.length,
            "sha256:ffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffffff");
        Path dir = Files.createTempDirectory("kortty-update-download-mismatch");
        try {
            UpdateAssetDownloader downloader = new UpdateAssetDownloader(
                uri -> new ByteArrayInputStream(content));

            expectThrows(DownloadException.class, () -> downloader.download(asset, dir));

            assertThat(Files.exists(dir.resolve(asset.name()))).isFalse();
            assertThat(Files.exists(dir.resolve(asset.name() + ".part"))).isFalse();
        } finally {
            deleteDirectory(dir);
        }
    }

    private static UpdateAsset asset(String name, byte[] content) throws Exception {
        return new UpdateAsset(
            name,
            URI.create("https://example.test/" + name),
            content.length,
            "sha256:" + sha256(content));
    }

    private static String sha256(byte[] content) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(digest.digest(content));
    }

    private static void deleteDirectory(Path dir) throws Exception {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted((a, b) -> b.compareTo(a)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
