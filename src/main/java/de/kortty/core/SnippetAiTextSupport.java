package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Safe local helpers for snippet-editor AI text operations.
 */
public final class SnippetAiTextSupport {

    public static final int DEFAULT_DESCRIPTION_WRAP_WIDTH = 80;

    public enum SegmentType {
        COMMENT,
        STRING,
        XML_COMMENT
    }

    public record EditableTextSegment(int start, int end, String originalText, SegmentType type) {
        public EditableTextSegment {
            if (start < 0 || end < start) {
                throw new IllegalArgumentException("Invalid segment range");
            }
            originalText = originalText != null ? originalText : "";
        }

        public String coreText() {
            return trimOuterWhitespace(originalText);
        }

        public String applyReplacement(String replacement) {
            String normalizedReplacement = replacement != null ? replacement.trim() : "";
            if (normalizedReplacement.isBlank()) {
                return originalText;
            }
            return leadingWhitespace(originalText) + normalizedReplacement + trailingWhitespace(originalText);
        }

        private static String trimOuterWhitespace(String value) {
            int startIndex = 0;
            int endIndex = value.length();
            while (startIndex < endIndex && Character.isWhitespace(value.charAt(startIndex))) {
                startIndex++;
            }
            while (endIndex > startIndex && Character.isWhitespace(value.charAt(endIndex - 1))) {
                endIndex--;
            }
            return value.substring(startIndex, endIndex);
        }

        private static String leadingWhitespace(String value) {
            int index = 0;
            while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
                index++;
            }
            return value.substring(0, index);
        }

        private static String trailingWhitespace(String value) {
            int index = value.length();
            while (index > 0 && Character.isWhitespace(value.charAt(index - 1))) {
                index--;
            }
            return value.substring(index);
        }
    }

    private static final Pattern C_STYLE_BLOCK_COMMENT_PATTERN = Pattern.compile("(?s)/\\*(.*?)\\*/");
    private static final Pattern XML_COMMENT_PATTERN = Pattern.compile("(?s)<!--(.*?)-->");
    private static final Pattern DOUBLE_QUOTED_PATTERN = Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern SINGLE_QUOTED_PATTERN = Pattern.compile("'((?:[^'\\\\]|\\\\.)*)'");
    private static final Pattern BACKTICK_QUOTED_PATTERN = Pattern.compile("`((?:[^`\\\\]|\\\\.)*)`");
    private static final Pattern PYTHON_TRIPLE_DOUBLE_PATTERN = Pattern.compile("(?s)\"\"\"(.*?)\"\"\"");
    private static final Pattern PYTHON_TRIPLE_SINGLE_PATTERN = Pattern.compile("(?s)'''(.*?)'''");

    private SnippetAiTextSupport() {
    }

    public static List<EditableTextSegment> extractEditableSegments(String selectedText, String language) {
        String text = selectedText != null ? selectedText : "";
        if (text.isBlank()) {
            return List.of();
        }
        String normalizedLanguage = SnippetLanguageSupport.detectSnippetLanguage(language, text);
        List<EditableTextSegment> segments = new ArrayList<>();
        switch (normalizedLanguage) {
            case "bash", "python", "perl", "ruby", "powershell", "dockerfile", "yaml", "properties" ->
                extractHashStyleSegments(text, segments, normalizedLanguage);
            case "java", "javascript", "groovy" -> extractCStyleSegments(text, segments, true);
            case "sql" -> extractSqlSegments(text, segments);
            case "xml", "html" -> extractXmlSegments(text, segments);
            default -> {
                return List.of();
            }
        }
        segments.sort((left, right) -> Integer.compare(left.start(), right.start()));
        return segments;
    }

    public static String applyReplacements(String selectedText, List<EditableTextSegment> segments, List<String> replacements) {
        if (selectedText == null || selectedText.isEmpty() || segments == null || segments.isEmpty()) {
            return selectedText != null ? selectedText : "";
        }
        StringBuilder builder = new StringBuilder(selectedText);
        int count = Math.min(segments.size(), replacements != null ? replacements.size() : 0);
        for (int index = count - 1; index >= 0; index--) {
            EditableTextSegment segment = segments.get(index);
            String replacement = replacements.get(index);
            if (segment == null || replacement == null || replacement.isBlank()) {
                continue;
            }
            builder.replace(segment.start(), segment.end(), segment.applyReplacement(replacement));
        }
        return builder.toString();
    }

    public static boolean supportsCommentFormatting(String language) {
        return commentFormat(SnippetLanguageSupport.normalizeSnippetLanguage(language)) != null;
    }

    public static String formatDescriptionAsComment(String description, String language, String indent) {
        String normalizedDescription = wrapDescriptionText(description, DEFAULT_DESCRIPTION_WRAP_WIDTH);
        CommentFormat format = commentFormat(SnippetLanguageSupport.normalizeSnippetLanguage(language));
        String indentation = indent != null ? indent : "";
        if (normalizedDescription.isBlank() || format == null) {
            return normalizedDescription;
        }
        String[] lines = normalizedDescription.split("\\R");
        if (format.block()) {
            StringBuilder blockBuilder = new StringBuilder();
            blockBuilder.append(indentation).append(format.prefix()).append("\n");
            for (String line : lines) {
                blockBuilder.append(indentation).append(" ").append(line.stripTrailing()).append("\n");
            }
            blockBuilder.append(indentation).append(format.suffix());
            return blockBuilder.toString();
        }
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                builder.append('\n');
            }
            builder.append(indentation)
                .append(format.prefix())
                .append(' ')
                .append(lines[i].stripTrailing());
        }
        return builder.toString();
    }

    public static String wrapDescriptionText(String text, int maxLineLength) {
        String normalized = normalizePlainText(text);
        int width = Math.max(20, maxLineLength);
        if (normalized.isBlank()) {
            return normalized;
        }
        String[] paragraphs = normalized.split("\\n\\s*\\n");
        List<String> wrappedParagraphs = new ArrayList<>();
        for (String paragraph : paragraphs) {
            String trimmedParagraph = paragraph != null ? paragraph.trim() : "";
            if (trimmedParagraph.isBlank()) {
                continue;
            }
            wrappedParagraphs.add(wrapParagraph(trimmedParagraph, width));
        }
        return String.join("\n\n", wrappedParagraphs);
    }

    public static String normalizePlainText(String text) {
        if (text == null) {
            return "";
        }
        String normalized = text.replace("\r", "\n");
        normalized = normalized.replaceAll("(?s)```(?:\\w+)?\\n?", "");
        return normalized.trim();
    }

    private static String wrapParagraph(String paragraph, int width) {
        String collapsed = paragraph.replaceAll("\\s*\\n\\s*", " ").trim();
        if (collapsed.isEmpty() || collapsed.length() <= width) {
            return collapsed;
        }
        StringBuilder builder = new StringBuilder();
        int lineLength = 0;
        for (String word : collapsed.split("\\s+")) {
            if (word.isEmpty()) {
                continue;
            }
            int additionalLength = lineLength == 0 ? word.length() : word.length() + 1;
            if (lineLength > 0 && lineLength + additionalLength > width) {
                builder.append('\n');
                builder.append(word);
                lineLength = word.length();
            } else {
                if (lineLength > 0) {
                    builder.append(' ');
                    lineLength++;
                }
                builder.append(word);
                lineLength += word.length();
            }
        }
        return builder.toString();
    }

    public static String findLineIndentation(String text, int offset) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        int safeOffset = Math.max(0, Math.min(offset, text.length()));
        int lineStart = text.lastIndexOf('\n', Math.max(0, safeOffset - 1));
        lineStart = lineStart < 0 ? 0 : lineStart + 1;
        int cursor = lineStart;
        while (cursor < text.length()) {
            char character = text.charAt(cursor);
            if (character != ' ' && character != '\t') {
                break;
            }
            cursor++;
        }
        return text.substring(lineStart, cursor);
    }

    public static String toSegmentsJson(List<EditableTextSegment> segments) {
        JsonArray array = new JsonArray();
        if (segments != null) {
            for (int index = 0; index < segments.size(); index++) {
                EditableTextSegment segment = segments.get(index);
                if (segment == null) {
                    continue;
                }
                JsonObject object = new JsonObject();
                object.addProperty("index", index);
                object.addProperty("type", segment.type().name().toLowerCase(Locale.ROOT));
                object.addProperty("text", segment.coreText());
                array.add(object);
            }
        }
        return array.toString();
    }

    private static void extractHashStyleSegments(String text, List<EditableTextSegment> segments, String language) {
        Pattern commentPattern = switch (language) {
            case "properties" -> Pattern.compile("(?m)^[ \\t]*[;#](.*)$");
            case "python", "perl", "ruby", "bash", "powershell", "dockerfile", "yaml" ->
                Pattern.compile("(?m)^\\s*#(?!\\!)(.*)$");
            default -> Pattern.compile("(?m)^\\s*#(.*)$");
        };
        addGroupSegments(text, segments, commentPattern, 1, SegmentType.COMMENT);
        addGroupSegments(text, segments, DOUBLE_QUOTED_PATTERN, 1, SegmentType.STRING);
        addGroupSegments(text, segments, SINGLE_QUOTED_PATTERN, 1, SegmentType.STRING);
        if ("python".equals(language)) {
            addGroupSegments(text, segments, PYTHON_TRIPLE_DOUBLE_PATTERN, 1, SegmentType.STRING);
            addGroupSegments(text, segments, PYTHON_TRIPLE_SINGLE_PATTERN, 1, SegmentType.STRING);
        }
    }

    private static void extractCStyleSegments(String text, List<EditableTextSegment> segments, boolean includeBackticks) {
        addGroupSegments(text, segments, Pattern.compile("(?m)^\\s*//(.*)$"), 1, SegmentType.COMMENT);
        addGroupSegments(text, segments, C_STYLE_BLOCK_COMMENT_PATTERN, 1, SegmentType.COMMENT);
        addGroupSegments(text, segments, DOUBLE_QUOTED_PATTERN, 1, SegmentType.STRING);
        addGroupSegments(text, segments, SINGLE_QUOTED_PATTERN, 1, SegmentType.STRING);
        if (includeBackticks) {
            addGroupSegments(text, segments, BACKTICK_QUOTED_PATTERN, 1, SegmentType.STRING);
        }
    }

    private static void extractSqlSegments(String text, List<EditableTextSegment> segments) {
        addGroupSegments(text, segments, Pattern.compile("(?m)^\\s*--(.*)$"), 1, SegmentType.COMMENT);
        addGroupSegments(text, segments, C_STYLE_BLOCK_COMMENT_PATTERN, 1, SegmentType.COMMENT);
        addGroupSegments(text, segments, SINGLE_QUOTED_PATTERN, 1, SegmentType.STRING);
    }

    private static void extractXmlSegments(String text, List<EditableTextSegment> segments) {
        addGroupSegments(text, segments, XML_COMMENT_PATTERN, 1, SegmentType.XML_COMMENT);
    }

    private static void addGroupSegments(
        String text,
        List<EditableTextSegment> segments,
        Pattern pattern,
        int groupIndex,
        SegmentType type) {

        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) {
            int start = matcher.start(groupIndex);
            int end = matcher.end(groupIndex);
            if (start < 0 || end <= start) {
                continue;
            }
            String originalText = text.substring(start, end);
            if (originalText.isBlank()) {
                continue;
            }
            if (type == SegmentType.STRING && looksLikeCodeOnlyString(originalText)) {
                continue;
            }
            segments.add(new EditableTextSegment(start, end, originalText, type));
        }
    }

    private static boolean looksLikeCodeOnlyString(String originalText) {
        String coreText = new EditableTextSegment(0, originalText != null ? originalText.length() : 0, originalText, SegmentType.STRING).coreText();
        if (coreText.isBlank()) {
            return true;
        }
        if (coreText.contains("$") || coreText.contains("${") || coreText.contains("%")) {
            return true;
        }
        return coreText.matches("^[~./A-Za-z0-9_-]+(/[~./A-Za-z0-9_.-]+)+$");
    }

    private static CommentFormat commentFormat(String normalizedLanguage) {
        return switch (normalizedLanguage != null ? normalizedLanguage.toLowerCase(Locale.ROOT) : "plain") {
            case "bash", "python", "perl", "ruby", "powershell", "dockerfile", "yaml", "properties" ->
                new CommentFormat("#", null, false);
            case "java", "javascript", "groovy" -> new CommentFormat("//", null, false);
            case "sql" -> new CommentFormat("--", null, false);
            case "xml", "html" -> new CommentFormat("<!--", "-->", true);
            default -> null;
        };
    }

    private record CommentFormat(String prefix, String suffix, boolean block) {
    }
}
