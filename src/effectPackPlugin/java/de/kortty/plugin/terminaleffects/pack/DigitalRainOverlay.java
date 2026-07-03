package de.kortty.plugin.terminaleffects.pack;

import javafx.scene.canvas.GraphicsContext;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.function.DoubleSupplier;

/**
 * Matrix-style digital rain: faint columns of falling glyphs, stronger near the screen edges
 * and deliberately subtle over the center so terminal text stays readable.
 */
final class DigitalRainOverlay extends AbstractPackOverlay {

    private static final String GLYPHS = "01<>+*=$#%&@!?;:^~";
    private static final double COLUMN_WIDTH = 14.0;
    private static final double ROW_HEIGHT = 15.0;
    private static final int MAX_COLUMNS = 64;
    private static final long FRAME_INTERVAL_MILLIS = 50L;

    private double[] columnPositions = new double[0];
    private double[] columnSpeeds = new double[0];
    private int[] trailLengths = new int[0];
    private char[][] columnGlyphs = new char[0][];
    private Font glyphFont;

    DigitalRainOverlay(DoubleSupplier animationSpeed) {
        super(animationSpeed, FRAME_INTERVAL_MILLIS);
    }

    @Override
    protected void onSizeChanged(double width, double height) {
        int columns = (int) Math.min(MAX_COLUMNS, Math.max(1.0, width / COLUMN_WIDTH));
        if (columns == columnPositions.length) {
            return;
        }
        columnPositions = new double[columns];
        columnSpeeds = new double[columns];
        trailLengths = new int[columns];
        columnGlyphs = new char[columns][];
        int rows = Math.max(8, (int) (height / ROW_HEIGHT) + 16);
        for (int i = 0; i < columns; i++) {
            columnPositions[i] = -random.nextDouble() * rows;
            columnSpeeds[i] = 4.0 + random.nextDouble() * 6.0;
            trailLengths[i] = 6 + random.nextInt(9);
            columnGlyphs[i] = randomGlyphColumn(rows);
        }
    }

    private char[] randomGlyphColumn(int rows) {
        char[] glyphs = new char[rows];
        for (int i = 0; i < rows; i++) {
            glyphs[i] = GLYPHS.charAt(random.nextInt(GLYPHS.length()));
        }
        return glyphs;
    }

    @Override
    protected void paintFrame(
            GraphicsContext gc, double width, double height, long frame, double elapsedSeconds, double speed) {
        if (columnPositions.length == 0) {
            onSizeChanged(width, height);
        }
        if (glyphFont == null) {
            glyphFont = Font.font("Monospaced", 13.0);
        }
        gc.setFont(glyphFont);

        int totalRows = (int) (height / ROW_HEIGHT) + 1;
        double frameSeconds = FRAME_INTERVAL_MILLIS / 1000.0;
        for (int column = 0; column < columnPositions.length; column++) {
            columnPositions[column] += columnSpeeds[column] * frameSeconds * speed;
            char[] glyphs = columnGlyphs[column];
            int headRow = (int) columnPositions[column];
            if (headRow - trailLengths[column] > totalRows) {
                columnPositions[column] = -random.nextDouble() * 14.0;
                columnSpeeds[column] = 4.0 + random.nextDouble() * 6.0;
                trailLengths[column] = 6 + random.nextInt(9);
                continue;
            }

            double x = column * COLUMN_WIDTH + 2.0;
            double edgeFactor = edgeFactor(x, width);
            for (int t = 0; t < trailLengths[column]; t++) {
                int row = headRow - t;
                if (row < 0 || row > totalRows) {
                    continue;
                }
                double fade = 1.0 - (t / (double) trailLengths[column]);
                double alpha;
                if (t == 0) {
                    alpha = Math.min(0.30, 0.16 * edgeFactor);
                    if (random.nextInt(4) == 0) {
                        glyphs[row % glyphs.length] = GLYPHS.charAt(random.nextInt(GLYPHS.length()));
                    }
                } else {
                    alpha = Math.min(0.14, (0.03 + 0.07 * fade) * edgeFactor);
                }
                gc.setFill(t == 0
                        ? Color.color(0.71, 1.0, 0.78, alpha)
                        : Color.color(0.0, 1.0, 0.4, alpha));
                gc.fillText(String.valueOf(glyphs[row % glyphs.length]), x, row * ROW_HEIGHT + ROW_HEIGHT);
            }
        }
    }

    private static double edgeFactor(double x, double width) {
        double distanceFromCenter = Math.abs(x - width / 2.0) / (width / 2.0);
        return distanceFromCenter > 0.7 ? 1.6 : 0.6 + distanceFromCenter;
    }
}
