package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.FileHandle;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileAttribute;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * Uploads, folder creation and the disconnect notification of {@link SFTPSession}, against a real
 * loopback SSHD with the SFTP subsystem rooted in a temporary directory.
 */
class SFTPSessionTransferIntegrationTest {

    /** Just above 3 GiB: more than a Java array (and the old {@code readAllBytes} upload) can hold. */
    private static final long LARGE_FILE_SIZE = 3L * 1024 * 1024 * 1024 + 7;

    private Path tmp;
    private Path remoteRoot;
    private SshServer server;
    private SFTPSession session;
    /** Receives uploads of {@code *.sink} files instead of the disk, so the large-file test needs no 3 GB. */
    private volatile CountingSink sink;

    @BeforeMethod
    void startServer() throws IOException {
        tmp = Files.createTempDirectory("kortty-sftp-transfer-it-");
        remoteRoot = Files.createDirectory(tmp.resolve("remote"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("host.ser")));
        // AES-GCM runs on the CPU's AES instructions; it keeps the 3 GiB upload short on CI runners.
        server.setCipherFactories(List.of(org.apache.sshd.common.cipher.BuiltinCiphers.aes128gcm));
        server.setPasswordAuthenticator((username, password, serverSession) -> "secret".equals(password));
        server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        server.setSubsystemFactories(List.of(new SftpSubsystemFactory.Builder()
            .withFileSystemAccessor(new SinkingAccessor())
            .build()));
        server.start();
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (session != null) {
            session.close();
            session = null;
        }
        if (server != null) {
            server.stop(true);
            server = null;
        }
        sink = null;
        deleteTree(tmp);
    }

    @Test
    void uploadFileStreamsMultiMegabyteFileByteForByte() throws Exception {
        Path local = tmp.resolve("data.bin");
        byte[] data = new byte[8 * 1024 * 1024 + 13];
        new Random(42).nextBytes(data);
        Files.write(local, data);

        session = connect();
        session.uploadFile(local, "/data.bin");

        assertThat(Files.mismatch(local, remoteRoot.resolve("data.bin"))).isEqualTo(-1L);

        // Uploading again replaces the remote file instead of leaving a longer old tail behind.
        Files.writeString(local, "short", StandardCharsets.UTF_8);
        session.uploadFile(local, "/data.bin");
        assertThat(Files.readString(remoteRoot.resolve("data.bin"), StandardCharsets.UTF_8)).isEqualTo("short");
    }

    @Test
    void uploadFileStreamsAFileLargerThanAJavaArray() throws Exception {
        // A sparse file: its size on disk stays tiny although it reads as 3 GiB of zeros plus a marker.
        Path local = tmp.resolve("big.bin");
        try (SeekableByteChannel channel = Files.newByteChannel(local,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, StandardOpenOption.SPARSE)) {
            channel.position(LARGE_FILE_SIZE - 1);
            channel.write(ByteBuffer.wrap(new byte[] {42}));
        }
        assertThat(Files.size(local)).isEqualTo(LARGE_FILE_SIZE);

        session = connect();
        session.uploadFile(local, "/big.sink");

        CountingSink received = sink;
        assertThat(received).isNotNull();
        assertThat(received.received).isEqualTo(LARGE_FILE_SIZE);
        assertThat(received.contiguous).isTrue();
        assertThat(received.lastByte).isEqualTo(42);
    }

    @Test
    void createDirectoryIfMissingIsIdempotentAndFailsOnRegularFile() throws Exception {
        Files.writeString(remoteRoot.resolve("file.txt"), "x", StandardCharsets.UTF_8);
        session = connect();

        session.createDirectoryIfMissing("/folder");
        session.createDirectoryIfMissing("/folder");
        assertThat(Files.isDirectory(remoteRoot.resolve("folder"))).isTrue();

        assertThrows(IOException.class, () -> session.createDirectoryIfMissing("/file.txt"));
        // New Folder stays strict: an existing name is an error there.
        assertThrows(IOException.class, () -> session.createDirectory("/folder"));

        session.createDirectories("/deep/er/still");
        session.createDirectories("/deep/er/still");
        assertThat(Files.isDirectory(remoteRoot.resolve("deep/er/still"))).isTrue();
    }

    @Test
    void uploadDirectoryTwiceMergesIntoExistingTree() throws Exception {
        Path tree = Files.createDirectories(tmp.resolve("local/project"));
        Files.writeString(tree.resolve("a.txt"), "first", StandardCharsets.UTF_8);
        Files.createDirectory(tree.resolve("sub"));
        Files.writeString(tree.resolve("sub/b.txt"), "b", StandardCharsets.UTF_8);

        SftpFileTransferService service = new SftpFileTransferService();
        session = newSession();
        service.connect(session);
        service.uploadDirectory(tree, "/");

        Files.writeString(tree.resolve("a.txt"), "second", StandardCharsets.UTF_8);
        Files.writeString(tree.resolve("sub/c.txt"), "c", StandardCharsets.UTF_8);
        // The remote folders exist now; the second upload must merge instead of failing on mkdir.
        service.uploadDirectory(tree, "/");

        Path remote = remoteRoot.resolve("project");
        assertThat(Files.readString(remote.resolve("a.txt"), StandardCharsets.UTF_8)).isEqualTo("second");
        assertThat(Files.readString(remote.resolve("sub/b.txt"), StandardCharsets.UTF_8)).isEqualTo("b");
        assertThat(Files.readString(remote.resolve("sub/c.txt"), StandardCharsets.UTF_8)).isEqualTo("c");
    }

    @Test
    void copyFileOntoAnExistingFolderMerges() throws Exception {
        Files.createDirectories(remoteRoot.resolve("src/inner"));
        Files.writeString(remoteRoot.resolve("src/inner/f.txt"), "f", StandardCharsets.UTF_8);
        Files.createDirectories(remoteRoot.resolve("dst/inner"));
        Files.writeString(remoteRoot.resolve("dst/keep.txt"), "keep", StandardCharsets.UTF_8);
        session = connect();

        session.copyFile("/src", "/dst");

        assertThat(Files.readString(remoteRoot.resolve("dst/inner/f.txt"), StandardCharsets.UTF_8)).isEqualTo("f");
        assertThat(Files.readString(remoteRoot.resolve("dst/keep.txt"), StandardCharsets.UTF_8)).isEqualTo("keep");
    }

    @Test
    void copyFileOntoItselfOrIntoItsOwnFolderIsRefusedAndKeepsTheData() throws Exception {
        Files.createDirectories(remoteRoot.resolve("proj/sub"));
        Files.writeString(remoteRoot.resolve("proj/a.txt"), "alpha", StandardCharsets.UTF_8);
        Files.writeString(remoteRoot.resolve("proj/sub/b.txt"), "beta", StandardCharsets.UTF_8);
        session = connect();

        // "Copy to..." offers the folder shown, i.e. the parent of the selection: the target is the
        // source itself. Merging into it used to open every file with truncate before reading it.
        assertThrows(IOException.class, () -> session.copyFile("/proj", "/proj"));
        assertThrows(IOException.class, () -> session.copyFile("/proj", "/proj/"));
        assertThrows(IOException.class, () -> session.copyFile("/proj/a.txt", "/proj/./a.txt"));
        assertThrows(IOException.class, () -> session.copyFile("/proj/a.txt", "/proj/sub/../a.txt"));
        // Into its own subfolder the copy would walk the copies it creates.
        assertThrows(IOException.class, () -> session.copyFile("/proj", "/proj/sub/proj"));

        assertThat(Files.readString(remoteRoot.resolve("proj/a.txt"), StandardCharsets.UTF_8)).isEqualTo("alpha");
        assertThat(Files.readString(remoteRoot.resolve("proj/sub/b.txt"), StandardCharsets.UTF_8)).isEqualTo("beta");
        assertThat(Files.exists(remoteRoot.resolve("proj/sub/proj"))).isFalse();

        // A sibling whose name merely starts with the source's name is a different folder.
        session.copyFile("/proj", "/proj-copy");
        assertThat(Files.readString(remoteRoot.resolve("proj-copy/sub/b.txt"), StandardCharsets.UTF_8)).isEqualTo("beta");
    }

    @Test
    void disconnectListenerFiresWhenServerStops() throws Exception {
        session = newSession();
        CountDownLatch lost = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        session.setDisconnectListener(() -> {
            calls.incrementAndGet();
            lost.countDown();
        });
        session.connect();
        assertThat(session.isConnected()).isTrue();

        server.stop(true);

        assertThat(lost.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(session.isConnected()).isFalse();
        // Session and SFTP channel both close; the listener still runs only once.
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void disconnectListenerSilentOnDeliberateClose() throws Exception {
        session = newSession();
        CountDownLatch lost = new CountDownLatch(1);
        session.setDisconnectListener(lost::countDown);
        session.connect();

        session.close();
        server.stop(true);

        assertThat(lost.await(1, TimeUnit.SECONDS)).isFalse();
        assertThat(session.isConnected()).isFalse();
    }

    private SFTPSession connect() throws Exception {
        SFTPSession connected = newSession();
        connected.connect();
        return connected;
    }

    private SFTPSession newSession() {
        ServerConnection connection = new ServerConnection("it", "127.0.0.1", server.getPort(), "tester");
        connection.setAuthMethod(AuthMethod.PASSWORD);
        SshHostKeyTrustManager trust = new SshHostKeyTrustManager(
            tmp.resolve("hostkeys.properties"), new LoopbackSshServers.AcceptingPrompt());
        return new SFTPSession(connection, "secret", trust);
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Serves {@code *.sink} files from a {@link CountingSink}; everything else from the real directory. */
    private final class SinkingAccessor implements SftpFileSystemAccessor {
        @Override
        public SeekableByteChannel openFile(SftpSubsystemProxy subsystem, FileHandle fileHandle, Path file,
                String handle, Set<? extends OpenOption> options, FileAttribute<?>... attrs) throws IOException {
            if (file.getFileName() != null && file.getFileName().toString().endsWith(".sink")) {
                CountingSink counting = new CountingSink();
                sink = counting;
                return counting;
            }
            return SftpFileSystemAccessor.super.openFile(subsystem, fileHandle, file, handle, options, attrs);
        }
    }

    /**
     * Discards what is written, but records how much arrived, whether every write continued where
     * the previous one ended, and the last byte. The SFTP subsystem writes from a single thread.
     */
    private static final class CountingSink implements SeekableByteChannel {
        volatile long received;
        volatile boolean contiguous = true;
        volatile int lastByte = -1;
        private long position;
        private long size;
        private boolean open = true;

        @Override
        public int read(ByteBuffer dst) {
            return -1;
        }

        @Override
        public int write(ByteBuffer src) {
            if (position != received) {
                contiguous = false;
            }
            int length = src.remaining();
            if (length > 0) {
                lastByte = src.get(src.limit() - 1);
            }
            src.position(src.limit());
            position += length;
            size = Math.max(size, position);
            received += length;
            return length;
        }

        @Override
        public long position() {
            return position;
        }

        @Override
        public SeekableByteChannel position(long newPosition) {
            position = newPosition;
            return this;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public SeekableByteChannel truncate(long newSize) {
            size = Math.min(size, newSize);
            position = Math.min(position, newSize);
            return this;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public void close() {
            open = false;
        }
    }
}
