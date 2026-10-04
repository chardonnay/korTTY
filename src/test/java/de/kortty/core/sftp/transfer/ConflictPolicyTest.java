package de.kortty.core.sftp.transfer;

import de.kortty.core.sftp.transfer.ConflictInfo.EntryType;
import de.kortty.core.sftp.transfer.ConflictResolver.Resolution;
import org.testng.annotations.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/** Sticky answers per kind, cancel propagation and the symbolic-link rule. */
class ConflictPolicyTest {

    private static ConflictInfo file(String name) {
        return ConflictInfo.files(TransferDirection.UPLOAD, "/local/" + name, "/srv", name, 10, 20, 1L, 2L);
    }

    private static ConflictInfo symlink(String name) {
        return new ConflictInfo(TransferDirection.UPLOAD, "/local/" + name, "/srv", name,
            EntryType.FILE, EntryType.FILE, true, 10, 5, null, null, false);
    }

    private static ConflictInfo folder(String name) {
        return new ConflictInfo(TransferDirection.DOWNLOAD, "/srv/" + name, "/home", name,
            EntryType.FOLDER, EntryType.FOLDER, false, -1, -1, null, null, false);
    }

    private static ConflictInfo mismatch(String name) {
        return new ConflictInfo(TransferDirection.DOWNLOAD, "/srv/" + name, "/home", name,
            EntryType.FILE, EntryType.FOLDER, false, 3, -1, null, null, false);
    }

    /** Answers with {@code answer} and counts how often it was asked. */
    private static final class Counting implements ConflictResolver {
        final AtomicInteger asked = new AtomicInteger();
        final Resolution answer;

        Counting(ConflictAction action, boolean applyToAll) {
            this.answer = new Resolution(action, applyToAll);
        }

        @Override
        public Resolution resolve(ConflictInfo info, ConflictPolicy policy) {
            asked.incrementAndGet();
            return answer;
        }
    }

    @Test
    void kindsFollowTheEntryTypes() {
        assertThat(file("a").kind()).isEqualTo(ConflictKind.FILE);
        assertThat(symlink("a").kind()).isEqualTo(ConflictKind.SYMLINK);
        assertThat(folder("a").kind()).isEqualTo(ConflictKind.FOLDER);
        assertThat(mismatch("a").kind()).isEqualTo(ConflictKind.TYPE_MISMATCH);
        assertThat(mismatch("a").allowedActions())
            .containsExactly(ConflictAction.SKIP, ConflictAction.RENAME, ConflictAction.CANCEL_ALL).inOrder();
        assertThat(symlink("a").allowedActions()).doesNotContain(ConflictAction.OVERWRITE);
    }

    @Test
    void withoutApplyToAllEveryConflictIsAsked() throws InterruptedException {
        ConflictPolicy policy = new ConflictPolicy();
        Counting resolver = new Counting(ConflictAction.OVERWRITE, false);
        assertThat(policy.resolve(file("a"), resolver)).isEqualTo(ConflictAction.OVERWRITE);
        assertThat(policy.resolve(file("b"), resolver)).isEqualTo(ConflictAction.OVERWRITE);
        assertThat(resolver.asked.get()).isEqualTo(2);
    }

    @Test
    void applyToAllIsStickyPerKindOnly() throws InterruptedException {
        ConflictPolicy policy = new ConflictPolicy();
        Counting overwrite = new Counting(ConflictAction.OVERWRITE, true);
        assertThat(policy.resolve(file("a"), overwrite)).isEqualTo(ConflictAction.OVERWRITE);
        assertThat(policy.resolve(file("b"), overwrite)).isEqualTo(ConflictAction.OVERWRITE);
        assertThat(overwrite.asked.get()).isEqualTo(1);

        Counting rename = new Counting(ConflictAction.RENAME, true);
        assertThat(policy.resolve(mismatch("c"), rename)).isEqualTo(ConflictAction.RENAME);
        assertThat(policy.resolve(mismatch("d"), rename)).isEqualTo(ConflictAction.RENAME);
        assertThat(rename.asked.get()).isEqualTo(1);
        // The FILE answer is still OVERWRITE; the mismatch answer did not leak into it.
        assertThat(policy.resolve(file("e"), rename)).isEqualTo(ConflictAction.OVERWRITE);
        assertThat(rename.asked.get()).isEqualTo(1);
    }

    @Test
    void aSymlinkNeverAutoOverwrites() throws InterruptedException {
        ConflictPolicy policy = new ConflictPolicy(ConflictAction.OVERWRITE);
        Counting overwriteAll = new Counting(ConflictAction.OVERWRITE, true);
        // The default answers plain files without asking...
        assertThat(policy.resolve(file("a"), overwriteAll)).isEqualTo(ConflictAction.OVERWRITE);
        assertThat(overwriteAll.asked.get()).isEqualTo(0);
        // ...but a symbolic link is asked, and an OVERWRITE answer is refused as SKIP.
        assertThat(policy.resolve(symlink("l1"), overwriteAll)).isEqualTo(ConflictAction.SKIP);
        assertThat(policy.resolve(symlink("l2"), overwriteAll)).isEqualTo(ConflictAction.SKIP);
        assertThat(overwriteAll.asked.get()).isEqualTo(2);
        assertThat(policy.answeredMeanwhile(symlink("l3"))).isNull();
    }

    @Test
    void foldersMergeWithoutAsking() throws InterruptedException {
        ConflictPolicy policy = new ConflictPolicy();
        Counting resolver = new Counting(ConflictAction.SKIP, false);
        assertThat(policy.resolve(folder("dir"), resolver)).isEqualTo(ConflictAction.OVERWRITE);
        assertThat(resolver.asked.get()).isEqualTo(0);
    }

    @Test
    void aSkipDefaultAppliesToEveryKindButFolders() throws InterruptedException {
        ConflictPolicy policy = new ConflictPolicy(ConflictAction.SKIP);
        Counting resolver = new Counting(ConflictAction.OVERWRITE, false);
        assertThat(policy.resolve(file("a"), resolver)).isEqualTo(ConflictAction.SKIP);
        assertThat(policy.resolve(symlink("b"), resolver)).isEqualTo(ConflictAction.SKIP);
        assertThat(policy.resolve(mismatch("c"), resolver)).isEqualTo(ConflictAction.SKIP);
        assertThat(policy.resolve(folder("d"), resolver)).isEqualTo(ConflictAction.OVERWRITE);
        assertThat(resolver.asked.get()).isEqualTo(0);
    }

    @Test
    void cancelAllPropagatesToEveryLaterConflict() throws InterruptedException {
        ConflictPolicy policy = new ConflictPolicy();
        Counting cancel = new Counting(ConflictAction.CANCEL_ALL, false);
        assertThat(policy.resolve(file("a"), cancel)).isEqualTo(ConflictAction.CANCEL_ALL);
        assertThat(policy.isCancelled()).isTrue();
        assertThat(policy.resolve(file("b"), cancel)).isEqualTo(ConflictAction.CANCEL_ALL);
        assertThat(policy.resolve(folder("c"), cancel)).isEqualTo(ConflictAction.CANCEL_ALL);
        assertThat(policy.resolve(symlink("d"), cancel)).isEqualTo(ConflictAction.CANCEL_ALL);
        assertThat(cancel.asked.get()).isEqualTo(1);
    }

    @Test
    void aMissingAnswerCancelsAndCancelStopsAsking() throws InterruptedException {
        ConflictPolicy policy = new ConflictPolicy();
        assertThat(policy.resolve(file("a"), (info, p) -> null)).isEqualTo(ConflictAction.CANCEL_ALL);

        ConflictPolicy other = new ConflictPolicy();
        other.cancel();
        Counting resolver = new Counting(ConflictAction.OVERWRITE, false);
        assertThat(other.resolve(file("a"), resolver)).isEqualTo(ConflictAction.CANCEL_ALL);
        assertThat(resolver.asked.get()).isEqualTo(0);
    }

    @Test
    void cancelAllIsNotADefault() {
        org.testng.Assert.assertThrows(IllegalArgumentException.class,
            () -> new ConflictPolicy(ConflictAction.CANCEL_ALL));
    }
}
