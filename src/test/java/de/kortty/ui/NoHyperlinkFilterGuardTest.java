package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * korTTY draws OSC 8 links in SithTermFX's {@code HOVER_WITH_CUSTOM_COLOR} mode so that linked text
 * keeps its own colours. In that mode a vendor link filter would overwrite every cell it matches,
 * OSC 8 links included, with the vendor's link colours, and in the other modes its targets go stale
 * while a line is still being written. Links in plain text are therefore found on demand, and no
 * code in korTTY may register a filter: this scan of the main sources fails as soon as one does.
 */
public class NoHyperlinkFilterGuardTest {

    private static final Path MAIN_SOURCES = Path.of("src/main/java");

    /** Registering a filter on a widget or its text processing, or implementing one. */
    private static final Pattern FILTER_USE = Pattern.compile("\\baddHyperlinkFilter\\b|\\bHyperlinkFilter\\b");

    @Test
    public void noMainSourceRegistersOrImplementsAVendorLinkFilter() throws IOException {
        Map<Path, String> code = mainSourceCode();
        // Not vacuous: the scan sees the widget that installs korTTY's own OSC 8 link provider.
        assertThat(code).containsKey(MAIN_SOURCES.resolve("de/kortty/ui/KorttyTermWidget.java"));
        assertThat(code.get(MAIN_SOURCES.resolve("de/kortty/ui/KorttyTermWidget.java")))
            .contains("setLinkInfoProvider(");

        List<Path> offenders = code.entrySet().stream()
            .filter(entry -> FILTER_USE.matcher(entry.getValue()).find())
            .map(Map.Entry::getKey)
            .sorted()
            .toList();
        assertWithMessage("main sources that use a SithTermFX HyperlinkFilter").that(offenders).isEmpty();
    }

    @Test
    public void scanIgnoresCommentsAndStringsButNotCode() {
        String source = String.join("\r\n",
            "// widget.addHyperlinkFilter(filter) would break the colours",
            "/* implements HyperlinkFilter */",
            "/** {@code addHyperlinkFilter} */",
            "String hint = \"addHyperlinkFilter\";",
            "String block = \"\"\"",
            "    HyperlinkFilter \\\"\"\" still text",
            "    \"\"\";",
            "char quote = '\"'; String url = \"https://example.com/\"; int kept = 1;");

        String code = codeOnly(source);

        assertThat(FILTER_USE.matcher(code).find()).isFalse();
        assertThat(code).contains("int kept = 1;");
        assertThat(FILTER_USE.matcher(codeOnly("widget.addHyperlinkFilter(filter);")).find()).isTrue();
        assertThat(FILTER_USE.matcher(codeOnly("class F implements HyperlinkFilter {}")).find()).isTrue();
        assertThat(FILTER_USE.matcher(codeOnly("String u = \"https://x\"; widget.addHyperlinkFilter(f);")).find())
            .isTrue();
    }

    private static Map<Path, String> mainSourceCode() throws IOException {
        try (Stream<Path> files = Files.walk(MAIN_SOURCES)) {
            return files.filter(path -> path.toString().endsWith(".java"))
                .collect(Collectors.toMap(path -> path, path -> {
                    try {
                        return codeOnly(Files.readString(path, StandardCharsets.UTF_8));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }));
        }
    }

    /**
     * The Java source with comments and the contents of string, text-block and character literals
     * blanked out, so that only code is searched. Line breaks are kept; CRLF sources work unchanged.
     */
    static String codeOnly(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        int n = source.length();
        while (i < n) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < n && source.charAt(i + 1) == '/') {
                while (i < n && source.charAt(i) != '\n' && source.charAt(i) != '\r') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? n : end + 2;
                out.append(' ');
            } else if (source.startsWith("\"\"\"", i)) {
                i = skipLiteral(source, i + 3, "\"\"\"");
                out.append("\"\"");
            } else if (c == '"' || c == '\'') {
                i = skipLiteral(source, i + 1, String.valueOf(c));
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    /** The index after the {@code close} that ends a literal starting at {@code from}, honouring escapes. */
    private static int skipLiteral(String source, int from, String close) {
        int i = from;
        while (i < source.length()) {
            if (source.charAt(i) == '\\') {
                i += 2;
            } else if (source.startsWith(close, i)) {
                return i + close.length();
            } else {
                i++;
            }
        }
        return source.length();
    }
}
