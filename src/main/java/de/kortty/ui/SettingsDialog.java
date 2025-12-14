package de.kortty.ui;

import de.kortty.core.ConfigurationManager;
import de.kortty.model.ConnectionSettings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;

/**
 * Dialog for editing global terminal settings.
 */
public class SettingsDialog extends Dialog<ConnectionSettings> {
    
    private final ConfigurationManager configManager;
    private final ConnectionSettings settings;
    
    // Font settings
    private final ComboBox<String> fontFamilyCombo;
    private final Spinner<Integer> fontSizeSpinner;
    
    // Colors
    private final ColorPicker foregroundColorPicker;
    private final ColorPicker backgroundColorPicker;
    private final ColorPicker cursorColorPicker;
    private final ColorPicker selectionColorPicker;
    
    // Terminal size
    private final Spinner<Integer> columnsSpinner;
    private final Spinner<Integer> rowsSpinner;
    private final Spinner<Integer> scrollbackSpinner;
    
    // Other settings
    private final CheckBox boldAsBrightCheck;
    private final ComboBox<String> encodingCombo;
    
    public SettingsDialog(Stage owner, ConfigurationManager configManager) {
        this.configManager = configManager;
        this.settings = new ConnectionSettings(configManager.getGlobalSettings());
        
        setTitle("Einstellungen");
        setHeaderText("Globale Terminal-Einstellungen");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
        // Create tabs
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        // Font tab
        Tab fontTab = new Tab("Schrift");
        GridPane fontGrid = new GridPane();
        fontGrid.setHgap(10);
        fontGrid.setVgap(10);
        fontGrid.setPadding(new Insets(20));
        
        fontFamilyCombo = new ComboBox<>();
        fontFamilyCombo.getItems().addAll(getMonospaceFonts());
        fontFamilyCombo.setValue(settings.getFontFamily());
        fontFamilyCombo.setPrefWidth(200);
        
        fontSizeSpinner = new Spinner<>(8, 72, settings.getFontSize());
        fontSizeSpinner.setEditable(true);
        fontSizeSpinner.setPrefWidth(80);
        
        fontGrid.add(new Label("Schriftart:"), 0, 0);
        fontGrid.add(fontFamilyCombo, 1, 0);
        fontGrid.add(new Label("Schriftgröße:"), 0, 1);
        fontGrid.add(fontSizeSpinner, 1, 1);
        
        // Preview
        Label previewLabel = new Label("AaBbCcDdEe 0123456789");
        previewLabel.setStyle("-fx-background-color: #1e1e1e; -fx-padding: 10;");
        previewLabel.setTextFill(Color.WHITE);
        updatePreviewFont(previewLabel);
        
        fontFamilyCombo.valueProperty().addListener((obs, old, newVal) -> updatePreviewFont(previewLabel));
        fontSizeSpinner.valueProperty().addListener((obs, old, newVal) -> updatePreviewFont(previewLabel));
        
        fontGrid.add(new Label("Vorschau:"), 0, 2);
        fontGrid.add(previewLabel, 1, 2);
        
        fontTab.setContent(fontGrid);
        
        // Colors tab
        Tab colorsTab = new Tab("Farben");
        GridPane colorsGrid = new GridPane();
        colorsGrid.setHgap(10);
        colorsGrid.setVgap(10);
        colorsGrid.setPadding(new Insets(20));
        
        foregroundColorPicker = new ColorPicker(Color.web(settings.getForegroundColor()));
        backgroundColorPicker = new ColorPicker(Color.web(settings.getBackgroundColor()));
        cursorColorPicker = new ColorPicker(Color.web(settings.getCursorColor()));
        selectionColorPicker = new ColorPicker(Color.web(settings.getSelectionColor()));
        
        colorsGrid.add(new Label("Textfarbe:"), 0, 0);
        colorsGrid.add(foregroundColorPicker, 1, 0);
        colorsGrid.add(new Label("Hintergrund:"), 0, 1);
        colorsGrid.add(backgroundColorPicker, 1, 1);
        colorsGrid.add(new Label("Cursor:"), 0, 2);
        colorsGrid.add(cursorColorPicker, 1, 2);
        colorsGrid.add(new Label("Auswahl:"), 0, 3);
        colorsGrid.add(selectionColorPicker, 1, 3);
        
        // ANSI Colors section
        colorsGrid.add(new Separator(), 0, 4, 2, 1);
        colorsGrid.add(new Label("ANSI-Farben:"), 0, 5, 2, 1);
        
        String[] colorNames = {"Schwarz", "Rot", "Grün", "Gelb", "Blau", "Magenta", "Cyan", "Weiß"};
        HBox normalColorsBox = new HBox(5);
        HBox brightColorsBox = new HBox(5);
        
        for (int i = 0; i < 8; i++) {
            ColorPicker normalPicker = new ColorPicker(Color.web(settings.getAnsiColor(i, false)));
            normalPicker.setPrefWidth(40);
            normalPicker.setStyle("-fx-color-label-visible: false;");
            Tooltip.install(normalPicker, new Tooltip(colorNames[i]));
            normalColorsBox.getChildren().add(normalPicker);
            
            ColorPicker brightPicker = new ColorPicker(Color.web(settings.getAnsiColor(i, true)));
            brightPicker.setPrefWidth(40);
            brightPicker.setStyle("-fx-color-label-visible: false;");
            Tooltip.install(brightPicker, new Tooltip(colorNames[i] + " (hell)"));
            brightColorsBox.getChildren().add(brightPicker);
        }
        
        colorsGrid.add(new Label("Normal:"), 0, 6);
        colorsGrid.add(normalColorsBox, 1, 6);
        colorsGrid.add(new Label("Hell:"), 0, 7);
        colorsGrid.add(brightColorsBox, 1, 7);
        
        colorsTab.setContent(colorsGrid);
        
        // Terminal tab
        Tab terminalTab = new Tab("Terminal");
        GridPane terminalGrid = new GridPane();
        terminalGrid.setHgap(10);
        terminalGrid.setVgap(10);
        terminalGrid.setPadding(new Insets(20));
        
        columnsSpinner = new Spinner<>(40, 500, settings.getTerminalColumns());
        columnsSpinner.setEditable(true);
        columnsSpinner.setPrefWidth(80);
        
        rowsSpinner = new Spinner<>(10, 200, settings.getTerminalRows());
        rowsSpinner.setEditable(true);
        rowsSpinner.setPrefWidth(80);
        
        scrollbackSpinner = new Spinner<>(100, 100000, settings.getScrollbackLines(), 1000);
        scrollbackSpinner.setEditable(true);
        scrollbackSpinner.setPrefWidth(100);
        
        boldAsBrightCheck = new CheckBox("Fett als helle Farbe anzeigen");
        boldAsBrightCheck.setSelected(settings.isBoldAsBright());
        
        encodingCombo = new ComboBox<>();
        encodingCombo.getItems().addAll("UTF-8", "ISO-8859-1", "ISO-8859-15", "Windows-1252");
        encodingCombo.setValue(settings.getEncoding());
        
        terminalGrid.add(new Label("Spalten:"), 0, 0);
        terminalGrid.add(columnsSpinner, 1, 0);
        terminalGrid.add(new Label("Zeilen:"), 0, 1);
        terminalGrid.add(rowsSpinner, 1, 1);
        terminalGrid.add(new Label("Scrollback:"), 0, 2);
        terminalGrid.add(scrollbackSpinner, 1, 2);
        terminalGrid.add(new Label("Kodierung:"), 0, 3);
        terminalGrid.add(encodingCombo, 1, 3);
        terminalGrid.add(boldAsBrightCheck, 0, 4, 2, 1);
        
        terminalTab.setContent(terminalGrid);
        
        tabPane.getTabs().addAll(fontTab, colorsTab, terminalTab);
        
        VBox content = new VBox(tabPane);
        content.setPrefSize(500, 400);
        getDialogPane().setContent(content);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                applySettings();
                configManager.setGlobalSettings(settings);
                return settings;
            }
            return null;
        });
    }
    
    private void applySettings() {
        settings.setFontFamily(fontFamilyCombo.getValue());
        settings.setFontSize(fontSizeSpinner.getValue());
        settings.setForegroundColor(toHex(foregroundColorPicker.getValue()));
        settings.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
        settings.setCursorColor(toHex(cursorColorPicker.getValue()));
        settings.setSelectionColor(toHex(selectionColorPicker.getValue()));
        settings.setTerminalColumns(columnsSpinner.getValue());
        settings.setTerminalRows(rowsSpinner.getValue());
        settings.setScrollbackLines(scrollbackSpinner.getValue());
        settings.setBoldAsBright(boldAsBrightCheck.isSelected());
        settings.setEncoding(encodingCombo.getValue());
    }
    
    private void updatePreviewFont(Label previewLabel) {
        previewLabel.setFont(Font.font(fontFamilyCombo.getValue(), fontSizeSpinner.getValue()));
    }
    
    private List<String> getMonospaceFonts() {
        return List.of(
                "Monospaced",
                "Courier New",
                "Consolas",
                "Monaco",
                "Menlo",
                "Source Code Pro",
                "JetBrains Mono",
                "Fira Code",
                "SF Mono"
        );
    }
    
    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }
}
