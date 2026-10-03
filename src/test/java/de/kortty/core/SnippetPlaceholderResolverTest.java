package de.kortty.core;

import org.testng.annotations.Test;

import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static com.google.common.truth.Truth.assertThat;

class SnippetPlaceholderResolverTest {

    private static final Map<String, String> BUILT_INS = Map.of(
        "date", "2026-10-02",
        "time", "12:34:56",
        "datetime", "2026-10-02 12:34:56",
        "hostname", "workstation",
        "username", "alice",
        "clipboard", "copied");

    private static SnippetPlaceholderResolver.Declarations declared(String... names) {
        Set<String> set = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        set.addAll(java.util.List.of(names));
        return set::contains;
    }

    private static String resolve(String content, Map<String, String> values, SnippetPlaceholderResolver.Declarations declarations) {
        return SnippetPlaceholderResolver.resolve(content, BUILT_INS, values, declarations).text();
    }

    @Test
    void leavesUndeclaredShellExpansionsVerbatim() {
        String content = "echo \"${HOME}/x ${1:-d} ${#a[@]} ${v%%.*} ${target}\"";

        assertThat(resolve(content, Map.of(), SnippetPlaceholderResolver.Declarations.none())).isEqualTo(content);
    }

    @Test
    void substitutesDeclaredCaseInsensitively() {
        String resolved = resolve("cd ${Target} && ls ${TARGET}", Map.of("target", "/srv"), declared("target"));

        assertThat(resolved).isEqualTo("cd /srv && ls /srv");
    }

    @Test
    void builtInsUseInjectedValues() {
        String resolved = resolve("${date} ${time} ${datetime} ${hostname} ${username} ${clipboard}", Map.of(), null);

        assertThat(resolved).isEqualTo("2026-10-02 12:34:56 2026-10-02 12:34:56 workstation alice copied");
    }

    @Test
    void builtInsAreCaseSensitive() {
        assertThat(resolve("${DATE}", Map.of(), null)).isEqualTo("${DATE}");
    }

    @Test
    void builtInWinsOverDeclaredSameName() {
        String resolved = resolve("${date}", Map.of("date", "declared"), declared("date"));

        assertThat(resolved).isEqualTo("2026-10-02");
    }

    @Test
    void escapeKeepsLiteralForBuiltInAndDeclared() {
        String resolved = resolve("$${date} $${target} $${HOME} $${cursor} ${target}", Map.of("target", "/srv"), declared("target"));

        assertThat(resolved).isEqualTo("${date} ${target} ${HOME} ${cursor} /srv");
        // A literal $${name} (Makefile, Compose file) is written with one more dollar.
        assertThat(resolve("$$${HOME} $$${target}", Map.of("target", "/srv"), declared("target")))
            .isEqualTo("$${HOME} $${target}");
    }

    @Test
    void cursorOffsetMeasuredAfterSubstitution() {
        SnippetPlaceholderResolver.ResolvedSnippet resolved = SnippetPlaceholderResolver.resolve(
            "a${target}b${cursor}c", BUILT_INS, Map.of("target", "XYZ"), declared("target"));

        assertThat(resolved.text()).isEqualTo("aXYZbc");
        assertThat(resolved.cursorOffset()).isEqualTo(5);
    }

    @Test
    void onlyTheFirstCursorCountsAndEveryMarkerIsRemoved() {
        SnippetPlaceholderResolver.ResolvedSnippet resolved = SnippetPlaceholderResolver.resolve(
            "ab${cursor}cd${cursor}", BUILT_INS, Map.of(), null);

        assertThat(resolved.text()).isEqualTo("abcd");
        assertThat(resolved.cursorOffset()).isEqualTo(2);
        assertThat(SnippetPlaceholderResolver.resolve("no marker", BUILT_INS, Map.of(), null).cursorOffset())
            .isEqualTo(-1);
    }

    @Test
    void insertedValuesAreNotReScanned() {
        String resolved = resolve("${a} ${b}", Map.of("a", "${b} ${date}", "b", "$${x}"), declared("a", "b"));

        assertThat(resolved).isEqualTo("${b} ${date} $${x}");
    }

    @Test
    void innerPlaceholderOfANestedShellExpansionIsResolved() {
        String resolved = resolve("${dir:-${target}} ${dir:-x}", Map.of("target", "/srv"), declared("target"));

        assertThat(resolved).isEqualTo("${dir:-/srv} ${dir:-x}");
    }

    @Test
    void declaredVariablesInOrderWithoutDuplicatesOrBuiltIns() {
        String content = "${b} ${date} ${A} ${cursor} ${B} $${c} ${c:-x} ${undeclared} ${a}";

        assertThat(SnippetPlaceholderResolver.declaredVariables(content, declared("a", "b", "c", "date")))
            .containsExactly("b", "A").inOrder();
    }

    @Test
    void declaredWithoutSuppliedValueStaysVerbatim() {
        assertThat(resolve("echo ${ticket}", Map.of(), declared("ticket"))).isEqualTo("echo ${ticket}");
        // A value supplied for a name that is not declared is not used either.
        assertThat(resolve("echo ${ticket}", Map.of("ticket", "T-1"), null)).isEqualTo("echo ${ticket}");
    }

    @Test
    void emptySuppliedValueReplacesWithNothing() {
        assertThat(resolve("[${ticket}]", Map.of("ticket", ""), declared("ticket"))).isEqualTo("[]");
    }

    @Test
    void undeclaredSimpleNamesSkipShellFormsEnvironmentNamesAndEscapes() {
        String content = "${target} ${HOME} ${USER} ${PATH} ${PWD} ${1} ${@} ${_} ${1:-x} ${#a[@]} ${v%%.*}"
            + " $${escaped} ${date} ${cursor} ${declared} ${target} ${other_1} ${_tmp}";

        assertThat(SnippetPlaceholderResolver.undeclaredSimpleNames(content, declared("declared")))
            .containsExactly("target", "other_1", "_tmp").inOrder();
        assertThat(SnippetPlaceholderResolver.undeclaredSimpleNames("${HOSTNAME}", null))
            .containsExactly("HOSTNAME");
    }

    @Test
    void referencedBuiltInsListsOnlyUnescapedBuiltIns() {
        assertThat(SnippetPlaceholderResolver.referencedBuiltIns("${date} $${clipboard} ${hostname} ${date} ${x}"))
            .containsExactly("date", "hostname").inOrder();
    }

    @Test
    void nullAndEmptyContentResolveToEmptyText() {
        assertThat(SnippetPlaceholderResolver.resolve(null, BUILT_INS, Map.of(), null).text()).isEmpty();
        assertThat(SnippetPlaceholderResolver.resolve("", BUILT_INS, Map.of(), null).cursorOffset()).isEqualTo(-1);
        assertThat(SnippetPlaceholderResolver.declaredVariables(null, declared("a"))).isEmpty();
        assertThat(SnippetPlaceholderResolver.undeclaredSimpleNames(null, null)).isEmpty();
    }
}
