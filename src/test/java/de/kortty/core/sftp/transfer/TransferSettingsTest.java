package de.kortty.core.sftp.transfer;

import org.testng.annotations.Test;

import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/** Parallelism clamps (D1), the borrowed-session cap (D24) and when resume is on. */
class TransferSettingsTest {

    @Test
    void parallelTransfersAreClampedAndCappedOnABorrowedSession() {
        assertThat(TransferSettings.defaults().parallelTransfers()).isEqualTo(3);
        assertThat(TransferSettings.defaults().withParallelTransfers(0).parallelTransfers()).isEqualTo(1);
        assertThat(TransferSettings.defaults().withParallelTransfers(99).parallelTransfers()).isEqualTo(8);

        TransferSettings eight = TransferSettings.defaults().withParallelTransfers(8);
        assertThat(eight.channelsFor(true)).isEqualTo(8);
        assertThat(eight.channelsFor(false)).isEqualTo(TransferSettings.BORROWED_SESSION_CAP);
        assertThat(TransferSettings.defaults().withParallelTransfers(1).channelsFor(false)).isEqualTo(1);
    }

    @Test
    void resumeNeedsAnIndexAndAScope() {
        ResumeIndex index = new ResumeIndex(Path.of("unused-resume-index.json"));
        assertThat(TransferSettings.defaults().resumeEnabled()).isFalse();
        assertThat(TransferSettings.defaults().withResume(index, " ").resumeEnabled()).isFalse();
        assertThat(TransferSettings.defaults().withResume(index, "conn").resumeEnabled()).isTrue();
    }

    @Test
    void onlyOverwriteSkipOrAskAreConflictDefaults() {
        assertThat(TransferSettings.defaults().conflictDefault()).isNull();
        assertThat(TransferSettings.defaults().withConflictDefault(ConflictAction.SKIP).conflictDefault())
            .isEqualTo(ConflictAction.SKIP);
        expectThrows(IllegalArgumentException.class,
            () -> TransferSettings.defaults().withConflictDefault(ConflictAction.CANCEL_ALL));
        expectThrows(IllegalArgumentException.class,
            () -> TransferSettings.defaults().withConflictDefault(ConflictAction.RENAME));
    }
}
