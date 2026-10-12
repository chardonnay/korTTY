package de.kortty.ui;

import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;

/** Original 24-unit, two-layer glyphs: theme-aware contour and a small semantic colour core. */
final class ToolbarIcons {
    private record Glyph(String contour, String core, String color) { }
    private ToolbarIcons() { }

    static Node create(String name, javafx.beans.value.ObservableValue<? extends javafx.scene.paint.Paint> contourColor,
                       javafx.beans.value.ObservableDoubleValue size) {
        Glyph glyph = glyph(name);
        SVGPath outline = path(glyph.contour());
        outline.getStyleClass().add("toolbar-icon-contour");
        // ToolBar overflow moves buttons into a popup, outside the toolbar CSS ancestor chain.
        outline.strokeProperty().bind(contourColor);
        SVGPath core = path(glyph.core());
        core.setStyle("-fx-stroke: " + glyph.color() + ";");
        Group drawing = new Group(outline, core);
        // Invisible fixed bounds keep every glyph optically aligned without stretching its paths.
        javafx.scene.shape.Rectangle bounds = new javafx.scene.shape.Rectangle(24, 24);
        bounds.setFill(javafx.scene.paint.Color.TRANSPARENT);
        Group art = new Group(bounds, drawing);
        var scale = new javafx.scene.transform.Scale(1, 1, 0, 0);
        scale.xProperty().bind(javafx.beans.binding.Bindings.divide(size, 24));
        scale.yProperty().bind(scale.xProperty());
        art.getTransforms().add(scale);
        // The wrapper exposes the transformed bounds to StackPane's centering calculation.
        StackPane canvas = new StackPane(new Group(art));
        canvas.minWidthProperty().bind(size);
        canvas.minHeightProperty().bind(size);
        canvas.prefWidthProperty().bind(size);
        canvas.prefHeightProperty().bind(size);
        canvas.maxWidthProperty().bind(size);
        canvas.maxHeightProperty().bind(size);
        canvas.setMouseTransparent(true);
        return canvas;
    }

    private static SVGPath path(String data) {
        SVGPath path = new SVGPath();
        path.setContent(data);
        path.setFill(null);
        path.setStrokeWidth(1.8);
        path.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
        path.setStrokeLineJoin(javafx.scene.shape.StrokeLineJoin.ROUND);
        return path;
    }

    private static Glyph glyph(String name) {
        return switch (name) {
            case "tab" -> blue("M3 20 V6 Q3 3 6 3 H18 Q21 3 21 6 V20", "M8 12 H16 M12 8 V16");
            case "shell", "terminal" -> blue("M5 3 H19 Q21 3 21 5 V19 Q21 21 19 21 H5 Q3 21 3 19 V5 Q3 3 5 3", "M7 8 L11 12 L7 16 M13 16 H17");
            case "sftp" -> teal("M3 7 V5 Q3 3 5 3 H10 L13 6 H19 Q21 6 21 8 V19 Q21 21 19 21 H5 Q3 21 3 19 Z", "M7 11 H17 L14 8 M17 16 H7 L10 19");
            case "ai" -> new Glyph("M9 4 Q5 2 5 7 Q1 8 4 12 Q1 16 5 18 Q5 23 10 20 M15 4 Q19 2 19 7 Q23 8 20 12 Q23 16 19 18 Q19 23 14 20", "M12 3 V21 M8 8 H12 M12 15 H17 M8 17 V13 H12 M16 6 V10 H12", "#a56ae6");
            case "jobs" -> new Glyph("M12 21 H5 Q3 21 3 19 V6 Q3 4 5 4 H18 Q20 4 20 6 V10 M3 9 H20 M7 2 V6 M16 2 V6 M7 13 H9 M7 17 H9 M16 12 A5 5 0 1 0 16.01 12", "M17 15 V18 H20", "#dc9825");
            case "journal", "logging" -> teal("M7 3 H20 V21 H7 Q4 21 4 18 V6 Q4 3 7 3 Z M8 3 V21 M2 7 H5 M2 12 H5 M2 17 H5", "M13 3 V11 L16 9 L19 11 V3");
            case "gear" -> blue("M9 3 H15 L16 6 L19 5 L22 10 L19 12 L22 14 L19 19 L16 18 L15 21 H9 L8 18 L5 19 L2 14 L5 12 L2 10 L5 5 L8 6 Z", "M12 8 A4 4 0 1 0 12.01 8");
            case "server" -> blue("M5 3 H19 Q21 3 21 5 V9 Q21 11 19 11 H5 Q3 11 3 9 V5 Q3 3 5 3 M5 13 H19 Q21 13 21 15 V19 Q21 21 19 21 H5 Q3 21 3 19 V15 Q3 13 5 13", "M16 7 H17 M16 17 H17");
            case "group" -> blue("M8 2 H19 V10 H8 Z M2 12 H12 V21 H2 Z M15 13 H23 V21 H15 Z", "M15 6 H16 M8 16 H9 M19 17 H20");
            case "font" -> blue("M3 17 L8 4 L13 17 M5 12 H11 M16 10 Q22 7 22 12 V17 M22 13 Q15 11 16 16 Q18 19 22 15", "M4 21 H20");
            case "colors" -> blue("M12 3 C1 3 0 17 8 20 C13 23 11 17 16 17 C24 18 24 3 12 3 Z M7 8 H7.1 M13 6 H13.1 M18 9 H18.1", "M7 14 A1 1 0 1 0 7.01 14");
            case "keyboard" -> blue("M3 5 H21 V19 H3 Z M6 9 H7 M10 9 H11 M14 9 H15 M18 9 H18.1 M6 13 H7 M10 13 H11 M6 16 H14", "M15 13 H18 V16 H15 Z");
            case "security" -> blue("M12 2 L21 6 V12 Q21 18 12 22 Q3 18 3 12 V6 Z", "M9 12 V10 A3 3 0 0 1 15 10 V12 M8 12 H16 V17 H8 Z");
            case "privacy" -> new Glyph("M2 12 Q12 1 22 12 Q12 23 2 12 Z", "M12 8 A4 4 0 1 0 12.01 8 M4 21 L21 3", "#a56ae6");
            case "language" -> blue("M12 2 A10 10 0 1 0 12.01 2 M2 12 H22 M12 2 C5 7 5 17 12 22 C19 17 19 7 12 2", "M8 7 H16 M8 17 H16");
            case "translation" -> teal("M2 4 H13 V15 H5 L2 18 Z M15 9 H22 V21 H12 V17", "M5 8 H10 M7 6 V8 M6 8 Q6 11 10 12 M10 8 Q10 11 5 12 M15 18 L17 12 L19 18 M16 16 H18");
            case "themes" -> new Glyph("M12 2 A10 10 0 1 0 12.01 2 M12 2 V22", "M16 6 V18 M19 9 V15", "#a56ae6");
            case "appearance" -> blue("M3 4 H21 V20 H3 Z M3 9 H21 M9 9 V20", "M13 13 H18 M13 16 H17");
            case "window" -> blue("M3 4 H21 V20 H3 Z M3 9 H21", "M6 7 H7 M10 7 H11 M14 7 H15");
            case "video" -> new Glyph("M3 4 H21 V20 H3 Z M7 4 V20 M17 4 V20 M3 9 H7 M3 15 H7 M17 9 H21 M17 15 H21", "M10 9 L14 12 L10 15 Z", "#a56ae6");
            // A document with text lines and a folded corner: the terminal log files.
            case "logfile" -> teal("M6 3 H14 L19 8 V21 H6 Z M14 3 V8 H19", "M9 12 H16 M9 15 H16 M9 18 H13");
            case "backup" -> teal("M3 8 H21 V21 H3 Z M2 3 H22 V8 H2 Z", "M8 13 H16 M12 10 V18 M9 15 L12 18 L15 15");
            case "export" -> teal("M13 3 H5 V21 H19 V13", "M10 14 L21 3 M15 3 H21 V9");
            case "updates" -> blue("M20 9 A8 8 0 0 0 6 5 L3 8 M3 15 A8 8 0 0 0 17 19 L21 16", "M3 3 V8 H8 M16 16 H21 V21");
            case "resources" -> teal("M6 6 H18 V18 H6 Z M3 8 H6 M3 12 H6 M3 16 H6 M18 8 H21 M18 12 H21 M18 16 H21 M8 3 V6 M12 3 V6 M16 3 V6 M8 18 V21 M12 18 V21 M16 18 V21", "M10 10 H14 V14 H10 Z");
            case "editor" -> blue("M3 3 H17 L21 7 V21 H3 Z M17 3 V7 H21", "M8 10 L5 13 L8 16 M16 10 L19 13 L16 16 M13 9 L11 18");
            case "snippetEditor" -> teal("M6 2 H20 V18 M3 6 H16 V22 H3 Z", "M8 10 L5 14 L8 18 M11 10 L14 14 L11 18");
            case "separator" -> blue("M12 3 V21", "M12 10 V14");
            case "add" -> blue("M12 3 A9 9 0 1 0 12.01 3", "M7 12 H17 M12 7 V17");
            default -> blue("M4 4 H20 V20 H4 Z", "M8 12 H16 M12 8 V16");
        };
    }

    private static Glyph blue(String outline, String core) { return new Glyph(outline, core, "#368af5"); }
    private static Glyph teal(String outline, String core) { return new Glyph(outline, core, "#13a8ad"); }
}
