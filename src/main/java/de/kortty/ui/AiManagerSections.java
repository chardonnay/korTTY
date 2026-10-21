package de.kortty.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.text.TextAlignment;

/**
 * The secondary tab strip inside an AI Manager section (the Profiles editor, Local AI) and the
 * label/field grids on its pages. One page holds one topic, so each fits without scrolling at the
 * dialog's default size; the grid keeps every label at full width and lets the field column give
 * way when the window is narrow.
 */
final class AiManagerSections {

    /** Style class of the secondary strip, distinct from the primary {@code ai-manager-primary-navigation}. */
    static final String NAVIGATION_STYLE_CLASS = "ai-manager-section-navigation";

    private AiManagerSections() {
    }

    static TabPane tabPane(Tab... tabs) {
        TabPane pane = new TabPane(tabs);
        pane.getStyleClass().add(NAVIGATION_STYLE_CLASS);
        pane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        return pane;
    }

    /** A page: the content under some padding, scrolling only when the window is smaller than the page. */
    static Tab tab(String titleKey, Node content) {
        VBox body = new VBox(10, content);
        body.setPadding(new Insets(12, 12, 12, 8));
        ScrollPane scroller = new ScrollPane(body);
        scroller.setFitToWidth(true);
        scroller.setFitToHeight(false);
        scroller.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scroller.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        return new Tab(I18n.get(titleKey), scroller);
    }

    /** A two-column form: the labels keep their full width, the field column grows. */
    static GridPane formGrid() {
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(Region.USE_PREF_SIZE);
        ColumnConstraints fieldColumn = new ColumnConstraints();
        fieldColumn.setHgrow(Priority.ALWAYS);
        grid.getColumnConstraints().addAll(labelColumn, fieldColumn);
        return grid;
    }

    static void addRow(GridPane grid, String labelKey, Node field) {
        int row = grid.getRowCount();
        grid.add(new Label(I18n.get(labelKey)), 0, row);
        grid.add(field, 1, row);
    }

    /** A row without a label: a check box, or a hint under the field above it. */
    static void addFieldRow(GridPane grid, Node field) {
        grid.add(field, 1, grid.getRowCount());
    }

    static void addSeparator(GridPane grid) {
        grid.add(new Separator(), 0, grid.getRowCount(), 2, 1);
    }

    /**
     * The row under a section's pages: its status text, then {@code save} at the right edge, above the
     * dialog's Close button, as the Profiles editor places its Save.
     */
    static HBox saveRow(Button save, Label status) {
        status.setMaxWidth(Double.MAX_VALUE);
        status.setAlignment(Pos.CENTER_RIGHT);
        status.setTextAlignment(TextAlignment.RIGHT);
        HBox.setHgrow(status, Priority.ALWAYS);
        HBox row = new HBox(10, status, save);
        row.setAlignment(Pos.CENTER_RIGHT);
        return row;
    }
}
