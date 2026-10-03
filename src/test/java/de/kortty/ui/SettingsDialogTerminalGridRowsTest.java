package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;

/**
 * Regression guard for the row layout of the Settings &rarr; Terminal tab. New sections land in the
 * middle of that grid, and with hard-coded row indices every insert had to renumber all rows below
 * it; a missed one put two controls on the same row without any error. The rows therefore come
 * from one counter, as on the Window tab.
 *
 * <p>Building the dialog needs a JavaFX toolkit, so, like {@link AiSelectionRedactionWiringTest},
 * this test reads the source: every {@code terminalGrid.add} takes its row from the counter, a
 * label in column 0 that keeps the row shares it with exactly one control in column 1, and nothing
 * but the declaration assigns the counter.</p>
 */
class SettingsDialogTerminalGridRowsTest {

    private static final Path SETTINGS_DIALOG = Path.of("src/main/java/de/kortty/ui/SettingsDialog.java");
    private static final String ADD_CALL = "terminalGrid.add(";
    private static final String ROW = "terminalRow";
    private static final String NEXT_ROW = "terminalRow++";

    @Test
    void everyRowOfTheTerminalGridComesFromTheCounter() throws IOException {
        List<List<String>> calls = addCalls(source());
        assertThat(calls).isNotEmpty();

        for (int i = 0; i < calls.size(); i++) {
            List<String> args = calls.get(i);
            assertThat(args.size()).isAnyOf(3, 5);
            String row = args.get(2);
            assertThat(row).isAnyOf(ROW, NEXT_ROW);
            if (args.size() == 5) {
                // A node spanning both columns owns its row.
                assertThat(row).isEqualTo(NEXT_ROW);
                continue;
            }
            if (row.equals(ROW)) {
                // The label of a label + control row; the control right after it closes the row.
                assertThat(args.get(1)).isEqualTo("0");
                assertThat(i + 1).isLessThan(calls.size());
                List<String> control = calls.get(i + 1);
                assertThat(control).hasSize(3);
                assertThat(control.get(1)).isEqualTo("1");
                assertThat(control.get(2)).isEqualTo(NEXT_ROW);
            }
        }
    }

    @Test
    void onlyTheDeclarationAssignsTheCounter() throws IOException {
        String code = source();
        assertThat(code).contains("int terminalRow = 0;");
        Matcher writes = Pattern.compile("\\bterminalRow\\s*(?:\\+=|-=|--|=(?!=))").matcher(code);
        int count = 0;
        while (writes.find()) {
            count++;
        }
        assertThat(count).isEqualTo(1);
    }

    /** SettingsDialog with comments and string literals removed; CRLF-safe. */
    private static String source() throws IOException {
        return stripCommentsAndStrings(Files.readString(SETTINGS_DIALOG, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    /** The top-level arguments of every {@code terminalGrid.add(...)} call, trimmed. */
    private static List<List<String>> addCalls(String code) {
        List<List<String>> calls = new ArrayList<>();
        int at = code.indexOf(ADD_CALL);
        while (at >= 0) {
            List<String> args = new ArrayList<>();
            int depth = 0;
            int argStart = at + ADD_CALL.length();
            int i = argStart;
            for (; i < code.length(); i++) {
                char c = code.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    if (depth == 0) {
                        break;
                    }
                    depth--;
                } else if (c == ',' && depth == 0) {
                    args.add(code.substring(argStart, i).trim());
                    argStart = i + 1;
                }
            }
            args.add(code.substring(argStart, i).trim());
            calls.add(args);
            at = code.indexOf(ADD_CALL, i);
        }
        return calls;
    }

    private static String stripCommentsAndStrings(String source) {
        StringBuilder out = new StringBuilder(source.length());
        int i = 0;
        while (i < source.length()) {
            char c = source.charAt(i);
            if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '/') {
                while (i < source.length() && source.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < source.length() && source.charAt(i + 1) == '*') {
                int end = source.indexOf("*/", i + 2);
                i = end < 0 ? source.length() : end + 2;
            } else if (c == '"' || c == '\'') {
                i++;
                while (i < source.length() && source.charAt(i) != c) {
                    i += source.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
                out.append(c).append(c);
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }
}
