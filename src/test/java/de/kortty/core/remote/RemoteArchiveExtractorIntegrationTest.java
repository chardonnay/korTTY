package de.kortty.core.remote;

import de.kortty.core.remote.extract.ArchiveExtractException;
import de.kortty.core.remote.extract.RemoteArchiveExtractor;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream;
import org.apache.commons.compress.compressors.gzip.GzipCompressorOutputStream;
import org.apache.sshd.client.session.ClientSession;
import org.testng.SkipException;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Extracts real archives through a loopback SSH server whose exec channels run local shells. Unix
 * only; tests skip when tar or unzip is missing.
 */
public class RemoteArchiveExtractorIntegrationTest {

    private Path dir;
    private ExecLoopbackServer server;
    private ClientSession session;
    private RemoteArchiveExtractor extractor;

    @BeforeClass
    public void startServer() throws IOException {
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) {
            throw new SkipException("exec fixture runs /bin/sh");
        }
        dir = Files.createTempDirectory("kortty-extract-it-").toRealPath();
        Path bin = Files.createDirectories(dir.resolve("bin"));
        server = new ExecLoopbackServer(dir.resolve("host.ser"), bin);
        session = server.connect();
        extractor = new RemoteArchiveExtractor(new RemoteCommandRunner(() -> session));
    }

    @AfterClass(alwaysRun = true)
    public void stopServer() throws IOException {
        if (session != null) {
            session.close(true);
        }
        if (server != null) {
            server.close();
        }
    }

    private static void requireTool(String tool) throws Exception {
        Process probe = new ProcessBuilder("/bin/sh", "-c", "command -v " + tool).redirectErrorStream(true).start();
        if (probe.waitFor() != 0) {
            throw new SkipException(tool + " is not installed");
        }
    }

    private Path folder(String name) throws IOException {
        return Files.createDirectories(dir.resolve(name));
    }

    private static List<String> names(Path folder) throws IOException {
        try (Stream<Path> entries = Files.list(folder)) {
            return entries.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    private static void writeTarGz(Path archive, Map<String, String> files) throws IOException {
        try (OutputStream out = Files.newOutputStream(archive);
             GzipCompressorOutputStream gzip = new GzipCompressorOutputStream(out);
             TarArchiveOutputStream tar = new TarArchiveOutputStream(gzip)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                byte[] data = file.getValue().getBytes(StandardCharsets.UTF_8);
                TarArchiveEntry entry = new TarArchiveEntry(file.getKey(), true);
                entry.setSize(data.length);
                entry.setMode(0644);
                tar.putArchiveEntry(entry);
                tar.write(data);
                tar.closeArchiveEntry();
            }
        }
    }

    @Test
    public void tarGzLandsInANewFolderNextToTheArchive() throws Exception {
        requireTool("tar");
        Path parent = folder("plain dir");
        Path archive = parent.resolve("site 1.0.tar.gz");
        writeTarGz(archive, Map.of("docs/readme.txt", "hello", "top.txt", "top"));
        List<RemoteArchiveExtractor.Phase> phases = new ArrayList<>();

        RemoteArchiveExtractor.Result result = extractor.extract(archive.toString(), new RemoteCommandCancellation(),
            phases::add);

        Path target = parent.resolve("site 1.0");
        assertThat(result.folder()).isEqualTo(target.toString());
        assertThat(Files.readString(target.resolve("docs/readme.txt"))).isEqualTo("hello");
        assertThat(Files.readString(target.resolve("top.txt"))).isEqualTo("top");
        assertThat(names(parent)).containsExactly("site 1.0", "site 1.0.tar.gz");
        assertThat(phases).containsExactly(RemoteArchiveExtractor.Phase.CHECKING, RemoteArchiveExtractor.Phase.LISTING,
            RemoteArchiveExtractor.Phase.EXTRACTING, RemoteArchiveExtractor.Phase.VERIFYING,
            RemoteArchiveExtractor.Phase.MOVING).inOrder();

        // a second run never writes into the first folder
        RemoteArchiveExtractor.Result second = extractor.extract(archive.toString(), new RemoteCommandCancellation(),
            null);
        assertThat(second.folder()).isEqualTo(parent.resolve("site 1.0 (1)").toString());
        assertThat(names(parent)).containsExactly("site 1.0", "site 1.0 (1)", "site 1.0.tar.gz");
    }

    @Test
    public void maliciousArchiveIsRefusedBeforeAnythingIsWritten() throws Exception {
        requireTool("tar");
        Path parent = folder("malicious/inner");
        Path archive = parent.resolve("evil.tar.gz");
        writeTarGz(archive, Map.of("ok.txt", "fine", "../evil.txt", "pwned"));

        ArchiveExtractException error = expectThrows(ArchiveExtractException.class,
            () -> extractor.extract(archive.toString(), new RemoteCommandCancellation(), null));

        assertThat(error.reason()).isEqualTo(ArchiveExtractException.Reason.UNSAFE_MEMBER);
        assertThat(error.detail()).isEqualTo("../evil.txt");
        assertThat(names(parent)).containsExactly("evil.tar.gz");
        assertThat(Files.exists(parent.getParent().resolve("evil.txt"))).isFalse();
    }

    @Test
    public void symlinkEscapeIsCaughtAfterExtractionAndTheStagingFolderRemoved() throws Exception {
        requireTool("unzip");
        Path parent = folder("links");
        Path archive = parent.resolve("links.zip");
        try (ZipArchiveOutputStream zip = new ZipArchiveOutputStream(archive)) {
            ZipArchiveEntry file = new ZipArchiveEntry("a.txt");
            file.setUnixMode(0100644);
            zip.putArchiveEntry(file);
            zip.write("a".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
            ZipArchiveEntry link = new ZipArchiveEntry("esc");
            link.setUnixMode(0120777);
            zip.putArchiveEntry(link);
            zip.write("/etc".getBytes(StandardCharsets.UTF_8));
            zip.closeArchiveEntry();
        }

        ArchiveExtractException error = expectThrows(ArchiveExtractException.class,
            () -> extractor.extract(archive.toString(), new RemoteCommandCancellation(), null));

        assertThat(error.reason()).isEqualTo(ArchiveExtractException.Reason.UNSAFE_LINK);
        assertThat(error.detail()).isEqualTo("esc");
        assertThat(names(parent)).containsExactly("links.zip");
    }

    @Test
    public void passwordProtectedZipIsRefused() throws Exception {
        requireTool("unzip");
        requireTool("zip");
        Path parent = folder("secret");
        Files.writeString(parent.resolve("a.txt"), "a");
        Process zip = new ProcessBuilder("zip", "-q", "-P", "test-only", "locked.zip", "a.txt")
            .directory(parent.toFile()).redirectErrorStream(true).start();
        assertThat(zip.waitFor()).isEqualTo(0);
        Files.delete(parent.resolve("a.txt"));

        ArchiveExtractException error = expectThrows(ArchiveExtractException.class,
            () -> extractor.extract(parent.resolve("locked.zip").toString(), new RemoteCommandCancellation(), null));

        assertThat(error.reason()).isEqualTo(ArchiveExtractException.Reason.PASSWORD_PROTECTED);
        assertThat(names(parent)).containsExactly("locked.zip");
    }

    @Test
    public void cancelledBeforeStartWritesNothing() throws Exception {
        requireTool("tar");
        Path parent = folder("cancelled");
        Path archive = parent.resolve("c.tgz");
        writeTarGz(archive, Map.of("x.txt", "x"));
        RemoteCommandCancellation cancellation = new RemoteCommandCancellation();
        cancellation.cancel();

        expectThrows(RemoteCommandCancelledException.class,
            () -> extractor.extract(archive.toString(), cancellation, null));
        assertThat(names(parent)).containsExactly("c.tgz");
    }
}
