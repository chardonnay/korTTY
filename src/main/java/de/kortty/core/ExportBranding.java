package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.ui.I18n;

import java.awt.Color;

/**
 * Watermark and footer settings shared by every korTTY PDF export (session journals, AI chats).
 *
 * <p>The watermark is off by default — it belongs on documents the user wants marked, not on every
 * export. The footer is on by default because a document that says where it came from is useful
 * when it is passed around. Both texts are the user's: when a custom text is set, it is used
 * verbatim, and only the built-in default is accompanied by the repository link.</p>
 */
public record ExportBranding(
    boolean watermarkEnabled,
    String watermarkText,
    Color watermarkColor,
    boolean footerEnabled,
    String footerText,
    boolean footerUsesDefaultText) {

    public static final String REPOSITORY_URL = "https://github.com/chardonnay/korTTY";
    public static final String DEFAULT_WATERMARK_TEXT = "korTTY — Developed by Daniel Mengel";
    public static final Color DEFAULT_WATERMARK_COLOR = new Color(0x6b, 0x72, 0x80);

    /** The default footer/brand sentence, translated into the user's language. */
    public static String defaultFooterText() {
        try {
            String value = I18n.get("export.brand");
            return value != null && !value.equals("export.brand")
                ? value
                : "Created with korTTY — Developed by Daniel Mengel";
        } catch (Exception e) {
            return "Created with korTTY — Developed by Daniel Mengel";
        }
    }

    /** Reads the user's choices; falls back to the defaults when settings are unavailable. */
    public static ExportBranding fromSettings(GlobalSettings settings) {
        if (settings == null) {
            return defaults();
        }
        String watermark = trimToNull(settings.getPdfWatermarkText());
        String footer = trimToNull(settings.getExportFooterText());
        return new ExportBranding(
            settings.isPdfWatermarkEnabled(),
            watermark != null ? watermark : DEFAULT_WATERMARK_TEXT,
            parseColor(settings.getPdfWatermarkColor()),
            settings.isExportFooterEnabled(),
            footer != null ? footer : defaultFooterText(),
            footer == null);
    }

    public static ExportBranding defaults() {
        return new ExportBranding(false, DEFAULT_WATERMARK_TEXT, DEFAULT_WATERMARK_COLOR,
            true, defaultFooterText(), true);
    }

    /** True when the built-in watermark text is in use, which alone carries the repository URL. */
    public boolean watermarkUsesDefaultText() {
        return DEFAULT_WATERMARK_TEXT.equals(watermarkText);
    }

    /** The footer line as drawn: the repository URL is appended only to the built-in text. */
    public String footerLine() {
        return footerUsesDefaultText ? footerText + "  ·  " + REPOSITORY_URL : footerText;
    }

    /** Parses {@code #rrggbb}; unreadable or missing values fall back to the default grey. */
    public static Color parseColor(String hex) {
        if (hex == null || hex.isBlank()) {
            return DEFAULT_WATERMARK_COLOR;
        }
        try {
            return Color.decode(hex.trim().startsWith("#") ? hex.trim() : "#" + hex.trim());
        } catch (NumberFormatException e) {
            return DEFAULT_WATERMARK_COLOR;
        }
    }

    public static String toHex(Color color) {
        Color value = color != null ? color : DEFAULT_WATERMARK_COLOR;
        return String.format("#%02x%02x%02x", value.getRed(), value.getGreen(), value.getBlue());
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
