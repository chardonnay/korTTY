package de.kortty.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * The snippet editor's content row: the editor area, and while it is shown, a drag divider plus the
 * Full-code-analysis panel beside it.
 *
 * <p>Unlike an {@code HBox}, which shrinks every child evenly towards its minimum when the row is
 * narrower than the sum of the preferred widths, this row gives the panel its preferred (stored or
 * dragged) width and lets only the editor area flex. The editor form's preferred width is large (a
 * wide grid of fields), so an {@code HBox} used to squeeze the panel below its minimum as soon as
 * a library sat beside the editor. The panel only yields when the editor would otherwise fall below
 * {@link #MIN_EDITOR_WIDTH}, and never below its own minimum.
 *
 * <p>Every node stays a real child and the editor area never changes parent, so its Monaco page is
 * not reloaded when the panel comes and goes.
 */
final class SnippetEditorWorkbench extends Pane {

    /** The code keeps at least this width beside the panel (the panel yields before it does). */
    static final double MIN_EDITOR_WIDTH = 280;
    /** The drag handle between the editor area and the panel. */
    static final double DIVIDER_WIDTH = 6;

    private final Region editorArea;
    private final Region divider;
    private Region panel;

    SnippetEditorWorkbench(Region editorArea, Region divider) {
        this.editorArea = editorArea;
        this.divider = divider;
        getChildren().add(editorArea);
    }

    /** Shows {@code panel} (with the divider) beside the editor area; {@code null} hides it. */
    void setPanel(Region panel) {
        if (this.panel == panel) {
            return;
        }
        getChildren().removeIf(child -> child != editorArea);
        this.panel = panel;
        if (panel != null) {
            getChildren().addAll(divider, panel);
        }
    }

    Region panel() {
        return panel;
    }

    boolean isPanelShown() {
        return panel != null;
    }

    /** The widest the panel may be dragged to at the current width. */
    double maximumPanelWidth(double panelMinimum) {
        return Math.max(panelMinimum, contentWidth() - divider.prefWidth(-1) - MIN_EDITOR_WIDTH);
    }

    /**
     * The panel width at {@code available} px for editor plus panel (divider excluded): its
     * {@code preferred} width, capped so the editor keeps {@code editorMinimum}, and never below
     * {@code panelMinimum}.
     */
    static double panelWidth(double available, double preferred, double panelMinimum, double editorMinimum) {
        double minimum = Math.max(0, panelMinimum);
        double wanted = Double.isFinite(preferred) && preferred > 0 ? preferred : minimum;
        double room = available - Math.max(0, editorMinimum);
        return Math.max(minimum, Math.min(wanted, room));
    }

    /**
     * The width the code keeps in a row {@code workbenchWidth} wide once a panel that prefers
     * {@code panelPreferred} (and needs {@code panelMinimum}) sits beside it.
     */
    static double codeWidth(double workbenchWidth, double panelPreferred, double panelMinimum) {
        double available = Math.max(0, workbenchWidth - DIVIDER_WIDTH);
        return Math.max(0, available - panelWidth(available, panelPreferred, panelMinimum, MIN_EDITOR_WIDTH));
    }

    private double contentWidth() {
        Insets insets = getInsets();
        return getWidth() - insets.getLeft() - insets.getRight();
    }

    private double dividerWidth() {
        return panel != null ? divider.prefWidth(-1) : 0;
    }

    @Override
    protected void layoutChildren() {
        Insets insets = getInsets();
        double x = insets.getLeft();
        double y = insets.getTop();
        double width = contentWidth();
        double height = getHeight() - insets.getTop() - insets.getBottom();
        if (panel == null) {
            layoutInArea(editorArea, x, y, width, height, 0, HPos.LEFT, VPos.TOP);
            return;
        }
        double dividerWidth = dividerWidth();
        double available = Math.max(0, width - dividerWidth);
        double panelWidth = panelWidth(available, panel.prefWidth(-1), panel.minWidth(-1), MIN_EDITOR_WIDTH);
        double editorWidth = Math.max(0, available - panelWidth);
        layoutInArea(editorArea, x, y, editorWidth, height, 0, HPos.LEFT, VPos.TOP);
        layoutInArea(divider, x + editorWidth, y, dividerWidth, height, 0, HPos.LEFT, VPos.TOP);
        layoutInArea(panel, x + editorWidth + dividerWidth, y, panelWidth, height, 0, HPos.LEFT, VPos.TOP);
    }

    @Override
    protected double computeMinWidth(double height) {
        Insets insets = getInsets();
        double minimum = panel == null ? editorArea.minWidth(-1)
            : Math.max(editorArea.minWidth(-1), MIN_EDITOR_WIDTH) + dividerWidth() + panel.minWidth(-1);
        return insets.getLeft() + minimum + insets.getRight();
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets insets = getInsets();
        double preferred = editorArea.prefWidth(-1);
        if (panel != null) {
            preferred += dividerWidth() + Math.max(panel.minWidth(-1), panel.prefWidth(-1));
        }
        return insets.getLeft() + preferred + insets.getRight();
    }

    @Override
    protected double computeMinHeight(double width) {
        return verticalExtent(true);
    }

    @Override
    protected double computePrefHeight(double width) {
        return verticalExtent(false);
    }

    private double verticalExtent(boolean minimum) {
        double extent = 0;
        for (Node child : getManagedChildren()) {
            extent = Math.max(extent, minimum ? child.minHeight(-1) : child.prefHeight(-1));
        }
        Insets insets = getInsets();
        return insets.getTop() + extent + insets.getBottom();
    }

    @Override
    protected double computeMaxWidth(double height) {
        return Double.MAX_VALUE;
    }

    @Override
    protected double computeMaxHeight(double width) {
        return Double.MAX_VALUE;
    }
}
