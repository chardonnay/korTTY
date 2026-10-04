package de.kortty.core.sftp.transfer;

import de.kortty.core.sftp.SftpLoopbackFixture;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Random;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

/**
 * The partial-file settings of SFTP-08 against a loopback SSHD: "keep the partial file when a
 * transfer is cancelled" keeps it with its resume record so the retry continues it, and with resume
 * switched off a failed transfer removes its partial file, so the retry starts over instead of
 * stopping at a leftover.
 */
class PartRetentionIntegrationTest {

    private static final int SIZE = 512 * 1024 + 5;
    private static final long CUT = SIZE / 2;

    private Path tmp;
    private SftpLoopbackFixture fixture;
    private SftpClient client;
    private byte[] data;
    private ResumeIndex index;

    @BeforeMethod
    void setUp() throws IOException {
        tmp = Files.createTempDirectory("kortty-sftp-retention-it-");
        data = new byte[SIZE];
        new Random(8).nextBytes(data);
        index = new ResumeIndex(tmp.resolve("config").resolve(ResumeIndex.FILE_NAME));
        fixture = SftpLoopbackFixture.builder(tmp).start();
        client = fixture.openSftp(fixture.connect(), 3);
        Files.write(fixture.root().resolve("remote.bin"), data);
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

    private Path target() throws IOException {
        return Files.createDirectories(tmp.resolve("downloads")).resolve("remote.bin");
    }

    private ResumeContext downloadContext(Path target) {
        return new ResumeContext(index, ResumeIndex.Key.of("conn", TransferDirection.DOWNLOAD, "/remote.bin", target));
    }

    private ResumeContext uploadContext(Path local) {
        return new ResumeContext(index, ResumeIndex.Key.of("conn", TransferDirection.UPLOAD, "/up.bin", local));
    }

    private static TransferProgressListener cancelAtHalf(TransferCancellation cancel) {
        return (done, total) -> {
            if (done >= CUT) {
                cancel.cancel();
            }
        };
    }

    private static TransferProgressListener failAtHalf() {
        return (done, total) -> {
            if (done >= CUT) {
                throw new UncheckedIOException(new IOException("simulated connection drop"));
            }
        };
    }

    @Test
    void aCancelledDownloadKeepsItsPartAndTheRetryContinuesIt() throws IOException {
        Path target = target();
        TransferCancellation cancel = TransferCancellation.create();
        expectThrows(TransferCancelledException.class, () -> PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), PartRetention.KEEP, cancelAtHalf(cancel), cancel));

        Path part = PartFiles.localPart(target);
        assertWithMessage("KEEP keeps the partial file of a cancelled download")
            .that(Files.exists(part, LinkOption.NOFOLLOW_LINKS)).isTrue();
        assertThat(index.get(downloadContext(target).key())).isPresent();

        PartTransfers.Outcome outcome = PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), PartRetention.KEEP, null, TransferCancellation.create());
        assertThat(outcome.resumedFrom()).isGreaterThan(0L);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
        assertThat(index.size()).isEqualTo(0);
    }

    @Test
    void aCancelledUploadKeepsItsPartAndTheRetryContinuesIt() throws IOException {
        Path local = Files.write(tmp.resolve("local.bin"), data);
        TransferCancellation cancel = TransferCancellation.create();
        expectThrows(TransferCancelledException.class, () -> PartTransfers.upload(client, null, local, "/up.bin",
            uploadContext(local), PartRetention.KEEP, cancelAtHalf(cancel), cancel));

        Path part = fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX);
        assertThat(Files.exists(part)).isTrue();
        assertThat(index.get(uploadContext(local).key())).isPresent();

        PartTransfers.Outcome outcome = PartTransfers.upload(client, null, local, "/up.bin",
            uploadContext(local), PartRetention.KEEP, null, TransferCancellation.create());
        assertThat(outcome.resumedFrom()).isGreaterThan(0L);
        assertThat(Files.readAllBytes(fixture.root().resolve("up.bin"))).isEqualTo(data);
        assertThat(Files.exists(part)).isFalse();
    }

    @Test
    void theDefaultStillDeletesTheDownloadPartOnCancel() throws IOException {
        Path target = target();
        TransferCancellation cancel = TransferCancellation.create();
        expectThrows(TransferCancelledException.class, () -> PartTransfers.download(client, "/remote.bin", target,
            downloadContext(target), PartRetention.KEEP_ON_FAILURE, cancelAtHalf(cancel), cancel));

        assertThat(Files.exists(PartFiles.localPart(target), LinkOption.NOFOLLOW_LINKS)).isFalse();
        assertThat(index.size()).isEqualTo(0);
    }

    @Test
    void keepWithoutAResumeContextStillDeletesOnCancelBecauseNothingCouldContinueIt() throws IOException {
        Path target = target();
        TransferCancellation cancel = TransferCancellation.create();
        expectThrows(TransferCancelledException.class, () -> PartTransfers.download(client, "/remote.bin", target,
            null, PartRetention.KEEP, cancelAtHalf(cancel), cancel));

        assertThat(Files.exists(PartFiles.localPart(target), LinkOption.NOFOLLOW_LINKS)).isFalse();
    }

    @Test
    void withResumeOffAFailedDownloadRemovesItsPartSoTheRetryStartsOver() throws IOException {
        Path target = target();
        expectThrows(IOException.class, () -> PartTransfers.download(client, "/remote.bin", target,
            null, PartRetention.DISCARD, failAtHalf(), TransferCancellation.create()));
        assertThat(Files.exists(PartFiles.localPart(target), LinkOption.NOFOLLOW_LINKS)).isFalse();

        PartTransfers.Outcome outcome = PartTransfers.download(client, "/remote.bin", target,
            null, PartRetention.DISCARD, null, TransferCancellation.create());
        assertThat(outcome.resumedFrom()).isEqualTo(0L);
        assertThat(Files.readAllBytes(target)).isEqualTo(data);
    }

    @Test
    void withResumeOffAFailedUploadRemovesItsPart() throws IOException {
        Path local = Files.write(tmp.resolve("local.bin"), data);
        expectThrows(IOException.class, () -> PartTransfers.upload(client, null, local, "/up.bin",
            null, PartRetention.DISCARD, failAtHalf(), TransferCancellation.create()));

        assertThat(Files.exists(fixture.root().resolve("up.bin" + PartFiles.PART_SUFFIX))).isFalse();
        PartTransfers.upload(client, null, local, "/up.bin", null, PartRetention.DISCARD, null,
            TransferCancellation.create());
        assertThat(Files.readAllBytes(fixture.root().resolve("up.bin"))).isEqualTo(data);
    }

    @Test
    void transferSettingsPickTheRetentionFromResumeAndTheCancelSwitch() {
        TransferSettings noResume = TransferSettings.defaults().withKeepPartialOnCancel(true);
        assertWithMessage("without resume nothing could continue a kept part")
            .that(noResume.partRetention()).isEqualTo(PartRetention.DISCARD);
        TransferSettings resume = TransferSettings.defaults().withResume(index, "conn");
        assertThat(resume.partRetention()).isEqualTo(PartRetention.KEEP_ON_FAILURE);
        assertThat(resume.withKeepPartialOnCancel(true).partRetention()).isEqualTo(PartRetention.KEEP);
    }
}
