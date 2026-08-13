package de.kortty.core;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/** Shared raster helpers for journal screenshots (PDF export, AI screenshot analysis). */
public final class SessionJournalImageSupport {

    private SessionJournalImageSupport() {
    }

    /**
     * Bilinear downscale to at most {@code maxWidth} pixels wide, preserving aspect ratio. Images
     * that already fit are returned unchanged.
     */
    public static BufferedImage downscaleToWidth(BufferedImage image, int maxWidth) {
        if (image == null || maxWidth <= 0 || image.getWidth() <= maxWidth) {
            return image;
        }
        int newHeight = Math.max(1, (int) Math.round(image.getHeight() * (maxWidth / (double) image.getWidth())));
        BufferedImage scaled = new BufferedImage(maxWidth, newHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = scaled.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        graphics.drawImage(image, 0, 0, maxWidth, newHeight, null);
        graphics.dispose();
        return scaled;
    }
}
