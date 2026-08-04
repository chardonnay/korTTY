package de.kortty.core;

import de.kortty.model.SessionJournalPageScheme;

/**
 * How the generated journal page should look: colour scheme, fonts and text size. Pure, so the
 * CSS it produces is testable without a WebView.
 */
public record SessionJournalPageAppearance(String schemeId, String uiFont, String monoFont,
                                           int fontScalePercent, String theme) {

    public static final int MIN_FONT_SCALE = 70;
    public static final int MAX_FONT_SCALE = 250;

    /** The page's own light/dark switch; "auto" follows the operating system. */
    public static final String THEME_AUTO = "auto";

    public SessionJournalPageAppearance {
        schemeId = blankToNull(schemeId);
        uiFont = blankToNull(uiFont);
        monoFont = blankToNull(monoFont);
        fontScalePercent = Math.max(MIN_FONT_SCALE, Math.min(fontScalePercent, MAX_FONT_SCALE));
        theme = normalizeTheme(theme);
    }

    /** Keeps the four-argument call sites working; the page theme then follows the system. */
    public SessionJournalPageAppearance(String schemeId, String uiFont, String monoFont,
                                        int fontScalePercent) {
        this(schemeId, uiFont, monoFont, fontScalePercent, THEME_AUTO);
    }

    private static String blankToNull(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Only the three values the page understands survive; anything else follows the system. */
    public static String normalizeTheme(String value) {
        String trimmed = value != null ? value.trim().toLowerCase(java.util.Locale.ROOT) : "";
        return switch (trimmed) {
            case "light", "dark" -> trimmed;
            default -> THEME_AUTO;
        };
    }

    public static SessionJournalPageAppearance defaults() {
        return new SessionJournalPageAppearance(SessionJournalPageScheme.ID_AUTO, null, null, 100);
    }

    /** True when a fixed palette replaces the automatic light/dark pair. */
    public boolean hasFixedScheme() {
        return schemeId != null && !SessionJournalPageScheme.ID_AUTO.equals(schemeId);
    }

    public double fontScale() {
        return fontScalePercent / 100.0;
    }

    /**
     * The inline {@code style} value for the {@code <html>} element. Inline wins over every
     * stylesheet rule, which is also what makes the viewer's live preview instant.
     */
    public String htmlStyle() {
        StringBuilder style = new StringBuilder(96);
        style.append("--font-scale:").append(fontScale());
        if (uiFont != null) {
            style.append(";--ui-font:").append(fontStack(uiFont, "ui-sans-serif,sans-serif"));
        }
        if (monoFont != null) {
            style.append(";--mono-font:").append(fontStack(monoFont, "ui-monospace,monospace"));
        }
        return style.toString();
    }

    /**
     * A quoted family plus a generic fallback. The name is sanitized the same way marker colours
     * are: it is free user text that would otherwise land unchecked in a style attribute.
     */
    static String fontStack(String family, String fallback) {
        String safe = SessionJournalHtmlRenderer.cssFontFamily(family);
        return safe == null ? fallback : "'" + safe + "'," + fallback;
    }

    /**
     * The CSS block that pins a fixed scheme, scoped by {@code data-scheme} so it beats both
     * {@code :root} and the {@code [data-theme=...]} blocks and survives the page's own toggle.
     */
    public static String schemeCss(SessionJournalPageScheme scheme) {
        if (scheme == null || scheme.isAuto()) {
            return "";
        }
        String id = SessionJournalHtmlRenderer.cssIdent(scheme.id());
        if (id == null) {
            return "";
        }
        StringBuilder css = new StringBuilder(220);
        css.append("html[data-scheme=\"").append(id).append("\"]{");
        appendVar(css, "--bg", scheme.bg());
        appendVar(css, "--surface", scheme.surface());
        appendVar(css, "--surface2", scheme.surface2());
        appendVar(css, "--border", scheme.border());
        appendVar(css, "--text", scheme.text());
        appendVar(css, "--muted", scheme.muted());
        appendVar(css, "--accent", scheme.accent());
        appendVar(css, "--input", scheme.input());
        appendVar(css, "--output", scheme.output());
        appendVar(css, "--mark", scheme.mark());
        appendVar(css, "--mark-cur", scheme.markCurrent());
        css.append("}\n");
        return css.toString();
    }

    private static void appendVar(StringBuilder css, String name, String value) {
        String colour = SessionJournalHtmlRenderer.cssColor(value);
        if (colour != null) {
            css.append(name).append(':').append(colour).append(';');
        }
    }

    /**
     * A one-shot script that applies this look to an already-loaded page. Used for the viewer's
     * live preview: setting the properties on {@code documentElement.style} beats every stylesheet
     * rule, so the change is immediate and total while the file on disk catches up later.
     *
     * <p>Every value goes through the same JS string escaper the page's own data does.</p>
     */
    public String previewScript(SessionJournalPageScheme scheme) {
        StringBuilder js = new StringBuilder(512);
        js.append("(function(){var root=document.documentElement;root.setAttribute('style',")
            .append(AiChatRenderPageSupport.toJsStringLiteral(htmlStyle())).append(");");
        if (hasFixedScheme() && scheme != null && !scheme.isAuto()) {
            js.append("root.setAttribute('data-scheme',")
                .append(AiChatRenderPageSupport.toJsStringLiteral(
                    SessionJournalHtmlRenderer.cssIdent(scheme.id()))).append(");");
            appendProperty(js, "--bg", scheme.bg());
            appendProperty(js, "--surface", scheme.surface());
            appendProperty(js, "--surface2", scheme.surface2());
            appendProperty(js, "--border", scheme.border());
            appendProperty(js, "--text", scheme.text());
            appendProperty(js, "--muted", scheme.muted());
            appendProperty(js, "--accent", scheme.accent());
            appendProperty(js, "--input", scheme.input());
            appendProperty(js, "--output", scheme.output());
            appendProperty(js, "--mark", scheme.mark());
            appendProperty(js, "--mark-cur", scheme.markCurrent());
        } else {
            // Back to the page's own light/dark pair, including its inline overrides.
            js.append("root.removeAttribute('data-scheme');")
                .append("['--bg','--surface','--surface2','--border','--text','--muted',")
                .append("'--accent','--input','--output','--mark','--mark-cur']")
                .append(".forEach(function(n){root.style.removeProperty(n);});");
        }
        js.append("})()");
        return js.toString();
    }

    private static void appendProperty(StringBuilder js, String name, String value) {
        String colour = SessionJournalHtmlRenderer.cssColor(value);
        if (colour == null) {
            return;
        }
        js.append("root.style.setProperty(")
            .append(AiChatRenderPageSupport.toJsStringLiteral(name)).append(',')
            .append(AiChatRenderPageSupport.toJsStringLiteral(colour)).append(");");
    }
}
