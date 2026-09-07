package de.kortty.core;

import de.kortty.core.SnippetCompletionIdioms.Idiom;
import de.kortty.core.SnippetCompletionSupport.ContextKind;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class SnippetCompletionIdiomsTest {

    @Test
    void tableStaysSmall() {
        assertThat(SnippetCompletionIdioms.ALL.size()).isAtMost(SnippetCompletionIdioms.MAX_ROWS);
        assertThat(SnippetCompletionIdioms.ALL).isNotEmpty();
    }

    @Test
    void placeholdersAreWellFormedAndNumberedFromOne() {
        for (Idiom idiom : SnippetCompletionIdioms.ALL) {
            String text = idiom.insertText();
            assertWithMessage(text).that(text).isNotEmpty();
            // Placeholder numbers form 1..k without gaps (a number may repeat for linked placeholders).
            Set<Integer> numbers = new TreeSet<>(SnippetCompletionIdioms.placeholderNumbers(text));
            int expected = 1;
            for (int number : numbers) {
                assertWithMessage(text).that(number).isEqualTo(expected++);
            }
            // Every "${" opens a placeholder the parser recognised, so no half-written placeholder survives.
            assertWithMessage(text).that(SnippetCompletionIdioms.placeholderNumbers(text).size())
                .isEqualTo(count(text, "${"));
            // Every dollar sign is a placeholder, an escaped literal, or followed by something Monaco
            // does not read as a variable name or tabstop ($( and $@ are fine, $args and $1 are not).
            for (int i = 0; i < text.length(); i++) {
                if (text.charAt(i) != '$') {
                    continue;
                }
                boolean escaped = i > 0 && text.charAt(i - 1) == '\\';
                boolean placeholder = i + 2 < text.length() && text.charAt(i + 1) == '{'
                    && Character.isDigit(text.charAt(i + 2));
                boolean literal = i + 1 >= text.length()
                    || !(Character.isLetterOrDigit(text.charAt(i + 1)) || text.charAt(i + 1) == '_'
                        || text.charAt(i + 1) == '{');
                assertWithMessage("dollar sign at " + i + " in " + text)
                    .that(escaped || placeholder || literal).isTrue();
            }
        }
    }

    @Test
    void rowsAreUnique() {
        Set<String> seen = new HashSet<>();
        for (Idiom idiom : SnippetCompletionIdioms.ALL) {
            String key = idiom.language() + "|" + idiom.kind() + "|" + idiom.module() + "|" + idiom.insertText();
            assertWithMessage(key).that(seen.add(key)).isTrue();
            assertThat(idiom.language()).isNotEmpty();
            assertThat(idiom.module()).isNotNull();
            if (idiom.kind() == ContextKind.USE_MODULE) {
                assertWithMessage(key).that(idiom.language()).isEqualTo("perl");
                assertWithMessage(key).that(idiom.module()).isNotEmpty();
            } else {
                assertWithMessage(key).that(idiom.module()).isEmpty();
            }
        }
    }

    @Test
    void posixRowsComeFirstInOrder() {
        assertThat(SnippetCompletionIdioms.forContext("perl", ContextKind.USE_MODULE, "POSIX"))
            .containsExactly("qw(strftime floor ceil)", "qw(strftime)", "qw(floor ceil)", "qw(:sys_wait_h)")
            .inOrder();
        // The very first USE_MODULE row of the table is the POSIX one the user asked for.
        Idiom firstModuleRow = SnippetCompletionIdioms.ALL.stream()
            .filter(idiom -> idiom.kind() == ContextKind.USE_MODULE)
            .findFirst()
            .orElseThrow();
        assertThat(firstModuleRow.module()).isEqualTo("POSIX");
        assertThat(firstModuleRow.insertText()).isEqualTo("qw(strftime floor ceil)");
        assertThat(SnippetCompletionIdioms.forContext("perl", ContextKind.USE_MODULE, "List::Util"))
            .contains("qw(first sum max min)");
    }

    @Test
    void unknownModuleGetsEmptyImportListSnippet() {
        assertThat(SnippetCompletionIdioms.forContext("perl", ContextKind.USE_MODULE, "Acme::Unknown"))
            .containsExactly("qw(${1})");
        assertThat(SnippetCompletionIdioms.forContext("perl", ContextKind.USE_MODULE, ""))
            .containsExactly("qw(${1})");
        assertThat(SnippetCompletionIdioms.forContext("perl", ContextKind.USE_MODULE, null))
            .containsExactly("qw(${1})");
    }

    @Test
    void yamlForInHasNoIdioms() {
        assertThat(SnippetCompletionIdioms.forContext("yaml", ContextKind.FOR_IN, "")).isEmpty();
        assertThat(SnippetCompletionIdioms.forContext("yaml", ContextKind.GENERIC, "")).isEmpty();
        assertThat(SnippetCompletionIdioms.forContext("bash", ContextKind.GENERIC, "")).isEmpty();
        assertThat(SnippetCompletionIdioms.forContext(null, ContextKind.FOR_IN, "")).isEmpty();
        assertThat(SnippetCompletionIdioms.forContext("bash", null, "")).isEmpty();
    }

    @Test
    void bashForInRowsStartWithPositionalParameters() {
        List<String> rows = SnippetCompletionIdioms.forContext("bash", ContextKind.FOR_IN, "");
        assertThat(rows.get(0)).isEqualTo("\"$@\"");
        assertThat(rows).contains("$(${1:command})");
        assertThat(rows).contains("{1..${1:10}}");
        assertThat(SnippetCompletionIdioms.forContext("powershell", ContextKind.FOR_IN, "")).contains("\\$args");
    }

    @Test
    void labelsResolvePlaceholdersAndEscapes() {
        assertThat(SnippetCompletionIdioms.label("$(${1:command})")).isEqualTo("$(command)");
        assertThat(SnippetCompletionIdioms.label("{1..${1:10}}")).isEqualTo("{1..10}");
        assertThat(SnippetCompletionIdioms.label("qw(${1})")).isEqualTo("qw(…)");
        assertThat(SnippetCompletionIdioms.label("\\$args")).isEqualTo("$args");
        assertThat(SnippetCompletionIdioms.label("split(/${1:,}/, \\$${2:line})")).isEqualTo("split(/,/, $line)");
        assertThat(SnippetCompletionIdioms.label("\"$@\"")).isEqualTo("\"$@\"");
        assertThat(SnippetCompletionIdioms.label("")).isEmpty();
        assertThat(SnippetCompletionIdioms.label(null)).isEmpty();
    }

    private static int count(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }
}
