package de.kortty.core.sftp.transfer;

import de.kortty.core.sftp.SftpLoopbackFixture;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.server.SftpFileSystemAccessor;
import org.apache.sshd.sftp.server.SftpSubsystemProxy;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.CopyOption;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collection;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Part files and finalize against a loopback SSHD: posix-rename when the server lists it, the
 * atomic v5+ rename, the backup swap on a bare v3 server with its rollback, the in-place path for a
 * target owned by someone else, the exclusive part creation, and part cleanup on cancel.
 */
class RemoteFinalizerIntegrationTest {

    private static final int SIZE = 300 * 1024 + 5;

    private Path tmp;
    private SftpLoopbackFixture fixture;
    private byte[] data;
    private Path localFile;

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-sftp-finalize-it-");
        data = new byte[SIZE];
        new Random(11).nextBytes(data);
        localFile = Files.write(tmp.resolve("local.bin"), data);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
        deleteTree(tmp);
    }

    @Test
    void posixRenameReplacesAtomicallyAndKeepsTheTargetMode() throws Exception {
        requirePosix();
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        assertThat(client.getServerExtensions()).containsKey(RemoteFinalizer.POSIX_RENAME_EXTENSION);
        Path target = existingRemoteTarget("report.bin", "rw-r-----");

        PartTransfers.Outcome outcome = PartTransfers.upload(client, null, localFile, "/report.bin",
            null, TransferCancellation.create());

        assertThat(outcome.method()).isEqualTo(FinalizeMethod.POSIX_RENAME);
        assertThat(outcome.bytes()).isEqualTo((long) SIZE);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rw-r-----");
        assertNoLeftovers();
    }

    @Test
    void newerProtocolUsesTheAtomicOverwriteRename() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).advertiseExtensions(false).start();
        SftpClient client = fixture.openSftp(fixture.connect(), SftpLoopbackFixture.DEFAULT_VERSION);
        assertThat(client.getVersion()).isAtLeast(5);
        Path target = existingRemoteTarget("report.bin", null);

        PartTransfers.Outcome outcome = PartTransfers.upload(client, null, localFile, "/report.bin",
            null, TransferCancellation.create());

        assertThat(outcome.method()).isEqualTo(FinalizeMethod.ATOMIC_RENAME);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        assertNoLeftovers();
    }

    @Test
    void bareVersionThreeServerTakesTheBackupSwap() throws Exception {
        requirePosix();
        fixture = SftpLoopbackFixture.builder(tmp).advertiseExtensions(false).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        assertThat(client.getServerExtensions()).doesNotContainKey(RemoteFinalizer.POSIX_RENAME_EXTENSION);
        Path target = existingRemoteTarget("report.bin", "rwxr-x---");

        PartTransfers.Outcome outcome = PartTransfers.upload(client, null, localFile, "/report.bin",
            null, TransferCancellation.create());

        assertThat(outcome.method()).isEqualTo(FinalizeMethod.BACKUP_SWAP);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(target))).isEqualTo("rwxr-x---");
        assertNoLeftovers();
    }

    @Test
    void aNewTargetIsRenamedWithoutOverwriting() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);

        PartTransfers.Outcome outcome = PartTransfers.upload(client, null, localFile, "/fresh.bin",
            null, TransferCancellation.create());

        assertThat(outcome.method()).isEqualTo(FinalizeMethod.RENAME);
        assertThat(Files.readAllBytes(fixture.root().resolve("fresh.bin"))).isEqualTo(data);
        assertNoLeftovers();
    }

    @Test
    void aFailedSecondRenameRestoresTheOriginal() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp)
            .advertiseExtensions(false)
            .fileSystemAccessor(new SftpFileSystemAccessor() {
                @Override
                public void renameFile(SftpSubsystemProxy subsystem, Path oldPath, Path newPath,
                        Collection<CopyOption> opts) throws IOException {
                    if (oldPath.getFileName().toString().endsWith(PartFiles.PART_SUFFIX)) {
                        throw new IOException("injected rename failure");
                    }
                    SftpFileSystemAccessor.super.renameFile(subsystem, oldPath, newPath, opts);
                }
            })
            .start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        Path target = existingRemoteTarget("report.bin", null);
        byte[] original = Files.readAllBytes(target);

        expectThrows(IOException.class, () -> PartTransfers.upload(client, null, localFile, "/report.bin",
            null, TransferCancellation.create()));

        assertThat(Files.readAllBytes(target)).isEqualTo(original);
        assertThat(Files.exists(fixture.root().resolve("report.bin" + PartFiles.BACKUP_SUFFIX))).isFalse();
        // A failure (not a cancel) keeps the completed part for a later resume.
        assertThat(Files.readAllBytes(fixture.root().resolve("report.bin" + PartFiles.PART_SUFFIX))).isEqualTo(data);
    }

    @Test
    void aTargetOwnedByAnotherUserIsWrittenInPlace() throws Exception {
        requirePosix();
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient real = fixture.openSftp(fixture.connect(), 3);
        Path target = existingRemoteTarget("shared.bin", null);
        Object inode = Files.readAttributes(target, "unix:ino").get("ino");
        SftpClient foreignTarget = withForeignOwner(real, "/shared.bin");

        PartTransfers.Outcome outcome = PartTransfers.upload(foreignTarget, null, localFile, "/shared.bin",
            null, TransferCancellation.create());

        assertThat(outcome.method()).isEqualTo(FinalizeMethod.IN_PLACE);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        // In place: the very same file, not a renamed replacement.
        assertThat(Files.readAttributes(target, "unix:ino").get("ino")).isEqualTo(inode);
        assertNoLeftovers();
    }

    @Test
    void aServerThatReportsNoOwnersGetsAnExistingTargetWrittenInPlace() throws Exception {
        // A file store without owners (NTFS behind a Windows server, for one): korTTY cannot tell
        // whether a rename would take over another user's file, so it never renames over it.
        for (int version : new int[] {3, SftpLoopbackFixture.DEFAULT_VERSION}) {
            fixture = SftpLoopbackFixture.builder(tmp)
                .ownerReporting(SftpLoopbackFixture.OwnerReporting.NONE)
                .start();
            SftpClient client = fixture.openSftp(fixture.connect(), version);
            Path target = existingRemoteTarget("report.bin", null);

            PartTransfers.Outcome outcome = PartTransfers.upload(client, null, localFile, "/report.bin",
                null, TransferCancellation.create());

            assertThat(outcome.method()).isEqualTo(FinalizeMethod.IN_PLACE);
            assertThat(Files.readAllBytes(target)).isEqualTo(data);
            assertNoLeftovers();
            fixture.close();
            fixture = null;
            Files.delete(target);
        }
    }

    @Test
    void ownershipIsComparedByUidOrOwnerName() {
        SftpClient.Attributes mine = new SftpClient.Attributes().owner(1000, 100);
        assertThat(RemoteFinalizer.ownedByLogin(mine, new SftpClient.Attributes().owner(1000, 50))).isTrue();
        assertThat(RemoteFinalizer.ownedByLogin(mine, new SftpClient.Attributes().owner(1001, 100))).isFalse();
        assertThat(RemoteFinalizer.ownedByLogin(new SftpClient.Attributes().owner("alice"),
            new SftpClient.Attributes().owner("bob"))).isFalse();
        assertThat(RemoteFinalizer.ownedByLogin(mine, new SftpClient.Attributes())).isNull();
    }

    @Test
    void aSymlinkAtThePartNameFailsTheUploadWithoutTouchingTheLinkTarget() throws Exception {
        requirePosix();
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "precious");
        Files.createSymbolicLink(fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX), outside);
        Path dangling = tmp.resolve("never-created.txt");
        Files.createSymbolicLink(fixture.root().resolve("other.bin" + PartFiles.PART_SUFFIX), dangling);

        PartExistsException existing = expectThrows(PartExistsException.class, () -> PartTransfers.upload(
            client, null, localFile, "/up.bin", null, TransferCancellation.create()));
        expectThrows(PartExistsException.class, () -> PartTransfers.upload(
            client, null, localFile, "/other.bin", null, TransferCancellation.create()));

        assertThat(existing.partPath()).isEqualTo("/up.bin" + PartFiles.PART_SUFFIX);
        assertThat(Files.readString(outside)).isEqualTo("precious");
        assertThat(Files.exists(dangling, LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(Files.isSymbolicLink(fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX))).isTrue();
        assertThat(Files.exists(fixture.root().resolve("up.bin"))).isFalse();
    }

    @Test
    void anExistingPartIsNeverTruncated() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        Path leftover = Files.writeString(fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX), "half");

        expectThrows(PartExistsException.class, () -> PartTransfers.upload(
            client, null, localFile, "/up.bin", null, TransferCancellation.create()));

        assertThat(Files.readString(leftover)).isEqualTo("half");
    }

    @Test
    void aSymlinkedRemoteTargetIsRefused() throws Exception {
        requirePosix();
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        Path elsewhere = Files.writeString(fixture.root().resolve("real.txt"), "keep");
        Files.createSymbolicLink(fixture.root().resolve("link.txt"), elsewhere);

        expectThrows(IOException.class, () -> PartTransfers.upload(
            client, null, localFile, "/link.txt", null, TransferCancellation.create()));

        assertThat(Files.readString(elsewhere)).isEqualTo("keep");
        assertNoLeftovers();
    }

    @Test
    void cancelDeletesTheRemotePart() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        TransferCancellation cancel = TransferCancellation.create();

        expectThrows(TransferCancelledException.class, () -> PartTransfers.upload(client, client, localFile,
            "/up.bin", (done, total) -> {
                if (done > 64 * 1024) {
                    cancel.cancel();
                }
            }, cancel));

        assertThat(Files.exists(fixture.root().resolve("up.bin"))).isFalse();
        assertNoLeftovers();
    }

    @Test
    void downloadGoesThroughAPartAndCancelDeletesIt() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        Files.write(fixture.root().resolve("remote.bin"), data);
        Path downloads = Files.createDirectories(tmp.resolve("downloads"));
        Path target = downloads.resolve("remote.bin");

        PartTransfers.Outcome outcome = PartTransfers.download(client, "/remote.bin", target, null,
            TransferCancellation.create());

        assertThat(outcome.method()).isAnyOf(FinalizeMethod.LOCAL_ATOMIC_MOVE, FinalizeMethod.LOCAL_MOVE);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        assertThat(Files.exists(PartFiles.localPart(target))).isFalse();

        TransferCancellation cancel = TransferCancellation.create();
        Path second = downloads.resolve("second.bin");
        expectThrows(TransferCancelledException.class, () -> PartTransfers.download(client, "/remote.bin", second,
            (done, total) -> {
                if (done > 64 * 1024) {
                    cancel.cancel();
                }
            }, cancel));
        assertThat(Files.exists(second)).isFalse();
        assertThat(Files.exists(PartFiles.localPart(second), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void aLocalLeftoverPartStopsTheDownload() throws Exception {
        fixture = SftpLoopbackFixture.builder(tmp).start();
        SftpClient client = fixture.openSftp(fixture.connect(), 3);
        Files.write(fixture.root().resolve("remote.bin"), data);
        Path target = tmp.resolve("remote.bin");
        Path leftover = Files.writeString(PartFiles.localPart(target), "half");

        expectThrows(PartExistsException.class, () -> PartTransfers.download(client, "/remote.bin", target, null,
            TransferCancellation.create()));

        assertThat(Files.readString(leftover)).isEqualTo("half");
        assertThat(Files.exists(target)).isFalse();
    }

    private Path existingRemoteTarget(String name, String permissions) throws IOException {
        Path target = Files.writeString(fixture.root().resolve(name), "the old content");
        if (permissions != null) {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString(permissions));
        }
        return target;
    }

    private void assertNoLeftovers() throws IOException {
        try (Stream<Path> entries = Files.list(fixture.root())) {
            assertThat(entries.map(path -> path.getFileName().toString())
                .filter(name -> PartFiles.isPartName(name) || PartFiles.isBackupName(name))
                .toList()).isEmpty();
        }
    }

    /** {@code real}, except that {@code lstat(foreignPath)} reports another user as the owner. */
    private static SftpClient withForeignOwner(SftpClient real, String foreignPath) {
        return (SftpClient) Proxy.newProxyInstance(SftpClient.class.getClassLoader(), new Class<?>[] {SftpClient.class},
            (proxy, method, args) -> {
                Object result;
                try {
                    result = method.invoke(real, args);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
                if ("lstat".equals(method.getName()) && args != null && args.length == 1
                        && foreignPath.equals(args[0]) && result instanceof SftpClient.Attributes attributes) {
                    attributes.owner(attributes.getUserId() + 1, attributes.getGroupId());
                }
                return result;
            });
    }

    private static void requirePosix() {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            throw new SkipException("POSIX permissions and symbolic links only");
        }
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
}
