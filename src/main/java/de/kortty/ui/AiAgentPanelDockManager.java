package de.kortty.ui;

import de.kortty.model.GlobalSettings;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Holds where the terminal AI-agent activity panel is shown: at the BOTTOM of each terminal split
 * (default) or docked to the LEFT/RIGHT side of the main window.
 *
 * <p>This is intentionally <b>per-window</b> (one instance per {@code MainWindow}), not a singleton:
 * each window holds its own placement/width and listener, seeded from / persisted to
 * {@code GlobalSettings}. A shared singleton would make a second window unable to dock (its listener
 * never fires when the value already matches) and would leak listeners across windows. Width is
 * clamped to a sane range.
 */
public class AiAgentPanelDockManager {

    public enum Placement {
        BOTTOM,
        LEFT,
        RIGHT
    }

    // Derived from GlobalSettings so the docked-width bounds have a single source of truth.
    public static final double MIN_WIDTH = GlobalSettings.AI_AGENT_PANEL_MIN_WIDTH;
    public static final double MAX_WIDTH = GlobalSettings.AI_AGENT_PANEL_MAX_WIDTH;
    public static final double DEFAULT_WIDTH = GlobalSettings.AI_AGENT_PANEL_DEFAULT_WIDTH;

    private Placement placement = Placement.BOTTOM;
    private double preferredWidth = DEFAULT_WIDTH;
    private final List<Consumer<Placement>> placementListeners = new CopyOnWriteArrayList<>();

    public AiAgentPanelDockManager() {
    }

    public Placement getPlacement() {
        return placement;
    }

    /** True when the panel is docked to a side (LEFT/RIGHT) rather than shown at the bottom. */
    public boolean isDocked() {
        return placement != Placement.BOTTOM;
    }

    public void setPlacement(Placement newPlacement) {
        if (newPlacement == null || newPlacement == placement) {
            return;
        }
        placement = newPlacement;
        for (Consumer<Placement> listener : placementListeners) {
            listener.accept(newPlacement);
        }
    }

    /** Toggles a side placement: selecting the current side returns to BOTTOM, otherwise docks there. */
    public void toggle(Placement target) {
        setPlacement(placement == target ? Placement.BOTTOM : target);
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

    /** Parses a persisted placement name defensively, falling back to BOTTOM. */
    public static Placement parsePlacement(String name) {
        if (name == null) {
            return Placement.BOTTOM;
        }
        try {
            return Placement.valueOf(name.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Placement.BOTTOM;
        }
    }

    /** Clamps a requested side width into the allowed range, using the default for invalid input. */
    public static double clampWidth(double width) {
        if (Double.isNaN(width) || width <= 0) {
            return DEFAULT_WIDTH;
        }
        return Math.max(MIN_WIDTH, Math.min(width, MAX_WIDTH));
    }
}
