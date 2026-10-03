package de.kortty.ui.actions;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/** The markers menu builders put on items for the harvester. Toolkit-free. */
class ActionIdsTest {

    @Test
    void tagStoresTheIdAndReturnsTheSameItem() {
        MenuItem item = new MenuItem("Quit");

        assertThat(ActionIds.tag(item, "menu.file.quit")).isSameInstanceAs(item);
        assertThat(ActionIds.idOf(item)).isEqualTo("menu.file.quit");
        assertThat(item.getProperties()).containsEntry(ActionIds.ID_PROPERTY, "menu.file.quit");
    }

    @Test
    void untaggedItemHasNoId() {
        assertThat(ActionIds.idOf(new MenuItem("Quit"))).isNull();
        assertThat(ActionIds.idOf(null)).isNull();
    }

    @Test
    void blankIdIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> ActionIds.tag(new MenuItem("x"), " "));
        assertThrows(IllegalArgumentException.class, () -> ActionIds.tag(new MenuItem("x"), null));
    }

    @Test
    void excludeAndPolicyLockAreSeparateMarkers() {
        Menu menu = new Menu("Jobs");
        MenuItem item = new MenuItem("Credentials");

        assertThat(ActionIds.exclude(menu)).isSameInstanceAs(menu);
        assertThat(ActionIds.markPolicyLocked(item)).isSameInstanceAs(item);

        assertThat(ActionIds.isExcluded(menu)).isTrue();
        assertThat(ActionIds.isPolicyLocked(menu)).isFalse();
        assertThat(ActionIds.isPolicyLocked(item)).isTrue();
        assertThat(ActionIds.isExcluded(item)).isFalse();
        assertThat(ActionIds.isExcluded(null)).isFalse();
        assertThat(ActionIds.isPolicyLocked(null)).isFalse();
    }
}
