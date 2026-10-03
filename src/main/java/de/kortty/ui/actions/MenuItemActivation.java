package de.kortty.ui.actions;

import javafx.event.Event;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;

/**
 * Runs a {@link MenuItem} the way a JavaFX accelerator does, so an action chosen elsewhere behaves
 * exactly like its shortcut: {@link MenuItem#MENU_VALIDATION_EVENT} goes to the item and to its
 * parent menu (each only when it has an {@code onMenuValidation} handler), a disabled item is
 * refused, a {@link CheckMenuItem} or {@link RadioMenuItem} is toggled, and only then is the item
 * fired. {@link MenuItem#fire()} alone does not toggle, so a handler reading {@code isSelected()}
 * would see the old state.
 *
 * <p>One deliberate difference: an item inside a disabled menu is refused as well. The accelerator
 * would still fire it, but the mouse cannot reach it, and neither should a palette.
 */
public final class MenuItemActivation {

    private MenuItemActivation() {
    }

    /** Activates {@code item}; returns {@code false} when it was refused because it is disabled. */
    public static boolean activate(MenuItem item) {
        if (item == null) {
            return false;
        }
        if (item.getOnMenuValidation() != null) {
            Event.fireEvent(item, new Event(MenuItem.MENU_VALIDATION_EVENT));
        }
        Menu parent = item.getParentMenu();
        if (parent != null && parent.getOnMenuValidation() != null) {
            Event.fireEvent(parent, new Event(MenuItem.MENU_VALIDATION_EVENT));
        }
        if (isDisabled(item)) {
            return false;
        }
        if (item instanceof RadioMenuItem radio) {
            radio.setSelected(radio.getToggleGroup() == null ? !radio.isSelected() : true);
        } else if (item instanceof CheckMenuItem check) {
            check.setSelected(!check.isSelected());
        }
        item.fire();
        return true;
    }

    /** Whether the item or any menu above it is disabled. */
    static boolean isDisabled(MenuItem item) {
        for (MenuItem current = item; current != null; current = current.getParentMenu()) {
            if (current.isDisable()) {
                return true;
            }
        }
        return false;
    }
}
