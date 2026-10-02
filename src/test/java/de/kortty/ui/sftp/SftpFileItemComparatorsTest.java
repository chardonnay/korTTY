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
        // sftp.md "Default sort order": .., dot-directories, directories, dot-files, files.
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
            .containsExactly("..", ".config", ".git", "bin", "src", ".bashrc", "Alpha.txt", "zeta.txt")
            .inOrder();
        assertThat(sortedNames(items, SftpFileItemComparators.type(false)))
            .containsExactly("..", "zeta.txt", "Alpha.txt", ".bashrc", "src", "bin", ".git", ".config")
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
