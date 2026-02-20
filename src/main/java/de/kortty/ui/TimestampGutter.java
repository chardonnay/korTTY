package de.kortty.ui;

import javafx.geometry.Insets;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.control.Label;
import javafx.scene.layout.Pane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Popup;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * A narrow gutter panel displayed to the left of a terminal widget.
 * Shows date/time timestamps for each command entered by the user (on Enter key press).
 * The gutter uses a Canvas for efficient rendering and synchronizes its display
 * with the terminal's scrollbar position and character size.
 * Hovering over a timestamp row shows a popup with full details.
 */
public class TimestampGutter extends Pane {

    private static final DateTimeFormatter DATE_SHORT_FORMAT = DateTimeFormatter.ofPattern("dd.MM.");
    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter POPUP_DATE_FORMAT = DateTimeFormatter.ofPattern("EEEE, dd. MMMM yyyy", Locale.getDefault());
    private static final DateTimeFormatter POPUP_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    public static final double GUTTER_WIDTH = 88;
    private static final double TEXT_LEFT_PADDING = 6;
    private static final Color SEPARATOR_COLOR = Color.web("#444444");
    private static final double OVERLAY_BACKGROUND_ALPHA = 1.0;
    private static final double TIME_TEXT_ALPHA = 0.86;
    private static final double DATE_TEXT_ALPHA = 0.72;

    private final Canvas canvas = new Canvas();

    /** Hover popup shown when mouse is over a timestamp row. */
    private Popup hoverPopup;
    private Label popupDateLabel;
    private Label popupTimeLabel;
    private Label popupDurationLabel;
    private int currentPopupRow = -1;

    /**
     * Maps absolute line numbers to the timestamp when Enter was pressed on that line.
     * Absolute line = historyLinesCount + screenRow at the time of the key press.
     */
    private final TreeMap<Integer, LocalDateTime> timestamps = new TreeMap<>();

    private double charHeight = 16;
    private double baselineOffset = 12;
    private int scrollOrigin = 0;
    private int historyLinesCount = 0;
    private int visibleRows = 24;
    private Color backgroundColor = Color.web("#1a1a1a");
    private Color textColor = Color.web("#666666");
    private Font font = Font.font("Monospaced", FontWeight.NORMAL, 10);
    private Font dateFont = Font.font("Monospaced", FontWeight.NORMAL, 8);

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

        // Setup hover popup
        setupHoverPopup();
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
     * Replaces all timestamps with the provided map.
     * Used to restore/synchronize previously recorded timestamps.
     */
    public void setAllTimestamps(Map<Integer, LocalDateTime> allTimestamps) {
        timestamps.clear();
        if (allTimestamps != null && !allTimestamps.isEmpty()) {
            timestamps.putAll(allTimestamps);
        }
        render();
    }

    /**
     * Updates the scroll state to synchronize with the terminal display.
     *
     * @param scrollOrigin the current scroll origin (range [-historyLines, 0])
     * @param historyLinesCount the current number of history lines in the buffer
     * @param charHeight the height of a single character cell in pixels
     * @param visibleRows the number of visible rows in the terminal
     * @param baselineOffset the terminal text baseline offset inside a row in pixels
     */
    public void updateScrollState(int scrollOrigin, int historyLinesCount, double charHeight, int visibleRows, double baselineOffset) {
        this.scrollOrigin = scrollOrigin;
        this.historyLinesCount = historyLinesCount;
        this.charHeight = charHeight;
        this.visibleRows = visibleRows;
        this.baselineOffset = baselineOffset;
        render();
    }

    /**
     * Sets the background color to match the terminal.
     */
    public void setGutterBackgroundColor(Color color) {
        Color base = deriveGutterBackground(color);
        this.backgroundColor = new Color(base.getRed(), base.getGreen(), base.getBlue(), OVERLAY_BACKGROUND_ALPHA);
        render();
    }

    /**
     * Sets the text color for timestamps.
     */
    public void setGutterTextColor(Color color) {
        this.textColor = color.deriveColor(0, 1.0, 1.0, TIME_TEXT_ALPHA);
        render();
    }

    /**
     * Sets the font for timestamp text.
     * Uses a smaller size than the terminal font for a clean look.
     */
    public void setTimestampFont(String fontFamily, double terminalFontSize) {
        double gutterFontSize = Math.max(8, terminalFontSize * 0.75);
        double dateFontSize = Math.max(7, terminalFontSize * 0.55);
        this.font = Font.font(fontFamily, FontWeight.NORMAL, gutterFontSize);
        this.dateFont = Font.font(fontFamily, FontWeight.NORMAL, dateFontSize);
        render();
    }

    /**
     * Returns whether any timestamps have been recorded.
     */
    public boolean hasTimestamps() {
        return !timestamps.isEmpty();
    }

    /**
     * Returns whether a timestamp exists for the given absolute line.
     */
    public boolean hasTimestampForLine(int absoluteLine) {
        return timestamps.containsKey(absoluteLine);
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
     * Two-line layout per row: date + duration on top, time below.
     * Full details are shown in the hover popup.
     */
    private void render() {
        double width = canvas.getWidth();
        double height = canvas.getHeight();
        if (width <= 0 || height <= 0 || charHeight <= 0) {
            return;
        }

        GraphicsContext gc = canvas.getGraphicsContext2D();

        // Clear first, otherwise repeated alpha fills make the overlay appear to change opacity.
        gc.clearRect(0, 0, width, height);

        // Clear background
        gc.setFill(backgroundColor);
        gc.fillRect(0, 0, width, height);

        // Draw right-side separator line
        gc.setStroke(SEPARATOR_COLOR);
        gc.setLineWidth(1);
        gc.strokeLine(width - 0.5, 0, width - 0.5, height);

        // Draw timestamps for visible rows (two-line layout: date+duration above, time below)
        for (int row = 0; row < visibleRows; row++) {
            int absoluteLine = historyLinesCount + scrollOrigin + row;
            LocalDateTime ts = timestamps.get(absoluteLine);
            if (ts != null) {
                double rowTop = row * charHeight;
                Integer prevLine = timestamps.lowerKey(absoluteLine);
                Duration duration = prevLine != null
                    ? Duration.between(timestamps.get(prevLine), ts)
                    : null;

                // Top line: short date + duration (e.g. "09.02. +12s")
                gc.setFont(dateFont);
                gc.setFill(textColor.deriveColor(0, 1.0, 1.0, DATE_TEXT_ALPHA));
                String dateText = ts.format(DATE_SHORT_FORMAT);
                if (duration != null && !duration.isNegative()) {
                    dateText += " " + formatDuration(duration);
                }
                double topY = rowTop + Math.max(7.0, baselineOffset - charHeight * 0.42);
                gc.fillText(dateText, TEXT_LEFT_PADDING, topY);

                // Bottom line: time (e.g. "17:20:03")
                gc.setFont(font);
                gc.setFill(textColor);
                String timeText = ts.format(TIME_FORMAT);
                double bottomY = rowTop + baselineOffset;
                gc.fillText(timeText, TEXT_LEFT_PADDING, bottomY);
            }
        }
    }

    private static String formatDuration(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return "+" + s + "s";
        if (s < 3600) return "+" + (s / 60) + ":" + String.format("%02d", s % 60);
        return "+" + (s / 3600) + ":" + String.format("%02d", (s % 3600) / 60) + ":" + String.format("%02d", s % 60);
    }

    /**
     * Formats a duration in a verbose, human-readable form for the popup.
     */
    private static String formatDurationVerbose(Duration d) {
        long s = d.getSeconds();
        if (s < 60) return s + " sec";
        if (s < 3600) return (s / 60) + " min " + (s % 60) + " sec";
        long h = s / 3600;
        long m = (s % 3600) / 60;
        long sec = s % 60;
        return h + " h " + m + " min " + sec + " sec";
    }

    // ---- Hover popup ----

    /**
     * Creates the hover popup with styled labels for date, time, and duration.
     * Also registers mouse event handlers on the canvas.
     */
    private void setupHoverPopup() {
        hoverPopup = new Popup();
        hoverPopup.setAutoHide(true);

        popupDateLabel = new Label();
        popupDateLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 12px; -fx-font-weight: bold;");

        popupTimeLabel = new Label();
        popupTimeLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 16px; -fx-font-weight: bold;");

        popupDurationLabel = new Label();
        popupDurationLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 11px;");

        VBox popupContent = new VBox(4, popupDateLabel, popupTimeLabel, popupDurationLabel);
        popupContent.setPadding(new Insets(8, 12, 8, 12));
        popupContent.setStyle(
            "-fx-background-color: #2a2a2a;" +
            "-fx-background-radius: 6;" +
            "-fx-border-color: #555555;" +
            "-fx-border-radius: 6;" +
            "-fx-border-width: 1;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 8, 0, 2, 2);"
        );

        hoverPopup.getContent().add(popupContent);

        canvas.setOnMouseMoved(event -> {
            if (charHeight <= 0) return;
            int row = (int) (event.getY() / charHeight);
            if (row < 0 || row >= visibleRows) {
                hidePopup();
                return;
            }
            int absoluteLine = historyLinesCount + scrollOrigin + row;
            LocalDateTime ts = timestamps.get(absoluteLine);
            if (ts == null) {
                hidePopup();
                return;
            }
            // Only update popup if we moved to a different row
            if (row == currentPopupRow && hoverPopup.isShowing()) {
                // Reposition to follow mouse
                repositionPopup(event.getScreenX(), event.getScreenY());
                return;
            }
            currentPopupRow = row;

            // Populate popup content
            popupDateLabel.setText(ts.format(POPUP_DATE_FORMAT));
            popupTimeLabel.setText(ts.format(POPUP_TIME_FORMAT));

            Integer prevLine = timestamps.lowerKey(absoluteLine);
            if (prevLine != null) {
                Duration duration = Duration.between(timestamps.get(prevLine), ts);
                if (!duration.isNegative()) {
                    popupDurationLabel.setText("Elapsed: " + formatDurationVerbose(duration));
                    popupDurationLabel.setVisible(true);
                    popupDurationLabel.setManaged(true);
                } else {
                    popupDurationLabel.setVisible(false);
                    popupDurationLabel.setManaged(false);
                }
            } else {
                popupDurationLabel.setVisible(false);
                popupDurationLabel.setManaged(false);
            }

            // Show popup near the mouse, offset to the right
            showPopup(event.getScreenX(), event.getScreenY());
        });

        canvas.setOnMouseExited(event -> hidePopup());
    }

    private void showPopup(double screenX, double screenY) {
        if (getScene() == null || getScene().getWindow() == null) return;
        hoverPopup.show(getScene().getWindow(), screenX + 14, screenY - 10);
    }

    private void repositionPopup(double screenX, double screenY) {
        if (hoverPopup.isShowing()) {
            hoverPopup.setAnchorX(screenX + 14);
            hoverPopup.setAnchorY(screenY - 10);
        }
    }

    private void hidePopup() {
        currentPopupRow = -1;
        if (hoverPopup != null && hoverPopup.isShowing()) {
            hoverPopup.hide();
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
