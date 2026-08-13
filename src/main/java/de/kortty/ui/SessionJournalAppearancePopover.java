package de.kortty.ui;

import de.kortty.core.SessionJournalPageAppearance;
import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalPageScheme;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Slider;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.stage.Popup;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Colour scheme, fonts and text size for the generated journal page, as a popover from the
 * viewer's toolbar. Chosen over a settings page because the effect is visible right behind the
 * popover: every change previews immediately in the WebView and is only then persisted.
 */
public final class SessionJournalAppearancePopover {

    /** "(default)" entry so a font can be cleared back to the page's own stack. */
    private static final String DEFAULT_FONT = "";

    private SessionJournalAppearancePopover() {
    }

    /**
     * Shows the popover anchored under {@code anchor}.
     *
     * @param onPreview called on every change with the appearance to preview immediately
     * @param onCommit  called (debounced by the caller) with the appearance to persist
     */
    public static void show(javafx.scene.Node anchor, GlobalSettings settings,
                            Consumer<SessionJournalPageAppearance> onPreview,
                            Consumer<SessionJournalPageAppearance> onCommit) {
        Popup popup = new Popup();
        popup.setAutoHide(true);
        popup.setAutoFix(true);

        ComboBox<SessionJournalPageScheme> schemeCombo = new ComboBox<>();
        schemeCombo.getItems().setAll(SessionJournalPageSchemes.all());
        schemeCombo.setCellFactory(view -> new SchemeCell());
        schemeCombo.setButtonCell(new SchemeCell());
        schemeCombo.setValue(SessionJournalPageSchemes.byId(settings.getSessionJournalPageSchemeId()));
        schemeCombo.setMaxWidth(Double.MAX_VALUE);

        ComboBox<String> uiFont = fontCombo(fontFamilies(), settings.getSessionJournalPageUiFont());
        ComboBox<String> monoFont = fontCombo(
            MonospaceFontFamilies.monospaceFirst(), settings.getSessionJournalPageMonoFont());

        Slider size = new Slider(SessionJournalPageAppearance.MIN_FONT_SCALE,
            SessionJournalPageAppearance.MAX_FONT_SCALE, settings.getSessionJournalFontScalePercent());
        size.setBlockIncrement(10);
        size.setMajorTickUnit(10);
        size.setSnapToTicks(true);
        Label sizeValue = new Label(settings.getSessionJournalFontScalePercent() + " %");
        sizeValue.setMinWidth(48);

        Runnable notify = () -> {
            SessionJournalPageAppearance appearance = new SessionJournalPageAppearance(
                schemeCombo.getValue() != null ? schemeCombo.getValue().id() : SessionJournalPageScheme.ID_AUTO,
                emptyToNull(uiFont.getValue()), emptyToNull(monoFont.getValue()),
                (int) Math.round(size.getValue()));
            sizeValue.setText(appearance.fontScalePercent() + " %");
            onPreview.accept(appearance);
            onCommit.accept(appearance);
        };
        schemeCombo.valueProperty().addListener((obs, old, value) -> notify.run());
        uiFont.valueProperty().addListener((obs, old, value) -> notify.run());
        monoFont.valueProperty().addListener((obs, old, value) -> notify.run());
        size.valueProperty().addListener((obs, old, value) -> notify.run());

        Button reset = new Button(I18n.get("journal.viewer.appearance.reset"));
        reset.setOnAction(event -> {
            schemeCombo.setValue(SessionJournalPageSchemes.byId(SessionJournalPageScheme.ID_AUTO));
            uiFont.setValue(DEFAULT_FONT);
            monoFont.setValue(DEFAULT_FONT);
            size.setValue(100);
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        int row = 0;
        grid.addRow(row++, new Label(I18n.get("journal.viewer.appearance.scheme")), schemeCombo);
        grid.addRow(row++, new Label(I18n.get("journal.viewer.appearance.uiFont")), uiFont);
        grid.addRow(row++, new Label(I18n.get("journal.viewer.appearance.monoFont")), monoFont);
        HBox sizeRow = new HBox(8, size, sizeValue);
        sizeRow.setAlignment(Pos.CENTER_LEFT);
        grid.addRow(row++, new Label(I18n.get("journal.viewer.appearance.fontSize")), sizeRow);

        Label hint = new Label(I18n.get("journal.viewer.appearance.hint"));
        hint.setWrapText(true);
        hint.setMaxWidth(320);
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");

        VBox container = new VBox(10, grid, reset, hint);
        container.setPadding(new Insets(10));
        container.setStyle("-fx-background-color: -fx-control-inner-background;"
            + " -fx-border-color: rgba(128,128,128,0.4); -fx-border-radius: 6; -fx-background-radius: 6;"
            + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.3), 12, 0, 0, 4);");
        // A raw Popup does not inherit the owner scene's stylesheets, so the UI font scale has to
        // be applied to its content root directly.
        UiFontScaleSupport.applyToParent(container);
        popup.getContent().add(container);

        var bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            popup.show(anchor, bounds.getMinX(), bounds.getMaxY() + 4);
        }
    }

    private static ComboBox<String> fontCombo(List<String> families, String current) {
        ComboBox<String> combo = new ComboBox<>();
        List<String> items = new ArrayList<>(families.size() + 1);
        items.add(DEFAULT_FONT);
        items.addAll(families);
        combo.getItems().setAll(items);
        combo.setCellFactory(view -> new FontCell());
        combo.setButtonCell(new FontCell());
        combo.setValue(current != null && families.contains(current) ? current : DEFAULT_FONT);
        combo.setMaxWidth(Double.MAX_VALUE);
        combo.setPrefWidth(200);
        return combo;
    }

    private static List<String> fontFamilies() {
        return javafx.scene.text.Font.getFamilies();
    }

    private static String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Scheme name plus a strip of its colours, so the palettes can be told apart at a glance. */
    private static final class SchemeCell extends ListCell<SessionJournalPageScheme> {

        @Override
        protected void updateItem(SessionJournalPageScheme item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            setText(SessionJournalPageSchemes.displayName(item));
            setGraphic(item.derived() ? null : chips(item));
        }

        private static HBox chips(SessionJournalPageScheme scheme) {
            HBox strip = new HBox(2);
            for (String colour : List.of(scheme.bg(), scheme.surface(), scheme.accent(),
                scheme.input(), scheme.output())) {
                Rectangle chip = new Rectangle(9, 9);
                chip.setArcWidth(3);
                chip.setArcHeight(3);
                try {
                    chip.setFill(Color.web(colour));
                } catch (IllegalArgumentException e) {
                    chip.setFill(Color.GRAY);
                }
                strip.getChildren().add(chip);
            }
            return strip;
        }
    }

    /** Renders the empty value as "(default)" instead of a blank row. */
    private static final class FontCell extends ListCell<String> {

        @Override
        protected void updateItem(String item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                return;
            }
            setText(item.isBlank() ? I18n.get("journal.viewer.appearance.default") : item);
        }
    }
}
