package de.kortty.plugin.terminaleffects.mother;

import com.sithtermfx.ui.SithTermFxWidget;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

final class MotherCrtOverlay extends Canvas {

    private static final long LINE_FLASH_DURATION_NANOS = 260_000_000L;
    private static final double LINE_FLASH_MAX_ALPHA = 0.24;

    private final Random random = new Random();
    private final Timeline timeline;
    private final List<LineFlash> lineFlashes = new ArrayList<>();

    MotherCrtOverlay() {
        setMouseTransparent(true);
        setManaged(false);
        timeline = new Timeline(new KeyFrame(Duration.millis(35), event -> draw()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        widthProperty().addListener((observable, oldValue, newValue) -> draw());
        heightProperty().addListener((observable, oldValue, newValue) -> draw());
    }

    void start() {
        timeline.playFromStart();
        draw();
    }

    void stop() {
        timeline.stop();
        lineFlashes.clear();
    }

    void flashCurrentLine(SithTermFxWidget widget) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> flashCurrentLine(widget));
            return;
        }
        if (widget == null || widget.getTerminal() == null || widget.getTerminalPanel() == null) {
            return;
        }

        double cellHeight = widget.getTerminalPanel().getCellHeightPixels();
        if (!Double.isFinite(cellHeight) || cellHeight <= 0.0) {
            return;
        }
        int row = widget.getTerminal().getCursorY() - 1 - widget.getTerminalPanel().getScrollOrigin();
        if (row < 0 || row * cellHeight >= getHeight()) {
            return;
        }
        flashLine(row, cellHeight);
    }

    private void draw() {
        double width = getWidth();
        double height = getHeight();
        if (width <= 0.0 || height <= 0.0) {
            return;
        }
        GraphicsContext gc = getGraphicsContext2D();
        gc.clearRect(0, 0, width, height);
        drawLineFlashes(gc, width, height);
        drawScanlines(gc, width, height);
        drawNoise(gc, width, height);
        drawVignette(gc, width, height);
    }

    private void flashLine(int row, double cellHeight) {
        long now = System.nanoTime();
        for (int i = 0; i < lineFlashes.size(); i++) {
            LineFlash flash = lineFlashes.get(i);
            if (flash.row() == row) {
                lineFlashes.set(i, new LineFlash(row, cellHeight, now));
                draw();
                return;
            }
        }
        lineFlashes.add(new LineFlash(row, cellHeight, now));
        draw();
    }

    private void drawLineFlashes(GraphicsContext gc, double width, double height) {
        long now = System.nanoTime();
        Iterator<LineFlash> iterator = lineFlashes.iterator();
        while (iterator.hasNext()) {
            LineFlash flash = iterator.next();
            double progress = (double) (now - flash.startedAtNanos()) / LINE_FLASH_DURATION_NANOS;
            if (progress >= 1.0) {
                iterator.remove();
                continue;
            }
            if (progress < 0.0) {
                continue;
            }

            double alpha = LINE_FLASH_MAX_ALPHA * Math.pow(1.0 - progress, 1.8);
            double y = flash.row() * flash.cellHeight();
            double flashHeight = Math.min(flash.cellHeight() + 2.0, height - y);
            if (flashHeight <= 0.0) {
                iterator.remove();
                continue;
            }
            gc.setFill(Color.rgb(150, 255, 175, alpha));
            gc.fillRect(0.0, y, width, flashHeight);
            gc.setFill(Color.rgb(245, 255, 210, alpha * 0.45));
            gc.fillRect(0.0, y, width, Math.max(1.0, flashHeight * 0.45));
        }
    }

    private void drawScanlines(GraphicsContext gc, double width, double height) {
        gc.setFill(Color.rgb(0, 0, 0, 0.28));
        for (double y = 0.0; y < height; y += 4.0) {
            gc.fillRect(0.0, y, width, 1.0);
        }
        gc.setFill(Color.rgb(40, 255, 80, 0.05));
        for (double y = 2.0; y < height; y += 8.0) {
            gc.fillRect(0.0, y, width, 1.0);
        }
    }

    private void drawNoise(GraphicsContext gc, double width, double height) {
        gc.setFill(Color.rgb(180, 255, 190, 0.06));
        int points = (int) Math.max(80, (width * height) / 26000.0);
        for (int i = 0; i < points; i++) {
            double x = random.nextDouble(width);
            double y = random.nextDouble(height);
            gc.fillRect(x, y, 1.0, 1.0);
        }
    }

    private void drawVignette(GraphicsContext gc, double width, double height) {
        gc.setFill(Color.rgb(0, 0, 0, 0.18));
        double edge = Math.max(24.0, Math.min(width, height) * 0.035);
        gc.fillRect(0.0, 0.0, edge, height);
        gc.fillRect(width - edge, 0.0, edge, height);
        gc.fillRect(0.0, 0.0, width, edge);
        gc.fillRect(0.0, height - edge, width, edge);
    }

    private record LineFlash(int row, double cellHeight, long startedAtNanos) {
    }
}
