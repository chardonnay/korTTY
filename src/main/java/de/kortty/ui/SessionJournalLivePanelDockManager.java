package de.kortty.ui;

import de.kortty.model.GlobalSettings;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds where the live session-journal panel is docked: HIDDEN (default), or to the LEFT/RIGHT
 * side of the main window.
 *
 * <p>This is intentionally <b>per-window</b> (one instance per {@code MainWindow}), not a singleton:
 * each window holds its own placement/width and listener, seeded from / persisted to
 * {@code GlobalSettings}. A shared singleton would make a second window unable to dock (its listener
 * never fires when the value already matches) and would leak listeners across windows. Width is
 * clamped to a sane range.
 */
public class SessionJournalLivePanelDockManager {

    public enum Placement {
        HIDDEN,
        LEFT,
        RIGHT
    }

    // Derived from GlobalSettings so the docked-width bounds have a single source of truth.
    public static final double MIN_WIDTH = GlobalSettings.JOURNAL_LIVE_PANEL_MIN_WIDTH;
    public static final double MAX_WIDTH = GlobalSettings.JOURNAL_LIVE_PANEL_MAX_WIDTH;
    public static final double DEFAULT_WIDTH = GlobalSettings.JOURNAL_LIVE_PANEL_DEFAULT_WIDTH;

    private Placement placement = Placement.HIDDEN;
    private double preferredWidth = DEFAULT_WIDTH;
    /** Last side the panel was docked to; the toggle shortcut re-opens on this side. */
    private Placement lastDockedSide = Placement.RIGHT;
    private final List<Consumer<Placement>> placementListeners = new CopyOnWriteArrayList<>();

    public SessionJournalLivePanelDockManager() {
    }

    public Placement getPlacement() {
        return placement;
    }

    /** True when the panel is docked to a side (LEFT/RIGHT) rather than hidden. */
    public boolean isDocked() {
        return placement != Placement.HIDDEN;
    }

    public void setPlacement(Placement newPlacement) {
        if (newPlacement == null || newPlacement == placement) {
            return;
        }
        placement = newPlacement;
        if (newPlacement != Placement.HIDDEN) {
            lastDockedSide = newPlacement;
        }
        for (Consumer<Placement> listener : placementListeners) {
            listener.accept(newPlacement);
        }
    }

    /** Toggles a side placement: selecting the current side hides the panel, otherwise docks there. */
    public void toggle(Placement target) {
        setPlacement(placement == target ? Placement.HIDDEN : target);
    }

    /** Show/hide toggle for the keyboard shortcut: hides when visible, otherwise re-opens the last side. */
    public void toggleVisible() {
        setPlacement(isDocked() ? Placement.HIDDEN : lastDockedSide);
    }

    public double getPreferredWidth() {
        return preferredWidth;
    }

    public void setPreferredWidth(double width) {
        this.preferredWidth = clampWidth(width);
    }

    public void addPlacementListener(Consumer<Placement> listener) {
        placementListeners.add(listener);
    }

    public void removePlacementListener(Consumer<Placement> listener) {
        placementListeners.remove(listener);
    }

    /** Parses a persisted placement name defensively, falling back to HIDDEN. */
    public static Placement parsePlacement(String name) {
        if (name == null) {
            return Placement.HIDDEN;
        }
        try {
            return Placement.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Placement.HIDDEN;
        }
    }

    /** Clamps a requested width into the allowed range, using the default for invalid input. */
    public static double clampWidth(double width) {
        if (Double.isNaN(width) || width <= 0) {
            return DEFAULT_WIDTH;
        }
        return Math.max(MIN_WIDTH, Math.min(width, MAX_WIDTH));
    }
}
