package de.kortty.core;

import de.kortty.core.SnippetAiResponseSupport.CompletionSuggestion;
import de.kortty.core.SnippetCompletionSupport.Candidate;
import de.kortty.core.SnippetCompletionSupport.CandidateKind;
import de.kortty.core.SnippetCompletionSupport.Context;
import de.kortty.core.SnippetCompletionSupport.ContextKind;
import de.kortty.core.SnippetCompletionSupport.PromptWindow;
import de.kortty.core.SnippetCompletionSupport.Symbols;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class SnippetCompletionSupportTest {

    // ---------------------------------------------------------------- bash

    @Test
    void bashForInOffersArraysAndHashesThenVariablesThenIdioms() {
        String text = """
            #!/usr/bin/env bash
            ARR=(a b c)
            declare -A MAP=([k]=v)
            NAME=value
            count=3
            usage() { echo; }
            for item in\s""";
        List<Candidate> list = SnippetCompletionSupport.localCandidates("bash", text, text.length(), 40);
        assertThat(inserts(list).subList(0, 5))
            .containsExactly("\"${ARR[@]}\"", "\"${!MAP[@]}\"", "\"$NAME\"", "\"$count\"", "\"$@\"")
            .inOrder();
        assertThat(inserts(list)).containsAtLeast("$(${1:command})", "*.${1:txt}", "{1..${1:10}}").inOrder();
        assertThat(inserts(list)).doesNotContain("usage");
        assertThat(list.get(0).kind()).isEqualTo(CandidateKind.ARRAY);
        assertThat(list.get(1).kind()).isEqualTo(CandidateKind.HASH);
        assertThat(list.get(2).kind()).isEqualTo(CandidateKind.VARIABLE);
        assertThat(list.get(4).kind()).isEqualTo(CandidateKind.IDIOM);
        assertThat(list.get(4).snippet()).isTrue();
        assertThat(list.get(0).snippet()).isFalse();
        // sortText = rank_index, index running over the whole list.
        assertThat(list.get(0).sortText()).isEqualTo("0_000");
        assertThat(list.get(1).sortText()).isEqualTo("0_001");
        assertThat(list.get(2).sortText()).isEqualTo("1_002");
        assertThat(list.get(4).sortText()).isEqualTo("2_004");
        // filterText = bare name + insert text, so a typed "$A still finds the quoted expansion.
        assertThat(list.get(0).filterText()).isEqualTo("ARR \"${ARR[@]}\"");
        assertThat(list.get(0).label()).isEqualTo("\"${ARR[@]}\"");
        // The idiom label shows the placeholder default, the insert text keeps the snippet syntax.
        Candidate command = list.stream()
            .filter(c -> c.insertText().equals("$(${1:command})"))
            .findFirst()
            .orElseThrow();
        assertThat(command.label()).isEqualTo("$(command)");
        for (Candidate candidate : list) {
            assertWithMessage(candidate.toString()).that(candidate.replaceStart()).isEqualTo(text.length());
        }
    }

    @Test
    void partiallyTypedTokenSetsReplaceStartOnTheCaretLine() {
        String text = "ARR=(a b)\nLIST=(c)\nfor x in \"$A";
        Context ctx = SnippetCompletionSupport.classify("bash", text, text.length());
        assertThat(ctx.kind()).isEqualTo(ContextKind.FOR_IN);
        assertThat(ctx.token()).isEqualTo("\"$A");
        assertThat(ctx.replaceStart()).isEqualTo(text.length() - 3);

        List<Candidate> list = SnippetCompletionSupport.localCandidates("bash", text, text.length(), 40);
        assertThat(list).isNotEmpty();
        assertThat(list.get(0).insertText()).isEqualTo("\"${ARR[@]}\"");
        for (Candidate candidate : list) {
            assertThat(candidate.replaceStart()).isEqualTo(ctx.replaceStart());
        }

        // A second word: only the last one is the token.
        String twoWords = "for x in \"${ARR[@]}\" \"$L";
        Context second = SnippetCompletionSupport.classify("bash", twoWords, twoWords.length());
        assertThat(second.kind()).isEqualTo(ContextKind.FOR_IN);
        assertThat(second.token()).isEqualTo("\"$L");
        assertThat(second.replaceStart()).isEqualTo(twoWords.length() - 3);

        // Past the loop header the position is generic again.
        assertThat(SnippetCompletionSupport.classify("bash", "for x in a b; do", 16).kind())
            .isEqualTo(ContextKind.GENERIC);

        // A generic position keeps the sigil with the typed identifier.
        String generic = "NAME=1\necho $NA";
        Context ctxGeneric = SnippetCompletionSupport.classify("bash", generic, generic.length());
        assertThat(ctxGeneric.kind()).isEqualTo(ContextKind.GENERIC);
        assertThat(ctxGeneric.token()).isEqualTo("$NA");
        assertThat(ctxGeneric.replaceStart()).isEqualTo(generic.length() - 3);
        List<Candidate> genericList = SnippetCompletionSupport.localCandidates("bash", generic, generic.length(), 40);
        assertThat(genericList.get(0).insertText()).isEqualTo("$NAME");
        assertThat(genericList.get(0).filterText()).isEqualTo("NAME $NAME");
        assertThat(genericList.get(0).replaceStart()).isEqualTo(generic.length() - 3);
    }

    @Test
    void bashHarvestReadsDeclareMapfileReadAndFunctions() {
        String text = """
            declare -a items
            mapfile -t lines < input.txt
            read -r -a words <<< "$x"
            read -p "Name: " user
            function deploy {
            run_it() {
            for f in *.txt; do
            while IFS= read -r line; do
            local -A opts=()
            export PATH_X=/tmp
            readarray -d '' found < <(find . -print0)
            declare -i counter=0
            """;
        Symbols symbols = SnippetCompletionSupport.harvest("bash", text, text.length());
        assertThat(symbols.arrays()).containsExactly("items", "lines", "words", "found").inOrder();
        assertThat(symbols.hashes()).containsExactly("opts");
        assertThat(symbols.scalars()).containsExactly("user", "f", "line", "PATH_X", "counter").inOrder();
        assertThat(symbols.functions()).containsExactly("deploy", "run_it").inOrder();
    }

    // ---------------------------------------------------------------- perl

    @Test
    void perlForeachParenOffersArraysHashesAndIdioms() {
        String text = """
            use strict;
            my @files = glob("*");
            my %config = ();
            our @copies = @ARGV;
            my $count = 0;
            sub run { }
            foreach my $f (""";
        Context ctx = SnippetCompletionSupport.classify("perl", text, text.length());
        assertThat(ctx.kind()).isEqualTo(ContextKind.FOREACH_PAREN);
        assertThat(ctx.token()).isEmpty();

        List<String> list = inserts(SnippetCompletionSupport.localCandidates("perl", text, text.length(), 40));
        assertThat(list.subList(0, 3)).containsExactly("@files", "@copies", "keys %config").inOrder();
        assertThat(list).containsAtLeast("values %config", "sort @files", "sort @copies", "@ARGV").inOrder();
        assertThat(list).doesNotContain("$count");
        assertThat(list).doesNotContain("run");

        // After a comma the next list element is expected; a closed paren ends the context.
        Context afterComma = SnippetCompletionSupport.classify("perl", "foreach my $k (keys %config, ", 29);
        assertThat(afterComma.kind()).isEqualTo(ContextKind.FOREACH_PAREN);
        assertThat(afterComma.token()).isEmpty();
        Context typed = SnippetCompletionSupport.classify("perl", "foreach my $k (@fi", 18);
        assertThat(typed.token()).isEqualTo("@fi");
        assertThat(typed.replaceStart()).isEqualTo(15);
        assertThat(SnippetCompletionSupport.classify("perl", "foreach my $x (@files) {", 24).kind())
            .isEqualTo(ContextKind.GENERIC);
        assertThat(SnippetCompletionSupport.classify("perl", "foreach (", 9).kind())
            .isEqualTo(ContextKind.FOREACH_PAREN);
    }

    @Test
    void perlUseModuleOffersPosixImportsAndUnknownModuleSnippet() {
        String text = "use strict;\nuse POSIX ";
        Context ctx = SnippetCompletionSupport.classify("perl", text, text.length());
        assertThat(ctx.kind()).isEqualTo(ContextKind.USE_MODULE);
        assertThat(ctx.module()).isEqualTo("POSIX");
        assertThat(ctx.token()).isEmpty();

        List<Candidate> list = SnippetCompletionSupport.localCandidates("perl", text, text.length(), 40);
        assertThat(inserts(list))
            .containsExactly("qw(strftime floor ceil)", "qw(strftime)", "qw(floor ceil)", "qw(:sys_wait_h)")
            .inOrder();
        for (Candidate candidate : list) {
            assertThat(candidate.kind()).isEqualTo(CandidateKind.IDIOM);
            assertThat(candidate.snippet()).isTrue();
            assertThat(candidate.replaceStart()).isEqualTo(text.length());
        }

        String typed = "use POSIX qw(str";
        Context typedCtx = SnippetCompletionSupport.classify("perl", typed, typed.length());
        assertThat(typedCtx.token()).isEqualTo("qw(str");
        assertThat(typedCtx.replaceStart()).isEqualTo(typed.length() - 6);
        List<Candidate> typedList = SnippetCompletionSupport.localCandidates("perl", typed, typed.length(), 40);
        assertThat(inserts(typedList)).contains("qw(strftime floor ceil)");
        assertThat(typedList.get(0).replaceStart()).isEqualTo(typed.length() - 6);

        String unknown = "use Acme::Widget ";
        List<Candidate> unknownList = SnippetCompletionSupport.localCandidates("perl", unknown, unknown.length(), 40);
        assertThat(inserts(unknownList)).containsExactly("qw(${1})");
        assertThat(unknownList.get(0).label()).isEqualTo("qw(…)");
        assertThat(unknownList.get(0).snippet()).isTrue();

        // A finished statement is no import position any more.
        assertThat(SnippetCompletionSupport.classify("perl", "use POSIX qw(floor); ", 21).kind())
            .isEqualTo(ContextKind.GENERIC);
    }

    @Test
    void perlListDeclarationHarvestsEverySigil() {
        String text = """
            sub new {
                my ($self, @args, %opts) = @_;
                my ($first, $second);
                while (my $line = <STDIN>) {
                print\s""";
        Symbols symbols = SnippetCompletionSupport.harvest("perl", text, text.length());
        assertThat(symbols.scalars()).containsExactly("self", "first", "second", "line").inOrder();
        assertThat(symbols.arrays()).containsExactly("args");
        assertThat(symbols.hashes()).containsExactly("opts");
        assertThat(symbols.functions()).containsExactly("new");

        List<String> list = inserts(SnippetCompletionSupport.localCandidates("perl", text, text.length(), 40));
        assertThat(list.get(0)).isEqualTo("new");
        assertThat(list).containsAtLeast("@args", "%opts", "$self", "$first").inOrder();
    }

    // ---------------------------------------------------------------- python

    @Test
    void pythonForInOffersIterablesItemsAndRanges() {
        String text = """
            import sys
            items = [1, 2]
            config = {"a": 1}
            names: list[str] = []
            total = 0
            def main(argv):
                for x in\s""";
        Context ctx = SnippetCompletionSupport.classify("python", text, text.length());
        assertThat(ctx.kind()).isEqualTo(ContextKind.FOR_IN);

        List<String> list = inserts(SnippetCompletionSupport.localCandidates("python", text, text.length(), 40));
        assertThat(list.subList(0, 3)).containsExactly("items", "names", "config.items()").inOrder();
        assertThat(list).containsAtLeast("enumerate(items)", "config", "range(len(items))", "total", "argv",
            "range(${1:10})").inOrder();
        assertThat(list).doesNotContain("main");
        assertThat(list).doesNotContain("x");

        // A comprehension is a for-in position too; a finished header is not.
        String comprehension = "squares = [n * n for n in ";
        assertThat(SnippetCompletionSupport.classify("python", comprehension, comprehension.length()).kind())
            .isEqualTo(ContextKind.FOR_IN);
        assertThat(SnippetCompletionSupport.classify("python", "for x in items:", 15).kind())
            .isEqualTo(ContextKind.GENERIC);
        Context tuple = SnippetCompletionSupport.classify("python", "for key, value in con", 21);
        assertThat(tuple.kind()).isEqualTo(ContextKind.FOR_IN);
        assertThat(tuple.token()).isEqualTo("con");
        assertThat(tuple.replaceStart()).isEqualTo(18);
    }

    @Test
    void pythonHarvestClassifiesByRightHandSideAndAnnotation() {
        String text = """
            import os
            mapping: dict[str, int] = {}
            pairs = dict(a=1)
            lines = open(path).readlines()
            entries = os.listdir(".")
            squares = [n * n for n in range(3)]
            single = (1 + 2) * 3
            point = (1, 2)
            with open(path) as handle:
                a, b = 1, 2
            class Runner:
                pass
            """;
        Symbols symbols = SnippetCompletionSupport.harvest("python", text, text.length());
        assertThat(symbols.hashes()).containsExactly("mapping", "pairs").inOrder();
        assertThat(symbols.arrays()).containsExactly("lines", "entries", "squares", "point").inOrder();
        assertThat(symbols.scalars()).containsExactly("single", "handle", "a", "b").inOrder();
        assertThat(symbols.functions()).containsExactly("Runner");
    }

    // ---------------------------------------------------------------- powershell / ruby

    @Test
    void powershellForeachOffersArraysAndHashKeys() {
        String text = """
            $files = @(1, 2)
            $map = @{ a = 1 }
            $name = "x"
            [string[]]$names = "a", "b"
            function Get-Stuff {
            foreach ($f in\s""";
        List<String> list = inserts(SnippetCompletionSupport.localCandidates("powershell", text, text.length(), 40));
        assertThat(list.subList(0, 3)).containsExactly("$files", "$names", "$map.Keys").inOrder();
        assertThat(list).containsAtLeast("$map.GetEnumerator()", "$name", "1..${1:10}", "\\$args").inOrder();
        assertThat(list).doesNotContain("Get-Stuff");

        String generic = "$files = @(1, 2)\n$map = @{}\nfunction Get-Stuff {\nWrite-Host ";
        List<Candidate> genericList = SnippetCompletionSupport.localCandidates("pwsh", generic, generic.length(), 40);
        assertThat(inserts(genericList).subList(0, 3)).containsExactly("Get-Stuff", "$files", "$map").inOrder();
        assertThat(genericList.get(0).kind()).isEqualTo(CandidateKind.FUNCTION);
    }

    @Test
    void rubyForInOffersArraysAndHashes() {
        String text = """
            items = [1, 2]
            @cache = {}
            name = "x"
            def helper
            end
            for i in\s""";
        List<String> list = inserts(SnippetCompletionSupport.localCandidates("ruby", text, text.length(), 40));
        assertThat(list.subList(0, 4)).containsExactly("items", "@cache", "@cache.keys", "name").inOrder();
        assertThat(list).contains("(1..${1:10})");
        assertThat(list).doesNotContain("helper");

        String generic = "items = [1]\n@cache = {}\ndef helper\nend\nputs ";
        List<String> genericList =
            inserts(SnippetCompletionSupport.localCandidates("rb", generic, generic.length(), 40));
        assertThat(genericList.subList(0, 3)).containsExactly("helper", "items", "@cache").inOrder();
    }

    // ---------------------------------------------------------------- generic languages

    @Test
    void genericContextListsFunctionsBeforeVariablesAndIdentifiers() {
        String text = """
            function fetchData(url) {
              return fetch(url);
            }
            const parse = (raw) => {
              return JSON.parse(raw);
            };
            const cache = new Map();
            let items = [];
            console.log(items.length);
            fetchData(cache);
            fe""";
        List<Candidate> list = SnippetCompletionSupport.localCandidates("javascript", text, text.length(), 40);
        // Functions first, then the variables in bucket order (arrays, hashes, plain variables).
        assertThat(inserts(list).subList(0, 4)).containsExactly("fetchData", "parse", "items", "cache").inOrder();
        assertThat(list.get(0).kind()).isEqualTo(CandidateKind.FUNCTION);
        assertThat(list.get(1).kind()).isEqualTo(CandidateKind.FUNCTION);
        assertThat(list.get(2).kind()).isEqualTo(CandidateKind.ARRAY);
        assertThat(list.get(3).kind()).isEqualTo(CandidateKind.HASH);
        assertThat(list.get(0).sortText()).isEqualTo("0_000");
        assertThat(list.get(2).sortText()).isEqualTo("1_002");
        // Plain identifiers follow, most frequent first; keywords and harvested names are excluded.
        List<String> plain = new ArrayList<>();
        for (Candidate candidate : list) {
            if (candidate.kind() == CandidateKind.TEXT) {
                plain.add(candidate.insertText());
            }
        }
        assertThat(plain).isNotEmpty();
        assertThat(plain.get(0)).isEqualTo("url");
        assertThat(plain).containsAtLeast("raw", "console", "log", "length");
        assertThat(plain).containsNoneOf("function", "const", "return", "new", "let", "fetchData", "items", "fe");
        assertThat(list.get(4).sortText()).startsWith("3_");
        assertThat(list.get(list.size() - 1).replaceStart()).isEqualTo(text.length() - 2);

        // Monaco's own language service supplies the words: only the plain-identifier bucket is dropped.
        List<Candidate> withoutWords =
            SnippetCompletionSupport.localCandidates("javascript", text, text.length(), 40, false);
        assertThat(inserts(withoutWords)).containsExactly("fetchData", "parse", "items", "cache").inOrder();
    }

    @Test
    void sqlGenericContextOffersIdentifiersByFrequency() {
        String text = """
            SELECT name, email FROM users WHERE active = 1;
            SELECT name FROM users;
            SELECT\s""";
        List<Candidate> list = SnippetCompletionSupport.localCandidates("sql", text, text.length(), 40);
        assertThat(inserts(list)).containsExactly("name", "users", "email", "active").inOrder();
        for (Candidate candidate : list) {
            assertThat(candidate.kind()).isEqualTo(CandidateKind.TEXT);
            assertThat(candidate.sortText()).startsWith("3_");
        }
    }

    @Test
    void javaHarvestClassifiesCollectionsByDeclaredType() {
        String text = """
            public class Demo {
                private final List<String> names = new ArrayList<>();
                Map<String, Integer> counts = new HashMap<>();
                int total = 0;
                public static void main(String[] args) {
                    String[] parts = line.split(",");
                }
                private boolean isReady() {
            """;
        Symbols symbols = SnippetCompletionSupport.harvest("java", text, text.length());
        assertThat(symbols.arrays()).containsExactly("names", "parts").inOrder();
        assertThat(symbols.hashes()).containsExactly("counts");
        assertThat(symbols.scalars()).containsExactly("total");
        assertThat(symbols.functions()).containsExactly("main", "isReady").inOrder();
    }

    // ---------------------------------------------------------------- effective language

    @Test
    void heredocSwitchesToTheEmbeddedLanguageRules() {
        String text = """
            #!/usr/bin/env bash
            ARR=(a b)
            python3 - <<'PY'
            items = [1, 2]
            for x in\s
            PY
            echo done
            """;
        int caret = text.indexOf("for x in ") + "for x in ".length();
        assertThat(SnippetCompletionSupport.effectiveLanguage("bash", text, caret)).isEqualTo("python");
        List<String> list = inserts(SnippetCompletionSupport.localCandidates("bash", text, caret, 40));
        assertThat(list.get(0)).isEqualTo("items");
        assertThat(list).contains("range(${1:10})");
        assertThat(list).doesNotContain("\"${ARR[@]}\"");
        assertThat(list).doesNotContain("\"$@\"");

        // Outside the heredoc the bash rules apply.
        int bashCaret = text.indexOf("ARR=(a b)") + "ARR=(a b)".length();
        assertThat(SnippetCompletionSupport.effectiveLanguage("bash", text, bashCaret)).isEqualTo("bash");
    }

    @Test
    void effectiveLanguageNormalizesAliasesAndShebangs() {
        assertThat(SnippetCompletionSupport.effectiveLanguage("sh", "echo hi", 3)).isEqualTo("bash");
        assertThat(SnippetCompletionSupport.effectiveLanguage("Perl", "print 1;", 0)).isEqualTo("perl");
        assertThat(SnippetCompletionSupport.effectiveLanguage("pwsh", "", 0)).isEqualTo("powershell");
        assertThat(SnippetCompletionSupport.effectiveLanguage(null, null, 0)).isEqualTo("plain");
        assertThat(SnippetCompletionSupport.effectiveLanguage("", "#!/usr/bin/env python3\nx = 1\n", 30))
            .isEqualTo("python");
    }

    // ---------------------------------------------------------------- Shift+TAB rule

    @Test
    void shiftTabOpensTheListOnlyAtTheEndOfATypedLine() {
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList("for x in ", "")).isTrue();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList("  echo", "   ")).isTrue();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList("echo", "\r")).isTrue();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList("echo", null)).isTrue();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList("", "")).isFalse();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList("    ", "")).isFalse();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList(null, "")).isFalse();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList("echo", "done")).isFalse();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList("echo", " x")).isFalse();
        assertThat(SnippetCompletionSupport.shiftTabShouldOpenList(null, null)).isFalse();
    }

    // ---------------------------------------------------------------- limits

    @Test
    void commentLinesAreSkippedAndACommentCaretLineOffersNothing() {
        String bash = "# HIDDEN=(x y)\n  # OTHER=(z)\nARR=(a)\nfor x in ";
        List<String> list = inserts(SnippetCompletionSupport.localCandidates("bash", bash, bash.length(), 40));
        assertThat(list.get(0)).isEqualTo("\"${ARR[@]}\"");
        assertThat(list).containsNoneOf("\"${HIDDEN[@]}\"", "\"${OTHER[@]}\"");

        String js = "// const hidden = [];\n/* const alsoHidden = []; */\nconst shown = [];\nsh";
        List<String> jsList = inserts(SnippetCompletionSupport.localCandidates("javascript", js, js.length(), 40));
        assertThat(jsList).contains("shown");
        assertThat(jsList).containsNoneOf("hidden", "alsoHidden");

        String comment = "ARR=(a)\n# for x in ";
        assertThat(SnippetCompletionSupport.classify("bash", comment, comment.length()).kind())
            .isEqualTo(ContextKind.NONE);
        assertThat(SnippetCompletionSupport.localCandidates("bash", comment, comment.length(), 40)).isEmpty();
        assertThat(SnippetCompletionSupport.candidates(
            new Context("bash", ContextKind.NONE, "", 0, ""), Symbols.EMPTY, 40)).isEmpty();
    }

    @Test
    void manyLinesAreHarvestedOnlyInAWindowAroundTheCaret() {
        StringBuilder text = new StringBuilder();
        int total = SnippetCompletionSupport.MAX_LINES + 1_000;
        int caretLine = total - 100;
        int nearLine = caretLine - SnippetCompletionSupport.WINDOW_LINES + 10;
        int caret = -1;
        for (int line = 0; line < total; line++) {
            if (line == 0) {
                text.append("FAR=(1)\n");
            } else if (line == nearLine) {
                text.append("NEAR=(1)\n");
            } else if (line == caretLine) {
                text.append("for x in ");
                caret = text.length();
                text.append('\n');
            } else {
                text.append("echo line\n");
            }
        }
        List<String> list = inserts(SnippetCompletionSupport.localCandidates("bash", text.toString(), caret, 40));
        assertThat(list).contains("\"${NEAR[@]}\"");
        assertThat(list).doesNotContain("\"${FAR[@]}\"");

        // A small file is read completely.
        String small = "FAR=(1)\n" + "echo line\n".repeat(100) + "for x in ";
        assertThat(inserts(SnippetCompletionSupport.localCandidates("bash", small, small.length(), 40)))
            .contains("\"${FAR[@]}\"");
    }

    @Test
    void hugeContentUsesTheWindowSkipsTheEmbeddedScanAndStaysFast() {
        String padding = "echo \"" + "x".repeat(180) + "\"\n";
        StringBuilder text = new StringBuilder();
        int total = 3_200;
        int caretLine = total - 100;
        int caret = -1;
        for (int line = 0; line < total; line++) {
            if (line == 0) {
                text.append("FAR=(1)\n");
            } else if (line == 1) {
                text.append("perl <<'EOF'\n");
            } else if (line == 1_000) {
                text.append("NEAR=(1)\n");
            } else if (line == caretLine) {
                text.append("for x in ");
                caret = text.length();
                text.append('\n');
            } else {
                text.append(padding);
            }
        }
        String content = text.toString();
        assertThat(content.length()).isGreaterThan(SnippetCompletionSupport.MAX_TEXT_CHARS);
        assertThat(total).isLessThan(SnippetCompletionSupport.MAX_LINES);

        // Over the size limit the (unbounded) embedded-language scan is skipped: the unterminated
        // heredoc would otherwise turn the whole rest of the file into perl.
        assertThat(SnippetCompletionSupport.effectiveLanguage("bash", content, caret)).isEqualTo("bash");

        SnippetCompletionSupport.localCandidates("bash", content, caret, 40);
        long start = System.nanoTime();
        List<String> list = inserts(SnippetCompletionSupport.localCandidates("bash", content, caret, 40));
        long millis = (System.nanoTime() - start) / 1_000_000;
        assertThat(list).contains("\"${NEAR[@]}\"");
        assertThat(list).doesNotContain("\"${FAR[@]}\"");
        assertWithMessage("harvest of " + content.length() + " chars took " + millis + " ms")
            .that(millis).isLessThan(1_000);
    }

    @Test
    void candidatesAreDeduplicatedAndCapped() {
        String text = "ARR=(a)\nARR+=(b)\nARR[2]=c\nfor x in ";
        List<String> list = inserts(SnippetCompletionSupport.localCandidates("bash", text, text.length(), 40));
        assertThat(list.stream().filter("\"${ARR[@]}\""::equals).count()).isEqualTo(1);
        assertThat(SnippetCompletionSupport.localCandidates("bash", text, text.length(), 2)).hasSize(2);

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            many.append("A").append(i).append("=(x)\n");
        }
        many.append("for x in ");
        List<Candidate> capped = SnippetCompletionSupport.localCandidates("bash", many.toString(), many.length(), 0);
        assertThat(capped).hasSize(SnippetCompletionSupport.DEFAULT_MAX_ITEMS);
        for (int i = 0; i < capped.size(); i++) {
            assertThat(capped.get(i).sortText()).isEqualTo(String.format("0_%03d", i));
        }
        // The bucket itself is bounded before the list cap applies.
        Symbols symbols = SnippetCompletionSupport.harvest("bash", many.toString(), many.length());
        assertThat(symbols.arrays()).hasSize(SnippetCompletionSupport.MAX_NAMES_PER_BUCKET);
        assertThat(symbols.arrays().get(0)).isEqualTo("A0");
    }

    // ---------------------------------------------------------------- AI candidates

    @Test
    void aiCandidatesDedupeRelabelAndReanchorOnTheTypedToken() {
        String text = "ARR=(a b)\nfor x in \"$A";
        int caret = text.length();
        Context ctx = SnippetCompletionSupport.classify("bash", text, caret);
        List<Candidate> local = SnippetCompletionSupport.localCandidates("bash", text, caret, 40);
        assertThat(inserts(local)).contains("\"${ARR[@]}\"");

        List<CompletionSuggestion> ai = List.of(
            // The local expansion itself: a duplicate.
            new CompletionSuggestion("\"${ARR[@]}\"", "same as local"),
            // Begins with the local expansion of the token: replaces the token; CRLF normalized.
            new CompletionSuggestion("\"${ARR[@]}\"; do\r\n  echo \"$x\"\r\ndone\n", "loop body"),
            new CompletionSuggestion("   ", "blank"),
            // A continuation of the typed token, twice.
            new CompletionSuggestion("RGS\"", ""),
            new CompletionSuggestion("RGS\"", "duplicate"),
            // Repeats the token itself.
            new CompletionSuggestion("\"$ALL\"", "repeat"),
            new CompletionSuggestion("\n" + "y".repeat(80), "long"));
        List<Candidate> result = SnippetCompletionSupport.aiCandidates(ctx, caret, local, ai, "AI suggestion");

        assertThat(inserts(result)).containsExactly(
            "\"${ARR[@]}\"; do\n  echo \"$x\"\ndone",
            "\"$ARGS\"",
            "\"$ALL\"",
            "\"$A\n" + "y".repeat(80)).inOrder();
        Candidate loop = result.get(0);
        assertThat(loop.kind()).isEqualTo(CandidateKind.AI);
        assertThat(loop.snippet()).isFalse();
        assertThat(loop.label()).isEqualTo("✨ \"${ARR[@]}\"; do");
        assertThat(loop.sortText()).isEqualTo("4_000");
        assertThat(loop.replaceStart()).isEqualTo(ctx.replaceStart());
        assertThat(loop.filterText()).isEqualTo("\"$A \"${ARR[@]}\"; do\n  echo \"$x\"\ndone");
        assertThat(loop.documentation()).isEqualTo("loop body");
        Candidate args = result.get(1);
        assertThat(args.label()).isEqualTo("✨ \"$ARGS\"");
        assertThat(args.sortText()).isEqualTo("4_001");
        assertThat(args.documentation()).isEqualTo("AI suggestion");
        assertThat(args.replaceStart()).isEqualTo(ctx.replaceStart());
        assertThat(args.filterText()).isEqualTo("\"$A \"$ARGS\"");
        assertThat(result.get(2).sortText()).isEqualTo("4_002");
        // A continuation that does not repeat the token is re-anchored at the token start.
        Candidate continuation = result.get(3);
        assertThat(continuation.replaceStart()).isEqualTo(ctx.replaceStart());
        assertThat(continuation.label()).hasLength(SnippetCompletionSupport.AI_LABEL_PREFIX.length()
            + SnippetCompletionSupport.AI_LABEL_MAX_CHARS);
        assertThat(continuation.label()).startsWith("✨ yyy");
        assertThat(continuation.label()).endsWith("…");

        // Without a typed token the entries insert at the caret.
        String plain = "for x in ";
        Context plainCtx = SnippetCompletionSupport.classify("bash", plain, plain.length());
        List<Candidate> atCaret = SnippetCompletionSupport.aiCandidates(plainCtx, plain.length(), List.of(),
            List.of(new CompletionSuggestion("\"$@\"; do", null)), null);
        assertThat(atCaret).hasSize(1);
        assertThat(atCaret.get(0).replaceStart()).isEqualTo(plain.length());
        assertThat(atCaret.get(0).filterText()).isEqualTo("\"$@\"; do");
        assertThat(atCaret.get(0).documentation()).isEmpty();
        assertThat(SnippetCompletionSupport.aiCandidates(plainCtx, plain.length(), null, null, null)).isEmpty();
        assertThat(SnippetCompletionSupport.aiCandidates(null, 0, null, List.of(), null)).isEmpty();
    }

    // ---------------------------------------------------------------- prompt helpers

    @Test
    void localContextIsBoundedAndDeterministic() {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < 50; i++) {
            text.append("A").append(i).append("=(x)\n");
        }
        text.append("declare -A H=()\nNAME=1\nmain() {\nfor x in \"$A");
        String content = text.toString();
        String context = SnippetCompletionSupport.localContext("bash", content, content.length());
        assertThat(context).isEqualTo(SnippetCompletionSupport.localContext("bash", content, content.length()));
        assertThat(context).startsWith("Cursor context: bash, for-in loop");
        assertThat(context).contains("typed so far: \"\"$A\"");
        assertThat(context).contains("Known symbols: arrays A0, A1, ");
        assertThat(context).contains("hashes H");
        assertThat(context).contains("variables NAME");
        assertThat(context).contains("functions main");
        String names = context.substring(context.indexOf("Known symbols: "));
        int listed = 0;
        for (String group : names.substring("Known symbols: ".length(), names.length() - 1).split("; ")) {
            listed += group.substring(group.indexOf(' ') + 1).split(", ").length;
        }
        assertThat(listed).isAtMost(SnippetCompletionSupport.MAX_CONTEXT_NAMES);
        assertThat(listed).isEqualTo(SnippetCompletionSupport.MAX_CONTEXT_NAMES);

        assertThat(SnippetCompletionSupport.localContext(
            new Context("bash", ContextKind.NONE, "", 0, ""), Symbols.EMPTY)).isEmpty();
        assertThat(SnippetCompletionSupport.localContext(
            new Context("perl", ContextKind.USE_MODULE, "", 0, "POSIX"), Symbols.EMPTY))
            .isEqualTo("Cursor context: perl, use-module import list for POSIX.");
        assertThat(SnippetCompletionSupport.localContext(null, null)).isEmpty();
    }

    @Test
    void promptWindowCutsAtLineBoundaries() {
        StringBuilder text = new StringBuilder();
        String filler = "x".repeat(30);
        for (int i = 0; i < 400; i++) {
            text.append(String.format("line-%03d ", i)).append(filler).append('\n'); // 40 chars each
        }
        String content = text.toString();
        assertThat(content.length()).isEqualTo(16_000);
        int caret = 200 * 40 + 5; // inside line 200 (0-based), 1-based line 201, column 6

        PromptWindow window = SnippetCompletionSupport.promptWindow(content, caret);
        assertThat(window.line()).isEqualTo(201);
        assertThat(window.column()).isEqualTo(6);
        assertThat(window.beforeTruncated()).isTrue();
        assertThat(window.afterTruncated()).isTrue();
        assertThat(window.before().length()).isAtMost(SnippetCompletionSupport.PREFIX_MAX_CHARS);
        assertThat(window.after().length()).isAtMost(SnippetCompletionSupport.SUFFIX_MAX_CHARS);
        assertThat(window.before()).startsWith("line-");
        assertThat(window.before()).endsWith("line-");
        assertThat(window.before().length() % 40).isEqualTo(5);
        assertThat(window.after()).startsWith("200 " + filler + "\n");
        assertThat(window.after()).endsWith(filler);
        assertThat(window.after()).doesNotContain("\n\n");
        assertThat(content.charAt(caret + window.after().length())).isEqualTo('\n');
        // Both cuts sit on line boundaries.
        assertThat(content.charAt(caret - window.before().length() - 1)).isEqualTo('\n');

        // A short text is sent whole.
        PromptWindow whole = SnippetCompletionSupport.promptWindow("ab\ncd", 3);
        assertThat(whole.before()).isEqualTo("ab\n");
        assertThat(whole.after()).isEqualTo("cd");
        assertThat(whole.beforeTruncated()).isFalse();
        assertThat(whole.afterTruncated()).isFalse();
        assertThat(whole.line()).isEqualTo(2);
        assertThat(whole.column()).isEqualTo(1);
        assertThat(SnippetCompletionSupport.promptWindow(null, 7).before()).isEmpty();

        // A single line longer than the budget is cut mid-line rather than dropped.
        String longLine = "x".repeat(20_000);
        PromptWindow cut = SnippetCompletionSupport.promptWindow(longLine, 10_000);
        assertThat(cut.before()).hasLength(SnippetCompletionSupport.PREFIX_MAX_CHARS);
        assertThat(cut.after()).hasLength(SnippetCompletionSupport.SUFFIX_MAX_CHARS);
        assertThat(cut.beforeTruncated()).isTrue();
        assertThat(cut.afterTruncated()).isTrue();
    }

    private static List<String> inserts(List<Candidate> candidates) {
        List<String> result = new ArrayList<>();
        for (Candidate candidate : candidates) {
            result.add(candidate.insertText());
        }
        return result;
    }
}
