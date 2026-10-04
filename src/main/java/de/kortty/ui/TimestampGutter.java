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
import javafx.scene.text.Text;
import javafx.stage.Popup;

import de.kortty.core.LanguageManager;
import de.kortty.shellintegration.CommandStatus;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;

/**
 * A narrow gutter panel displayed to the left of a terminal widget.
 * Shows date/time timestamps for each command entered by the user (on Enter key press).
 * The gutter uses a Canvas for efficient rendering and synchronizes its display
 * with the terminal's scrollbar position and character size.
 * Hovering over a timestamp row shows a popup with full details.
 *
 * <p>In a shell set up for shell integration the gutter also shows how each command ended
 * ({@link #setCommandStatuses}): a {@code ✓} for exit status 0 or a {@code ✗} for any other, in green
 * or red, on the line where the command finished, with its real runtime next to the date, and a
 * {@code …} on the first output line of a command that still runs. The popup adds the exit status
 * and the runtime. The statuses are runtime-only and follow the scrollback like the timestamps.
 */
public class TimestampGutter extends Pane {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter POPUP_TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm:ss");
    /**
     * Date formats and texts for the UI language they were built for; shared by every gutter and
     * rebuilt on the first render after the language changes.
     */
    private static volatile TimestampGutterFormats cachedFormats;
    public static final double GUTTER_WIDTH = 88;
    private static final double TEXT_LEFT_PADDING = 6;
    private static final Color SEPARATOR_COLOR = Color.web("#444444");
    private static final double OVERLAY_BACKGROUND_ALPHA = 1.0;
    private static final double TIME_TEXT_ALPHA = 0.86;
    private static final double DATE_TEXT_ALPHA = 0.72;
    private static final double GLYPH_GAP = 4;
    private static final Color SUCCEEDED_ON_DARK = Color.web("#3fb950");
    private static final Color FAILED_ON_DARK = Color.web("#ff6b61");
    private static final Color SUCCEEDED_ON_LIGHT = Color.web("#1a7f37");
    private static final Color FAILED_ON_LIGHT = Color.web("#cf222e");

    private final Canvas canvas = new Canvas();

    /** Hover popup shown when mouse is over a timestamp row. */
    private Popup hoverPopup;
    private Label popupDateLabel;
    private Label popupTimeLabel;
    private Label popupDurationLabel;
    private Label popupExitLabel;
    private int currentPopupRow = -1;

    /**
     * Maps absolute line numbers to the timestamp when Enter was pressed on that line.
     * Absolute line = historyLinesCount + screenRow at the time of the key press.
     */
    private final TreeMap<Integer, LocalDateTime> timestamps = new TreeMap<>();

    /**
     * The OSC 133 command statuses by the absolute line they had when they were set; lines trimmed
     * from the scrollback since then are counted in {@link #commandStatusShift}.
     */
    private NavigableMap<Integer, CommandStatus> commandStatuses = Collections.emptyNavigableMap();
    private long commandStatusShift;
    private BooleanSupplier commandStatusesShown = () -> true;

    private double charHeight = 16;
    private double baselineOffset = 12;
    private int scrollOrigin = 0;
    private int historyLinesCount = 0;
    private int visibleRows = 24;
    private Color backgroundColor = Color.web("#1a1a1a");
    private Color textColor = Color.web("#666666");
    private Font font = Font.font("Monospaced", FontWeight.NORMAL, 10);
    private Font dateFont = Font.font("Monospaced", FontWeight.NORMAL, 8);
    private Font glyphFont = Font.font("Monospaced", FontWeight.BOLD, 10);
    private Font smallGlyphFont = Font.font("Monospaced", FontWeight.BOLD, 8);
    private boolean lightBackground;
    // Width of a drawn time ("00:00:00") in the fonts it was measured for, where the glyph column
    // starts, and the widths of the glyphs in the glyph fonts.
    private @Nullable Font measuredFont;
    private double timeTextWidth;
    private final Map<String, Double> glyphWidths = new HashMap<>();

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
        this.lightBackground = base.getBrightness() >= 0.5;
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
        this.glyphFont = Font.font(fontFamily, FontWeight.BOLD, gutterFontSize);
        this.smallGlyphFont = Font.font(fontFamily, FontWeight.BOLD, dateFontSize);
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
     * Replaces the command statuses with {@code statuses}, keyed by absolute line as things stand now
     * (see {@code CommandBlockStore.commandStatuses()}).
     */
    public void setCommandStatuses(@Nullable NavigableMap<Integer, CommandStatus> statuses) {
        commandStatuses = statuses == null || statuses.isEmpty()
            ? Collections.emptyNavigableMap()
            : Collections.unmodifiableNavigableMap(new TreeMap<>(statuses));
        commandStatusShift = 0;
        render();
    }

    /**
     * {@code lines} lines left the top of the scrollback: the statuses move up with their lines, the
     * way {@code TimestampHistory.shift} moves the timestamps. Constant time; statuses whose lines are
     * gone are simply never drawn again.
     */
    public void shiftCommandStatuses(int lines) {
        if (lines > 0 && !commandStatuses.isEmpty()) {
            commandStatusShift += lines;
            render();
        }
    }

    /** Drops every command status: the scrollback was cleared. */
    public void clearCommandStatuses() {
        if (!commandStatuses.isEmpty()) {
            commandStatuses = Collections.emptyNavigableMap();
            commandStatusShift = 0;
            render();
        }
    }

    /**
     * Says whether the command statuses are shown, asked on every render: they hide while shell
     * integration is switched off, and the rows then show what they showed before it.
     */
    public void setCommandStatusesShown(BooleanSupplier shown) {
        this.commandStatusesShown = Objects.requireNonNull(shown, "shown");
    }

    /** The command status shown on {@code absoluteLine}, or {@code null}. */
    @Nullable CommandStatus commandStatusAt(int absoluteLine) {
        return !commandStatuses.isEmpty() && statusesShown() ? lookupCommandStatus(absoluteLine) : null;
    }

    private @Nullable CommandStatus lookupCommandStatus(int absoluteLine) {
        long key = absoluteLine + commandStatusShift;
        return key >= 0 && key <= Integer.MAX_VALUE ? commandStatuses.get((int) key) : null;
    }

    private boolean statusesShown() {
        try {
            return commandStatusesShown.getAsBoolean();
        } catch (RuntimeException e) {
            return true;
        }
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
        TimestampGutterFormats formats = currentFormats();
        boolean showStatuses = !commandStatuses.isEmpty() && statusesShown();
        for (int row = 0; row < visibleRows; row++) {
            int absoluteLine = historyLinesCount + scrollOrigin + row;
            LocalDateTime ts = timestamps.get(absoluteLine);
            CommandStatus status = showStatuses ? lookupCommandStatus(absoluteLine) : null;
            if (ts == null && status == null) {
                continue;
            }
            double rowTop = row * charHeight;

            // Top line: short date + the command's runtime, or the time since the previous mark
            // (e.g. "02.10. 12s", "10/02 +12s")
            String topText = TimestampGutterFormats.topLine(ts != null ? ts.format(formats.shortDate()) : null,
                ts != null ? sincePreviousMark(absoluteLine, ts) : null, status);
            double topY = rowTop + Math.max(7.0, baselineOffset - charHeight * 0.42);
            if (!topText.isEmpty()) {
                gc.setFont(dateFont);
                gc.setFill(textColor.deriveColor(0, 1.0, 1.0, DATE_TEXT_ALPHA));
                gc.fillText(topText, TEXT_LEFT_PADDING, topY);
            }

            // Bottom line: time (e.g. "17:20:03"), then the status glyph in its colour
            double bottomY = rowTop + baselineOffset;
            gc.setFont(font);
            if (ts != null) {
                gc.setFill(textColor);
                gc.fillText(ts.format(TIME_FORMAT), TEXT_LEFT_PADDING, bottomY);
            }
            String glyph = status != null ? status.kind().glyph() : "";
            if (!glyph.isEmpty()) {
                gc.setFill(statusColor(status.kind()));
                drawGlyph(gc, glyph, width, topText, topY, bottomY);
            }
        }
    }

    /** The time since the mark above {@code absoluteLine}, or {@code null} for the first mark. */
    private @Nullable Duration sincePreviousMark(int absoluteLine, LocalDateTime ts) {
        Integer prevLine = timestamps.lowerKey(absoluteLine);
        return prevLine != null ? Duration.between(timestamps.get(prevLine), ts) : null;
    }

    /**
     * Draws a status glyph right after where a time is drawn, so the glyphs of all rows line up. A
     * large terminal font leaves no room there in the fixed-width gutter; the glyph then goes, smaller,
     * after the row's top line, and only when that is full too against the right edge.
     */
    private void drawGlyph(GraphicsContext gc, String glyph, double width, String topText, double topY,
            double bottomY) {
        if (measuredFont != font) {
            measuredFont = font;
            timeTextWidth = textWidth("00:00:00", font);
            glyphWidths.clear();
        }
        double right = width - 2;
        double glyphWidth = glyphWidths.computeIfAbsent(glyph, text -> textWidth(text, glyphFont));
        double afterTime = TEXT_LEFT_PADDING + timeTextWidth + GLYPH_GAP;
        if (afterTime + glyphWidth <= right) {
            gc.setFont(glyphFont);
            gc.fillText(glyph, afterTime, bottomY);
            return;
        }
        double smallWidth = glyphWidths.computeIfAbsent("small:" + glyph, text -> textWidth(glyph, smallGlyphFont));
        double afterTop = TEXT_LEFT_PADDING + (topText.isEmpty() ? 0 : textWidth(topText, dateFont) + GLYPH_GAP);
        if (afterTop + smallWidth <= right) {
            gc.setFont(smallGlyphFont);
            gc.fillText(glyph, afterTop, topY);
            return;
        }
        gc.setFont(glyphFont);
        gc.fillText(glyph, Math.max(TEXT_LEFT_PADDING, right - glyphWidth), bottomY);
    }

    private static double textWidth(String text, Font font) {
        Text measure = new Text(text);
        measure.setFont(font);
        return measure.getLayoutBounds().getWidth();
    }

    /** Green for success and red for failure, darker on a light background; the text colour otherwise. */
    private Color statusColor(CommandStatus.Kind kind) {
        return switch (kind) {
            case SUCCEEDED -> lightBackground ? SUCCEEDED_ON_LIGHT : SUCCEEDED_ON_DARK;
            case FAILED -> lightBackground ? FAILED_ON_LIGHT : FAILED_ON_DARK;
            case RUNNING, NO_STATUS -> textColor;
        };
    }

    /**
     * Formats for the current UI language (not the JVM default locale), rebuilt when the
     * language changes.
     */
    static TimestampGutterFormats currentFormats() {
        Locale locale = currentUiLocale();
        TimestampGutterFormats formats = cachedFormats;
        if (formats == null || !formats.locale().equals(locale)) {
            formats = TimestampGutterFormats.forLocale(locale, I18n::get);
            cachedFormats = formats;
        }
        return formats;
    }

    private static Locale currentUiLocale() {
        try {
            Locale locale = LanguageManager.getInstance().getCurrentLocale();
            if (locale != null) {
                return locale;
            }
        } catch (RuntimeException e) {
            // Fall back to the JVM locale below.
        }
        return Locale.getDefault();
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
        popupDateLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 0.9231em; -fx-font-weight: bold;");

        popupTimeLabel = new Label();
        popupTimeLabel.setStyle("-fx-text-fill: #ffffff; -fx-font-size: 1.2308em; -fx-font-weight: bold;");

        popupDurationLabel = new Label();
        popupDurationLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 0.8462em;");

        popupExitLabel = new Label();
        popupExitLabel.setStyle("-fx-text-fill: #aaaaaa; -fx-font-size: 0.8462em;");

        VBox popupContent = new VBox(4, popupDateLabel, popupTimeLabel, popupDurationLabel, popupExitLabel);
        popupContent.setPadding(new Insets(8, 12, 8, 12));
        popupContent.setStyle(
            "-fx-background-color: #2a2a2a;" +
            "-fx-background-radius: 6;" +
            "-fx-border-color: #555555;" +
            "-fx-border-radius: 6;" +
            "-fx-border-width: 1;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.5), 8, 0, 2, 2);"
        );

        // A raw Popup does not inherit the owner scene's stylesheets, so the UI font scale has to
        // be applied to its content root directly.
        UiFontScaleSupport.applyToParent(popupContent);
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
            CommandStatus status = commandStatusAt(absoluteLine);
            if (ts == null && status == null) {
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

            // Populate popup content: date and time, then the runtime or the elapsed time, then
            // the exit status
            TimestampGutterFormats formats = currentFormats();
            showPopupLine(popupDateLabel, ts != null ? ts.format(formats.popupDate()) : null);
            showPopupLine(popupTimeLabel, ts != null ? ts.format(POPUP_TIME_FORMAT) : null);
            showPopupLine(popupDurationLabel, formats.durationLine(
                ts != null ? sincePreviousMark(absoluteLine, ts) : null, status, System.nanoTime()));
            showPopupLine(popupExitLabel, formats.exitStatusLine(status));
            popupExitLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: "
                + (status != null && status.kind() == CommandStatus.Kind.FAILED ? "#ff6b61" : "#aaaaaa") + ";");

            // Show popup near the mouse, offset to the right
            showPopup(event.getScreenX(), event.getScreenY());
        });

        canvas.setOnMouseExited(event -> hidePopup());
    }

    private static void showPopupLine(Label label, @Nullable String text) {
        boolean shown = text != null && !text.isEmpty();
        label.setText(shown ? text : "");
        label.setVisible(shown);
        label.setManaged(shown);
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
