package de.kortty.ui.actions;

import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Refreshing menu items as if their menus opened, before the command palette reads them.
 * Toolkit-free: plain Menu and MenuItem only.
 */
class MenuStateRefreshTest {

    @Test
    void everyMenusOpeningHandlerRunsOutermostFirst() {
        List<String> opened = new ArrayList<>();
        Menu security = new Menu("Security");
        security.setOnShowing(event -> opened.add("Security"));
        Menu configuration = new Menu("Configuration");
        configuration.setOnShowing(event -> opened.add("Configuration"));
        configuration.getItems().addAll(security, new MenuItem("Settings"));
        Menu file = new Menu("File");
        file.setOnShowing(event -> opened.add("File"));

        MenuStateRefresh.refresh(List.of(file, configuration));

        assertThat(opened).containsExactly("File", "Configuration", "Security").inOrder();
    }

    @Test
    void whatAnOpeningHandlerSetsIsWhatThePaletteThenReads() {
        MenuItem rename = ActionIds.tag(new MenuItem("Rename Tab..."), "menu.file.renameTab");
        Menu file = new Menu("File");
        file.getItems().add(rename);
        boolean[] terminalTabSelected = {false};
        file.setOnShowing(event -> rename.setDisable(!terminalTabSelected[0]));
        ActionRegistry registry = new ActionRegistry();
        registry.addContributor(() -> MenuActionHarvester.harvest(List.of(file)));

        MenuStateRefresh.refresh(List.of(file));
        assertThat(registry.find("menu.file.renameTab").orElseThrow().isEnabled()).isFalse();

        terminalTabSelected[0] = true;
        MenuStateRefresh.refresh(List.of(file));
        assertThat(registry.find("menu.file.renameTab").orElseThrow().isEnabled()).isTrue();
    }

    @Test
    void excludedMenusAreLeftAloneWithEverythingBelowThem() {
        List<String> opened = new ArrayList<>();
        Menu effects = ActionIds.exclude(new Menu("Terminal Effect"));
        effects.setOnShowing(event -> opened.add("Terminal Effect"));
        Menu speed = new Menu("Speed");
        speed.setOnShowing(event -> opened.add("Speed"));
        effects.getItems().add(speed);
        Menu view = new Menu("View");
        view.setOnShowing(event -> opened.add("View"));
        view.getItems().add(effects);

        MenuStateRefresh.refresh(List.of(view));

        assertThat(opened).containsExactly("View");
    }

    @Test
    void aSubmenuThatAnOpeningHandlerAddsIsRefreshedToo() {
        List<String> opened = new ArrayList<>();
        Menu tools = new Menu("Tools");
        tools.setOnShowing(event -> {
            opened.add("Tools");
            if (tools.getItems().isEmpty()) {
                Menu added = new Menu("Added");
                added.setOnShowing(inner -> opened.add("Added"));
                tools.getItems().add(added);
            }
        });

        MenuStateRefresh.refresh(List.of(tools));

        assertThat(opened).containsExactly("Tools", "Added").inOrder();
    }

    @Test
    void nothingToRefreshIsFine() {
        MenuStateRefresh.refresh(null);
        MenuStateRefresh.refresh(List.of(new Menu("Help")));
    }
}
