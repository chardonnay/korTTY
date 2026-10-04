package de.kortty.ui;

import de.kortty.core.SFTPSession;
import de.kortty.core.sftp.SftpLoopbackFixture;
import de.kortty.core.sftp.transfer.TransferCancellation;
import de.kortty.core.sftp.transfer.TransferCancelledException;
import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.SftpFileItem;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

/**
 * Dragging remote files out of the SFTP tab against a loopback SSHD, without JavaFX: the download
 * into the drag-out folder goes through the stream copier, so a cancel (the wait ran out, the tab
 * closed) stops it in the middle of a file and leaves nothing in the folder.
 */
class SFTPManagerTabDragOutCancelTest {

    private static final long TIMEOUT_SECONDS = 60;

    private Path tmp;
    private Path directory;
    private SftpLoopbackFixture fixture;
    private SftpClient client;
    private ExecutorService executor;

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-sftp-dragout-");
        directory = Files.createDirectories(tmp.resolve("drag"));
        executor = Executors.newSingleThreadExecutor();
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        executor.shutdownNow();
        if (client != null) {
            client.close();
        }
        if (fixture != null) {
            fixture.close();
        }
        try (Stream<Path> paths = Files.walk(tmp)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void cancellingMidFileLeavesNoPartialFileInTheDragOutFolder() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).requestDelayMillis(20).start();
        client = fixture.openSftp();
        byte[] content = new byte[8 * 1024 * 1024];
        new Random(3).nextBytes(content);
        Files.write(fixture.root().resolve("big.bin"), content);
        SFTPSession session = new FixtureSession(client);
        TransferCancellation cancel = TransferCancellation.create();

        Future<List<File>> download = executor.submit(
            () -> SFTPManagerTab.downloadForDragOut(session, List.of(listed("big.bin", content.length)), directory,
                cancel));
        Path target = directory.resolve("big.bin");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (!Files.exists(target) || Files.size(target) < 64 * 1024) {
            assertWithMessage("the download writes").that(System.nanoTime()).isLessThan(deadline);
            Thread.sleep(5);
        }
        assertThat(Files.size(target)).isLessThan((long) content.length);
        cancel.cancel();

        ExecutionException failure = expectThrows(ExecutionException.class,
            () -> download.get(TIMEOUT_SECONDS, TimeUnit.SECONDS));
        assertThat(failure.getCause()).isInstanceOf(TransferCancelledException.class);
        try (Stream<Path> left = Files.list(directory)) {
            assertThat(left.toList()).isEmpty();
        }
        // The shared channel stays usable for the tab's listings.
        assertThat(client.isOpen()).isTrue();
        assertThat(client.stat("/big.bin").getSize()).isEqualTo((long) content.length);
    }

    @Test
    void aCompleteDragOutCopiesEveryByteAndAMissingFileLeavesNothing() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        client = fixture.openSftp();
        byte[] content = new byte[300 * 1024];
        new Random(5).nextBytes(content);
        Files.write(fixture.root().resolve("a.bin"), content);
        SFTPSession session = new FixtureSession(client);

        List<File> files = SFTPManagerTab.downloadForDragOut(session, List.of(listed("a.bin", content.length)),
            directory, TransferCancellation.create());
        assertThat(files).containsExactly(directory.resolve("a.bin").toFile());
        assertThat(Files.readAllBytes(directory.resolve("a.bin"))).isEqualTo(content);

        Path other = Files.createDirectories(tmp.resolve("other"));
        expectThrows(IOException.class, () -> session.downloadNewFile("/missing.bin", other.resolve("missing.bin"),
            TransferCancellation.create()));
        try (Stream<Path> left = Files.list(other)) {
            assertThat(left.toList()).isEmpty();
        }
        // An existing local entry is never overwritten (or followed, for a link).
        expectThrows(java.nio.file.FileAlreadyExistsException.class, () -> session.downloadNewFile("/a.bin",
            directory.resolve("a.bin"), TransferCancellation.create()));
        assertThat(Files.readAllBytes(directory.resolve("a.bin"))).isEqualTo(content);
    }

    private static SftpFileItem listed(String name, long size) {
        return SftpFileItem.fromDetails(name, "/" + name, true, size + " B", "", "", "", "", size);
    }

    /** The tab's session over one loopback SFTP channel. */
    private static final class FixtureSession extends SFTPSession {
        private final SftpClient client;

        FixtureSession(SftpClient client) {
            super(new ServerConnection("test", "127.0.0.1", 22, SftpLoopbackFixture.USER), "");
            this.client = client;
        }

        @Override
        public boolean isConnected() {
            return client.isOpen();
        }

        @Override
        public SftpClient primaryClient() {
            return client;
        }

        @Override
        public SftpClient.Attributes getAttributes(String remotePath) throws IOException {
            return client.stat(remotePath);
        }
    }
}
