package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class SnippetCodeMaskTest {

    @Test
    void shellKeepsLineLengthsAndDoubleQuotedTextForReferences() {
        String text = "echo \"$name\" 'x $y' # note\nn=${#arr[@]} $#\n";

        SnippetCodeMask.Masked masked = SnippetCodeMask.mask("bash", text);

        assertThat(masked.code()).asList().containsExactly(
            "echo \"     \" '    '       ", "n=${#arr[@]} $#", "").inOrder();
        assertThat(masked.references()[0]).isEqualTo("echo \"$name\" '    '       ");
    }

    @Test
    void shellHeredocBodiesAreBlankedOnlyWhenATerminatorFollows() {
        String text = "cat <<'EOF'\nf() {\nEOF\nx=$(( 1 << bits ))\nlast() {\n";

        SnippetCodeMask.Masked masked = SnippetCodeMask.mask("bash", text);

        assertThat(masked.code()[1]).isEqualTo("     ");
        assertThat(masked.references()[1]).isEqualTo("     ");
        assertThat(masked.code()[4]).isEqualTo("last() {");
    }

    @Test
    void pythonDocstringsAndCommentsAreBlanked() {
        String text = "def f():\n    \"\"\"def g():\n    \"\"\"\n    return '#' # done\n";

        SnippetCodeMask.Masked masked = SnippetCodeMask.mask("python", text);

        assertThat(masked.code()[1]).isEqualTo("    \"\"\"" + " ".repeat("def g():".length()));
        assertThat(masked.code()[3]).isEqualTo("    return ' '       ");
    }

    @Test
    void cLikeBlockCommentsAndTemplateLiterals() {
        String text = "/* a {\n b */ x = `${y}`; // }\n";

        SnippetCodeMask.Masked masked = SnippetCodeMask.mask("javascript", text);

        assertThat(masked.code()).asList().containsExactly("      ", "      x = `    `;     ", "").inOrder();
        assertThat(masked.references()[1]).isEqualTo("      x = `${y}`;     ");
    }

    @Test
    void perlPodAndPowershellBlockComments() {
        SnippetCodeMask.Masked perl = SnippetCodeMask.mask("perl", "my $x;\n=pod\nsub y {}\n=cut\nsub z {}\n");
        assertThat(perl.code()).asList().containsExactly("my $x;", "    ", "        ", "    ", "sub z {}", "")
            .inOrder();

        SnippetCodeMask.Masked powershell = SnippetCodeMask.mask("powershell", "<# function a {\n} #>\n$b = 'it''s'\n");
        assertThat(powershell.code()).asList().containsExactly(" ".repeat("<# function a {".length()), "    ", "$b = '     '", "")
            .inOrder();
    }

    @Test
    void luaBlanksLongBracketCommentsAndStrings() {
        String text = "x = 1 -- end\n--[==[ function a()\n]==] y = [[\nend ]] .. 'it''s'\n#t\n";

        SnippetCodeMask.Masked masked = SnippetCodeMask.mask("lua", text);

        assertThat(masked.code()).asList().containsExactly(
            "x = 1       ", " ".repeat("--[==[ function a()".length()), "     y = [[", "    ]] .. '  '' '", "#t", "")
            .inOrder();
        assertThat(masked.references()[0]).isEqualTo("x = 1       ");
    }

    @Test
    void textWithoutRulesComesBackUnchanged() {
        SnippetCodeMask.Masked masked = SnippetCodeMask.mask("markdown", "# Title\n`code`\n");

        assertThat(masked.code()).asList().containsExactly("# Title", "`code`", "").inOrder();
    }
}
