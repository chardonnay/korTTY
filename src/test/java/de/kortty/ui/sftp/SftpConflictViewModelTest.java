package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.ConflictAction;
import de.kortty.core.sftp.transfer.ConflictInfo;
import de.kortty.core.sftp.transfer.ConflictInfo.EntryType;
import de.kortty.core.sftp.transfer.TransferDirection;
import org.testng.annotations.Test;

import java.time.ZoneOffset;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

/** What the conflict dialog shows, headless. */
class SftpConflictViewModelTest {

    private static SftpConflictViewModel model(ConflictInfo info) {
        return SftpConflictViewModel.of(info, ZoneOffset.UTC, Locale.ROOT);
    }

    @Test
    void aFileConflictOffersAllFourAnswersAndMarksTheNewerSide() {
        ConflictInfo info = ConflictInfo.files(TransferDirection.UPLOAD, "/home/me/a.txt", "/srv", "a.txt",
            1536, 10, 2_000L, 1_000L);
        SftpConflictViewModel model = model(info);
        assertThat(model.actions().stream().map(SftpConflictViewModel.Action::action).toList())
            .containsExactly(ConflictAction.OVERWRITE, ConflictAction.SKIP, ConflictAction.RENAME,
                ConflictAction.CANCEL_ALL).inOrder();
        assertThat(model.actions().stream().filter(SftpConflictViewModel.Action::cancel).toList()).hasSize(1);
        assertThat(model.source().newer()).isTrue();
        assertThat(model.existing().newer()).isFalse();
        assertThat(model.source().size()).isEqualTo("1.5 KB (1,536 B)");
        assertThat(model.existing().size()).isEqualTo("10 B");
        assertThat(model.notes()).isEmpty();
        assertThat(model.header()).contains("a.txt");
        assertThat(model.header()).contains("/srv");
        assertThat(model.rowLabels()).hasSize(2);
    }

    @Test
    void aSymlinkHasNoReplaceButtonAndANote() {
        ConflictInfo info = new ConflictInfo(TransferDirection.DOWNLOAD, "/srv/l", "/home", "l",
            EntryType.FILE, EntryType.FILE, true, 5, -1, null, null, false);
        SftpConflictViewModel model = model(info);
        assertThat(model.actions().stream().map(SftpConflictViewModel.Action::action).toList())
            .doesNotContain(ConflictAction.OVERWRITE);
        assertThat(model.notes()).hasSize(1);
        assertThat(model.existing().newer()).isFalse();
        assertThat(model.source().newer()).isFalse();
    }

    @Test
    void aTypeMismatchOffersOnlySkipAndRename() {
        ConflictInfo info = new ConflictInfo(TransferDirection.UPLOAD, "/home/me/x", "/srv", "x",
            EntryType.FOLDER, EntryType.FILE, false, -1, 7, null, 1_000L, false);
        SftpConflictViewModel model = model(info);
        assertThat(model.actions().stream().map(SftpConflictViewModel.Action::action).toList())
            .containsExactly(ConflictAction.SKIP, ConflictAction.RENAME, ConflictAction.CANCEL_ALL).inOrder();
        assertThat(model.notes()).hasSize(1);
        ConflictInfo fileOntoFolder = new ConflictInfo(TransferDirection.UPLOAD, "/home/me/x", "/srv", "x",
            EntryType.FILE, EntryType.FOLDER, false, 7, -1, null, null, false);
        assertThat(model.header()).isNotEqualTo(model(fileOntoFolder).header());
    }

    @Test
    void anotherOwnerIsExplained() {
        ConflictInfo info = new ConflictInfo(TransferDirection.UPLOAD, "/home/me/a", "/srv", "a",
            EntryType.FILE, EntryType.FILE, false, 1, 1, null, null, true);
        assertThat(model(info).notes()).hasSize(1);
    }

    @Test
    void unknownValuesAndLargeSizesAreFormatted() {
        assertThat(SftpConflictViewModel.sizeText(EntryType.FILE, false, 5L * 1024 * 1024 * 1024, Locale.ROOT))
            .isEqualTo("5.0 GB (5,368,709,120 B)");
        assertThat(SftpConflictViewModel.sizeText(EntryType.FILE, false, -1, Locale.ROOT))
            .isEqualTo(SftpConflictViewModel.timeText(null, ZoneOffset.UTC, Locale.ROOT));
        assertThat(SftpConflictViewModel.timeText(0L, ZoneOffset.UTC, Locale.ENGLISH)).contains("1970");
    }
}
