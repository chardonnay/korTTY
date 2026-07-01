package de.kortty.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Detects SVG images inside AI chat code blocks and prepares them for safe inline display.
 *
 * <p>AI responses deliver SVG as fenced code blocks (```svg, ```xml, ```html or untagged). The
 * chat renders those as an image in a WebView with JavaScript disabled; {@link #sanitizeSvg}
 * additionally strips scripts and event-handler attributes as defense in depth, since SVG is a
 * full XML dialect that may embed executable content.
 */
public final class AiSvgContentSupport {

    /** Languages whose fenced blocks may legitimately carry an SVG document. */
    private static final Pattern SVG_CAPABLE_LANGUAGE = Pattern.compile("(?i)^(svg|xml|html)?$");
    private static final Pattern LEADING_PROLOG = Pattern.compile(
        "(?is)^(?:\\s*(?:<\\?xml[^>]*\\?>|<!DOCTYPE[^>]*>|<!--.*?-->))*\\s*");
    private static final Pattern SCRIPT_ELEMENT = Pattern.compile("(?is)<script\\b.*?(?:</script\\s*>|$)");
    private static final Pattern EVENT_HANDLER_ATTRIBUTE = Pattern.compile(
        "(?is)\\s+on[a-z]+\\s*=\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s>]+)");
    private static final Pattern JAVASCRIPT_HREF = Pattern.compile(
        "(?is)\\s+(?:xlink:)?href\\s*=\\s*([\"'])\\s*javascript:[^\"']*\\1");
    private static final Pattern VIEW_BOX = Pattern.compile(
        "(?is)<svg\\b[^>]*\\bviewBox\\s*=\\s*[\"']\\s*[-0-9.]+[\\s,]+[-0-9.]+[\\s,]+([0-9.]+)[\\s,]+([0-9.]+)\\s*[\"']");
    private static final Pattern HEIGHT_ATTRIBUTE = Pattern.compile(
        "(?is)<svg\\b[^>]*\\bheight\\s*=\\s*[\"']\\s*([0-9.]+)\\s*(?:px)?\\s*[\"']");

    private AiSvgContentSupport() {
    }

    /**
     * True when a fenced code block should be rendered as an SVG image: the language tag is svg,
     * xml, html or absent, and the content (after an optional XML prolog/doctype/comments) is an
     * {@code <svg>} document.
     */
    public static boolean isSvgContent(String language, String content) {
        String tag = language != null ? language.trim().toLowerCase(Locale.ROOT) : "";
        if (!SVG_CAPABLE_LANGUAGE.matcher(tag).matches()) {
            return false;
        }
        if (content == null || content.isBlank()) {
            return false;
        }
        String body = LEADING_PROLOG.matcher(content).replaceFirst("");
        return body.regionMatches(true, 0, "<svg", 0, 4);
    }

    /**
     * Removes scripts, event-handler attributes and {@code javascript:} links from an SVG
     * document. Display additionally runs with JavaScript disabled; this is defense in depth.
     */
    public static String sanitizeSvg(String svg) {
        if (svg == null) {
            return "";
        }
        String sanitized = SCRIPT_ELEMENT.matcher(svg).replaceAll("");
        sanitized = EVENT_HANDLER_ATTRIBUTE.matcher(sanitized).replaceAll("");
        sanitized = JAVASCRIPT_HREF.matcher(sanitized).replaceAll("");
        return sanitized;
    }

    /**
     * Wraps a (sanitized) SVG document in a minimal HTML page for WebView display: neutral white
     * canvas so dark-theme-unaware images stay readable, image centered and scaled down to the
     * available width while keeping its aspect ratio.
     */
    public static String buildSvgHtml(String sanitizedSvg) {
        return "<!DOCTYPE html><html><head><meta charset=\"utf-8\"><style>"
            + "html,body{margin:0;padding:8px;background:#ffffff;}"
            + "svg{max-width:100%;height:auto;display:block;margin:0 auto;}"
            + "</style></head><body>"
            + (sanitizedSvg != null ? sanitizedSvg : "")
            + "</body></html>";
    }

    /**
     * Estimates a display height for the image block from the SVG's own height or viewBox,
     * clamped to {@code [minHeight, maxHeight]}; {@code fallback} (clamped as well) is used when
     * the document declares no usable size.
     */
    public static double estimateDisplayHeight(String svg, double minHeight, double maxHeight, double fallback) {
        double declared = declaredHeight(svg);
        double height = declared > 0 ? declared : fallback;
        return Math.max(minHeight, Math.min(maxHeight, height));
    }

    private static double declaredHeight(String svg) {
        if (svg == null || svg.isBlank()) {
            return 0;
        }
        Matcher heightMatcher = HEIGHT_ATTRIBUTE.matcher(svg);
        if (heightMatcher.find()) {
            try {
                return Double.parseDouble(heightMatcher.group(1));
            } catch (NumberFormatException ignored) {
                // fall through to the viewBox
            }
        }
        Matcher viewBoxMatcher = VIEW_BOX.matcher(svg);
        if (viewBoxMatcher.find()) {
            try {
                return Double.parseDouble(viewBoxMatcher.group(2));
            } catch (NumberFormatException ignored) {
                // no usable size declared
            }
        }
        return 0;
    }
}
