package de.kortty.ui.actions;

import javafx.scene.input.KeyCombination;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The command palette's rows for the actions of an {@link ActionRegistry}: the menu commands of the
 * window and the actions registered next to them. Each row is keyed {@code action:<id>}, shows the
 * action's label with its menu path as the detail and its shortcut, and is enabled while the action
 * is. Choosing a row runs the action only if it is still enabled then
 * ({@link ActionRegistry#runIfEnabled}).
 *
 * <p>The shortcut text and the reasons are passed in, so the source stays free of the JavaFX toolkit
 * (a shortcut's display text depends on the platform and needs it) and of the UI language.
 */
public final class ActionPaletteSource implements PaletteSource {

    /** Prefix of an action row's key. */
    public static final String KEY_PREFIX = "action:";

    private final ActionRegistry registry;
    private final Function<KeyCombination, String> shortcutText;
    private final Supplier<String> policyReason;
    private final Supplier<String> unavailableReason;

    /**
     * @param shortcutText      how a shortcut is shown, such as {@code KeyCombination::getDisplayText}
     * @param policyReason      why an action the organization's policy disabled does not run
     * @param unavailableReason why any other disabled action does not run right now
     */
    public ActionPaletteSource(ActionRegistry registry, Function<KeyCombination, String> shortcutText,
                               Supplier<String> policyReason, Supplier<String> unavailableReason) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.shortcutText = Objects.requireNonNull(shortcutText, "shortcutText");
        this.policyReason = Objects.requireNonNull(policyReason, "policyReason");
        this.unavailableReason = Objects.requireNonNull(unavailableReason, "unavailableReason");
    }

    @Override
    public PaletteEntry.Kind kind() {
        return PaletteEntry.Kind.ACTION;
    }

    @Override
    public List<PaletteEntry> entries() {
        List<PaletteEntry> entries = new ArrayList<>();
        for (AppAction action : registry.snapshot()) {
            boolean enabled = action.isEnabled();
            String reason = enabled ? "" : action.policyLocked() ? policyReason.get() : unavailableReason.get();
            String shortcut = action.accelerator() != null ? shortcutText.apply(action.accelerator()) : "";
            entries.add(new PaletteEntry(
                PaletteEntry.Kind.ACTION,
                KEY_PREFIX + action.id(),
                action.label(),
                action.category(),
                shortcut,
                enabled,
                reason,
                action.isChecked(),
                () -> ActionRegistry.runIfEnabled(action)));
        }
        return entries;
    }
}
