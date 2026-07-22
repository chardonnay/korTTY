package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.List;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;

class FileBrowserSortTest {

    /** Simple immutable fake so the comparators can be tested without the filesystem. */
    private record Item(String name, long size, long lastModified, boolean directory)
        implements FileBrowserSort.Entry {
    }

    private static Item file(String name, long size, long lastModified) {
        return new Item(name, size, lastModified, false);
    }

    private static Item dir(String name) {
        return new Item(name, 0, 0, true);
    }

    private static List<String> names(List<Item> items, FileBrowserSort.Key key, boolean ascending) {
        return items.stream()
            .sorted(FileBrowserSort.comparator(key, ascending))
            .map(Item::name)
            .collect(Collectors.toList());
    }

    @Test
    void foldersSortBeforeFilesRegardlessOfKey() {
        List<Item> items = List.of(file("aaa", 1, 1), dir("zzz"));
        assertThat(names(items, FileBrowserSort.Key.NAME, true)).containsExactly("zzz", "aaa").inOrder();
        assertThat(names(items, FileBrowserSort.Key.SIZE, true)).containsExactly("zzz", "aaa").inOrder();
        assertThat(names(items, FileBrowserSort.Key.DATE, false)).containsExactly("zzz", "aaa").inOrder();
    }

    @Test
    void nameAscendingIsCaseInsensitive() {
        List<Item> items = List.of(file("Banana", 1, 1), file("apple", 1, 1), file("Cherry", 1, 1));
        assertThat(names(items, FileBrowserSort.Key.NAME, true))
            .containsExactly("apple", "Banana", "Cherry").inOrder();
    }

    @Test
    void nameDescendingReverses() {
        List<Item> items = List.of(file("Banana", 1, 1), file("apple", 1, 1), file("Cherry", 1, 1));
        assertThat(names(items, FileBrowserSort.Key.NAME, false))
            .containsExactly("Cherry", "Banana", "apple").inOrder();
    }

    @Test
    void sizeAscendingOrdersFilesBySize() {
        List<Item> items = List.of(file("big", 900, 1), file("small", 10, 1), file("mid", 100, 1));
        assertThat(names(items, FileBrowserSort.Key.SIZE, true))
            .containsExactly("small", "mid", "big").inOrder();
    }

    @Test
    void dateDescendingOrdersByLastModified() {
        List<Item> items = List.of(file("old", 1, 100), file("new", 1, 900), file("mid", 1, 500));
        assertThat(names(items, FileBrowserSort.Key.DATE, false))
            .containsExactly("new", "mid", "old").inOrder();
    }

    @Test
    void equalKeysFallBackToNameTiebreak() {
        List<Item> items = List.of(file("b", 50, 1), file("a", 50, 1), file("c", 50, 1));
        assertThat(names(items, FileBrowserSort.Key.SIZE, true))
            .containsExactly("a", "b", "c").inOrder();
    }
}
