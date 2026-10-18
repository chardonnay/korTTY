package de.kortty.ui.sftp;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SftpFileItemComparatorsTest {

    private static SftpFileItem file(String name, String label, long bytes) {
        return SftpFileItem.fromDetails(name, "/x/" + name, true, label, "", "", "", "", bytes);
    }

    private static SftpFileItem dir(String name) {
        return SftpFileItem.fromDetails(name, "/x/" + name, false, "—", "", "", "", "", -1);
    }

    private static SftpFileItem dated(String name, boolean isFile, String date) {
        return SftpFileItem.fromDetails(name, "/x/" + name, isFile, isFile ? "1 B" : "—", date, "", "", "",
            isFile ? 1 : -1);
    }

    private static SftpFileItem parent() {
        return SftpFileItem.fromDetails("..", "/", false, "—", "", "", "", "", -1);
    }

    private static List<String> sortedNames(List<SftpFileItem> items, Comparator<SftpFileItem> order) {
        List<SftpFileItem> copy = new ArrayList<>(items);
        // Reversed, so a comparator that merely keeps the input order would fail.
        Collections.reverse(copy);
        copy.sort(order);
        return copy.stream().map(SftpFileItem::getName).toList();
    }

    private static List<SftpFileItem> sizedItems() {
        return List.of(
            file("tiny", "10 B", 10),
            // German labels use a decimal comma; sorting must not depend on parsing them.
            file("medium", "9,5 KB", 9_728),
            file("small", "2 KB", 2_048),
            file("huge", "1,2 GB", 1_288_490_189L),
            dir("folder"),
            parent());
    }

    @Test
    void sizeSortsByBytesAscending() {
        assertThat(sortedNames(sizedItems(), SftpFileItemComparators.size(true)))
            .containsExactly("..", "folder", "tiny", "small", "medium", "huge")
            .inOrder();
    }

    @Test
    void sizeSortsByBytesDescendingWithParentAndFoldersFirst() {
        assertThat(sortedNames(sizedItems(), SftpFileItemComparators.size(false)))
            .containsExactly("..", "folder", "huge", "medium", "small", "tiny")
            .inOrder();
    }

    @Test
    void sizeTiesAreBrokenByNameCaseInsensitively() {
        List<SftpFileItem> items = List.of(file("b", "1 B", 1), file("A", "1 B", 1), file("c", "1 B", 1));
        assertThat(sortedNames(items, SftpFileItemComparators.size(true))).containsExactly("A", "b", "c").inOrder();
        assertThat(sortedNames(items, SftpFileItemComparators.size(false))).containsExactly("A", "b", "c").inOrder();
    }

    @Test
    void typeKeepsTheDocumentedGroupOrder() {
        // sftp.md "Sort order": .., hidden folders, hidden files, folders, files.
        List<SftpFileItem> items = List.of(
            file("zeta.txt", "1 B", 1),
            file(".bashrc", "1 B", 1),
            dir("src"),
            dir(".git"),
            file("Alpha.txt", "1 B", 1),
            dir(".config"),
            dir("bin"),
            parent());

        assertThat(sortedNames(items, SftpFileItemComparators.type(true)))
            .containsExactly("..", ".config", ".git", ".bashrc", "bin", "src", "Alpha.txt", "zeta.txt")
            .inOrder();
        // Descending reverses the names within each group, never the groups.
        assertThat(sortedNames(items, SftpFileItemComparators.type(false)))
            .containsExactly("..", ".git", ".config", ".bashrc", "src", "bin", "zeta.txt", "Alpha.txt")
            .inOrder();
    }

    @Test
    void sizeSortsWithinTheGroups() {
        List<SftpFileItem> items = List.of(
            file("big.iso", "1 GB", 1_073_741_824L),
            file(".zshrc", "2 KB", 2_048),
            file(".profile", "10 B", 10),
            file("note.txt", "5 B", 5),
            dir("docs"),
            dir(".ssh"),
            parent());

        assertThat(sortedNames(items, SftpFileItemComparators.size(true)))
            .containsExactly("..", ".ssh", ".profile", ".zshrc", "docs", "note.txt", "big.iso")
            .inOrder();
        assertThat(sortedNames(items, SftpFileItemComparators.size(false)))
            .containsExactly("..", ".ssh", ".zshrc", ".profile", "docs", "big.iso", "note.txt")
            .inOrder();
    }

    @Test
    void anyColumnSortsWithinTheGroups() {
        // The Date column descending, as the table hands it over: newest first, but only per group.
        List<SftpFileItem> items = List.of(
            dated("old.txt", true, "2020-01-01 10:00:00"),
            dated("new.txt", true, "2026-01-01 10:00:00"),
            dated(".old-rc", true, "2019-01-01 10:00:00"),
            dated(".new-rc", true, "2025-01-01 10:00:00"),
            dated("old-dir", false, "2018-01-01 10:00:00"),
            dated("new-dir", false, "2024-01-01 10:00:00"),
            dated(".cache", false, "2017-01-01 10:00:00"),
            parent());
        Comparator<SftpFileItem> byDateDescending =
            Comparator.comparing(SftpFileItem::getDate, Comparator.reverseOrder());

        assertThat(sortedNames(items, SftpFileItemComparators.grouped(byDateDescending)))
            .containsExactly("..", ".cache", ".new-rc", ".old-rc", "new-dir", "old-dir", "new.txt", "old.txt")
            .inOrder();
    }

    @Test
    void hiddenComesFromTheDotOrTheFileSystemButNeverForTheParent() {
        assertThat(file(".env", "1 B", 1).isHidden()).isTrue();
        assertThat(file("env", "1 B", 1).isHidden()).isFalse();
        assertThat(parent().isHidden()).isFalse();
        assertThat(SftpFileItem.parent("/").isHidden()).isFalse();
        // A Windows file with the hidden attribute but no dot.
        SftpFileItem systemHidden =
            SftpFileItem.fromDetails("desktop.ini", "C:/x/desktop.ini", true, "1 B", "", "", "", "", 1, true);
        assertThat(systemHidden.isHidden()).isTrue();
        assertThat(SftpFileItem.fromDetails("..", "C:/", false, "—", "", "", "", "", -1, true).isHidden())
            .isFalse();

        List<SftpFileItem> items = List.of(file("a.txt", "1 B", 1), systemHidden, dir("folder"));
        assertThat(sortedNames(items, SftpFileItemComparators.type(true)))
            .containsExactly("desktop.ini", "folder", "a.txt")
            .inOrder();
    }

    @Test
    void parentFirstWrapsAnyColumnOrder() {
        List<SftpFileItem> items = List.of(file("a", "1 B", 1), parent(), file("b", "1 B", 1));
        Comparator<SftpFileItem> byNameDescending =
            Comparator.comparing(SftpFileItem::getName, String.CASE_INSENSITIVE_ORDER).reversed();

        assertThat(sortedNames(items, SftpFileItemComparators.parentFirst(byNameDescending)))
            .containsExactly("..", "b", "a")
            .inOrder();
    }

    @Test
    void fromDetailsKeepsTheGivenByteCount() {
        // The old overload re-parsed the label: a German "9,5 KB" became 95 KB.
        assertThat(SftpFileItem.fromDetails("f", "/f", true, "9,5 KB", "", "", "", "", 9_728).getSizeBytes())
            .isEqualTo(9_728L);
        assertThat(dir("d").getSizeBytes()).isEqualTo(-1L);
        assertThat(parent().isParentEntry()).isTrue();
    }
}
