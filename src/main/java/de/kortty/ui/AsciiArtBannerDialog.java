package de.kortty.ui;

import com.github.lalyos.jfiglet.FigletFont;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Dialog to create ASCII art banners using the jfiglet library (FIGfonts).
 * Supports multiple font styles (Standard, Slant, and bundled fonts).
 */
public class AsciiArtBannerDialog extends Dialog<Void> {

    private final TextField inputField;
    private final TextArea outputArea;
    private final ComboBox<String> styleCombo;

    /** Bundled font names (must match /flf/<name>.flf filename without .flf). */
    private static final String[] BUNDLED_FONTS = {
        "3-D", "banner", "big", "block", "cosmic", "Digital", "Lean", "roman", "script", "small"
    };

    public AsciiArtBannerDialog() {
        setTitle(I18n.get("asciiArt.title"));
        setResizable(true);

        styleCombo = new ComboBox<>();
        styleCombo.getItems().addAll(buildFontList());
        styleCombo.setValue(styleCombo.getItems().isEmpty() ? null : styleCombo.getItems().get(0));
        styleCombo.setPrefWidth(200);
        styleCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateOutput());

        Label inputLabel = new Label(I18n.get("asciiArt.inputLabel") + ":");
        inputField = new TextField();
        inputField.setPromptText(I18n.get("asciiArt.inputPrompt"));
        inputField.setPrefColumnCount(30);

        Label outputLabel = new Label(I18n.get("asciiArt.outputLabel") + ":");
        outputArea = new TextArea();
        outputArea.setEditable(false);
        outputArea.setWrapText(false);
        outputArea.setPrefRowCount(14);
        outputArea.setStyle("-fx-font-family: monospace; -fx-font-size: 12px;");

        inputField.textProperty().addListener((obs, oldVal, newVal) -> updateOutput());

        Button copyBtn = new Button(I18n.get("asciiArt.copyToClipboard"));
        copyBtn.setOnAction(e -> copyToClipboard());

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));
        grid.add(new Label(I18n.get("asciiArt.style") + ":"), 0, 0);
        grid.add(styleCombo, 1, 0);
        GridPane.setHgrow(styleCombo, Priority.ALWAYS);
        grid.add(inputLabel, 0, 1);
        grid.add(inputField, 1, 1);
        GridPane.setHgrow(inputField, Priority.ALWAYS);
        grid.add(outputLabel, 0, 2);
        grid.add(outputArea, 1, 2);
        GridPane.setHgrow(outputArea, Priority.ALWAYS);
        GridPane.setVgrow(outputArea, Priority.ALWAYS);
        grid.add(copyBtn, 1, 3);

        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        getDialogPane().setPrefWidth(560);
        getDialogPane().setPrefHeight(500);

        updateOutput();
    }

    private static List<String> buildFontList() {
        List<String> names = new ArrayList<>();
        names.add("Standard");
        try {
            InputStream slant = FigletFont.class.getResourceAsStream("/slant.flf");
            if (slant != null) {
                slant.close();
                names.add("Slant");
            }
        } catch (Exception ignored) { /* no Slant */ }
        for (String name : BUNDLED_FONTS) {
            String path = "/flf/" + name + ".flf";
            if (AsciiArtBannerDialog.class.getResource(path) != null) {
                names.add(name);
            }
        }
        return names;
    }

    private void updateOutput() {
        String text = inputField.getText();
        if (text == null || text.isBlank()) {
            outputArea.setText("");
            return;
        }
        String styleVal = styleCombo.getValue() != null ? styleCombo.getValue() : "Standard";
        try {
            String result = text.lines()
                    .map(line -> {
                        if (line.isBlank()) return "";
                        try {
                            return convertLine(styleVal, line);
                        } catch (Exception e) {
                            return line;
                        }
                    })
                    .collect(Collectors.joining("\n"));
            outputArea.setText(result);
        } catch (Exception e) {
            outputArea.setText(text);
        }
    }

    private static String convertLine(String style, String line) throws Exception {
        if ("Standard".equals(style)) {
            return FigletFont.convertOneLine(line);
        }
        if ("Slant".equals(style)) {
            try (InputStream font = FigletFont.class.getResourceAsStream("/slant.flf")) {
                if (font != null) return FigletFont.convertOneLine(font, line);
            }
        }
        String path = "/flf/" + style + ".flf";
        try (InputStream font = AsciiArtBannerDialog.class.getResourceAsStream(path)) {
            if (font != null) return FigletFont.convertOneLine(font, line);
        }
        return FigletFont.convertOneLine(line);
    }

    private void copyToClipboard() {
        String text = outputArea.getText();
        if (text == null || text.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
