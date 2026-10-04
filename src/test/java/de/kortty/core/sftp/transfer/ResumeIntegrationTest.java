package de.kortty.core.sftp.transfer;

import de.kortty.core.sftp.SftpLoopbackFixture;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Resume against a loopback SSHD: a transfer that fails at 40% continues from there to a byte-equal
 * result in both directions; a changed source or a corrupted part tail restarts; a part that is not
 * provably this transfer's (no entry, another owner, a planted link) is refused and left alone;
 * success and cancel forget the transfer.
 */
class ResumeIntegrationTest {

    private static final int SIZE = 1024 * 1024 + 17;
    private static final long CUT = SIZE * 4L / 10;

    private Path tmp;
    private SftpLoopbackFixture fixture;
    private SftpClient client;
    private byte[] data;
    private ResumeIndex index;

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-sftp-resume-it-");
        data = new byte[SIZE];
        new Random(23).nextBytes(data);
        index = new ResumeIndex(tmp.resolve("config").resolve(ResumeIndex.FILE_NAME));
        fixture = SftpLoopbackFixture.builder(tmp).start();
        client = fixture.openSftp(fixture.connect(), 3);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (fixture != null) {
            fixture.close();
            fixture = null;
        }
        try (Stream<Path> paths = Files.walk(tmp)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    /** Fails (not cancels) the transfer once it passed 40%. */
    private static TransferProgressListener failAtFortyPercent() {
        return (done, total) -> {
            if (done >= CUT) {
                throw new UncheckedIOException(new IOException("simulated connection drop"));
            }
        };
    }

    private Path downloads() throws IOException {
        return Files.createDirectories(tmp.resolve("downloads"));
    }

    private ResumeContext downloadContext(Path target) {
        return new ResumeContext(index, ResumeIndex.Key.of("conn", TransferDirection.DOWNLOAD, "/remote.bin", target));
    }

    private ResumeContext uploadContext(Path local) {
        return new ResumeContext(index, ResumeIndex.Key.of("conn", TransferDirection.UPLOAD, "/up.bin", local));
    }

    private Path interruptedDownload() throws IOException {
        Files.write(fixture.root().resolve("remote.bin"), data);
        Path target = downloads().resolve("remote.bin");
        expectThrows(IOException.class, () -> PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), failAtFortyPercent(), TransferCancellation.create()));
        Path part = PartFiles.localPart(target);
        assertThat(Files.size(part)).isAtLeast(CUT);
        assertThat(index.get(downloadContext(target).key()).orElseThrow().partStateKnown()).isTrue();
        return target;
    }

    private Path interruptedUpload() throws IOException {
        Path local = Files.write(tmp.resolve("local.bin"), data);
        expectThrows(IOException.class, () -> PartTransfers.upload(client, null, local, "/up.bin",
            uploadContext(local), failAtFortyPercent(), TransferCancellation.create()));
        Path part = fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX);
        assertThat(Files.size(part)).isAtLeast(CUT);
        return local;
    }

    @Test
    void aFailedDownloadResumesFromWhereItStopped() throws IOException {
        Path target = interruptedDownload();
        long partSize = Files.size(PartFiles.localPart(target));

        PartTransfers.Outcome outcome = PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), null, TransferCancellation.create());

        assertThat(outcome.resumedFrom()).isEqualTo(partSize);
        assertThat(outcome.bytes()).isEqualTo(SIZE - partSize);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        assertThat(Files.exists(PartFiles.localPart(target), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(index.get(downloadContext(target).key())).isEmpty();
    }

    @Test
    void aFailedUploadResumesFromWhereItStopped() throws IOException {
        Path local = interruptedUpload();
        long partSize = Files.size(fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX));

        PartTransfers.Outcome outcome = PartTransfers.upload(client, null, local, "/up.bin",
            uploadContext(local), null, TransferCancellation.create());

        assertThat(outcome.resumedFrom()).isEqualTo(partSize);
        assertThat(outcome.bytes()).isEqualTo(SIZE - partSize);
        assertThat(Files.readAllBytes(fixture.root().resolve("up.bin"))).isEqualTo(data);
        assertThat(Files.exists(fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX))).isFalse();
        assertThat(index.size()).isEqualTo(0);
    }

    @Test
    void aChangedRemoteSourceTimeRestartsTheDownload() throws IOException {
        Path target = interruptedDownload();
        Path source = fixture.root().resolve("remote.bin");
        Files.setLastModifiedTime(source, FileTime.fromMillis(Files.getLastModifiedTime(source).toMillis() + 60_000));

        PartTransfers.Outcome outcome = PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), null, TransferCancellation.create());

        assertThat(outcome.resumedFrom()).isEqualTo(0);
        assertThat(outcome.bytes()).isEqualTo(SIZE);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
    }

    @Test
    void aChangedLocalSourceTimeRestartsTheUpload() throws IOException {
        Path local = interruptedUpload();
        Files.setLastModifiedTime(local, FileTime.fromMillis(Files.getLastModifiedTime(local).toMillis() + 60_000));

        PartTransfers.Outcome outcome = PartTransfers.upload(client, null, local, "/up.bin",
            uploadContext(local), null, TransferCancellation.create());

        assertThat(outcome.resumedFrom()).isEqualTo(0);
        assertThat(Files.readAllBytes(fixture.root().resolve("up.bin"))).isEqualTo(data);
    }

    @Test
    void aCorruptedTailByteInTheLocalPartFailsTheOverlapCheck() throws IOException {
        Path target = interruptedDownload();
        Path part = PartFiles.localPart(target);
        flipLastByteKeepingTheTime(part);

        PartTransfers.Outcome outcome = PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), null, TransferCancellation.create());

        assertThat(outcome.resumedFrom()).isEqualTo(0);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
    }

    @Test
    void aCorruptedTailByteInTheRemotePartFailsTheOverlapCheck() throws IOException {
        Path local = interruptedUpload();
        flipLastByteKeepingTheTime(fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX));

        PartTransfers.Outcome outcome = PartTransfers.upload(client, null, local, "/up.bin",
            uploadContext(local), null, TransferCancellation.create());

        assertThat(outcome.resumedFrom()).isEqualTo(0);
        assertThat(Files.readAllBytes(fixture.root().resolve("up.bin"))).isEqualTo(data);
    }

    @Test
    void aPartThatChangedSinceTheFailureRestarts() throws IOException {
        Path target = interruptedDownload();
        Path part = PartFiles.localPart(target);
        Files.write(part, new byte[] {1}, java.nio.file.StandardOpenOption.APPEND);

        PartTransfers.Outcome outcome = PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), null, TransferCancellation.create());

        assertThat(outcome.resumedFrom()).isEqualTo(0);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
    }

    @Test
    void aLinkPlantedAtTheLocalPartIsRefusedNotFollowedNorRemoved() throws IOException {
        requirePosix();
        Path target = interruptedDownload();
        Path part = PartFiles.localPart(target);
        Path victim = Files.writeString(tmp.resolve("victim.txt"), "keep me");
        Files.delete(part);
        Files.createSymbolicLink(part, victim);

        expectThrows(PartExistsException.class, () -> PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), null, TransferCancellation.create()));

        assertThat(Files.isSymbolicLink(part)).isTrue();
        assertThat(Files.readString(victim)).isEqualTo("keep me");
        assertThat(Files.exists(target, LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void aLocalPartWithoutAnEntryIsNeverDeleted() throws IOException {
        // Another tab or korTTY may be writing it right now: deleting it would let that transfer
        // move this one's incomplete part onto the target when it finishes.
        Files.write(fixture.root().resolve("remote.bin"), data);
        Path target = downloads().resolve("remote.bin");
        Path part = Files.writeString(PartFiles.localPart(target), "someone else's");

        expectThrows(PartExistsException.class, () -> PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), null, TransferCancellation.create()));

        assertThat(Files.readString(part)).isEqualTo("someone else's");
        assertThat(Files.exists(target)).isFalse();
    }

    @Test
    void aRemotePartWithoutAnEntryIsNeverDeleted() throws IOException {
        Path local = Files.write(tmp.resolve("local.bin"), data);
        Path part = Files.writeString(fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX), "in flight");

        expectThrows(PartExistsException.class, () -> PartTransfers.upload(client, null, local, "/up.bin",
            uploadContext(local), null, TransferCancellation.create()));

        assertThat(Files.readString(part)).isEqualTo("in flight");
        assertThat(Files.exists(fixture.root().resolve("up.bin"))).isFalse();
    }

    @Test
    void aRemotePartOfAnotherOwnerIsNeverDeletedEvenWhenTheSourceChanged() throws IOException {
        Path local = interruptedUpload();
        ResumeIndex.Key key = uploadContext(local).key();
        ResumeIndex.Entry entry = index.get(key).orElseThrow();
        index.put(key, new ResumeIndex.Entry(entry.sourceSize(), entry.sourceMtimeMillis(), entry.partSize(),
            entry.partMtimeMillis(), "uid:999999", entry.updatedAtMillis()));
        Files.write(local, new byte[] {1, 2, 3});
        Path part = fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX);
        long partSize = Files.size(part);

        expectThrows(PartExistsException.class, () -> PartTransfers.upload(client, null, local, "/up.bin",
            uploadContext(local), null, TransferCancellation.create()));

        assertThat(Files.size(part)).isEqualTo(partSize);
    }

    @Test
    void cancelForgetsTheTransferAndDeletesThePart() throws IOException {
        Path target = interruptedDownload();
        TransferCancellation cancel = TransferCancellation.create();

        expectThrows(TransferCancelledException.class, () -> PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), (done, total) -> cancel.cancel(), cancel));

        assertThat(index.get(downloadContext(target).key())).isEmpty();
        assertThat(Files.exists(PartFiles.localPart(target), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void theIndexHoldsNoPathsAfterAFailure() throws IOException {
        interruptedDownload();
        String json = Files.readString(index.file());
        assertThat(json).doesNotContain("remote.bin");
        assertThat(json).doesNotContain("downloads");
    }

    private static void flipLastByteKeepingTheTime(Path file) throws IOException {
        FileTime time = Files.getLastModifiedTime(file);
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "rw")) {
            raf.seek(raf.length() - 1);
            int value = raf.read();
            raf.seek(raf.length() - 1);
            raf.write(value ^ 0xFF);
        }
        Files.setLastModifiedTime(file, time);
    }

    private static void requirePosix() {
        if (!java.nio.file.FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            throw new SkipException("symbolic links need a POSIX file system here");
        }
    }
}
