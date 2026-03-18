package de.kortty.ui;

import com.github.lalyos.jfiglet.FigletFont;
import de.kortty.model.WindowGeometry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.stage.Stage;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Dialog to create ASCII art banners using the jfiglet library (FIGfonts).
 * Supports multiple font styles (Standard, Slant, and bundled fonts).
 */
public class AsciiArtBannerDialog extends ThemeAwareDialog<Void> {

    private final TextField inputField;
    private final TextArea outputArea;
    private final ComboBox<String> styleCombo;

    /** Bundled font names (must match flf/<name>.flf filename without .flf; use lowercase for case-sensitive FS). */
    private static final String[] BUNDLED_FONTS = {
        "3-D", "banner", "big", "block", "cosmic", "Digital", "lean", "roman", "script", "small"
    };

    /** Cached loaded fonts per style so each font file is only parsed once. */
    private static final Map<String, FigletFont> FONT_CACHE = new ConcurrentHashMap<>();

    public AsciiArtBannerDialog() {
        setTitle(I18n.get("asciiArt.title"));
        setResizable(true);

        styleCombo = new ComboBox<>();
        List<String> styleList = buildFontList();
        styleCombo.getItems().addAll(styleList);
        styleCombo.setValue(styleList.isEmpty() ? null : styleList.get(0));
        styleCombo.setPrefWidth(200);
        styleCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateOutput());
        styleCombo.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.LEFT || e.getCode() == KeyCode.UP) {
                selectPrevStyle();
                e.consume();
            } else if (e.getCode() == KeyCode.RIGHT || e.getCode() == KeyCode.DOWN) {
                selectNextStyle();
                e.consume();
            }
        });

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

        Button prevStyleBtn = new Button("◀");
        prevStyleBtn.setTooltip(new Tooltip(I18n.get("asciiArt.prevStyle")));
        prevStyleBtn.setOnAction(e -> selectPrevStyle());
        Button nextStyleBtn = new Button("▶");
        nextStyleBtn.setTooltip(new Tooltip(I18n.get("asciiArt.nextStyle")));
        nextStyleBtn.setOnAction(e -> selectNextStyle());
        HBox styleBox = new HBox(5);
        styleBox.setAlignment(Pos.CENTER_LEFT);
        styleBox.getChildren().addAll(prevStyleBtn, styleCombo, nextStyleBtn);
        GridPane.setHgrow(styleCombo, Priority.ALWAYS);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(15));
        grid.add(new Label(I18n.get("asciiArt.style") + ":"), 0, 0);
        grid.add(styleBox, 1, 0);
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

        restoreGeometry();
        setOnCloseRequest(e -> saveGeometry());
        setResultConverter(bt -> { saveGeometry(); return null; });

        updateOutput();
    }

    private void restoreGeometry() {
        try {
            de.kortty.model.GlobalSettings settings = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            if (settings == null) return;
            WindowGeometry geo = settings.getAsciiArtDialogGeometry();
            if (geo != null && geo.getWidth() > 100 && geo.getHeight() > 100) {
                getDialogPane().setPrefWidth(geo.getWidth());
                getDialogPane().setPrefHeight(geo.getHeight());
                setOnShowing(e -> {
                    javafx.stage.Window window = getDialogPane().getScene().getWindow();
                    if (window instanceof Stage s) {
                        s.setX(geo.getX());
                        s.setY(geo.getY());
                        s.setWidth(geo.getWidth());
                        s.setHeight(geo.getHeight());
                    }
                });
            }
        } catch (Exception ignored) { /* use default size/position */ }
    }

    private void saveGeometry() {
        try {
            javafx.stage.Window window = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
            if (window instanceof Stage stage) {
                WindowGeometry geo = new WindowGeometry(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
                de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
                if (gsm != null && gsm.getSettings() != null) {
                    gsm.getSettings().setAsciiArtDialogGeometry(geo);
                    gsm.save();
                }
            }
        } catch (Exception ignored) { /* skip save on error */ }
    }

    private static List<String> buildFontList() {
        List<String> names = new ArrayList<>();
        names.add("Standard");
        try (InputStream slant = openFontStream("Slant", "/slant.flf")) {
            if (slant != null) names.add("Slant");
        } catch (Exception ignored) { /* no Slant */ }
        for (String name : BUNDLED_FONTS) {
            boolean added = false;
            try (InputStream in = openFontStream(name, "/flf/" + name + ".flf")) {
                if (in != null) {
                    names.add(name);
                    added = true;
                }
            } catch (Exception ignored) {
                if (!added && fontResourceExists(name)) names.add(name);
            }
        }
        return names;
    }

    private static boolean fontResourceExists(String style) {
        if (AsciiArtBannerDialog.class.getResource("/flf/" + style + ".flf") != null) return true;
        if (!style.equals(style.toLowerCase()) && AsciiArtBannerDialog.class.getResource("/flf/" + style.toLowerCase() + ".flf") != null) return true;
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null && ctx.getResource("flf/" + style + ".flf") != null) return true;
        if (ctx != null && !style.equals(style.toLowerCase()) && ctx.getResource("flf/" + style.toLowerCase() + ".flf") != null) return true;
        return false;
    }

    /** Opens a font stream using classloader and path variants so resources are found from JAR or IDE. */
    private static InputStream openFontStream(String style, String classPath) {
        ClassLoader ctx = Thread.currentThread().getContextClassLoader();
        if (ctx != null) {
            String noLeading = classPath.startsWith("/") ? classPath.substring(1) : classPath;
            InputStream in = ctx.getResourceAsStream(noLeading);
            if (in != null) return in;
            String lower = "flf/" + style.toLowerCase() + ".flf";
            if (!lower.equals(noLeading)) {
                in = ctx.getResourceAsStream(lower);
                if (in != null) return in;
            }
        }
        InputStream in = AsciiArtBannerDialog.class.getResourceAsStream(classPath);
        if (in != null) return in;
        if (!style.equals(style.toLowerCase())) {
            in = AsciiArtBannerDialog.class.getResourceAsStream("/flf/" + style.toLowerCase() + ".flf");
            if (in != null) return in;
        }
        return null;
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
        FigletFont font = getOrLoadFont(style);
        if (font != null) {
            return font.convert(line);
        }
        return FigletFont.convertOneLine(line);
    }

    /** Load and cache a font by style; uses openFontStream and reads fully so stream is not reused. */
    private static FigletFont getOrLoadFont(String style) {
        FigletFont cached = FONT_CACHE.get(style);
        if (cached != null) return cached;
        InputStream in = null;
        if ("Slant".equals(style)) {
            in = FigletFont.class.getResourceAsStream("/slant.flf");
            if (in == null) in = openFontStream(style, "/slant.flf");
        } else {
            in = openFontStream(style, "/flf/" + style + ".flf");
        }
        try {
            if (in == null) return null;
            byte[] bytes = in.readAllBytes();
            FigletFont font = new FigletFont(new ByteArrayInputStream(bytes));
            FONT_CACHE.put(style, font);
            return font;
        } catch (IOException e) {
            return null;
        } finally {
            if (in != null) {
                try { in.close(); } catch (IOException ignored) { }
            }
        }
    }

    private void selectPrevStyle() {
        List<String> items = styleCombo.getItems();
        if (items.isEmpty()) return;
        String current = styleCombo.getValue();
        int idx = current != null ? items.indexOf(current) : 0;
        idx = idx <= 0 ? items.size() - 1 : idx - 1;
        styleCombo.setValue(items.get(idx));
    }

    private void selectNextStyle() {
        List<String> items = styleCombo.getItems();
        if (items.isEmpty()) return;
        String current = styleCombo.getValue();
        int idx = current != null ? items.indexOf(current) : -1;
        idx = idx < 0 || idx >= items.size() - 1 ? 0 : idx + 1;
        styleCombo.setValue(items.get(idx));
    }

    private void copyToClipboard() {
        String text = outputArea.getText();
        if (text == null || text.isEmpty()) return;
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }
}
