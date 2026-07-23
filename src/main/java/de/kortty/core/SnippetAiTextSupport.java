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
    // Unrolled-loop form of (?:[^X\\]|\\.)* — same language, but plain characters are consumed by
    // a single possessive class instead of one recursive alternation step per character, which kept
    // very long string literals from overflowing the regex engine's stack.
    private static final Pattern DOUBLE_QUOTED_PATTERN = Pattern.compile("\"([^\"\\\\]*+(?:\\\\.[^\"\\\\]*+)*+)\"");
    private static final Pattern SINGLE_QUOTED_PATTERN = Pattern.compile("'([^'\\\\]*+(?:\\\\.[^'\\\\]*+)*+)'");
    private static final Pattern BACKTICK_QUOTED_PATTERN = Pattern.compile("`([^`\\\\]*+(?:\\\\.[^`\\\\]*+)*+)`");
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

    /**
     * Extracts the editable parts of a selection while parsing the complete snippet for syntax context.
     * Returned offsets are relative to the selected text so callers can apply replacements directly to
     * that selection. This also recognizes selections that start and end inside a comment or string.
     */
    public static List<EditableTextSegment> extractEditableSegments(
        String fullContent,
        int selectionStart,
        int selectionEnd,
        String language) {

        String content = fullContent != null ? fullContent : "";
        int safeStart = Math.max(0, Math.min(selectionStart, content.length()));
        int safeEnd = Math.max(safeStart, Math.min(selectionEnd, content.length()));
        if (safeStart == safeEnd) {
            return List.of();
        }
        List<EditableTextSegment> selectedSegments = new ArrayList<>();
        for (EditableTextSegment segment : extractEditableSegments(content, language)) {
            int overlapStart = Math.max(safeStart, segment.start());
            int overlapEnd = Math.min(safeEnd, segment.end());
            if (overlapStart >= overlapEnd) {
                continue;
            }
            String selectedPart = content.substring(overlapStart, overlapEnd);
            if (selectedPart.isBlank()) {
                continue;
            }
            selectedSegments.add(new EditableTextSegment(
                overlapStart - safeStart,
                overlapEnd - safeStart,
                selectedPart,
                segment.type()));
        }
        return selectedSegments;
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
        return formatDescriptionAsComment(description, language, indent, DEFAULT_DESCRIPTION_WRAP_WIDTH);
    }

    public static String formatDescriptionAsComment(String description, String language, String indent, int maxLineLength) {
        CommentFormat format = commentFormat(SnippetLanguageSupport.normalizeSnippetLanguage(language));
        String indentation = indent != null ? indent : "";
        int contentWidth = commentContentWidth(format, indentation, maxLineLength);
        String normalizedDescription = wrapDescriptionText(description, contentWidth);
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

    private static int commentContentWidth(CommentFormat format, String indentation, int maxLineLength) {
        if (format == null) {
            return maxLineLength;
        }
        String linePrefix = format.block()
            ? indentation + " "
            : indentation + format.prefix() + " ";
        return Math.max(20, maxLineLength - linePrefix.length());
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
        if (collapsed.isEmpty()) {
            return collapsed;
        }
        List<String> sentenceLines = splitSentencesAfterPeriods(collapsed);
        if (sentenceLines.size() > 1) {
            List<String> wrappedSentences = new ArrayList<>();
            for (String sentence : sentenceLines) {
                wrappedSentences.add(wrapSingleLine(sentence, width));
            }
            return String.join("\n", wrappedSentences);
        }
        if (collapsed.length() <= width) {
            return collapsed;
        }
        return wrapSingleLine(collapsed, width);
    }

    private static String wrapSingleLine(String text, int width) {
        StringBuilder builder = new StringBuilder();
        int lineLength = 0;
        for (String word : text.split("\\s+")) {
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

    private static List<String> splitSentencesAfterPeriods(String text) {
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < text.length(); index++) {
            if (text.charAt(index) != '.') {
                continue;
            }
            int next = index + 1;
            if (next >= text.length() || !Character.isWhitespace(text.charAt(next))) {
                continue;
            }
            int following = next;
            while (following < text.length() && Character.isWhitespace(text.charAt(following))) {
                following++;
            }
            if (following >= text.length()) {
                continue;
            }
            String candidate = text.substring(start, index + 1).trim();
            if (!candidate.isBlank() && !looksLikeShortAbbreviation(candidate)) {
                sentences.add(candidate);
                start = following;
            }
        }
        String tail = text.substring(start).trim();
        if (!tail.isBlank()) {
            sentences.add(tail);
        }
        return sentences;
    }

    private static boolean looksLikeShortAbbreviation(String sentenceCandidate) {
        int lastSpace = sentenceCandidate.lastIndexOf(' ');
        String lastToken = sentenceCandidate.substring(lastSpace + 1);
        return lastToken.length() == 2 && lastToken.endsWith(".");
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
        return looksLikeFilesystemPath(coreText);
    }

    /**
     * Path heuristic ("~/backups/logs", "/usr/local/bin"): only path characters and at least two
     * segments. A linear scan replaces the earlier regex, whose overlapping character classes
     * (both containing {@code /}) allowed catastrophic backtracking on adversarial snippet text
     * (CodeQL java/redos).
     */
    private static boolean looksLikeFilesystemPath(String text) {
        boolean separatorSeen = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowed = c == '~' || c == '.' || c == '/' || c == '_' || c == '-'
                || (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!allowed) {
                return false;
            }
            // The first character may open an absolute path; a separator only counts once a
            // first segment exists, mirroring the old "prefix + (/segment)+" shape.
            if (c == '/' && i > 0) {
                separatorSeen = true;
            }
        }
        return separatorSeen;
    }

    private static CommentFormat commentFormat(String normalizedLanguage) {
        return switch (normalizedLanguage != null ? normalizedLanguage.toLowerCase(Locale.ROOT) : "plain") {
            case "bash", "python", "perl", "ruby", "powershell", "dockerfile", "yaml", "properties" ->
                new CommentFormat("#", null, false);
            case "java", "javascript", "typescript", "groovy" -> new CommentFormat("//", null, false);
            case "sql" -> new CommentFormat("--", null, false);
            case "xml", "html" -> new CommentFormat("<!--", "-->", true);
            default -> null;
        };
    }

    private record CommentFormat(String prefix, String suffix, boolean block) {
    }
}
