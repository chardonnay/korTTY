package de.kortty.ui;

import javafx.geometry.HPos;
import javafx.geometry.Insets;
import javafx.geometry.VPos;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

/**
 * The snippet editor's code row: the structure panel and its drag divider on the left, while the
 * panel is shown, and the code column (column ruler plus editor) taking the rest.
 *
 * <p>Built once with all three children; showing and hiding the panel only toggles its visibility,
 * so the code column — and the Monaco WebView in it — never changes parent and its page is never
 * reloaded. Like {@link SnippetEditorWorkbench}, and unlike an {@code HBox}, the panel keeps its
 * preferred (dragged) width and only the code flexes; the panel yields once the code would fall
 * below {@link SnippetEditorWorkbench#MIN_EDITOR_WIDTH}, never below its own minimum. The ruler
 * sits in the code column, so it stays aligned with the editor's columns. The row's height is the
 * code column's: the panel fills whatever height the code gets and never asks for more.
 */
final class SnippetStructureRow extends Pane {

    private final Region panel;
    private final Region divider;
    private final Region codeColumn;

    SnippetStructureRow(Region panel, Region divider, Region codeColumn) {
        this.panel = panel;
        this.divider = divider;
        this.codeColumn = codeColumn;
        getChildren().addAll(panel, divider, codeColumn);
        setPanelShown(false);
    }

    /** Shows or hides the panel and its divider beside the code. */
    void setPanelShown(boolean shown) {
        for (Region region : new Region[] {panel, divider}) {
            region.setVisible(shown);
            region.setManaged(shown);
        }
        requestLayout();
    }

    boolean isPanelShown() {
        return panel.isVisible();
    }

    /** The widest the panel may be dragged to at the current width. */
    double maximumPanelWidth() {
        return Math.max(panel.minWidth(-1),
            contentWidth() - divider.prefWidth(-1) - SnippetEditorWorkbench.MIN_EDITOR_WIDTH);
    }

    private double contentWidth() {
        Insets insets = getInsets();
        return getWidth() - insets.getLeft() - insets.getRight();
    }

    @Override
    protected void layoutChildren() {
        Insets insets = getInsets();
        double x = insets.getLeft();
        double y = insets.getTop();
        double width = contentWidth();
        double height = getHeight() - insets.getTop() - insets.getBottom();
        if (!isPanelShown()) {
            layoutInArea(codeColumn, x, y, width, height, 0, HPos.LEFT, VPos.TOP);
            return;
        }
        double dividerWidth = divider.prefWidth(-1);
        double available = Math.max(0, width - dividerWidth);
        double panelWidth = SnippetEditorWorkbench.panelWidth(available, panel.prefWidth(-1), panel.minWidth(-1),
            SnippetEditorWorkbench.MIN_EDITOR_WIDTH);
        double codeWidth = Math.max(0, available - panelWidth);
        layoutInArea(panel, x, y, panelWidth, height, 0, HPos.LEFT, VPos.TOP);
        layoutInArea(divider, x + panelWidth, y, dividerWidth, height, 0, HPos.LEFT, VPos.TOP);
        layoutInArea(codeColumn, x + panelWidth + dividerWidth, y, codeWidth, height, 0, HPos.LEFT, VPos.TOP);
    }

    @Override
    protected double computeMinWidth(double height) {
        Insets insets = getInsets();
        double minimum = codeColumn.minWidth(-1);
        if (isPanelShown()) {
            minimum += divider.prefWidth(-1) + panel.minWidth(-1);
        }
        return insets.getLeft() + minimum + insets.getRight();
    }

    @Override
    protected double computePrefWidth(double height) {
        Insets insets = getInsets();
        double preferred = codeColumn.prefWidth(-1);
        if (isPanelShown()) {
            preferred += divider.prefWidth(-1) + Math.max(panel.minWidth(-1), panel.prefWidth(-1));
        }
        return insets.getLeft() + preferred + insets.getRight();
    }

    @Override
    protected double computeMinHeight(double width) {
        Insets insets = getInsets();
        return insets.getTop() + codeColumn.minHeight(-1) + insets.getBottom();
    }

    @Override
    protected double computePrefHeight(double width) {
        Insets insets = getInsets();
        return insets.getTop() + codeColumn.prefHeight(-1) + insets.getBottom();
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
