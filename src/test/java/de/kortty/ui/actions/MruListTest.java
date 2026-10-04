package de.kortty.ui.actions;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** The most-recently-used list behind the command palette's recent choices. */
class MruListTest {

    @Test
    void theNewestItemComesFirst() {
        MruList<String> recent = new MruList<>(5);

        recent.add("a");
        recent.add("b");
        recent.add("c");

        assertThat(recent.items()).containsExactly("c", "b", "a").inOrder();
        assertThat(recent.indexOf("c")).isEqualTo(0);
        assertThat(recent.indexOf("a")).isEqualTo(2);
        assertThat(recent.indexOf("missing")).isEqualTo(-1);
    }

    @Test
    void addingAnItemAgainMovesItToTheFrontWithoutACopy() {
        MruList<String> recent = new MruList<>(5);
        recent.add("a");
        recent.add("b");
        recent.add("c");

        recent.add("a");

        assertThat(recent.items()).containsExactly("a", "c", "b").inOrder();
        assertThat(recent.size()).isEqualTo(3);
    }

    @Test
    void theOldestItemFallsOutOnceTheListIsFull() {
        MruList<String> recent = new MruList<>(3);
        for (String item : new String[] {"a", "b", "c", "d"}) {
            recent.add(item);
        }

        assertThat(recent.items()).containsExactly("d", "c", "b").inOrder();
        assertThat(recent.contains("a")).isFalse();
        assertThat(recent.capacity()).isEqualTo(3);
    }

    @Test
    void itemsCanBeRemovedAndCleared() {
        MruList<String> recent = new MruList<>(MruList.DEFAULT_CAPACITY);
        recent.add("a");
        recent.add("b");

        assertThat(recent.remove("a")).isTrue();
        assertThat(recent.remove("a")).isFalse();
        assertThat(recent.items()).containsExactly("b");

        recent.clear();
        assertThat(recent.items()).isEmpty();
    }

    @Test
    void theItemsAreACopy() {
        MruList<String> recent = new MruList<>(3);
        recent.add("a");

        var snapshot = recent.items();
        recent.add("b");

        assertThat(snapshot).containsExactly("a");
    }

    @Test
    void aListNeedsRoomAndRejectsNull() {
        assertThrows(IllegalArgumentException.class, () -> new MruList<String>(0));
        assertThrows(NullPointerException.class, () -> new MruList<String>(2).add(null));
    }
}
