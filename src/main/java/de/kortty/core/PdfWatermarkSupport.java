package de.kortty.core;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.IOException;

/** Draws the shared PDF watermark and the clickable footer repository link. */
public final class PdfWatermarkSupport {

    private static final float ALPHA = 0.08f;
    private static final float ROTATION_DEGREES = 38;
    private static final float MAX_FONT_SIZE = 26f;
    private static final float MIN_FONT_SIZE = 14f;

    private PdfWatermarkSupport() {
    }

    /**
     * Diagonal watermark across the page centre, faint enough to leave the content readable.
     * The repository URL is added as a second line only for the built-in text — a custom
     * watermark is the user's wording and stays untouched.
     */
    public static void draw(PDDocument document, PDPage page, PDFont boldFont, PDFont regularFont,
                            ExportBranding branding) throws IOException {
        String text = branding.watermarkText();
        if (text == null || text.isBlank()) {
            return;
        }
        try (PDPageContentStream stream =
                 new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
            PDExtendedGraphicsState graphicsState = new PDExtendedGraphicsState();
            graphicsState.setNonStrokingAlphaConstant(ALPHA);
            stream.saveGraphicsState();
            stream.setGraphicsStateParameters(graphicsState);

            float pageWidth = page.getMediaBox().getWidth();
            float centerX = pageWidth / 2f;
            float centerY = page.getMediaBox().getHeight() / 2f;
            float fontSize = MAX_FONT_SIZE;
            float textWidth = width(boldFont, fontSize, text);
            while (fontSize > MIN_FONT_SIZE && textWidth > pageWidth - 120f) {
                fontSize -= 1f;
                textWidth = width(boldFont, fontSize, text);
            }
            // The matrix rotates about the page centre AND makes it the new origin, so the text is
            // positioned relative to (0,0). Passing absolute page coordinates here would apply the
            // centre offset twice and push the watermark off the page — it then renders clipped in
            // a corner instead of across the middle.
            stream.transform(Matrix.getRotateInstance(Math.toRadians(ROTATION_DEGREES), centerX, centerY));
            Color color = branding.watermarkColor();
            drawText(stream, boldFont, fontSize, color, -textWidth / 2f, 0f, text);
            if (branding.watermarkUsesDefaultText()) {
                float urlSize = fontSize * 0.45f;
                float urlWidth = width(regularFont, urlSize, ExportBranding.REPOSITORY_URL);
                drawText(stream, regularFont, urlSize, color, -urlWidth / 2f, -fontSize,
                    ExportBranding.REPOSITORY_URL);
            }
            stream.restoreGraphicsState();
        }
    }

    /**
     * Puts a borderless link annotation over the repository URL that follows {@code prefix} in the
     * footer line, so the printed URL is clickable in a PDF viewer.
     */
    public static void addFooterRepositoryLink(PDPage page, PDFont font, float fontSize,
                                               float marginLeft, String prefix) throws IOException {
        float linkStart = marginLeft + width(font, fontSize, prefix);
        float linkWidth = width(font, fontSize, ExportBranding.REPOSITORY_URL);
        if (linkWidth <= 0 || linkStart + linkWidth > page.getMediaBox().getWidth() - marginLeft) {
            return;
        }
        PDActionURI action = new PDActionURI();
        action.setURI(ExportBranding.REPOSITORY_URL);
        PDAnnotationLink link = new PDAnnotationLink();
        link.setAction(action);
        PDBorderStyleDictionary border = new PDBorderStyleDictionary();
        border.setWidth(0);
        link.setBorderStyle(border);
        link.setRectangle(new PDRectangle(linkStart, 24f, linkWidth, fontSize + 4f));
        page.getAnnotations().add(link);
    }

    private static float width(PDFont font, float fontSize, String text) throws IOException {
        return font.getStringWidth(sanitize(font, text)) / 1000f * fontSize;
    }

    private static void drawText(PDPageContentStream stream, PDFont font, float fontSize, Color color,
                                 float x, float y, String text) throws IOException {
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(sanitize(font, text));
        stream.endText();
    }

    /** Drops glyphs the bundled fonts cannot encode so a custom text never breaks the export. */
    private static String sanitize(PDFont font, String text) {
        String safe = text != null ? text : "";
        StringBuilder builder = new StringBuilder(safe.length());
        for (int index = 0; index < safe.length(); ) {
            int codePoint = safe.codePointAt(index);
            String glyph = new String(Character.toChars(codePoint));
            if (Character.isWhitespace(codePoint)) {
                builder.append(' ');
            } else {
                try {
                    font.encode(glyph);
                    builder.append(glyph);
                } catch (IllegalArgumentException | IOException e) {
                    builder.append('?');
                }
            }
            index += Character.charCount(codePoint);
        }
        return builder.toString();
    }
}
