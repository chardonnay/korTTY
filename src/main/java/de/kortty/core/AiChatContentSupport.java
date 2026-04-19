package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared parsing helpers for AI chat content.
 */
public final class AiChatContentSupport {

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```([\\w#+.-]*)\\n(.*?)```");

    private AiChatContentSupport() {
    }

    public static List<ContentSection> splitContent(String content) {
        List<ContentSection> sections = new ArrayList<>();
        String safeContent = content != null ? content : "";
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(safeContent);
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                sections.add(new ContentSection(false, null, safeContent.substring(lastEnd, matcher.start()).trim()));
            }
            sections.add(new ContentSection(true, matcher.group(1), matcher.group(2)));
            lastEnd = matcher.end();
        }
        if (lastEnd < safeContent.length()) {
            sections.add(new ContentSection(false, null, safeContent.substring(lastEnd).trim()));
        }
        if (sections.isEmpty()) {
            sections.add(new ContentSection(false, null, safeContent));
        }
        return sections;
    }

    public static List<StructuredTextBlock> splitStructuredText(String content) {
        List<StructuredTextBlock> blocks = new ArrayList<>();
        List<String> lines = List.of((content != null ? content : "").split("\\R", -1));
        StringBuilder paragraphBuffer = new StringBuilder();
        int index = 0;
        while (index < lines.size()) {
            if (isMarkdownTableHeader(lines, index)) {
                flushParagraph(blocks, paragraphBuffer);
                List<List<String>> tableRows = new ArrayList<>();
                tableRows.add(parseMarkdownTableRow(lines.get(index)));
                index += 2;
                while (index < lines.size() && isMarkdownTableRow(lines.get(index))) {
                    tableRows.add(parseMarkdownTableRow(lines.get(index)));
                    index++;
                }
                if (tableRows.size() >= 2) {
                    blocks.add(StructuredTextBlock.table(tableRows));
                }
                continue;
            }

            if (paragraphBuffer.length() > 0) {
                paragraphBuffer.append("\n");
            }
            paragraphBuffer.append(lines.get(index));
            index++;
        }
        flushParagraph(blocks, paragraphBuffer);
        return blocks;
    }

    private static void flushParagraph(List<StructuredTextBlock> blocks, StringBuilder paragraphBuffer) {
        String text = paragraphBuffer.toString().trim();
        if (!text.isEmpty()) {
            blocks.add(StructuredTextBlock.paragraph(text));
        }
        paragraphBuffer.setLength(0);
    }

    private static boolean isMarkdownTableHeader(List<String> lines, int index) {
        return index + 1 < lines.size()
            && isMarkdownTableRow(lines.get(index))
            && isMarkdownTableSeparator(lines.get(index + 1));
    }

    private static boolean isMarkdownTableRow(String line) {
        if (line == null) {
            return false;
        }
        String trimmed = line.trim();
        return trimmed.startsWith("|") && trimmed.endsWith("|") && trimmed.length() > 2;
    }

    private static boolean isMarkdownTableSeparator(String line) {
        if (!isMarkdownTableRow(line)) {
            return false;
        }
        for (String cell : parseMarkdownTableRow(line)) {
            String normalized = cell.replace(":", "").replace("-", "").trim();
            if (!normalized.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static List<String> parseMarkdownTableRow(String line) {
        String normalized = line != null ? line.trim() : "";
        if (normalized.startsWith("|")) {
            normalized = normalized.substring(1);
        }
        if (normalized.endsWith("|")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        String[] rawCells = normalized.split("\\|", -1);
        List<String> cells = new ArrayList<>(rawCells.length);
        for (String cell : rawCells) {
            cells.add(cell != null ? cell.trim() : "");
        }
        return cells;
    }

    public record ContentSection(boolean code, String language, String content) {
    }

    public record StructuredTextBlock(Type type, String text, List<List<String>> tableRows) {

        public enum Type {
            PARAGRAPH,
            TABLE
        }

        public static StructuredTextBlock paragraph(String text) {
            return new StructuredTextBlock(Type.PARAGRAPH, text, List.of());
        }

        public static StructuredTextBlock table(List<List<String>> tableRows) {
            return new StructuredTextBlock(Type.TABLE, null, List.copyOf(tableRows));
        }
    }
}
