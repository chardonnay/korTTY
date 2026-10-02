package de.kortty.ui.sftp;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SftpDragOutPolicyTest {

    private static final int REGULAR_FILE = 0100644;

    @Test
    void smallSelectionOfFilesMayLeaveTheWindow() {
        assertThat(SftpDragOutPolicy.check(List.of(file("a.txt", 10), file("b.txt", 2_000))))
            .isEqualTo(SftpDragOutPolicy.Verdict.ALLOWED);
        assertThat(SftpDragOutPolicy.check(List.of(file("empty", 0))))
            .isEqualTo(SftpDragOutPolicy.Verdict.ALLOWED);
    }

    @Test
    void atMostTwentyFiles() {
        List<SftpFileItem> twenty = files(SftpDragOutPolicy.MAX_FILES, 1);
        assertThat(SftpDragOutPolicy.check(twenty)).isEqualTo(SftpDragOutPolicy.Verdict.ALLOWED);

        List<SftpFileItem> more = new ArrayList<>(twenty);
        more.add(file("one-more", 1));
        assertThat(SftpDragOutPolicy.check(more)).isEqualTo(SftpDragOutPolicy.Verdict.TOO_MANY_FILES);
    }

    @Test
    void atMostSixteenMebibytesTogether() {
        long half = SftpDragOutPolicy.MAX_TOTAL_BYTES / 2;
        assertThat(SftpDragOutPolicy.check(List.of(file("a", half), file("b", half))))
            .isEqualTo(SftpDragOutPolicy.Verdict.ALLOWED);
        assertThat(SftpDragOutPolicy.check(List.of(file("a", half), file("b", half + 1))))
            .isEqualTo(SftpDragOutPolicy.Verdict.TOO_LARGE);
        assertThat(SftpDragOutPolicy.check(List.of(file("huge", 100L * 1024 * 1024))))
            .isEqualTo(SftpDragOutPolicy.Verdict.TOO_LARGE);
        // A file of unknown size could be anything.
        assertThat(SftpDragOutPolicy.check(List.of(file("unknown", -1))))
            .isEqualTo(SftpDragOutPolicy.Verdict.TOO_LARGE);
    }

    @Test
    void foldersStayInsideTheWindow() {
        SftpFileItem folder = SftpFileItem.fromDetails("logs", "/srv/logs", false, "—", "", "", "", "", -1);
        SftpFileItem parent = SftpFileItem.fromDetails("..", "/srv", false, "—", "", "", "", "", -1);

        assertThat(SftpDragOutPolicy.check(List.of(file("a.txt", 1), folder)))
            .isEqualTo(SftpDragOutPolicy.Verdict.CONTAINS_FOLDER);
        assertThat(SftpDragOutPolicy.check(List.of(parent)))
            .isEqualTo(SftpDragOutPolicy.Verdict.CONTAINS_FOLDER);
        assertThat(SftpDragOutPolicy.check(List.of())).isEqualTo(SftpDragOutPolicy.Verdict.NOTHING_SELECTED);
        assertThat(SftpDragOutPolicy.check(null)).isEqualTo(SftpDragOutPolicy.Verdict.NOTHING_SELECTED);
    }

    @Test
    void hugeReportedSizesCannotOverflowTheSum() {
        assertThat(SftpDragOutPolicy.check(List.of(file("a", 10), file("b", Long.MAX_VALUE))))
            .isEqualTo(SftpDragOutPolicy.Verdict.TOO_LARGE);
        assertThat(SftpDragOutPolicy.checkResolved(List.of(
                new SftpDragOutPolicy.Resolved(REGULAR_FILE, 10),
                new SftpDragOutPolicy.Resolved(REGULAR_FILE, Long.MAX_VALUE))))
            .isEqualTo(SftpDragOutPolicy.Verdict.TOO_LARGE);
    }

    @Test
    void resolvedNamesMustBeSmallRegularFiles() {
        assertThat(SftpDragOutPolicy.checkResolved(List.of(new SftpDragOutPolicy.Resolved(REGULAR_FILE, 1_000))))
            .isEqualTo(SftpDragOutPolicy.Verdict.ALLOWED);
        // A server that sends no file type: the size still counts.
        assertThat(SftpDragOutPolicy.checkResolved(List.of(new SftpDragOutPolicy.Resolved(0644, 1_000))))
            .isEqualTo(SftpDragOutPolicy.Verdict.ALLOWED);
        // A link that pointed to a large file, a folder or a device such as /dev/zero.
        assertThat(SftpDragOutPolicy.checkResolved(List.of(
                new SftpDragOutPolicy.Resolved(REGULAR_FILE, SftpDragOutPolicy.MAX_TOTAL_BYTES + 1))))
            .isEqualTo(SftpDragOutPolicy.Verdict.TOO_LARGE);
        assertThat(SftpDragOutPolicy.checkResolved(List.of(new SftpDragOutPolicy.Resolved(040755, 4096))))
            .isEqualTo(SftpDragOutPolicy.Verdict.CONTAINS_FOLDER);
        assertThat(SftpDragOutPolicy.checkResolved(List.of(new SftpDragOutPolicy.Resolved(020666, 0))))
            .isEqualTo(SftpDragOutPolicy.Verdict.CONTAINS_FOLDER);
        // No size reported: it could be anything.
        assertThat(SftpDragOutPolicy.checkResolved(List.of(new SftpDragOutPolicy.Resolved(REGULAR_FILE, -1))))
            .isEqualTo(SftpDragOutPolicy.Verdict.TOO_LARGE);
        assertThat(SftpDragOutPolicy.checkResolved(List.of())).isEqualTo(SftpDragOutPolicy.Verdict.NOTHING_SELECTED);
    }

    @Test
    void theWindowWaitsFiveSecondsAtMost() {
        assertThat(SftpDragOutPolicy.MAX_WAIT.toSeconds()).isEqualTo(5);
    }

    private static List<SftpFileItem> files(int count, long size) {
        List<SftpFileItem> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(file("f" + i, size));
        }
        return items;
    }

    private static SftpFileItem file(String name, long size) {
        return SftpFileItem.fromDetails(name, "/srv/" + name, true, size + " B", "", "", "", "", size);
    }
}
