package de.kortty.ui.actions;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The actions of one main window. Use it on the FX thread only.
 *
 * <p>Actions come from two places: explicit registrations, whose ids must be unique, and
 * contributors such as the menu harvester, which are asked again for every {@link #snapshot()} so
 * the result follows the current menus. On an id clash an explicit action wins over a contributed
 * one, and an earlier contributor wins over a later one. A snapshot lists explicit actions in
 * registration order, then each contributor's actions in contributor order.
 */
public final class ActionRegistry {

    private final Map<String, AppAction> explicit = new LinkedHashMap<>();
    private final List<Supplier<? extends List<AppAction>>> contributors = new ArrayList<>();

    /** Adds an explicit action; a second action with the same id is a programming error. */
    public void register(AppAction action) {
        Objects.requireNonNull(action, "action");
        if (explicit.putIfAbsent(action.id(), action) != null) {
            throw new IllegalArgumentException("Duplicate action id: " + action.id());
        }
    }

    /** Adds a source of actions that is evaluated on every snapshot and lookup. */
    public void addContributor(Supplier<? extends List<AppAction>> contributor) {
        contributors.add(Objects.requireNonNull(contributor, "contributor"));
    }

    /** Every action right now, explicit ones first, without duplicate ids. */
    public List<AppAction> snapshot() {
        Map<String, AppAction> merged = new LinkedHashMap<>(explicit);
        for (Supplier<? extends List<AppAction>> contributor : contributors) {
            List<AppAction> contributed = contributor.get();
            if (contributed == null) {
                continue;
            }
            for (AppAction action : contributed) {
                if (action != null) {
                    merged.putIfAbsent(action.id(), action);
                }
            }
        }
        return List.copyOf(merged.values());
    }

    /** The action with this id, if any. */
    public Optional<AppAction> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        AppAction registered = explicit.get(id);
        if (registered != null) {
            return Optional.of(registered);
        }
        for (Supplier<? extends List<AppAction>> contributor : contributors) {
            List<AppAction> contributed = contributor.get();
            if (contributed == null) {
                continue;
            }
            for (AppAction action : contributed) {
                if (action != null && id.equals(action.id())) {
                    return Optional.of(action);
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Runs the action with this id if it exists and is enabled right now; returns whether it was
     * started (see {@link #runIfEnabled}).
     */
    public boolean run(String id) {
        return find(id).map(ActionRegistry::runIfEnabled).orElse(false);
    }

    /**
     * Runs {@code action} if it is enabled at this moment and returns whether it was started. The
     * state is read again here, because a snapshot can be older than the click or key press that
     * chose the action. A harvested menu item can still refuse in its own {@code onMenuValidation}
     * handler (see {@link MenuItemActivation}); that is not reported here.
     */
    public static boolean runIfEnabled(AppAction action) {
        if (action == null || !action.isEnabled()) {
            return false;
        }
        action.run().run();
        return true;
    }
}
