package de.kortty.core;

import java.util.regex.Pattern;

/**
 * Sanitizes AI responses before they are shown in the UI.
 */
public final class AiResponseSanitizer {

    private static final Pattern THINK_BLOCK_PATTERN = Pattern.compile("(?is)<think>.*?</think>");
    private static final Pattern EXCESSIVE_BLANK_LINES_PATTERN = Pattern.compile("\\n{3,}");

    private AiResponseSanitizer() {
    }

    public static String sanitizeForDisplay(String content) {
        if (content == null || content.isEmpty()) {
            return "";
        }
        String withoutThink = THINK_BLOCK_PATTERN.matcher(content).replaceAll("");
        String normalized = withoutThink.replace("\r\n", "\n").replace('\r', '\n').trim();
        return EXCESSIVE_BLANK_LINES_PATTERN.matcher(normalized).replaceAll("\n\n");
    }
}
