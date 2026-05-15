package de.kortty.core;

import java.util.regex.Pattern;

/**
 * Sanitizes AI responses before they are shown in the UI.
 */
public final class AiResponseSanitizer {

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think\\b[^>]*>.*?</think\\s*>");
    private static final Pattern DANGLING_THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think\\b[^>]*>.*$");
    private static final Pattern ORPHAN_CLOSING_THINK_PREFIX_PATTERN = Pattern.compile("(?is)^.*?</think\\s*>\\s*");
    private static final Pattern EXCESSIVE_BLANK_LINES_PATTERN = Pattern.compile("\\n{3,}");

    private AiResponseSanitizer() {
    }

    public static String sanitizeForDisplay(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String withoutThink = THINK_BLOCK_PATTERN.matcher(content).replaceAll("");
        withoutThink = DANGLING_THINK_BLOCK_PATTERN.matcher(withoutThink).replaceAll("");
        withoutThink = ORPHAN_CLOSING_THINK_PREFIX_PATTERN.matcher(withoutThink).replaceFirst("");
        String normalized = withoutThink.replace("\r\n", "\n").replace('\r', '\n').trim();
        return EXCESSIVE_BLANK_LINES_PATTERN.matcher(normalized).replaceAll("\n\n");
    }
}
