package de.kortty.ui.actions;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ToggleGroup;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Running a menu item the way a JavaFX accelerator does. Toolkit-free: only menu items, no
 * Control (Control's static initializer needs a running toolkit and would poison the test JVM).
 */
class MenuItemActivationTest {

    @Test
    void validationReachesItemAndParentBeforeTheAction() {
        List<String> events = new ArrayList<>();
        Menu parent = new Menu("Edit");
        MenuItem item = new MenuItem("Copy");
        parent.getItems().add(item);
        item.setOnMenuValidation(e -> events.add("item-validation"));
        parent.setOnMenuValidation(e -> events.add("parent-validation"));
        item.setOnAction(e -> events.add("action"));

        assertThat(MenuItemActivation.activate(item)).isTrue();

        assertThat(events).containsAtLeast("item-validation", "parent-validation", "action").inOrder();
        assertThat(events.get(events.size() - 1)).isEqualTo("action");
        assertThat(Collections.frequency(events, "action")).isEqualTo(1);
    }

    @Test
    void validationRunsBeforeTheDisabledCheck() {
        Menu parent = new Menu("Edit");
        MenuItem item = new MenuItem("Paste");
        parent.getItems().add(item);
        List<String> fired = new ArrayList<>();
        item.setOnAction(e -> fired.add("paste"));
        // Validation is where a menu refreshes its state, so a validation that disables the item wins.
        parent.setOnMenuValidation(e -> item.setDisable(true));

        assertThat(MenuItemActivation.activate(item)).isFalse();
        assertThat(fired).isEmpty();

        parent.setOnMenuValidation(e -> item.setDisable(false));
        assertThat(MenuItemActivation.activate(item)).isTrue();
        assertThat(fired).containsExactly("paste");
    }

    @Test
    void checkMenuItemHandlerSeesTheToggledState() {
        CheckMenuItem item = new CheckMenuItem("Status Bar");
        item.setSelected(true);
        List<Boolean> seen = new ArrayList<>();
        item.setOnAction(e -> seen.add(item.isSelected()));

        MenuItemActivation.activate(item);
        MenuItemActivation.activate(item);

        assertThat(seen).containsExactly(false, true).inOrder();
    }

    @Test
    void radioItemInAGroupStaysSelectedAndOneWithoutAGroupToggles() {
        ToggleGroup group = new ToggleGroup();
        RadioMenuItem dark = new RadioMenuItem("Dark");
        RadioMenuItem light = new RadioMenuItem("Light");
        dark.setToggleGroup(group);
        light.setToggleGroup(group);
        dark.setSelected(true);

        MenuItemActivation.activate(light);
        assertThat(light.isSelected()).isTrue();
        assertThat(dark.isSelected()).isFalse();
        MenuItemActivation.activate(light);
        assertThat(light.isSelected()).isTrue();

        RadioMenuItem loose = new RadioMenuItem("Loose");
        MenuItemActivation.activate(loose);
        assertThat(loose.isSelected()).isTrue();
        MenuItemActivation.activate(loose);
        assertThat(loose.isSelected()).isFalse();
    }

    @Test
    void disabledItemNeitherTogglesNorFires() {
        CheckMenuItem item = new CheckMenuItem("Broadcast");
        List<String> fired = new ArrayList<>();
        item.setOnAction(e -> fired.add("fired"));
        item.setDisable(true);

        assertThat(MenuItemActivation.activate(item)).isFalse();

        assertThat(item.isSelected()).isFalse();
        assertThat(fired).isEmpty();
    }

    @Test
    void itemInsideADisabledMenuIsRefused() {
        Menu outer = new Menu("Tools");
        Menu inner = new Menu("Effects");
        MenuItem item = new MenuItem("Matrix");
        inner.getItems().add(item);
        outer.getItems().add(inner);
        List<String> fired = new ArrayList<>();
        item.setOnAction(e -> fired.add("fired"));
        outer.setDisable(true);

        assertThat(MenuItemActivation.activate(item)).isFalse();
        assertThat(fired).isEmpty();

        outer.setDisable(false);
        assertThat(MenuItemActivation.activate(item)).isTrue();
        assertThat(fired).containsExactly("fired");
    }

    @Test
    void plainItemFiresExactlyOnce() {
        MenuItem item = new MenuItem("New Tab");
        List<String> fired = new ArrayList<>();
        item.setOnAction(e -> fired.add("fired"));

        assertThat(MenuItemActivation.activate(item)).isTrue();

        assertThat(fired).containsExactly("fired");
    }

    @Test
    void nullItemIsRefused() {
        assertThat(MenuItemActivation.activate(null)).isFalse();
    }
}
