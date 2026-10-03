package de.kortty.ui.actions;

import javafx.scene.control.MenuItem;
import org.jetbrains.annotations.Nullable;

/**
 * Markers that menu builders put on {@link MenuItem}s for the {@link MenuActionHarvester}, stored
 * in the item's properties map.
 *
 * <ul>
 *   <li>{@link #tag} gives an item a stable action id, by convention its i18n key, so the id does
 *       not change with the UI language.</li>
 *   <li>{@link #exclude} keeps an item, or a whole submenu, out of the harvest; use it for menus
 *       that are rebuilt while they show, whose items would go stale.</li>
 *   <li>{@link #markPolicyLocked} records that the organization's policy disabled an item (or every
 *       item of a submenu), so a caller can explain why it does not run.</li>
 * </ul>
 */
public final class ActionIds {

    /** Property key of the stable action id. */
    public static final String ID_PROPERTY = "kortty.action.id";
    /** Property key that keeps an item and everything below it out of the harvest. */
    public static final String EXCLUDE_PROPERTY = "kortty.action.exclude";
    /** Property key that marks an item, or a submenu, as disabled by policy. */
    public static final String POLICY_LOCKED_PROPERTY = "kortty.action.policyLocked";

    private ActionIds() {
    }

    /** Gives {@code item} the stable action id {@code id} and returns the item. */
    public static <T extends MenuItem> T tag(T item, String id) {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An action id must not be blank");
        }
        item.getProperties().put(ID_PROPERTY, id);
        return item;
    }

    /** Keeps {@code item}, and for a menu everything below it, out of the harvest; returns the item. */
    public static <T extends MenuItem> T exclude(T item) {
        item.getProperties().put(EXCLUDE_PROPERTY, Boolean.TRUE);
        return item;
    }

    /** Marks {@code item}, and for a menu everything below it, as disabled by policy; returns the item. */
    public static <T extends MenuItem> T markPolicyLocked(T item) {
        item.getProperties().put(POLICY_LOCKED_PROPERTY, Boolean.TRUE);
        return item;
    }

    /** The stable id given by {@link #tag}, or {@code null} for an untagged item. */
    public static @Nullable String idOf(MenuItem item) {
        if (item == null) {
            return null;
        }
        Object id = item.getProperties().get(ID_PROPERTY);
        return id instanceof String text && !text.isBlank() ? text : null;
    }

    /** Whether {@link #exclude} was called on this item itself. */
    public static boolean isExcluded(MenuItem item) {
        return item != null && Boolean.TRUE.equals(item.getProperties().get(EXCLUDE_PROPERTY));
    }

    /** Whether {@link #markPolicyLocked} was called on this item itself. */
    public static boolean isPolicyLocked(MenuItem item) {
        return item != null && Boolean.TRUE.equals(item.getProperties().get(POLICY_LOCKED_PROPERTY));
    }
}
