package de.kortty.ui;

import javafx.geometry.Side;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.Tooltip;
import javafx.scene.input.ContextMenuEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.function.IntConsumer;

/**
 * Horizontal column ruler for the snippet editor. The visual marker is mirrored
 * into Monaco as a vertical editor ruler by the owning dialog.
 */
final class SnippetColumnRuler extends StackPane {

    static final int MIN_LIMIT_COLUMN = 20;
    static final int MAX_LIMIT_COLUMN = 240;

    private static final double HEIGHT = 26.0;
    private static final double MARKER_HIT_TOLERANCE = 8.0;
    private static final Color CARET_MARKER = Color.web("#3dd6ff");

    private final Canvas canvas = new Canvas();
    private final ContextMenu markerMenu = new ContextMenu();
    private final MenuItem formatToLimitItem = new MenuItem();
    private final Tooltip rulerTooltip = new Tooltip();

    private IntConsumer limitColumnChangedHandler;
    private Runnable formatAtLimitHandler;
    private int caretColumn = 1;
    private double caretMarkerX = Double.NaN;
    private int limitColumn;
    private double editorContentLeft;
    private double editorCharacterWidth = 8.0;
    private double editorScrollLeft;
    private double lastMouseX = Double.NaN;
    private String fontFamily = "Monospaced";
    private int fontSize = 14;
    private Color foreground = Color.web("#d4d4d4");
    private Color background = Color.web("#1e1e1e");

    SnippetColumnRuler() {
        getStyleClass().add("snippet-column-ruler");
        setMinHeight(HEIGHT);
        setPrefHeight(HEIGHT);
        setMaxHeight(HEIGHT);
        rulerTooltip.setText(I18n.get("snippets.ruler.tooltip"));
        Tooltip.install(this, rulerTooltip);

        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());
        getChildren().add(canvas);

        markerMenu.getItems().add(formatToLimitItem);
        formatToLimitItem.setOnAction(event -> {
            if (formatAtLimitHandler != null) {
                formatAtLimitHandler.run();
            }
        });

        widthProperty().addListener((obs, oldValue, newValue) -> draw());
        heightProperty().addListener((obs, oldValue, newValue) -> draw());
        installMouseHandling();
        draw();
    }

    void setOnLimitColumnChanged(IntConsumer handler) {
        this.limitColumnChangedHandler = handler;
    }

    void setOnFormatAtLimit(Runnable handler) {
        this.formatAtLimitHandler = handler;
    }

    void setEditorMetrics(double contentLeft, double characterWidth, double scrollLeft) {
        editorContentLeft = Math.max(0.0, contentLeft);
        editorCharacterWidth = Math.max(1.0, characterWidth);
        editorScrollLeft = Math.max(0.0, scrollLeft);
        draw();
    }

    void setEditorAppearance(EditorSettingsHelper.Settings settings) {
        if (settings == null) {
            return;
        }
        fontFamily = settings.fontFamily() != null && !settings.fontFamily().isBlank()
            ? settings.fontFamily()
            : "Monospaced";
        fontSize = Math.max(8, settings.fontSize());
        foreground = EditorSettingsHelper.parseColor(settings.foregroundColor(), Color.web("#d4d4d4"));
        background = EditorSettingsHelper.parseColor(settings.backgroundColor(), Color.web("#1e1e1e"));
        draw();
    }

    void setCaretColumn(int column) {
        setCaretColumn(column, Double.NaN);
    }

    void setCaretColumn(int column, double markerX) {
        caretColumn = Math.max(1, column);
        caretMarkerX = Double.isFinite(markerX) ? markerX : Double.NaN;
        updateTooltipForX(lastMouseX);
        draw();
    }

    int getLimitColumn() {
        return limitColumn;
    }

    void setLimitColumn(int column) {
        int safeColumn = clamp(column, MIN_LIMIT_COLUMN, MAX_LIMIT_COLUMN);
        if (safeColumn == limitColumn) {
            return;
        }
        limitColumn = safeColumn;
        if (limitColumnChangedHandler != null) {
            limitColumnChangedHandler.accept(limitColumn);
        }
        draw();
    }

    private void installMouseHandling() {
        addEventHandler(MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getButton() == MouseButton.PRIMARY) {
                markerMenu.hide();
                setLimitColumn(columnAt(event.getX()));
                event.consume();
            }
        });
        addEventHandler(MouseEvent.MOUSE_MOVED, event -> {
            lastMouseX = event.getX();
            updateTooltipForX(lastMouseX);
        });
        addEventHandler(MouseEvent.MOUSE_EXITED, event -> {
            lastMouseX = Double.NaN;
            updateTooltipForX(lastMouseX);
        });
        addEventHandler(ContextMenuEvent.CONTEXT_MENU_REQUESTED, event -> {
            if (limitColumn <= 0 || !isNearLimitMarker(event.getX())) {
                return;
            }
            formatToLimitItem.setText(I18n.get("snippets.ruler.formatToLimit", limitColumn));
            markerMenu.show(this, Side.BOTTOM, event.getX(), 0);
            event.consume();
        });
    }

    private boolean isNearLimitMarker(double x) {
        return Math.abs(xForColumn(limitColumn) - x) <= MARKER_HIT_TOLERANCE;
    }

    private boolean isNearCaretMarker(double x) {
        return Math.abs(caretMarkerX() - x) <= MARKER_HIT_TOLERANCE;
    }

    private void updateTooltipForX(double x) {
        if (!Double.isNaN(x) && isNearCaretMarker(x)) {
            rulerTooltip.setText(I18n.get("snippets.ruler.caretTooltip", caretColumn));
            return;
        }
        rulerTooltip.setText(I18n.get("snippets.ruler.tooltip"));
    }

    private int columnAt(double x) {
        double rawColumn = ((x - editorContentLeft + editorScrollLeft) / editorCharacterWidth) + 1.0;
        return clamp((int) Math.round(rawColumn), MIN_LIMIT_COLUMN, MAX_LIMIT_COLUMN);
    }

    private double xForColumn(int column) {
        return editorContentLeft - editorScrollLeft + ((Math.max(1, column) - 1) * editorCharacterWidth);
    }

    private double caretMarkerX() {
        return Double.isFinite(caretMarkerX) ? caretMarkerX : xForColumn(caretColumn);
    }

    private void draw() {
        GraphicsContext gc = canvas.getGraphicsContext2D();
        double width = Math.max(0.0, getWidth());
        double height = Math.max(HEIGHT, getHeight());

        gc.setFill(background.deriveColor(0, 1.0, 0.88, 1.0));
        gc.fillRect(0, 0, width, height);
        gc.setStroke(foreground.deriveColor(0, 1.0, 1.0, 0.22));
        gc.strokeLine(0, height - 0.5, width, height - 0.5);

        gc.setFont(Font.font(fontFamily, Math.max(10, fontSize * 0.78)));
        drawTicks(gc, width, height);
        drawLimitMarker(gc, height);
        drawCaretLabel(gc, width);
        drawCaretMarker(gc, width, height);
    }

    private void drawTicks(GraphicsContext gc, double width, double height) {
        int firstColumn = Math.max(1, (int) Math.floor(editorScrollLeft / editorCharacterWidth) + 1);
        int lastColumn = Math.min(
            MAX_LIMIT_COLUMN,
            (int) Math.ceil((width - editorContentLeft + editorScrollLeft) / editorCharacterWidth) + 2);

        for (int column = firstColumn; column <= lastColumn; column++) {
            double x = xForColumn(column);
            if (x < 0 || x > width) {
                continue;
            }
            boolean major = column == 1 || column % 10 == 0;
            boolean medium = column % 5 == 0;
            double tickHeight = major ? 11.0 : medium ? 8.0 : 4.0;
            gc.setStroke(foreground.deriveColor(0, 1.0, 1.0, major ? 0.58 : 0.32));
            gc.strokeLine(x + 0.5, height - 1.0, x + 0.5, height - 1.0 - tickHeight);
            if (major) {
                gc.setFill(foreground.deriveColor(0, 1.0, 1.0, 0.72));
                gc.fillText(Integer.toString(column), x + 3.0, 10.5);
            }
        }
    }

    private void drawLimitMarker(GraphicsContext gc, double height) {
        if (limitColumn <= 0) {
            return;
        }
        double x = xForColumn(limitColumn);
        if (x < -MARKER_HIT_TOLERANCE || x > getWidth() + MARKER_HIT_TOLERANCE) {
            return;
        }
        Color marker = Color.web("#ffb86c");
        gc.setStroke(marker);
        gc.setLineWidth(1.4);
        gc.strokeLine(x + 0.5, 0.0, x + 0.5, height);
        gc.setLineWidth(1.0);
        gc.setFill(marker);
        gc.fillPolygon(
            new double[] {x - 4.0, x + 4.0, x},
            new double[] {0.5, 0.5, 7.0},
            3);
        gc.fillText(I18n.get("snippets.ruler.limit", limitColumn), x + 6.0, height - 6.0);
    }

    private void drawCaretMarker(GraphicsContext gc, double width, double height) {
        double x = caretMarkerX();
        if (x < -MARKER_HIT_TOLERANCE || x > width + MARKER_HIT_TOLERANCE) {
            return;
        }

        double markerX = Math.round(x) + 0.5;
        gc.setFill(CARET_MARKER.deriveColor(0, 1.0, 1.0, 0.20));
        gc.fillRect(Math.max(0.0, markerX - 2.0), 0.0, 4.0, height);
        gc.setStroke(CARET_MARKER);
        gc.setLineWidth(2.2);
        gc.strokeLine(markerX, 0.0, markerX, height);
        gc.setLineWidth(1.0);
        gc.setFill(CARET_MARKER);
        gc.fillPolygon(
            new double[] {markerX - 5.0, markerX + 5.0, markerX},
            new double[] {1.0, 1.0, 8.0},
            3);
        gc.fillPolygon(
            new double[] {markerX - 5.0, markerX + 5.0, markerX},
            new double[] {height - 1.0, height - 1.0, height - 8.0},
            3);
    }

    private void drawCaretLabel(GraphicsContext gc, double width) {
        String label = I18n.get("snippets.ruler.position", caretColumn);
        gc.setFont(Font.font(fontFamily, Math.max(10, fontSize * 0.78)));
        double boxWidth = Math.min(width, Math.max(96.0, label.length() * fontSize * 0.54 + 16.0));
        gc.setFill(background.deriveColor(0, 1.0, 0.72, 0.92));
        gc.fillRect(0, 0, boxWidth, HEIGHT - 1.0);
        gc.setStroke(CARET_MARKER.deriveColor(0, 1.0, 0.95, 0.92));
        gc.strokeLine(boxWidth - 0.5, 3.0, boxWidth - 0.5, HEIGHT - 4.0);
        gc.setFill(foreground.deriveColor(0, 1.0, 1.0, 0.92));
        gc.fillText(label, 8.0, 17.0);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
