package de.kortty.ui.actions;

import javafx.scene.input.KeyCombination;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * One user action of a main window: what a menu item, and later the command palette, can run.
 *
 * <p>{@code enabled} and {@code checked} are read lazily, so a descriptor taken from a snapshot
 * still reports the state at the moment it is asked. {@code checked} is {@code null} for an action
 * that is not a toggle. {@code stableId} is {@code true} when {@code id} does not depend on the UI
 * language (an explicit id or a tagged menu item), so it can key a most-recently-used list or a
 * later key binding. {@code policyLocked} marks an action that the organization's policy disabled.
 */
public record AppAction(
        String id,
        String label,
        String category,
        @Nullable KeyCombination accelerator,
        List<String> keywords,
        BooleanSupplier enabled,
        @Nullable BooleanSupplier checked,
        Runnable run,
        boolean stableId,
        boolean policyLocked) {

    public AppAction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("An action needs an id");
        }
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(enabled, "enabled");
        Objects.requireNonNull(run, "run");
        category = category != null ? category : "";
        keywords = keywords != null ? List.copyOf(keywords) : List.of();
    }

    /** Whether the action may run right now. */
    public boolean isEnabled() {
        return enabled.getAsBoolean();
    }

    /** Whether the action is a toggle with an on/off state. */
    public boolean isCheckable() {
        return checked != null;
    }

    /** The current state of a toggle; {@code false} for an action that is not one. */
    public boolean isChecked() {
        return checked != null && checked.getAsBoolean();
    }
}
