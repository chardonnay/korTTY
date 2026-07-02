package de.kortty.core;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects base64 raster images (data URIs) in AI chat responses so they can be shown as images.
 *
 * <p>Two shapes are supported: markdown image syntax {@code ![alt](data:image/png;base64,...)}
 * inside a text section, and a fenced code block whose entire content is a bare
 * {@code data:image/...;base64,...} URI. Only formats JavaFX can decode natively are accepted
 * (PNG, JPEG, GIF, BMP), and decoded payloads are capped to keep a hostile response from
 * exhausting memory.
 */
public final class AiRasterImageSupport {

    /** Upper bound for a decoded image payload (bytes). */
    static final int MAX_DECODED_BYTES = 8 * 1024 * 1024;

    /**
     * Upper bound for the decoded pixel count (~160 MB ARGB). Guards against decompression
     * bombs: a tiny compressed payload may declare enormous dimensions in its header.
     */
    static final long MAX_PIXELS = 40_000_000L;

    private static final String DATA_URI_BODY = "data:image/(?:png|jpe?g|gif|bmp);base64,[A-Za-z0-9+/=\\s]+";
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*\\]\\(\\s*(" + DATA_URI_BODY + ")\\)");
    private static final Pattern BARE_DATA_URI = Pattern.compile("^\\s*" + DATA_URI_BODY + "\\s*$");
    private static final Pattern BASE64_PAYLOAD = Pattern.compile("base64,([A-Za-z0-9+/=\\s]+)$");

    /**
     * One part of a text section: either plain text ({@code imageBytes == null}) or a decoded
     * raster image.
     */
    public record Segment(String text, byte[] imageBytes) {
    }

    private AiRasterImageSupport() {
    }

    /** True when a fenced code block's whole content is a base64 raster-image data URI. */
    public static boolean isImageDataUri(String content) {
        return content != null && BARE_DATA_URI.matcher(content).matches();
    }

    /**
     * Splits a text section into plain-text and image segments. Markdown images whose payload
     * does not decode (or exceeds the size cap) stay in the text verbatim.
     */
    public static List<Segment> splitTextWithImages(String text) {
        List<Segment> segments = new ArrayList<>();
        String safeText = text != null ? text : "";
        Matcher matcher = MARKDOWN_IMAGE.matcher(safeText);
        int lastEnd = 0;
        while (matcher.find()) {
            byte[] decoded = decodeImageDataUri(matcher.group(1));
            if (decoded == null) {
                continue; // leave undecodable image markdown as part of the surrounding text
            }
            if (matcher.start() > lastEnd) {
                String leading = safeText.substring(lastEnd, matcher.start()).strip();
                if (!leading.isEmpty()) {
                    segments.add(new Segment(leading, null));
                }
            }
            segments.add(new Segment(null, decoded));
            lastEnd = matcher.end();
        }
        if (segments.isEmpty()) {
            // No images found: hand the section back untouched.
            segments.add(new Segment(safeText, null));
            return segments;
        }
        String trailing = safeText.substring(lastEnd).strip();
        if (!trailing.isEmpty()) {
            segments.add(new Segment(trailing, null));
        }
        return segments;
    }

    /**
     * True when the image header declares plausible dimensions within the pixel budget. Reads
     * only the header (no full decode), so a decompression bomb is rejected before any large
     * allocation happens. Unreadable or dimensionless payloads are rejected as well.
     */
    public static boolean hasSaneDimensions(byte[] imageBytes) {
        if (imageBytes == null || imageBytes.length == 0) {
            return false;
        }
        try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(imageBytes))) {
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                return false;
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                long width = reader.getWidth(0);
                long height = reader.getHeight(0);
                return width > 0 && height > 0 && width * height <= MAX_PIXELS;
            } finally {
                reader.dispose();
            }
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * Decodes a raster-image data URI, or returns {@code null} when the payload is not valid
     * base64 or exceeds {@link #MAX_DECODED_BYTES}.
     */
    public static byte[] decodeImageDataUri(String dataUri) {
        if (dataUri == null) {
            return null;
        }
        Matcher payload = BASE64_PAYLOAD.matcher(dataUri.strip());
        if (!payload.find()) {
            return null;
        }
        String base64 = payload.group(1).replaceAll("\\s", "");
        // Base64 expands data by 4/3: reject oversized payloads before decoding.
        if (base64.length() > (long) MAX_DECODED_BYTES * 4 / 3) {
            return null;
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(base64);
            return decoded.length > 0 ? decoded : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
