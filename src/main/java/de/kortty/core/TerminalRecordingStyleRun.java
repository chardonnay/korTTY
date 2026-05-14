package de.kortty.core;

import java.util.List;

public record TerminalRecordingStyleRun(
    int row,
    int column,
    String text,
    String foreground,
    String background,
    List<String> options) {

    public TerminalRecordingStyleRun {
        row = Math.max(0, row);
        column = Math.max(0, column);
        text = sanitizeText(text);
        options = options != null ? List.copyOf(options) : List.of();
    }

    private static String sanitizeText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        StringBuilder sanitized = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            sanitized.append(isRenderableLineCharacter(c) ? c : ' ');
        }
        return sanitized.toString();
    }

    private static boolean isRenderableLineCharacter(char c) {
        return c >= ' ' && c != '\u007F';
    }
}
