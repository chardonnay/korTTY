package de.kortty.core.sftp;

import de.kortty.core.sftp.FolderComparison.Entry;
import de.kortty.core.sftp.FolderComparison.State;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class FolderComparisonTest {

    private static final long T = 1_760_000_000_000L;

    private static Entry file(String path, long size, long time) {
        return new Entry(path, false, size, time);
    }

    private static Entry dir(String path) {
        return new Entry(path, true, 0, T);
    }

    private static FolderComparison sample() {
        List<Entry> local = List.of(
            file("same.txt", 10, T),
            file("newer-here.txt", 10, T + 60_000),
            file("newer-there.txt", 10, T),
            file("other-size.txt", 11, T + 1_000),     // within the tolerance, other size
            file("only-here.txt", 5, T),
            dir("site"),
            file("site/index.html", 100, T),
            file("site/app.js", 300, T + 90_000),
            dir("new-folder"),
            dir("new-folder/sub"),
            file("new-folder/sub/a.bin", 1_000, T),
            file("new-folder/b.bin", 2_000, T),
            file("clash", 1, T));
        List<Entry> remote = List.of(
            file("same.txt", 10, T + 999),              // SFTP 3 keeps seconds
            file("newer-here.txt", 10, T),
            file("newer-there.txt", 10, T + 60_000),
            file("other-size.txt", 12, T),
            file("only-there.log", 7, T),
            dir("site"),
            file("site/index.html", 100, T),
            file("site/app.js", 300, T),
            dir("old"),
            file("old/x.txt", 3, T),
            dir("clash"),
            file("clash/inner.txt", 4, T));
        return FolderComparison.compare(local, remote);
    }

    @Test
    void everyPathGetsItsState() {
        FolderComparison comparison = sample();

        assertThat(comparison.state("same.txt")).hasValue(State.SAME);
        assertThat(comparison.state("newer-here.txt")).hasValue(State.NEWER_LOCAL);
        assertThat(comparison.state("newer-there.txt")).hasValue(State.NEWER_REMOTE);
        assertThat(comparison.state("other-size.txt")).hasValue(State.DIFFERENT);
        assertThat(comparison.state("only-here.txt")).hasValue(State.ONLY_LOCAL);
        assertThat(comparison.state("only-there.log")).hasValue(State.ONLY_REMOTE);
        assertThat(comparison.state("site/index.html")).hasValue(State.SAME);
        assertThat(comparison.state("site/app.js")).hasValue(State.NEWER_LOCAL);
        // A folder on both sides with a difference inside it.
        assertThat(comparison.state("site")).hasValue(State.CONTENT_DIFFERS);
        assertThat(comparison.state("new-folder/sub/a.bin")).hasValue(State.ONLY_LOCAL);
        assertThat(comparison.state("clash")).hasValue(State.DIFFERENT);
        assertThat(comparison.state("/site/")).hasValue(State.CONTENT_DIFFERS);
        assertThat(comparison.state("nowhere")).isEmpty();
    }

    @Test
    void aFolderOnOneSideCountsOnceWithEverythingInIt() {
        FolderComparison.Counts counts = sample().counts();

        assertThat(counts.onlyLocal()).isEqualTo(2);    // only-here.txt, new-folder
        assertThat(counts.onlyRemote()).isEqualTo(3);   // only-there.log, old, clash/inner.txt
        assertThat(counts.newerLocal()).isEqualTo(2);   // newer-here.txt, site/app.js
        assertThat(counts.newerRemote()).isEqualTo(1);
        assertThat(counts.different()).isEqualTo(2);    // other-size.txt, clash
        assertThat(counts.same()).isEqualTo(2);         // same.txt, site/index.html
        assertThat(counts.differing()).isEqualTo(10);
    }

    @Test
    void anUploadSyncCopiesMissingNewerAndResizedButLeavesNewerServerFiles() {
        FolderComparison comparison = sample();

        assertThat(comparison.toUpload().stream().map(Entry::relativePath).toList()).containsExactly(
            "new-folder", "newer-here.txt", "only-here.txt", "other-size.txt", "site/app.js").inOrder();
        assertThat(comparison.newerOnRemote()).isEqualTo(1);
        assertThat(comparison.typeConflicts()).isEqualTo(1);
        assertThat(comparison.extraOnRemote().stream().map(Entry::relativePath).toList())
            .containsExactly("old", "only-there.log").inOrder();
        assertThat(comparison.state("clash/inner.txt")).hasValue(State.ONLY_REMOTE);
        // The missing folder is moved with everything in it.
        assertThat(comparison.bytes(comparison.toUpload(), true)).isEqualTo(3_000 + 10 + 5 + 11 + 300);
    }

    @Test
    void aDownloadSyncIsTheMirrorImage() {
        FolderComparison comparison = sample();

        // clash/inner.txt cannot land in "clash", a file here: it is left out.
        assertThat(comparison.toDownload().stream().map(Entry::relativePath).toList())
            .containsExactly("newer-there.txt", "old", "only-there.log", "other-size.txt").inOrder();
        assertThat(comparison.newerOnLocal()).isEqualTo(2);
        assertThat(comparison.extraOnLocal().stream().map(Entry::relativePath).toList())
            .containsExactly("new-folder", "only-here.txt").inOrder();
        assertThat(comparison.bytes(comparison.toDownload(), false)).isEqualTo(10 + 3 + 7 + 12);
    }

    @Test
    void identicalFoldersHaveNothingToDo() {
        List<Entry> side = List.of(dir("a"), file("a/b.txt", 4, T), file("c.txt", 1, T));
        FolderComparison comparison = FolderComparison.compare(side, side);

        assertThat(comparison.state("a")).hasValue(State.SAME);
        assertThat(comparison.counts().differing()).isEqualTo(0);
        assertThat(comparison.toUpload()).isEmpty();
        assertThat(comparison.toDownload()).isEmpty();
    }

    @Test
    void entriesAreNormalized() {
        Entry entry = new Entry("\\docs\\readme.md/", false, -5, T);
        assertThat(entry.relativePath()).isEqualTo("docs/readme.md");
        assertThat(entry.name()).isEqualTo("readme.md");
        assertThat(entry.parent()).isEqualTo("docs");
        assertThat(entry.size()).isEqualTo(0);
        assertThat(new Entry("top", true, 99, T).size()).isEqualTo(-1);
    }
}
