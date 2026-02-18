package de.kortty.core;

import javax.xml.XMLConstants;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.stream.StreamResult;
import javax.xml.transform.stream.StreamSource;
import java.io.StringReader;
import java.io.StringWriter;

/**
 * Built-in code formatter for snippet content.
 * Supports: xml, json, yml/yaml, toml, ini, shell/bash, perl, python, ruby, java, javascript, groovy, html.
 */
public final class SnippetCodeFormatter {

    private static final int JSON_INDENT = 2;
    private static final int DEFAULT_INDENT = 4;

    /**
     * Formats the given text according to the language.
     *
     * @param text     snippet content
     * @param language language key (e.g. "json", "xml", "yaml", "bash")
     * @return formatted text, or null if formatting is not supported or failed
     */
    public static String format(String text, String language) {
        if (text == null || text.isBlank()) return null;
        if (language == null) language = "plain";
        String lang = language.toLowerCase();

        return switch (lang) {
            case "json" -> formatJson(text);
            case "xml", "html" -> formatXml(text, lang);
            case "yaml", "yml" -> formatYaml(text);
            case "toml" -> formatToml(text);
            case "ini", "properties" -> formatIni(text);
            case "bash", "shell" -> normalizeIndent(text, 2);
            case "perl", "python", "ruby", "java", "javascript", "groovy" -> normalizeIndent(text, DEFAULT_INDENT);
            default -> null;
        };
    }

    /**
     * Returns whether the given language is supported for formatting.
     */
    public static boolean isSupported(String language) {
        if (language == null) return false;
        String lang = language.toLowerCase();
        return switch (lang) {
            case "json", "xml", "html", "yaml", "yml", "toml", "ini", "properties",
                 "bash", "shell", "perl", "python", "ruby", "java", "javascript", "groovy" -> true;
            default -> false;
        };
    }

    private static String formatJson(String text) {
        try {
            return prettyPrintJson(text.trim());
        } catch (Exception e) {
            return null;
        }
    }

    private static String prettyPrintJson(String input) {
        StringBuilder out = new StringBuilder();
        int indent = 0;
        boolean inString = false;
        char stringChar = 0;
        boolean escaped = false;
        int i = 0;
        while (i < input.length()) {
            char c = input.charAt(i);
            if (inString) {
                if (escaped) {
                    out.append(c);
                    escaped = false;
                } else if (c == '\\') {
                    out.append(c);
                    escaped = true;
                } else if (c == stringChar) {
                    out.append(c);
                    inString = false;
                } else {
                    out.append(c);
                }
                i++;
                continue;
            }
            switch (c) {
                case '"' -> {
                    inString = true;
                    stringChar = c;
                    out.append(c);
                    i++;
                }
                case '{', '[' -> {
                    out.append(c);
                    indent += JSON_INDENT;
                    i++;
                    if (i < input.length() && !isSpace(input.charAt(i))) {
                        out.append('\n');
                        out.append(" ".repeat(indent));
                    }
                }
                case '}', ']' -> {
                    indent -= JSON_INDENT;
                    if (indent < 0) indent = 0;
                    if (out.length() > 0 && out.charAt(out.length() - 1) != ',' && out.charAt(out.length() - 1) != '{' && out.charAt(out.length() - 1) != '[') {
                        out.append('\n');
                        out.append(" ".repeat(indent));
                    }
                    out.append(c);
                    i++;
                }
                case ',' -> {
                    out.append(c);
                    out.append('\n');
                    out.append(" ".repeat(indent));
                    i++;
                }
                case ':' -> {
                    out.append(c);
                    if (i + 1 < input.length() && input.charAt(i + 1) != ' ') {
                        out.append(' ');
                    }
                    i++;
                }
                default -> {
                    if (!Character.isWhitespace(c) || (out.length() > 0 && !Character.isWhitespace(out.charAt(out.length() - 1)))) {
                        out.append(c);
                    }
                    i++;
                }
            }
        }
        return out.toString();
    }

    private static boolean isSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private static String formatXml(String text, String language) {
        try {
            String trimmed = text.trim();
            boolean wrapHtml = "html".equals(language)
                    && !trimmed.toLowerCase().startsWith("<!doctype")
                    && !trimmed.toLowerCase().startsWith("<?xml")
                    && !trimmed.toLowerCase().startsWith("<html");
            if (wrapHtml) {
                trimmed = "<_root>" + trimmed + "</_root>";
            }
            TransformerFactory tf = TransformerFactory.newInstance();
            tf.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            tf.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer t = tf.newTransformer();
            t.setOutputProperty(OutputKeys.INDENT, "yes");
            t.setOutputProperty("{http://xml.apache.org/xslt}indent-amount", "2");
            t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter writer = new StringWriter();
            t.transform(new StreamSource(new StringReader(trimmed)), new StreamResult(writer));
            String result = writer.toString();
            if (wrapHtml && result.contains("<_root>")) {
                result = result.replace("<_root>", "").replace("</_root>", "").trim();
            }
            return result;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Simple YAML formatter: normalize indentation to 2 spaces, trim lines, collapse multiple blank lines.
     */
    private static String formatYaml(String text) {
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        int prevIndent = -1;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                continue;
            }
            int spaces = 0;
            while (spaces < line.length() && (line.charAt(spaces) == ' ' || line.charAt(spaces) == '\t')) {
                spaces++;
            }
            int indent = spaces;
            if (indent % 2 != 0) indent = (indent / 2 + 1) * 2;
            indent = Math.min(indent, prevIndent + 2);
            if (trimmed.startsWith("-") || trimmed.startsWith("#")) {
                // list item or comment: keep relative indent
            } else if (prevIndent >= 0 && indent > prevIndent + 2) {
                indent = prevIndent + 2;
            }
            prevIndent = trimmed.isEmpty() ? prevIndent : indent;
            out.append(" ".repeat(indent)).append(trimmed).append('\n');
        }
        return out.toString().trim();
    }

    /**
     * TOML formatter: normalize [sections], key = "value", blank line between sections.
     */
    private static String formatToml(String text) {
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        boolean first = true;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                if (!first && out.length() > 0 && out.charAt(out.length() - 1) != '\n') {
                    out.append('\n');
                }
                continue;
            }
            if (trimmed.startsWith("[")) {
                if (!first) out.append('\n');
                out.append(trimmed).append('\n');
                first = false;
                continue;
            }
            if (trimmed.startsWith("#")) {
                out.append(trimmed).append('\n');
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                out.append(key).append(" = ").append(value).append('\n');
            } else {
                out.append(trimmed).append('\n');
            }
            first = false;
        }
        return out.toString().trim();
    }

    /**
     * INI / properties formatter: [section] then key=value, blank line between sections.
     */
    private static String formatIni(String text) {
        String[] lines = text.split("\n");
        StringBuilder out = new StringBuilder();
        boolean needNewline = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                needNewline = true;
                continue;
            }
            if (needNewline && out.length() > 0) out.append('\n');
            needNewline = false;
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                out.append(trimmed).append('\n');
                continue;
            }
            if (trimmed.startsWith("#") || trimmed.startsWith(";")) {
                out.append(trimmed).append('\n');
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                String key = trimmed.substring(0, eq).trim();
                String value = trimmed.substring(eq + 1).trim();
                out.append(key).append(" = ").append(value).append('\n');
            } else {
                out.append(trimmed).append('\n');
            }
        }
        return out.toString().trim();
    }

    /**
     * Normalize indentation: tabs to spaces, consistent indent step (e.g. 2 or 4).
     */
    private static String normalizeIndent(String text, int indentSize) {
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int col = 0;
            while (col < line.length() && (line.charAt(col) == ' ' || line.charAt(col) == '\t')) {
                col++;
            }
            int indent = 0;
            for (int k = 0; k < col; k++) {
                if (line.charAt(k) == '\t') indent += indentSize;
                else indent++;
            }
            indent = (indent / indentSize) * indentSize;
            String content = line.substring(col);
            out.append(" ".repeat(indent)).append(content);
            if (i < lines.length - 1) out.append('\n');
        }
        return out.toString();
    }

    private SnippetCodeFormatter() {}
}
