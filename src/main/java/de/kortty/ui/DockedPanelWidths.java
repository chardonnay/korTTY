package de.kortty.ui;

import javafx.beans.InvalidationListener;
import javafx.scene.Node;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps the side panels docked beside the terminal at the width the user set.
 *
 * <p>The tab pane next to them prefers the width of its terminal, and an HBox short of room
 * shrinks every child towards its minimum regardless of hgrow — which squeezed the panels to
 * their smallest width although the window had space for them. So a docked panel's minimum is
 * its preferred width, and only a window too narrow for the panels plus a usable terminal
 * lowers it: the missing width is taken from the panels in proportion to how far each can
 * still give, never below its own minimum.
 */
final class DockedPanelWidths {

    /** Width the terminal keeps before the docked panels start to give way. */
    static final double TERMINAL_RESERVE = 320.0;

    private final HBox box;
    private final Node terminal;
    private final Map<Region, Double> panels = new LinkedHashMap<>();
    private final InvalidationListener update = observable -> update();

    DockedPanelWidths(HBox box, Node terminal) {
        this.box = box;
        this.terminal = terminal;
        box.widthProperty().addListener(update);
        box.getChildren().addListener(update);
        // The HBox's own minimum is the sum of its children's, and its parent never sizes it
        // below that; it has to follow the window so a too-narrow one can be detected at all.
        box.setMinWidth(0);
    }

    /** Tracks {@code panel}, which may shrink to {@code minWidth} when the window is too narrow. */
    void register(Region panel, double minWidth) {
        panels.put(panel, minWidth);
        panel.prefWidthProperty().addListener(update);
        update();
    }

    void update() {
        double fixed = box.snappedLeftInset() + box.snappedRightInset();
        int managed = 0;
        int docked = 0;
        for (Node child : box.getChildren()) {
            if (!child.isManaged()) {
                continue;
            }
            managed++;
            if (panels.containsKey(child)) {
                docked++;
            } else if (child != terminal) {
                fixed += child.minWidth(-1);
            }
        }
        fixed += box.getSpacing() * Math.max(0, managed - 1);

        double[] prefs = new double[docked];
        double[] mins = new double[docked];
        Region[] order = new Region[docked];
        int i = 0;
        for (Node child : box.getChildren()) {
            if (child.isManaged() && child instanceof Region panel && panels.containsKey(panel)) {
                order[i] = panel;
                prefs[i] = panel.getPrefWidth();
                mins[i] = Math.min(panels.get(panel), prefs[i]);
                i++;
            }
        }
        double total = fixed + TERMINAL_RESERVE;
        for (double pref : prefs) {
            total += pref;
        }
        double deficit = box.getWidth() <= 0 ? 0 : total - box.getWidth();
        double[] widths = shrink(prefs, mins, deficit);
        for (i = 0; i < docked; i++) {
            order[i].setMinWidth(deficit > 0 ? widths[i] : Region.USE_PREF_SIZE);
        }
        // Panels that left the box get their plain minimum back.
        for (Map.Entry<Region, Double> entry : panels.entrySet()) {
            if (!box.getChildren().contains(entry.getKey())) {
                entry.getKey().setMinWidth(entry.getValue());
            }
        }
    }

    /**
     * Takes {@code deficit} pixels from {@code prefs}, each in proportion to its room down to
     * {@code mins}; with no deficit the preferred widths stand, beyond the total room every
     * panel is at its minimum.
     */
    static double[] shrink(double[] prefs, double[] mins, double deficit) {
        double[] widths = prefs.clone();
        double room = 0;
        for (int i = 0; i < prefs.length; i++) {
            room += Math.max(0, prefs[i] - mins[i]);
        }
        if (deficit <= 0 || room <= 0) {
            return widths;
        }
        double fraction = Math.min(1.0, deficit / room);
        for (int i = 0; i < prefs.length; i++) {
            widths[i] = prefs[i] - Math.max(0, prefs[i] - mins[i]) * fraction;
        }
        return widths;
    }
}
