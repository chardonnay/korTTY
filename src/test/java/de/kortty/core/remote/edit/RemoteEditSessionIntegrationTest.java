package de.kortty.core.remote.edit;

import de.kortty.core.sftp.SftpLoopbackFixture;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * External edit against a loopback SFTP server: the local copy lives in a private folder, an
 * upload is byte-equal and keeps the file's mode, a change on the server by someone else stops the
 * upload with a conflict, and stopping deletes the folder.
 */
class RemoteEditSessionIntegrationTest {

    private Path tmp;
    private Path tempRoot;
    private SftpLoopbackFixture fixture;
    private SftpClient client;

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-remote-edit-it-");
        tempRoot = Files.createDirectory(tmp.resolve("local-temp"));
        fixture = SftpLoopbackFixture.builder(tmp).start();
        client = fixture.openSftp();
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (fixture != null) {
            fixture.close();
        }
        RemoteEditTempDirs.deleteTree(tmp);
    }

    @Test
    void anEditIsUploadedByteEqualAndKeepsTheMode() throws Exception {
        requirePosix();
        Path remote = Files.writeString(fixture.root().resolve("app.conf"), "port=80\n");
        Files.setPosixFilePermissions(remote, PosixFilePermissions.fromString("rw-r-----"));

        try (RemoteEditSession session = RemoteEditSession.open(() -> client, "/app.conf", tempRoot)) {
            assertThat(session.localFile().getParent()).isEqualTo(session.folder());
            assertThat(Files.readString(session.localFile())).isEqualTo("port=80\n");
            assertThat(session.baseline().sha256()).isEqualTo(RemoteEditHashes.sha256("port=80\n".getBytes(StandardCharsets.UTF_8)));
            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.UNCHANGED);

            byte[] edited = new byte[200_000];
            new Random(5).nextBytes(edited);
            Files.write(session.localFile(), edited);
            assertThat(session.hasUnsyncedChanges()).isTrue();

            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);

            assertThat(Files.readAllBytes(remote)).isEqualTo(edited);
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(remote))).isEqualTo("rw-r-----");
            assertThat(session.uploads()).isEqualTo(1);
            assertThat(session.lastUpload()).isNotNull();
            assertThat(session.hasUnsyncedChanges()).isFalse();
            assertThat(session.baseline().sha256()).isEqualTo(RemoteEditHashes.sha256(edited));
            try (var entries = Files.list(fixture.root())) {
                assertThat(entries.map(p -> p.getFileName().toString()).toList()).containsExactly("app.conf");
            }
            // A second save goes up as well.
            Files.writeString(session.localFile(), "port=8080\n");
            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);
            assertThat(Files.readString(remote)).isEqualTo("port=8080\n");
        }
    }

    @Test
    void aChangeOnTheServerByAnotherWriterIsAConflict() throws Exception {
        Path remote = Files.writeString(fixture.root().resolve("notes.txt"), "first\n");

        try (RemoteEditSession session = RemoteEditSession.open(() -> client, "/notes.txt", tempRoot)) {
            Files.writeString(remote, "someone else\n");
            Files.writeString(session.localFile(), "mine\n");

            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.CONFLICT);
            assertThat(session.conflict().kind()).isEqualTo(RemoteEditSession.ConflictKind.CHANGED);
            assertThat(Files.readString(remote)).isEqualTo("someone else\n");

            // Overwrite was chosen.
            assertThat(session.forceUpload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);
            assertThat(Files.readString(remote)).isEqualTo("mine\n");
            assertThat(session.conflict()).isNull();
        }
    }

    @Test
    void sameSizeAndTimeButOtherContentIsStillAConflict() throws Exception {
        Path remote = Files.writeString(fixture.root().resolve("same.txt"), "aaaa\n");
        FileTime time = Files.getLastModifiedTime(remote);

        try (RemoteEditSession session = RemoteEditSession.open(() -> client, "/same.txt", tempRoot)) {
            Files.writeString(remote, "bbbb\n");
            Files.setLastModifiedTime(remote, time);
            Files.writeString(session.localFile(), "cccc\n");

            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.CONFLICT);
            assertThat(session.conflict().kind()).isEqualTo(RemoteEditSession.ConflictKind.CONTENT_CHANGED);
            assertThat(Files.readString(remote)).isEqualTo("bbbb\n");
        }
    }

    @Test
    void aSaveDuringTheUploadIsNotMistakenForAChangeOnTheServer() throws Exception {
        Path remote = Files.writeString(fixture.root().resolve("busy.txt"), "v0\n");
        java.util.concurrent.atomic.AtomicReference<Runnable> onClient = new java.util.concurrent.atomic.AtomicReference<>();
        RemoteEditSession.ClientSource source = () -> {
            Runnable hook = onClient.getAndSet(null);
            if (hook != null) {
                hook.run();
            }
            return client;
        };

        try (RemoteEditSession session = RemoteEditSession.open(source, "/busy.txt", tempRoot)) {
            Files.writeString(session.localFile(), "v1\n");
            // The editor saves again while v1 is on its way: right before the transfer starts.
            onClient.set(() -> onClient.set(() -> {
                try {
                    Files.writeString(session.localFile(), "v2\n");
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }));
            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);
            assertThat(Files.readString(remote)).isEqualTo("v1\n");
            assertThat(session.hasUnsyncedChanges()).isTrue();

            // The next save goes up: the server still has exactly what korTTY sent.
            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);
            assertThat(Files.readString(remote)).isEqualTo("v2\n");
            try (var entries = Files.list(session.folder())) {
                assertThat(entries.toList()).containsExactly(session.localFile());
            }
        }
    }

    @Test
    void aDeletedServerFileIsAConflict() throws Exception {
        Path remote = Files.writeString(fixture.root().resolve("gone.txt"), "x\n");
        try (RemoteEditSession session = RemoteEditSession.open(() -> client, "/gone.txt", tempRoot)) {
            Files.delete(remote);
            Files.writeString(session.localFile(), "y\n");
            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.CONFLICT);
            assertThat(session.conflict().kind()).isEqualTo(RemoteEditSession.ConflictKind.GONE);
        }
    }

    @Test
    void theLocalFolderIsPrivateAndGoneAfterStop() throws Exception {
        Files.writeString(fixture.root().resolve("a&b%PATH%.txt"), "hello\n");

        RemoteEditSession session = RemoteEditSession.open(() -> client, "/a&b%PATH%.txt", tempRoot);
        Path folder = session.folder();
        assertThat(folder.getFileName().toString()).matches(RemoteEditTempDirs.NAME.pattern());
        assertThat(session.localFile().getFileName().toString()).isEqualTo("a_b_PATH_.txt");
        assertThat(RemoteEditTempDirs.live()).contains(folder);
        if (folder.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(folder))).isEqualTo("rwx------");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(session.localFile())))
                .isEqualTo("rw-------");
        }

        session.close();

        assertThat(Files.exists(folder)).isFalse();
        assertThat(RemoteEditTempDirs.live()).doesNotContain(folder);
        expectThrows(IOException.class, session::upload);
    }

    @Test
    void aFolderOrAMissingFileDoesNotOpenAndLeavesNoFolder() throws Exception {
        Files.createDirectory(fixture.root().resolve("dir"));

        expectThrows(IOException.class, () -> RemoteEditSession.open(() -> client, "/dir", tempRoot));
        expectThrows(IOException.class, () -> RemoteEditSession.open(() -> client, "/missing.txt", tempRoot));

        try (var entries = Files.list(tempRoot)) {
            assertThat(entries.toList()).isEmpty();
        }
    }

    @Test
    void aLinkIsEditedAtItsTarget() throws Exception {
        requirePosix();
        Path target = Files.writeString(fixture.root().resolve("real.conf"), "a=1\n");
        Files.createSymbolicLink(fixture.root().resolve("link.conf"), Path.of("real.conf"));

        try (RemoteEditSession session = RemoteEditSession.open(() -> client, "/link.conf", tempRoot)) {
            assertThat(session.remotePath()).isEqualTo("/real.conf");
            Files.writeString(session.localFile(), "a=2\n");
            assertThat(session.upload()).isEqualTo(RemoteEditSession.UploadResult.UPLOADED);
        }
        assertThat(Files.readString(target)).isEqualTo("a=2\n");
        assertThat(Files.isSymbolicLink(fixture.root().resolve("link.conf"))).isTrue();
    }

    private static void requirePosix() {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            throw new SkipException("POSIX permissions are not supported here");
        }
    }
}
