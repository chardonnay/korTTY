package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Conservative built-in preview renderer for snippet markup languages.
 * It escapes user content and renders a practical subset without scripts or external assets.
 */
public final class SnippetMarkupPreviewRenderer {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+)$");
    private static final Pattern ASCIIDOCTOR_HEADING = Pattern.compile("^(={1,6})\\s+(.+)$");
    private static final Pattern MARKDOWN_UNORDERED = Pattern.compile("^[-*+]\\s+(.+)$");
    private static final Pattern MARKDOWN_ORDERED = Pattern.compile("^\\d+[.)]\\s+(.+)$");
    private static final Pattern ASCIIDOCTOR_UNORDERED = Pattern.compile("^\\*+\\s+(.+)$");
    private static final Pattern ASCIIDOCTOR_ORDERED = Pattern.compile("^\\.+\\s+(.+)$");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern MARKDOWN_BOLD = Pattern.compile("\\*\\*([^*]+)\\*\\*");
    private static final Pattern MARKDOWN_ITALIC = Pattern.compile("(?<!\\*)\\*([^*]+)\\*(?!\\*)");
    private static final Pattern ASCIIDOCTOR_BOLD = Pattern.compile("\\*([^*]+)\\*");
    private static final Pattern ASCIIDOCTOR_ITALIC = Pattern.compile("_([^_]+)_");

    private SnippetMarkupPreviewRenderer() {
    }

    public static boolean supports(String language) {
        String normalized = SnippetLanguageSupport.normalizeSnippetLanguage(language);
        return "markdown".equals(normalized) || "asciidoctor".equals(normalized);
    }

    public static String renderHtml(String language, String content) {
        String normalized = SnippetLanguageSupport.normalizeSnippetLanguage(language);
        String body = "asciidoctor".equals(normalized)
            ? renderAsciidoctorBody(content)
            : renderMarkdownBody(content);
        return """
            <!doctype html>
            <html>
            <head>
              <meta charset="UTF-8">
              <style>
                :root { color-scheme: dark; }
                body {
                  margin: 0;
                  padding: 18px 22px;
                  background: #111827;
                  color: #E5E7EB;
                  font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                  font-size: 14px;
                  line-height: 1.55;
                }
                h1, h2, h3, h4, h5, h6 {
                  color: #F9FAFB;
                  line-height: 1.25;
                  margin: 1.2em 0 0.45em;
                  padding-bottom: 0.15em;
                }
                h1 { font-size: 1.85em; border-bottom: 1px solid #374151; }
                h2 { font-size: 1.45em; border-bottom: 1px solid #374151; }
                h3 { font-size: 1.2em; }
                p { margin: 0.65em 0; }
                ul, ol { margin: 0.6em 0 0.8em 1.45em; padding: 0; }
                li { margin: 0.25em 0; }
                blockquote {
                  margin: 0.8em 0;
                  padding: 0.35em 0.9em;
                  color: #CBD5E1;
                  border-left: 3px solid #10B981;
                  background: rgba(16, 185, 129, 0.08);
                }
                pre {
                  overflow-x: auto;
                  margin: 0.8em 0;
                  padding: 0.9em 1em;
                  border: 1px solid #334155;
                  border-radius: 6px;
                  background: #0B1120;
                }
                code {
                  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
                  color: #BAE6FD;
                }
                :not(pre) > code {
                  padding: 0.1em 0.3em;
                  border-radius: 4px;
                  background: rgba(148, 163, 184, 0.18);
                }
                table {
                  border-collapse: collapse;
                  margin: 0.9em 0;
                  width: 100%;
                }
                th, td {
                  border: 1px solid #334155;
                  padding: 0.4em 0.55em;
                  vertical-align: top;
                }
                th {
                  background: rgba(16, 185, 129, 0.15);
                  color: #F9FAFB;
                  text-align: left;
                }
                hr { border: 0; border-top: 1px solid #374151; margin: 1.1em 0; }
              </style>
            </head>
            <body>
            """ + body + """
            </body>
            </html>
            """;
    }

    static String renderMarkdownBody(String content) {
        String[] lines = normalizeLines(content);
        StringBuilder html = new StringBuilder();
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                index++;
                continue;
            }

            if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                index = appendFencedCode(html, lines, index, trimmed.substring(0, 3));
                continue;
            }

            Matcher heading = MARKDOWN_HEADING.matcher(trimmed);
            if (heading.matches()) {
                int level = Math.min(6, heading.group(1).length());
                appendBlock(html, "h" + level, renderMarkdownInline(heading.group(2).trim()));
                index++;
                continue;
            }

            if (isHorizontalRule(trimmed)) {
                html.append("<hr>\n");
                index++;
                continue;
            }

            if (isMarkdownTable(lines, index)) {
                index = appendMarkdownTable(html, lines, index);
                continue;
            }

            Matcher unordered = MARKDOWN_UNORDERED.matcher(trimmed);
            if (unordered.matches()) {
                index = appendList(html, lines, index, MARKDOWN_UNORDERED, "ul", true);
                continue;
            }

            Matcher ordered = MARKDOWN_ORDERED.matcher(trimmed);
            if (ordered.matches()) {
                index = appendList(html, lines, index, MARKDOWN_ORDERED, "ol", true);
                continue;
            }

            if (trimmed.startsWith(">")) {
                index = appendBlockquote(html, lines, index);
                continue;
            }

            index = appendParagraph(html, lines, index, true);
        }
        return html.toString();
    }

    static String renderAsciidoctorBody(String content) {
        String[] lines = normalizeLines(content);
        StringBuilder html = new StringBuilder();
        int index = 0;
        while (index < lines.length) {
            String line = lines[index];
            String trimmed = line.trim();
            if (trimmed.isEmpty() || isAsciiAttributeLine(trimmed)) {
                index++;
                continue;
            }

            if (trimmed.startsWith("[source") || trimmed.startsWith("[listing")) {
                index++;
                continue;
            }

            if ("----".equals(trimmed) || "....".equals(trimmed)) {
                index = appendDelimitedCode(html, lines, index, trimmed);
                continue;
            }

            Matcher heading = ASCIIDOCTOR_HEADING.matcher(trimmed);
            if (heading.matches()) {
                int level = Math.min(6, heading.group(1).length());
                appendBlock(html, "h" + level, renderAsciidoctorInline(heading.group(2).trim()));
                index++;
                continue;
            }

            Matcher unordered = ASCIIDOCTOR_UNORDERED.matcher(trimmed);
            if (unordered.matches()) {
                index = appendList(html, lines, index, ASCIIDOCTOR_UNORDERED, "ul", false);
                continue;
            }

            Matcher ordered = ASCIIDOCTOR_ORDERED.matcher(trimmed);
            if (ordered.matches()) {
                index = appendList(html, lines, index, ASCIIDOCTOR_ORDERED, "ol", false);
                continue;
            }

            index = appendParagraph(html, lines, index, false);
        }
        return html.toString();
    }

    private static int appendFencedCode(StringBuilder html, String[] lines, int startIndex, String fence) {
        StringBuilder code = new StringBuilder();
        int index = startIndex + 1;
        while (index < lines.length) {
            String trimmed = lines[index].trim();
            if (trimmed.startsWith(fence)) {
                index++;
                break;
            }
            code.append(lines[index]).append('\n');
            index++;
        }
        appendCodeBlock(html, code.toString());
        return index;
    }

    private static int appendDelimitedCode(StringBuilder html, String[] lines, int startIndex, String delimiter) {
        StringBuilder code = new StringBuilder();
        int index = startIndex + 1;
        while (index < lines.length) {
            if (delimiter.equals(lines[index].trim())) {
                index++;
                break;
            }
            code.append(lines[index]).append('\n');
            index++;
        }
        appendCodeBlock(html, code.toString());
        return index;
    }

    private static int appendList(
        StringBuilder html,
        String[] lines,
        int startIndex,
        Pattern itemPattern,
        String tag,
        boolean markdownInline) {

        html.append('<').append(tag).append(">\n");
        int index = startIndex;
        while (index < lines.length) {
            Matcher matcher = itemPattern.matcher(lines[index].trim());
            if (!matcher.matches()) {
                break;
            }
            String item = markdownInline
                ? renderMarkdownInline(matcher.group(1).trim())
                : renderAsciidoctorInline(matcher.group(1).trim());
            html.append("<li>").append(item).append("</li>\n");
            index++;
        }
        html.append("</").append(tag).append(">\n");
        return index;
    }

    private static int appendBlockquote(StringBuilder html, String[] lines, int startIndex) {
        StringBuilder quote = new StringBuilder();
        int index = startIndex;
        while (index < lines.length) {
            String trimmed = lines[index].trim();
            if (!trimmed.startsWith(">")) {
                break;
            }
            quote.append(trimmed.substring(1).trim()).append(' ');
            index++;
        }
        appendBlock(html, "blockquote", renderMarkdownInline(quote.toString().trim()));
        return index;
    }

    private static int appendParagraph(StringBuilder html, String[] lines, int startIndex, boolean markdownInline) {
        StringBuilder paragraph = new StringBuilder();
        int index = startIndex;
        while (index < lines.length) {
            String trimmed = lines[index].trim();
            if (trimmed.isEmpty() || startsNewBlock(lines, index, markdownInline)) {
                break;
            }
            if (!paragraph.isEmpty()) {
                paragraph.append(' ');
            }
            paragraph.append(trimmed);
            index++;
        }
        String body = markdownInline
            ? renderMarkdownInline(paragraph.toString())
            : renderAsciidoctorInline(paragraph.toString());
        appendBlock(html, "p", body);
        return index > startIndex ? index : startIndex + 1;
    }

    private static int appendMarkdownTable(StringBuilder html, String[] lines, int startIndex) {
        List<String> header = splitTableRow(lines[startIndex]);
        html.append("<table>\n<thead><tr>");
        for (String cell : header) {
            html.append("<th>").append(renderMarkdownInline(cell.trim())).append("</th>");
        }
        html.append("</tr></thead>\n<tbody>\n");

        int index = startIndex + 2;
        while (index < lines.length && lines[index].contains("|") && !lines[index].trim().isEmpty()) {
            html.append("<tr>");
            for (String cell : splitTableRow(lines[index])) {
                html.append("<td>").append(renderMarkdownInline(cell.trim())).append("</td>");
            }
            html.append("</tr>\n");
            index++;
        }
        html.append("</tbody>\n</table>\n");
        return index;
    }

    private static boolean startsNewBlock(String[] lines, int index, boolean markdown) {
        String trimmed = lines[index].trim();
        if (trimmed.isEmpty()) {
            return true;
        }
        if (markdown) {
            return trimmed.startsWith("```")
                || trimmed.startsWith("~~~")
                || MARKDOWN_HEADING.matcher(trimmed).matches()
                || MARKDOWN_UNORDERED.matcher(trimmed).matches()
                || MARKDOWN_ORDERED.matcher(trimmed).matches()
                || trimmed.startsWith(">")
                || isHorizontalRule(trimmed)
                || isMarkdownTable(lines, index);
        }
        return "----".equals(trimmed)
            || "....".equals(trimmed)
            || trimmed.startsWith("[source")
            || trimmed.startsWith("[listing")
            || ASCIIDOCTOR_HEADING.matcher(trimmed).matches()
            || ASCIIDOCTOR_UNORDERED.matcher(trimmed).matches()
            || ASCIIDOCTOR_ORDERED.matcher(trimmed).matches();
    }

    private static boolean isMarkdownTable(String[] lines, int index) {
        if (index + 1 >= lines.length) {
            return false;
        }
        String header = lines[index].trim();
        String separator = lines[index + 1].trim();
        return header.contains("|") && isMarkdownTableSeparator(separator);
    }

    private static boolean isMarkdownTableSeparator(String line) {
        if (!line.contains("|")) {
            return false;
        }
        String normalized = line.replace("|", "").replace(":", "").replace("-", "").trim();
        return normalized.isEmpty() && line.chars().filter(ch -> ch == '-').count() >= 3;
    }

    private static List<String> splitTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        List<String> cells = new ArrayList<>();
        for (String cell : trimmed.split("\\|", -1)) {
            cells.add(cell);
        }
        return cells;
    }

    private static boolean isHorizontalRule(String line) {
        String normalized = line.replace(" ", "");
        return normalized.length() >= 3
            && (normalized.chars().allMatch(ch -> ch == '-')
                || normalized.chars().allMatch(ch -> ch == '*')
                || normalized.chars().allMatch(ch -> ch == '_'));
    }

    private static boolean isAsciiAttributeLine(String line) {
        return line.length() > 2 && line.startsWith(":") && line.endsWith(":");
    }

    private static String renderMarkdownInline(String text) {
        String rendered = escapeHtml(text);
        rendered = INLINE_CODE.matcher(rendered).replaceAll("<code>$1</code>");
        rendered = MARKDOWN_BOLD.matcher(rendered).replaceAll("<strong>$1</strong>");
        rendered = MARKDOWN_ITALIC.matcher(rendered).replaceAll("<em>$1</em>");
        return rendered;
    }

    private static String renderAsciidoctorInline(String text) {
        String rendered = escapeHtml(text);
        rendered = INLINE_CODE.matcher(rendered).replaceAll("<code>$1</code>");
        rendered = ASCIIDOCTOR_BOLD.matcher(rendered).replaceAll("<strong>$1</strong>");
        rendered = ASCIIDOCTOR_ITALIC.matcher(rendered).replaceAll("<em>$1</em>");
        return rendered;
    }

    private static void appendBlock(StringBuilder html, String tag, String body) {
        html.append('<').append(tag).append('>').append(body).append("</").append(tag).append(">\n");
    }

    private static void appendCodeBlock(StringBuilder html, String code) {
        html.append("<pre><code>").append(escapeHtml(code.stripTrailing())).append("</code></pre>\n");
    }

    private static String[] normalizeLines(String content) {
        String normalized = content != null ? content : "";
        normalized = normalized.replace("\r\n", "\n").replace('\r', '\n');
        return normalized.split("\n", -1);
    }

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '&' -> escaped.append("&amp;");
                case '<' -> escaped.append("&lt;");
                case '>' -> escaped.append("&gt;");
                case '"' -> escaped.append("&quot;");
                case '\'' -> escaped.append("&#39;");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }
}
