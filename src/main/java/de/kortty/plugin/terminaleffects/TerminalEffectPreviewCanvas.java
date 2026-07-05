package de.kortty.plugin.terminaleffects;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.scene.Node;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Reusable {@link TerminalEffectPreview}: a small fake terminal (colored background, a few fake
 * shell lines, a blinking block cursor) with an optional effect overlay canvas on top.
 *
 * <p>The builder stores plain data only; all JavaFX objects are created lazily in {@link #node()}
 * so plugin metadata stays usable without a running JavaFX toolkit.</p>
 */
public final class TerminalEffectPreviewCanvas implements TerminalEffectPreview {

    public static final double PREVIEW_WIDTH = 360.0;
    public static final double PREVIEW_HEIGHT = 220.0;

    private static final double PADDING = 16.0;
    private static final double CORNER_RADIUS = 8.0;

    private final String backgroundColor;
    private final String foregroundColor;
    private final @Nullable String dimColor;
    private final List<String> lines;
    private final String fontFamily;
    private final double fontSize;
    private final long cursorBlinkMillis;
    private final @Nullable Supplier<? extends Canvas> overlayFactory;
    private final @Nullable Consumer<Canvas> overlayStart;
    private final @Nullable Consumer<Canvas> overlayStop;

    private @Nullable StackPane root;
    private @Nullable Canvas baseCanvas;
    private @Nullable Canvas overlayCanvas;
    private @Nullable Timeline cursorTimeline;
    private boolean cursorVisible = true;

    private TerminalEffectPreviewCanvas(Builder builder) {
        this.backgroundColor = builder.backgroundColor;
        this.foregroundColor = builder.foregroundColor;
        this.dimColor = builder.dimColor;
        this.lines = List.copyOf(builder.lines);
        this.fontFamily = builder.fontFamily;
        this.fontSize = builder.fontSize;
        this.cursorBlinkMillis = builder.cursorBlinkMillis;
        this.overlayFactory = builder.overlayFactory;
        this.overlayStart = builder.overlayStart;
        this.overlayStop = builder.overlayStop;
    }

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public @NotNull Node node() {
        if (root != null) {
            return root;
        }
        Canvas base = new Canvas(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        baseCanvas = base;
        paintBase();

        StackPane pane = new StackPane(base);
        pane.setMinSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        pane.setPrefSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        pane.setMaxSize(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        Rectangle clip = new Rectangle(PREVIEW_WIDTH, PREVIEW_HEIGHT);
        clip.setArcWidth(CORNER_RADIUS * 2.0);
        clip.setArcHeight(CORNER_RADIUS * 2.0);
        pane.setClip(clip);

        if (overlayFactory != null) {
            Canvas overlay = overlayFactory.get();
            overlay.setWidth(PREVIEW_WIDTH);
            overlay.setHeight(PREVIEW_HEIGHT);
            overlay.setMouseTransparent(true);
            overlayCanvas = overlay;
            pane.getChildren().add(overlay);
        }
        root = pane;
        return pane;
    }

    @Override
    public void start() {
        if (root == null) {
            return;
        }
        if (cursorTimeline == null) {
            cursorTimeline = new Timeline(new KeyFrame(Duration.millis(cursorBlinkMillis), event -> {
                cursorVisible = !cursorVisible;
                paintBase();
            }));
            cursorTimeline.setCycleCount(Timeline.INDEFINITE);
        }
        cursorTimeline.playFromStart();
        if (overlayCanvas != null && overlayStart != null) {
            overlayStart.accept(overlayCanvas);
        }
    }

    @Override
    public void stop() {
        if (cursorTimeline != null) {
            cursorTimeline.stop();
        }
        if (overlayCanvas != null && overlayStop != null) {
            overlayStop.accept(overlayCanvas);
        }
    }

    private void paintBase() {
        Canvas base = baseCanvas;
        if (base == null) {
            return;
        }
        GraphicsContext gc = base.getGraphicsContext2D();
        Color background = parseColor(backgroundColor, Color.BLACK);
        Color foreground = parseColor(foregroundColor, Color.LIGHTGRAY);
        Color dim = dimColor != null
                ? parseColor(dimColor, foreground)
                : foreground.deriveColor(0.0, 1.0, 1.0, 0.55);

        gc.setFill(background);
        gc.fillRect(0.0, 0.0, PREVIEW_WIDTH, PREVIEW_HEIGHT);

        gc.setFont(Font.font(fontFamily, fontSize));
        double lineHeight = fontSize + 6.0;
        double y = PADDING + fontSize;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            gc.setFill(isPromptLine(line) ? foreground : dim);
            gc.fillText(line, PADDING, y + i * lineHeight);
        }

        if (cursorVisible) {
            int lastIndex = Math.max(0, lines.size() - 1);
            String lastLine = lines.isEmpty() ? "" : lines.get(lastIndex);
            double charWidth = fontSize * 0.62;
            double cursorX = PADDING + lastLine.length() * charWidth + 2.0;
            double cursorY = PADDING + lastIndex * lineHeight + 3.0;
            gc.setFill(foreground);
            gc.fillRect(cursorX, cursorY, charWidth + 2.0, lineHeight - 4.0);
        }
    }

    private static boolean isPromptLine(String line) {
        String trimmed = line.stripLeading();
        return trimmed.startsWith("$") || trimmed.startsWith(">") || trimmed.startsWith("#")
                || trimmed.startsWith("~") || trimmed.contains("$ ");
    }

    private static Color parseColor(@Nullable String value, Color fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Color.web(value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    /**
     * Collects plain preview data; creates no JavaFX objects.
     */
    public static final class Builder {

        private String backgroundColor = "#000000";
        private String foregroundColor = "#D0D0D0";
        private @Nullable String dimColor;
        private List<String> lines = List.of(
                "$ ssh kortty@retro-01",
                "Welcome to korTTY",
                "retro-01:~$ ls -la",
                "drwxr-xr-x  4 kortty  staff   128 sessions",
                "-rw-r--r--  1 kortty  staff  2048 notes.txt",
                "retro-01:~$ ");
        private String fontFamily = "Monospaced";
        private double fontSize = 12.0;
        private long cursorBlinkMillis = 530L;
        private @Nullable Supplier<? extends Canvas> overlayFactory;
        private @Nullable Consumer<Canvas> overlayStart;
        private @Nullable Consumer<Canvas> overlayStop;

        private Builder() {
        }

        public Builder backgroundColor(String backgroundColor) {
            this.backgroundColor = Objects.requireNonNull(backgroundColor, "backgroundColor");
            return this;
        }

        public Builder foregroundColor(String foregroundColor) {
            this.foregroundColor = Objects.requireNonNull(foregroundColor, "foregroundColor");
            return this;
        }

        public Builder dimColor(@Nullable String dimColor) {
            this.dimColor = dimColor;
            return this;
        }

        public Builder lines(List<String> lines) {
            this.lines = Objects.requireNonNull(lines, "lines");
            return this;
        }

        public Builder fontFamily(String fontFamily) {
            this.fontFamily = Objects.requireNonNull(fontFamily, "fontFamily");
            return this;
        }

        public Builder fontSize(double fontSize) {
            this.fontSize = fontSize;
            return this;
        }

        public Builder cursorBlinkMillis(long cursorBlinkMillis) {
            this.cursorBlinkMillis = Math.max(120L, cursorBlinkMillis);
            return this;
        }

        /**
         * Registers the effect overlay for this preview. The factory runs lazily on the JavaFX
         * thread when {@link #node()} is built; start/stop actions receive the created canvas.
         */
        @SuppressWarnings("unchecked")
        public <C extends Canvas> Builder overlay(
                Supplier<C> overlayFactory,
                Consumer<C> startAction,
                Consumer<C> stopAction) {
            this.overlayFactory = Objects.requireNonNull(overlayFactory, "overlayFactory");
            this.overlayStart = (Consumer<Canvas>) Objects.requireNonNull(startAction, "startAction");
            this.overlayStop = (Consumer<Canvas>) Objects.requireNonNull(stopAction, "stopAction");
            return this;
        }

        public TerminalEffectPreviewCanvas build() {
            return new TerminalEffectPreviewCanvas(this);
        }
    }
}
