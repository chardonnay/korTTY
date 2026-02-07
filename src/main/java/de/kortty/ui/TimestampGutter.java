package de.kortty.ui;

import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.TreeMap;

/**
 * A narrow gutter panel displayed to the left of a terminal widget.
 * Shows date/time timestamps for each command entered by the user (on Enter key press).
 * The gutter uses a Canvas for efficient rendering and synchronizes its display
 * with the terminal's scrollbar position and character size.
 */
public class TimestampGutter extends Pane {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final double GUTTER_WIDTH = 76;
    private static final double TEXT_LEFT_PADDING = 6;
    private static final double TEXT_BOTTOM_OFFSET = 4;
    private static final Color SEPARATOR_COLOR = Color.web("#444444");

    private final Canvas canvas = new Canvas();

    /**
     * Maps absolute line numbers to the timestamp when Enter was pressed on that line.
     * Absolute line = historyLinesCount + screenRow at the time of the key press.
     */
    private final TreeMap<Integer, LocalDateTime> timestamps = new TreeMap<>();

    private double charHeight = 16;
    private int scrollOrigin = 0;
    private int historyLinesCount = 0;
    private int visibleRows = 24;
    private Color backgroundColor = Color.web("#1a1a1a");
    private Color textColor = Color.web("#666666");
    private Font font = Font.font("Monospaced", FontWeight.NORMAL, 10);

    public TimestampGutter() {
        setPrefWidth(GUTTER_WIDTH);
        setMinWidth(GUTTER_WIDTH);
        setMaxWidth(GUTTER_WIDTH);
        getChildren().add(canvas);

        // Bind canvas size to pane size
        canvas.widthProperty().bind(widthProperty());
        canvas.heightProperty().bind(heightProperty());

        // Repaint when size changes
        canvas.widthProperty().addListener((obs, o, n) -> render());
        canvas.heightProperty().addListener((obs, o, n) -> render());
    }

    /**
     * Records a timestamp for the given absolute line number.
     *
     * @param absoluteLine the absolute line number (historyLinesCount + cursorY)
     * @param time the timestamp when Enter was pressed
     */
    public void addTimestamp(int absoluteLine, LocalDateTime time) {
        timestamps.put(absoluteLine, time);
        render();
    }

    /**
     * Updates the scroll state to synchronize with the terminal display.
     *
     * @param scrollOrigin the current scroll origin (range [-historyLines, 0])
     * @param historyLinesCount the current number of history lines in the buffer
     * @param charHeight the height of a single character cell in pixels
     * @param visibleRows the number of visible rows in the terminal
     */
    public void updateScrollState(int scrollOrigin, int historyLinesCount, double charHeight, int visibleRows) {
        this.scrollOrigin = scrollOrigin;
        this.historyLinesCount = historyLinesCount;
        this.charHeight = charHeight;
        this.visibleRows = visibleRows;
        render();
    }

    /**
     * Sets the background color to match the terminal.
     */
    public void setGutterBackgroundColor(Color color) {
        this.backgroundColor = deriveGutterBackground(color);
        render();
    }

    /**
     * Sets the text color for timestamps.
     */
    public void setGutterTextColor(Color color) {
        // 50% opacity for subtlety
        this.textColor = color.deriveColor(0, 1.0, 1.0, 0.5);
        render();
    }

    /**
     * Sets the font for timestamp text.
     * Uses a smaller size than the terminal font for a clean look.
     */
    public void setTimestampFont(String fontFamily, double terminalFontSize) {
        double gutterFontSize = Math.max(8, terminalFontSize * 0.75);
        this.font = Font.font(fontFamily, FontWeight.NORMAL, gutterFontSize);
        render();
    }

    /**
     * Returns whether any timestamps have been recorded.
     */
    public boolean hasTimestamps() {
        return !timestamps.isEmpty();
    }

    /**
     * Clears all recorded timestamps.
     */
    public void clearTimestamps() {
        timestamps.clear();
        render();
    }

    /**
     * Renders the gutter content.
     * Draws timestamps aligned to the corresponding terminal rows.
     */
    private void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0 || charHeight <= 0) {
            return;
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear background
        gc.setFill(backgroundColor);
        gc.fillRect(0, 0, width, height);

        // Draw right-side separator line
        gc.setStroke(SEPARATOR_COLOR);
        gc.setLineWidth(1);
        gc.strokeLine(width - 0.5, 0, width - 0.5, height);

        // Draw timestamps for visible rows
        gc.setFill(textColor);
        gc.setFont(font);

        for (int row = 0; row < visibleRows; row++) {
            int absoluteLine = historyLinesCount + scrollOrigin + row;
            LocalDateTime ts = timestamps.get(absoluteLine);
            if (ts != null) {
                String text = ts.format(TIME_FORMAT);
                double y = row * charHeight + charHeight - TEXT_BOTTOM_OFFSET;
                gc.fillText(text, TEXT_LEFT_PADDING, y);
            }
        }
    }

    /**
     * Derives a slightly different background color for the gutter
     * to visually separate it from the terminal area.
     */
    private static Color deriveGutterBackground(Color terminalBg) {
        double brightness = terminalBg.getBrightness();
        if (brightness < 0.5) {
            // Dark theme: make gutter slightly lighter
            return terminalBg.deriveColor(0, 1.0, 1.15, 1.0);
        } else {
            // Light theme: make gutter slightly darker
            return terminalBg.deriveColor(0, 1.0, 0.92, 1.0);
        }
    }
}
