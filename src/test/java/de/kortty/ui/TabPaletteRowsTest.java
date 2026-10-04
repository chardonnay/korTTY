package de.kortty.ui;

import de.kortty.ui.actions.TabPaletteSource;
import javafx.scene.control.Tab;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The palette ids and rows of tabs that are not terminals, and the window label. Toolkit-free: a
 * plain {@link Tab} is no Control.
 */
class TabPaletteRowsTest {

    @Test
    void aTabKeepsItsIdAndTwoTabsGetTwoIds() {
        Tab first = new Tab("notes.txt");
        Tab second = new Tab("notes.txt");

        String id = TabPaletteRows.tabId(first);

        assertThat(TabPaletteRows.tabId(first)).isEqualTo(id);
        assertThat(TabPaletteRows.tabId(second)).isNotEqualTo(id);
        assertThat(first.getProperties()).containsEntry(TabPaletteRows.TAB_ID_PROPERTY, id);
        // The id says nothing about what the tab shows.
        assertThat(id).doesNotContain("notes");
    }

    @Test
    void anotherTabIsTitledWithItsTextAndRunsItsSelect() {
        Tab tab = new Tab("notes.txt");
        List<String> selected = new ArrayList<>();

        TabPaletteSource.TabRow row = TabPaletteRows.row(tab, () -> selected.add("notes"));

        assertThat(row.id()).isEqualTo(TabPaletteRows.tabId(tab));
        assertThat(row.title()).isEqualTo("notes.txt");
        assertThat(row.detail()).isEmpty();
        row.select().run();
        assertThat(selected).containsExactly("notes");
        assertThat(TabPaletteRows.row(new Tab(), () -> { }).title()).isEmpty();
    }

    @Test
    void anotherWindowIsNamedByItsPlace() {
        assertThat(TabPaletteRows.windowLabel(2)).isEqualTo(I18n.get("palette.detail.window", 2));
        assertThat(TabPaletteRows.windowLabel(2)).contains("2");
        assertThat(TabPaletteRows.currentTabNote()).isEqualTo(I18n.get("palette.detail.currentTab"));
    }
}
