package de.kortty.ui;

import de.kortty.core.SessionJournalScreenshotAnnotator;
import de.kortty.model.SessionJournalAnnotation;
import de.kortty.model.SessionJournalEntry;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.Slider;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.image.Image;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.StrokeLineCap;
import javafx.scene.shape.StrokeLineJoin;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Marks up a journal screenshot: a thick pen, a rectangle and text labels, in a colour of your
 * choice — plus the note that belongs to the picture.
 *
 * <p>Everything is drawn in the image's own pixel coordinates, so a mark sits where it was put no
 * matter how the picture is scaled to fit the dialog, the timeline or an export.</p>
 */
public final class SessionJournalScreenshotEditorDialog {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalScreenshotEditorDialog.class);

    /** The note field the user asked for; five rows is enough for a real handover remark. */
    private static final int NOTE_ROWS = 5;

    /** Initial dialog size; the picture then grows and shrinks with the window. */
    private static final double INITIAL_WIDTH = 1000;
    private static final double INITIAL_HEIGHT = 780;

    /** A screenshot may be blown up this far, past which it is only bigger, not clearer. */
    private static final double MAX_ZOOM = 4.0;

    private enum Tool { PEN, BOX, PIXELATE, TEXT }

    /** What the dialog produced: the marks and the note, both already normalized. */
    public record Result(List<SessionJournalAnnotation> annotations, String note) {
    }

    private final Image image;
    private final List<SessionJournalAnnotation> annotations = new ArrayList<>();
    private final Canvas canvas = new Canvas();
    /** Image pixels per canvas pixel; recomputed on every resize, so marks keep their place. */
    private double scale = 1.0;

    private final ColorPicker colorPicker =
        new ColorPicker(Color.web(SessionJournalAnnotation.DEFAULT_COLOR));
    private final Slider strokeSlider = new Slider(2, 24, SessionJournalAnnotation.DEFAULT_STROKE_WIDTH);
    private final TextArea noteArea = new TextArea();
    private final ToggleGroup toolGroup = new ToggleGroup();

    private Tool tool = Tool.PEN;
    private SessionJournalAnnotation inProgress;

    private SessionJournalScreenshotEditorDialog(Image image, SessionJournalEntry entry) {
        this.image = image;
        for (SessionJournalAnnotation annotation : entry.getAnnotations()) {
            annotations.add(new SessionJournalAnnotation(annotation));
        }
        noteArea.setText(entry.getUserNote() != null ? entry.getUserNote() : "");
    }

    /**
     * Opens the editor for a screenshot entry.
     *
     * @param sourceImage the untouched capture, never the already-annotated file — otherwise every
     *                    save would burn the previous marks in a second time
     */
    public static Optional<Result> open(Window owner, Path sourceImage, SessionJournalEntry entry) {
        Image image;
        try {
            image = new Image(sourceImage.toUri().toURL().toExternalForm());
            if (image.isError()) {
                throw new IllegalStateException("image could not be decoded");
            }
        } catch (Exception e) {
            logger.warn("Could not open the journal screenshot {}: {}",
                sourceImage.getFileName(), e.getMessage());
            return Optional.empty();
        }
        return new SessionJournalScreenshotEditorDialog(image, entry).show(owner);
    }

    /** An editor built but not shown, so a layout check can resize it and measure. */
    record Capture(Dialog<ButtonType> dialog, Canvas canvas, TextArea note) {
    }

    static Capture buildForCapture(Image image, SessionJournalEntry entry) {
        SessionJournalScreenshotEditorDialog editor =
            new SessionJournalScreenshotEditorDialog(image, entry);
        return new Capture(editor.buildDialog(null), editor.canvas, editor.noteArea);
    }

    private Dialog<ButtonType> buildDialog(Window owner) {
        Dialog<ButtonType> dialog = new Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.setTitle(I18n.get("journal.screenshot.editor.title"));
        dialog.setResizable(true);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().setContent(buildContent());
        dialog.getDialogPane().setPrefSize(INITIAL_WIDTH, INITIAL_HEIGHT);
        installGeometry(dialog);
        installMouseHandlers();
        return dialog;
    }

    /** Reopens the edit window where and how big the user last left it. */
    private static void installGeometry(Dialog<ButtonType> dialog) {
        de.kortty.core.GlobalSettingsManager manager = settingsManager();
        if (manager == null || manager.getSettings() == null) {
            return;
        }
        DialogGeometrySupport.install(dialog,
            manager.getSettings().getSessionJournalScreenshotEditorGeometry(),
            geometry -> {
                manager.getSettings().setSessionJournalScreenshotEditorGeometry(geometry);
                try {
                    manager.save();
                } catch (Exception e) {
                    logger.warn("Could not save the screenshot editor geometry: {}", e.getMessage());
                }
            },
            () -> false);
    }

    /** Test seam: lets the headless geometry check run without a full application. */
    private static de.kortty.core.GlobalSettingsManager settingsManagerOverride;

    static void setSettingsManagerForCapture(de.kortty.core.GlobalSettingsManager manager) {
        settingsManagerOverride = manager;
    }

    /** Null in headless tooling (the screenshot generator and the smokes run without an app). */
    private static de.kortty.core.GlobalSettingsManager settingsManager() {
        if (settingsManagerOverride != null) {
            return settingsManagerOverride;
        }
        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
        return app != null ? app.getGlobalSettingsManager() : null;
    }

    private Optional<Result> show(Window owner) {
        Dialog<ButtonType> dialog = buildDialog(owner);
        if (dialog.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return Optional.empty();
        }
        String note = noteArea.getText();
        return Optional.of(new Result(List.copyOf(annotations),
            note != null && !note.isBlank() ? note.strip() : null));
    }

    private VBox buildContent() {
        ToggleButton pen = toolButton(Tool.PEN, "journal.screenshot.tool.pen");
        ToggleButton box = toolButton(Tool.BOX, "journal.screenshot.tool.box");
        ToggleButton blur = toolButton(Tool.PIXELATE, "journal.screenshot.tool.pixelate");
        blur.setTooltip(new javafx.scene.control.Tooltip(
            I18n.get("journal.screenshot.tool.pixelate.hint")));
        ToggleButton text = toolButton(Tool.TEXT, "journal.screenshot.tool.text");
        pen.setSelected(true);

        colorPicker.setPrefWidth(120);
        strokeSlider.setPrefWidth(140);
        strokeSlider.setShowTickMarks(true);
        strokeSlider.setMajorTickUnit(6);

        Button undo = new Button(I18n.get("journal.screenshot.undo"));
        undo.setOnAction(event -> {
            if (!annotations.isEmpty()) {
                annotations.remove(annotations.size() - 1);
                redraw();
            }
        });
        Button clear = new Button(I18n.get("journal.screenshot.clear"));
        clear.setOnAction(event -> {
            annotations.clear();
            redraw();
        });

        HBox tools = new HBox(8, pen, box, blur, text,
            new Label(I18n.get("journal.screenshot.colour")), colorPicker,
            new Label(I18n.get("journal.screenshot.width")), strokeSlider,
            undo, clear);
        tools.setAlignment(Pos.CENTER_LEFT);

        // The canvas is unmanaged: a Canvas reports its fixed size as its preferred size, so a
        // managed one would dictate the holder's size instead of following it, and the picture
        // could never grow with the window.
        Pane canvasHolder = new Pane(canvas);
        canvas.setManaged(false);
        canvasHolder.setStyle("-fx-background-color: #101216;");
        canvasHolder.setMinSize(0, 0);
        canvasHolder.widthProperty().addListener((obs, old, value) -> layoutCanvas(canvasHolder));
        canvasHolder.heightProperty().addListener((obs, old, value) -> layoutCanvas(canvasHolder));

        noteArea.setPrefRowCount(NOTE_ROWS);
        noteArea.setMinHeight(Region.USE_PREF_SIZE);
        noteArea.setMaxHeight(Region.USE_PREF_SIZE);
        noteArea.setWrapText(true);
        noteArea.setPromptText(I18n.get("journal.screenshot.note.prompt"));

        Label hint = new Label(I18n.get("journal.screenshot.hint"));
        hint.setWrapText(true);
        hint.setStyle("-fx-text-fill: gray; -fx-font-size: 0.8462em;");

        VBox content = new VBox(10, tools, canvasHolder,
            new Label(I18n.get("journal.screenshot.note")), noteArea, hint);
        content.setPadding(new Insets(10));
        // Every spare pixel goes to the picture; the note keeps its five rows.
        VBox.setVgrow(canvasHolder, Priority.ALWAYS);
        return content;
    }

    /** Fits the picture into whatever space the window currently gives it, and centres it. */
    private void layoutCanvas(Pane holder) {
        double availableWidth = holder.getWidth();
        double availableHeight = holder.getHeight();
        if (availableWidth <= 0 || availableHeight <= 0) {
            return;
        }
        scale = Math.min(MAX_ZOOM,
            Math.min(availableWidth / image.getWidth(), availableHeight / image.getHeight()));
        canvas.setWidth(image.getWidth() * scale);
        canvas.setHeight(image.getHeight() * scale);
        canvas.setLayoutX(Math.round((availableWidth - canvas.getWidth()) / 2));
        canvas.setLayoutY(Math.round((availableHeight - canvas.getHeight()) / 2));
        redraw();
    }

    private ToggleButton toolButton(Tool value, String key) {
        ToggleButton button = new ToggleButton(I18n.get(key));
        button.setToggleGroup(toolGroup);
        button.setOnAction(event -> {
            // A toggle group lets the user deselect; keep a tool active at all times.
            button.setSelected(true);
            tool = value;
        });
        return button;
    }

    // ==== drawing ====

    private void installMouseHandlers() {
        canvas.setOnMousePressed(event -> {
            double x = event.getX() / scale;
            double y = event.getY() / scale;
            if (tool == Tool.TEXT) {
                addTextLabel(x, y);
                return;
            }
            inProgress = new SessionJournalAnnotation(switch (tool) {
                case PEN -> SessionJournalAnnotation.Kind.PEN;
                case PIXELATE -> SessionJournalAnnotation.Kind.PIXELATE;
                default -> SessionJournalAnnotation.Kind.BOX;
            }, hex(colorPicker.getValue()), strokeSlider.getValue());
            if (tool == Tool.PEN) {
                inProgress.addPoint(x, y);
            } else {
                // x, y, width, height — the size follows the drag.
                inProgress.setPoints(List.of(x, y, 0.0, 0.0));
            }
            annotations.add(inProgress);
        });
        canvas.setOnMouseDragged(event -> {
            if (inProgress == null) {
                return;
            }
            double x = event.getX() / scale;
            double y = event.getY() / scale;
            if (inProgress.getKind() == SessionJournalAnnotation.Kind.PEN) {
                inProgress.addPoint(x, y);
            } else {
                List<Double> p = inProgress.getPoints();
                inProgress.setPoints(List.of(p.get(0), p.get(1), x - p.get(0), y - p.get(1)));
            }
            redraw();
        });
        canvas.setOnMouseReleased(event -> {
            if (inProgress != null && !inProgress.isDrawable()) {
                // A plain click with the pen or a zero-size box leaves nothing behind.
                annotations.remove(inProgress);
            }
            inProgress = null;
            redraw();
        });
    }

    private void addTextLabel(double x, double y) {
        TextInputDialog prompt = new TextInputDialog();
        DialogThemeHelper.applyTheme(prompt);
        prompt.setTitle(I18n.get("journal.screenshot.tool.text"));
        prompt.setHeaderText(I18n.get("journal.screenshot.text.prompt"));
        String value = prompt.showAndWait().orElse(null);
        if (value == null || value.isBlank()) {
            return;
        }
        SessionJournalAnnotation label = new SessionJournalAnnotation(
            SessionJournalAnnotation.Kind.TEXT, hex(colorPicker.getValue()), strokeSlider.getValue());
        // The label scales with the pen width so it stays legible on a high-resolution capture.
        label.setFontSize(Math.max(16, strokeSlider.getValue() * 4));
        label.setText(value);
        label.setPoints(List.of(x, y));
        annotations.add(label);
        redraw();
    }

    private void redraw() {
        GraphicsContext g = canvas.getGraphicsContext2D();
        g.clearRect(0, 0, canvas.getWidth(), canvas.getHeight());
        g.drawImage(image, 0, 0, canvas.getWidth(), canvas.getHeight());
        g.setLineCap(StrokeLineCap.ROUND);
        g.setLineJoin(StrokeLineJoin.ROUND);
        for (SessionJournalAnnotation annotation : annotations) {
            if (!annotation.isDrawable()) {
                continue;
            }
            g.setStroke(Color.web(annotation.getColor()));
            g.setFill(Color.web(annotation.getColor()));
            g.setLineWidth(annotation.getStrokeWidth() * scale);
            List<Double> p = annotation.getPoints();
            switch (annotation.getKind()) {
                case PEN -> {
                    g.beginPath();
                    g.moveTo(p.get(0) * scale, p.get(1) * scale);
                    for (int i = 2; i + 1 < p.size(); i += 2) {
                        g.lineTo(p.get(i) * scale, p.get(i + 1) * scale);
                    }
                    g.stroke();
                }
                case BOX -> g.strokeRect(
                    Math.min(p.get(0), p.get(0) + p.get(2)) * scale,
                    Math.min(p.get(1), p.get(1) + p.get(3)) * scale,
                    Math.abs(p.get(2)) * scale, Math.abs(p.get(3)) * scale);
                case PIXELATE -> drawPixelated(g, annotation);
                case TEXT -> {
                    g.setFont(javafx.scene.text.Font.font(
                        javafx.scene.text.Font.getDefault().getFamily(),
                        javafx.scene.text.FontWeight.BOLD, annotation.getFontSize() * scale));
                    g.fillText(annotation.getText(), p.get(0) * scale, p.get(1) * scale);
                }
            }
        }
    }

    /**
     * Preview of a pixelate box: each block is filled with the average colour of the image region
     * behind it, matching what {@code SessionJournalScreenshotAnnotator} burns into the PNG.
     */
    private void drawPixelated(GraphicsContext g, SessionJournalAnnotation annotation) {
        List<Double> p = annotation.getPoints();
        int x = (int) Math.round(Math.min(p.get(0), p.get(0) + p.get(2)));
        int y = (int) Math.round(Math.min(p.get(1), p.get(1) + p.get(3)));
        int width = (int) Math.round(Math.abs(p.get(2)));
        int height = (int) Math.round(Math.abs(p.get(3)));
        int imageWidth = (int) image.getWidth();
        int imageHeight = (int) image.getHeight();
        x = Math.max(0, Math.min(x, imageWidth - 1));
        y = Math.max(0, Math.min(y, imageHeight - 1));
        width = Math.min(width, imageWidth - x);
        height = Math.min(height, imageHeight - y);
        if (width <= 0 || height <= 0) {
            return;
        }
        javafx.scene.image.PixelReader reader = image.getPixelReader();
        if (reader == null) {
            return;
        }
        int block = annotation.blockSize();
        for (int by = y; by < y + height; by += block) {
            for (int bx = x; bx < x + width; bx += block) {
                int bw = Math.min(block, x + width - bx);
                int bh = Math.min(block, y + height - by);
                g.setFill(averageColor(reader, bx, by, bw, bh));
                g.fillRect(bx * scale, by * scale, bw * scale + 0.5, bh * scale + 0.5);
            }
        }
    }

    private static Color averageColor(javafx.scene.image.PixelReader reader,
                                      int x, int y, int width, int height) {
        double red = 0;
        double green = 0;
        double blue = 0;
        for (int py = y; py < y + height; py++) {
            for (int px = x; px < x + width; px++) {
                Color pixel = reader.getColor(px, py);
                red += pixel.getRed();
                green += pixel.getGreen();
                blue += pixel.getBlue();
            }
        }
        int count = width * height;
        return Color.color(red / count, green / count, blue / count);
    }

    private static String hex(Color color) {
        Color value = color != null ? color : Color.web(SessionJournalAnnotation.DEFAULT_COLOR);
        return String.format("#%02x%02x%02x",
            Math.round(value.getRed() * 255),
            Math.round(value.getGreen() * 255),
            Math.round(value.getBlue() * 255));
    }
}
