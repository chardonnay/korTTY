package de.kortty.ui;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.SingleSelectionModel;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;

/**
 * Previous/next arrow buttons just above a tab pane's header, for dialogs with more tabs than fit
 * in one row. The tab pane's own overflow menu jumps to a tab by name; the arrows step through the
 * tabs one by one, and a counter ("3 / 8") says where you are. Disabled tabs are skipped, and an
 * arrow is disabled at either end. Ctrl+Tab / Ctrl+Shift+Tab (and Ctrl+Page Down / Up) do the same
 * from anywhere in the dialog.
 *
 * <p>The arrows sit in a slim row of their own rather than floating over the header's right end:
 * the header ignores extra right padding when it scrolls the selected tab into view, so a floating
 * arrow box ended up covering the very tab it had just selected.</p>
 */
final class TabPaneArrowNavigation {

    /** Style class of the arrow row, for tests and design stylesheets. */
    static final String ARROWS_STYLE_CLASS = "kortty-tab-arrows";

    private TabPaneArrowNavigation() {
    }

    /** Wraps the tab pane with its arrow row; put the returned node where the tab pane would go. */
    static BorderPane wrap(TabPane tabPane) {
        Button previous = arrow(ButtonIcons.CHEVRON_LEFT, "tabs.previous");
        Button next = arrow(ButtonIcons.CHEVRON_RIGHT, "tabs.next");
        previous.setOnAction(event -> step(tabPane, -1));
        next.setOnAction(event -> step(tabPane, 1));
        Label position = new Label();
        position.setStyle(MutedTextStyle.HINT);

        HBox arrows = new HBox(4, position, previous, next);
        arrows.getStyleClass().add(ARROWS_STYLE_CLASS);
        arrows.setAlignment(Pos.CENTER_RIGHT);
        arrows.setPadding(new Insets(0, 0, 4, 0));

        Runnable refresh = () -> {
            previous.setDisable(target(tabPane, -1) < 0);
            next.setDisable(target(tabPane, 1) < 0);
            int index = tabPane.getSelectionModel().getSelectedIndex();
            position.setText(index < 0 ? "" : (index + 1) + " / " + tabPane.getTabs().size());
        };
        tabPane.getSelectionModel().selectedIndexProperty().addListener((obs, oldIndex, newIndex) -> refresh.run());
        tabPane.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> refresh.run());
        tabPane.getTabs().forEach(tab -> tab.disableProperty().addListener((obs, was, disabled) -> refresh.run()));
        refresh.run();

        BorderPane wrapper = new BorderPane(tabPane);
        wrapper.setTop(arrows);
        // The tab pane steps on Ctrl+Tab only while its header has the focus; from a field inside a
        // tab the key just moved the focus. Caught for the whole dialog, so it always switches tabs.
        wrapper.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            int direction = keyDirection(event);
            if (direction != 0) {
                step(tabPane, direction);
                event.consume();
            }
        });
        return wrapper;
    }

    /** +1 for Ctrl+Tab / Ctrl+Page Down, −1 for Ctrl+Shift+Tab / Ctrl+Page Up, otherwise 0. */
    static int keyDirection(KeyEvent event) {
        if (!event.isControlDown() || event.isAltDown() || event.isMetaDown()) {
            return 0;
        }
        if (event.getCode() == KeyCode.TAB) {
            return event.isShiftDown() ? -1 : 1;
        }
        if (!event.isShiftDown() && event.getCode() == KeyCode.PAGE_DOWN) {
            return 1;
        }
        if (!event.isShiftDown() && event.getCode() == KeyCode.PAGE_UP) {
            return -1;
        }
        return 0;
    }

    private static Button arrow(String icon, String tooltipKey) {
        Button button = new Button();
        ButtonIcons.apply(button, icon);
        String text = I18n.get(tooltipKey);
        button.setTooltip(new Tooltip(text));
        button.setAccessibleText(text);
        button.setFocusTraversable(false);
        return button;
    }

    /** Selects the next enabled tab in {@code direction} (−1 or +1), if there is one. */
    static void step(TabPane tabPane, int direction) {
        int index = target(tabPane, direction);
        if (index >= 0) {
            tabPane.getSelectionModel().select(index);
        }
    }

    /** The index of the next enabled tab in {@code direction}, or −1 at that end. */
    static int target(TabPane tabPane, int direction) {
        SingleSelectionModel<Tab> selection = tabPane.getSelectionModel();
        int index = selection.getSelectedIndex();
        for (int i = index + direction; i >= 0 && i < tabPane.getTabs().size(); i += direction) {
            if (!tabPane.getTabs().get(i).isDisable()) {
                return i;
            }
        }
        return -1;
    }
}
