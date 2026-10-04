package de.kortty.core.sftp.transfer;

import de.kortty.core.sftp.transfer.ResumePlanner.Decision;
import de.kortty.core.sftp.transfer.ResumePlanner.Kind;
import de.kortty.core.sftp.transfer.ResumePlanner.PartState;
import de.kortty.core.sftp.transfer.ResumePlanner.Reason;
import de.kortty.core.sftp.transfer.ResumePlanner.SourceState;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/** Every rule of {@link ResumePlanner}: when a part is continued, and every reason it is not. */
class ResumePlannerTest {

    private static final long SIZE = 1_000_000;
    private static final long MTIME = 1_700_000_000_000L;
    private static final String OWNER = "uid:1000";

    private static ResumeIndex.Entry entry(long partSize, long partMtime) {
        return new ResumeIndex.Entry(SIZE, MTIME, partSize, partMtime, OWNER, 0);
    }

    private static PartState part(long size, long mtime) {
        return new PartState(true, true, size, mtime, OWNER);
    }

    @Test
    void noPartIsAFreshStartWithOrWithoutAnEntry() {
        SourceState source = new SourceState(SIZE, MTIME);
        assertThat(ResumePlanner.plan(null, source, PartState.ABSENT, true).kind()).isEqualTo(Kind.FRESH);
        assertThat(ResumePlanner.plan(entry(400, 7), source, PartState.ABSENT, false).kind()).isEqualTo(Kind.FRESH);
    }

    @Test
    void aPartWithoutAnEntryIsRestarted() {
        Decision decision = ResumePlanner.plan(null, new SourceState(SIZE, MTIME), part(400, 7), false);
        assertThat(decision).isEqualTo(new Decision(Kind.RESTART, 0, Reason.NO_INDEX_ENTRY));
    }

    @Test
    void aMatchingPartIsResumedAtItsSize() {
        Decision decision = ResumePlanner.plan(entry(400_000, 7), new SourceState(SIZE, MTIME), part(400_000, 7), true);
        assertThat(decision).isEqualTo(new Decision(Kind.RESUME, 400_000, Reason.NONE));
    }

    @Test
    void aPartOfUnknownStateIsResumedAtItsSize() {
        Decision decision = ResumePlanner.plan(entry(-1, 0), new SourceState(SIZE, MTIME), part(123_456, 99), true);
        assertThat(decision).isEqualTo(new Decision(Kind.RESUME, 123_456, Reason.NONE));
    }

    @Test
    void aCompletePartIsResumedAtTheEnd() {
        Decision decision = ResumePlanner.plan(entry(SIZE, 7), new SourceState(SIZE, MTIME), part(SIZE, 7), false);
        assertThat(decision).isEqualTo(new Decision(Kind.RESUME, SIZE, Reason.NONE));
    }

    @DataProvider
    Object[][] sizeAndTimeCombinations() {
        return new Object[][] {
            // source size, source mtime, part size, part mtime -> expected reason
            {SIZE, MTIME, 400L, 7L, Reason.NONE},
            {SIZE + 1, MTIME, 400L, 7L, Reason.SOURCE_SIZE_CHANGED},
            {SIZE - 1, MTIME, 400L, 7L, Reason.SOURCE_SIZE_CHANGED},
            {SIZE, MTIME + 1000, 400L, 7L, Reason.SOURCE_TIME_CHANGED},
            {SIZE, MTIME - 1000, 400L, 7L, Reason.SOURCE_TIME_CHANGED},
            {SIZE + 1, MTIME + 1000, 400L, 7L, Reason.SOURCE_SIZE_CHANGED},
            {SIZE, MTIME, 401L, 7L, Reason.PART_CHANGED},
            {SIZE, MTIME, 399L, 7L, Reason.PART_CHANGED},
            {SIZE, MTIME, 400L, 8L, Reason.PART_CHANGED},
            {SIZE, MTIME, SIZE + 1, 7L, Reason.PART_LARGER_THAN_SOURCE},
        };
    }

    @Test(dataProvider = "sizeAndTimeCombinations")
    void sizeAndTimeMustBeUnchanged(long sourceSize, long sourceMtime, long partSize, long partMtime,
            Reason expected) {
        Decision decision = ResumePlanner.plan(entry(400, 7), new SourceState(sourceSize, sourceMtime),
            part(partSize, partMtime), false);
        if (expected == Reason.NONE) {
            assertThat(decision.kind()).isEqualTo(Kind.RESUME);
            assertThat(decision.offset()).isEqualTo(partSize);
        } else {
            assertThat(decision).isEqualTo(new Decision(Kind.RESTART, 0, expected));
        }
    }

    @Test
    void anUnknownSourceTimeNeverResumes() {
        Decision decision = ResumePlanner.plan(entry(400, 7), new SourceState(SIZE, null), part(400, 7), false);
        assertThat(decision.reason()).isEqualTo(Reason.SOURCE_TIME_UNKNOWN);
    }

    @Test
    void aPartLargerThanTheSourceRestartsEvenWithUnknownState() {
        Decision decision = ResumePlanner.plan(entry(-1, 0), new SourceState(SIZE, MTIME), part(SIZE + 10, 7), true);
        assertThat(decision.reason()).isEqualTo(Reason.PART_LARGER_THAN_SOURCE);
    }

    @Test
    void aSymlinkOrFolderAtThePartNameRestarts() {
        PartState link = new PartState(true, false, 400, 7, OWNER);
        assertThat(ResumePlanner.plan(entry(400, 7), new SourceState(SIZE, MTIME), link, true))
            .isEqualTo(new Decision(Kind.RESTART, 0, Reason.NOT_A_REGULAR_FILE));
        assertThat(ResumePlanner.plan(null, new SourceState(SIZE, MTIME), link, false).reason())
            .isEqualTo(Reason.NOT_A_REGULAR_FILE);
    }

    @Test
    void aForeignOrUnknownOwnerOfARemotePartRestarts() {
        SourceState source = new SourceState(SIZE, MTIME);
        assertThat(ResumePlanner.plan(entry(400, 7), source, new PartState(true, true, 400, 7, "uid:0"), true).reason())
            .isEqualTo(Reason.PART_OWNER_DIFFERS);
        assertThat(ResumePlanner.plan(entry(400, 7), source, new PartState(true, true, 400, 7, null), true).reason())
            .isEqualTo(Reason.PART_OWNER_DIFFERS);
        ResumeIndex.Entry noOwner = new ResumeIndex.Entry(SIZE, MTIME, 400, 7, null, 0);
        assertThat(ResumePlanner.plan(noOwner, source, part(400, 7), true).reason())
            .isEqualTo(Reason.PART_OWNER_DIFFERS);
        // A local part is not checked for its owner.
        assertThat(ResumePlanner.plan(noOwner, source, new PartState(true, true, 400, 7, null), false).kind())
            .isEqualTo(Kind.RESUME);
    }

    @Test
    void anEmptyPartRestarts() {
        Decision decision = ResumePlanner.plan(entry(0, 7), new SourceState(SIZE, MTIME), part(0, 7), false);
        assertThat(decision.reason()).isEqualTo(Reason.PART_EMPTY);
    }

    @Test
    void theOverlapIsAtMost64KilobytesAndNeverMoreThanTheOffset() {
        assertThat(ResumePlanner.overlapLength(0)).isEqualTo(0);
        assertThat(ResumePlanner.overlapLength(100)).isEqualTo(100);
        assertThat(ResumePlanner.overlapLength(64 * 1024)).isEqualTo(64 * 1024);
        assertThat(ResumePlanner.overlapLength(10L * 1024 * 1024 * 1024)).isEqualTo(64 * 1024);
    }
}
