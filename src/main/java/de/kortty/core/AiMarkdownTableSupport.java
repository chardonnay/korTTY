package de.kortty.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Helpers for markdown table rendering and clipboard serialization.
 */
public final class AiMarkdownTableSupport {

    private AiMarkdownTableSupport() {
    }

    public static RenderedMarkdownTable buildRenderedTable(List<List<String>> rawRows) {
        int columnCount = rawRows.stream().mapToInt(List::size).max().orElse(0);
        List<String> header = new ArrayList<>(columnCount);
        List<List<String>> rows = new ArrayList<>();
        for (int column = 0; column < columnCount; column++) {
            String headerValue = column < rawRows.getFirst().size() ? rawRows.getFirst().get(column) : "";
            header.add(stripInlineMarkdown(headerValue));
        }
        for (int rowIndex = 1; rowIndex < rawRows.size(); rowIndex++) {
            List<String> rawRow = rawRows.get(rowIndex);
            List<String> normalizedRow = new ArrayList<>(columnCount);
            for (int column = 0; column < columnCount; column++) {
                String value = column < rawRow.size() ? rawRow.get(column) : "";
                normalizedRow.add(stripInlineMarkdown(value));
            }
            rows.add(normalizedRow);
        }
        return new RenderedMarkdownTable(header, rows);
    }

    public static String toTsv(RenderedMarkdownTable table) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.join("\t", table.header()));
        for (List<String> row : table.rows()) {
            builder.append("\n").append(String.join("\t", row));
        }
        return builder.toString();
    }

    public static String toColumnText(RenderedMarkdownTable table, int columnIndex) {
        if (table == null) {
            return "";
        }
        if (columnIndex < 0 || columnIndex >= table.header().size()) {
            throw new IllegalArgumentException(
                "Column index " + columnIndex + " is out of bounds for header size " + table.header().size());
        }
        List<String> values = new ArrayList<>();
        values.add(table.header().get(columnIndex));
        for (List<String> row : table.rows()) {
            values.add(columnIndex < row.size() ? row.get(columnIndex) : "");
        }
        return String.join("\n", values);
    }

    public static String toCellText(RenderedMarkdownTable table, int rowIndex, int columnIndex) {
        if (table == null || rowIndex < 0 || rowIndex >= table.rows().size()) {
            return "";
        }
        List<String> row = table.rows().get(rowIndex);
        if (columnIndex < 0 || columnIndex >= row.size()) {
            return "";
        }
        return row.get(columnIndex);
    }

    public static boolean isBoldMarkdown(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.startsWith("**") && trimmed.endsWith("**") && trimmed.length() >= 4;
    }

    public static String stripInlineMarkdown(String value) {
        if (value == null) {
            return "";
        }
        return value
            .replace("**", "")
            .replace("`", "")
            .replace("\u202f", " ")
            .trim();
    }

    public static boolean isNumericLike(String value) {
        if (value == null) {
            return false;
        }
        String normalized = stripInlineMarkdown(value)
            .replace("%", "")
            .replace(",", ".")
            .replace(" ", "")
            .replace("(", "")
            .replace(")", "");
        return normalized.matches("[-+]?\\d+(?:\\.\\d+)?");
    }

    public record RenderedMarkdownTable(List<String> header, List<List<String>> rows) {
    }
}
