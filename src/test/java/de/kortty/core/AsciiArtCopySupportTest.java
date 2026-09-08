package de.kortty.core;

import com.google.gson.Gson;
import de.kortty.model.AsciiArtCommentStyle;
import de.kortty.model.AsciiArtPrintStyle;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AsciiArtCopySupportTest {

    private static final String CAT = " /\\_/\\ \n( o.o )\n\n > ^ <";

    /** All 95 printable ASCII characters 0x20..0x7E, in order. */
    private static final String TORTURE = printableAscii();

    private static String printableAscii() {
        StringBuilder sb = new StringBuilder();
        for (char c = 0x20; c <= 0x7E; c++) {
            sb.append(c);
        }
        return sb.toString();
    }

    private static String comment(String art, AsciiArtCommentStyle style, int gap) {
        return AsciiArtCopySupport.format(art, style, AsciiArtPrintStyle.NONE, gap);
    }

    private static String print(String art, AsciiArtPrintStyle style, int gap) {
        return AsciiArtCopySupport.format(art, AsciiArtCommentStyle.NONE, style, gap);
    }

    private static List<String> lines(String text) {
        return List.of(text.split("\n", -1));
    }

    // ---- Plain copy and gap ----

    @Test
    void noneAndNoneReturnThePictureUnchanged() {
        assertThat(comment(CAT, AsciiArtCommentStyle.NONE, 4)).isEqualTo(CAT);
        assertThat(AsciiArtCopySupport.format(CAT, null, null, 4)).isEqualTo(CAT);
    }

    @Test
    void blankOrMissingArtYieldsAnEmptyString() {
        assertThat(AsciiArtCopySupport.format(null, AsciiArtCommentStyle.HASH, null, 1)).isEmpty();
        assertThat(AsciiArtCopySupport.format("   \n\n", AsciiArtCommentStyle.HASH, null, 1)).isEmpty();
        assertThat(AsciiArtCopySupport.format("", null, AsciiArtPrintStyle.PYTHON, 1)).isEmpty();
    }

    @Test
    void lineEndingsAreNormalisedAndTrailingNewlinesDropped() {
        assertThat(comment("a\r\nb\r", AsciiArtCommentStyle.NONE, 1)).isEqualTo("a\nb");
        assertThat(comment("a\nb\n\n", AsciiArtCommentStyle.HASH, 0)).isEqualTo("#a\n#b");
    }

    @Test
    void theResultNeverEndsWithANewline() {
        for (AsciiArtCommentStyle style : AsciiArtCommentStyle.values()) {
            assertThat(comment(CAT + "\n", style, 2)).doesNotMatch("(?s).*\n$");
        }
        for (AsciiArtPrintStyle style : AsciiArtPrintStyle.values()) {
            assertThat(print(CAT + "\n", style, 2)).doesNotMatch("(?s).*\n$");
        }
    }

    @Test
    void gapOutsideTheRangeIsClamped() {
        assertThat(AsciiArtCopySupport.clampGap(-3)).isEqualTo(AsciiArtCopySupport.MIN_GAP);
        assertThat(AsciiArtCopySupport.clampGap(99)).isEqualTo(AsciiArtCopySupport.MAX_GAP);
        assertThat(AsciiArtCopySupport.clampGap(7)).isEqualTo(7);
        assertThat(AsciiArtCopySupport.DEFAULT_GAP).isEqualTo(1);
        assertThat(comment("/\\", AsciiArtCommentStyle.HASH, 99))
            .isEqualTo("#" + " ".repeat(AsciiArtCopySupport.MAX_GAP) + "/\\");
        assertThat(comment("/\\", AsciiArtCommentStyle.HASH, -1)).isEqualTo("#/\\");
    }

    @Test
    void printStyleWinsWhenBothStylesAreSet() {
        String result = AsciiArtCopySupport.format("/\\", AsciiArtCommentStyle.HASH, AsciiArtPrintStyle.PYTHON, 1);
        assertThat(result).isEqualTo("print(\" /\\\\\")");
    }

    // ---- Line comment styles ----

    @Test
    void hashPutsTheGapBetweenMarkerAndPictureAndLeavesBlankLinesBare() {
        List<String> out = lines(comment(CAT, AsciiArtCommentStyle.HASH, 4));
        assertThat(out).containsExactly("#     /\\_/\\ ", "#    ( o.o )", "#", "#     > ^ <").inOrder();
        for (String line : out) {
            assertThat(line).startsWith("#");
        }
    }

    @Test
    void gapZeroGluesTheMarkerToThePictureExceptWhereTheMarkerNeedsASeparator() {
        assertThat(comment("/\\", AsciiArtCommentStyle.HASH, 0)).isEqualTo("#/\\");
        assertThat(comment("/x", AsciiArtCommentStyle.DOUBLE_SLASH, 0)).isEqualTo("///x");
        // "REMabc" is not a command and "--|" is an operator, so these two keep one space at gap 0.
        assertThat(comment("abc", AsciiArtCommentStyle.REM, 0)).isEqualTo("REM abc");
        assertThat(comment("|", AsciiArtCommentStyle.DOUBLE_DASH, 0)).isEqualTo("-- |");
        assertThat(comment("\n|", AsciiArtCommentStyle.DOUBLE_DASH, 0)).isEqualTo("--\n-- |");
        assertThat(comment("|", AsciiArtCommentStyle.DOUBLE_DASH, 2)).isEqualTo("--  |");
    }

    @Test
    void doubleSlashAppendsAGuardLineWhenThePictureEndsInABackslash() {
        // C/C++ splice a "//" line ending in a backslash with the next line; a trailing space would
        // not help (both compilers splice across it), so a bare "//" line absorbs the splice.
        assertThat(comment("/___\\\n |", AsciiArtCommentStyle.DOUBLE_SLASH, 1)).isEqualTo("// /___\\\n//  |");
        assertThat(comment(" |\n/___\\", AsciiArtCommentStyle.DOUBLE_SLASH, 1)).isEqualTo("//  |\n// /___\\\n//");
        assertThat(comment("/___\\", AsciiArtCommentStyle.HASH, 1)).isEqualTo("# /___\\");
        assertThat(comment("/___\\", AsciiArtCommentStyle.C_BLOCK, 1)).isEqualTo("/*\n * /___\\\n */");
        assertThat(AsciiArtCopySupport.previewLine("\\", AsciiArtCommentStyle.DOUBLE_SLASH, null, 1))
            .isEqualTo("// \\");
    }

    @Test
    void remLinesEndingInACaretGetATrailingSpace() {
        // cmd continues a REM line after a trailing "^"; the caret escapes the space, which ends it.
        assertThat(comment("^\n|", AsciiArtCommentStyle.REM, 1)).isEqualTo("REM ^ \nREM |");
        assertThat(comment("^", AsciiArtCommentStyle.DOUBLE_SLASH, 1)).isEqualTo("// ^");
        assertThat(AsciiArtCopySupport.breakLineContinuation(AsciiArtCommentStyle.REM, "REM a^")).isEqualTo("REM a^ ");
        assertThat(AsciiArtCopySupport.breakLineContinuation(AsciiArtCommentStyle.REM, "REM a")).isEqualTo("REM a");
        assertThat(AsciiArtCopySupport.breakLineContinuation(AsciiArtCommentStyle.DOUBLE_SLASH, "// a\\"))
            .isEqualTo("// a\\");
    }

    @Test
    void everyLineStyleUsesItsOwnMarker() {
        assertThat(comment("x", AsciiArtCommentStyle.DOUBLE_SLASH, 1)).isEqualTo("// x");
        assertThat(comment("x", AsciiArtCommentStyle.DOUBLE_DASH, 1)).isEqualTo("-- x");
        assertThat(comment("x", AsciiArtCommentStyle.SEMICOLON, 1)).isEqualTo("; x");
        assertThat(comment("x", AsciiArtCommentStyle.PERCENT, 1)).isEqualTo("% x");
        assertThat(comment("x", AsciiArtCommentStyle.APOSTROPHE, 1)).isEqualTo("' x");
        assertThat(comment("x", AsciiArtCommentStyle.REM, 1)).isEqualTo("REM x");
    }

    @Test
    void whitespaceOnlyPictureLinesCountAsBlank() {
        assertThat(comment("a\n   \nb", AsciiArtCommentStyle.HASH, 2)).isEqualTo("#  a\n#\n#  b");
    }

    // ---- C block ----

    @Test
    void cBlockSurroundsThePictureWithStarLines() {
        List<String> out = lines(comment(CAT, AsciiArtCommentStyle.C_BLOCK, 3));
        assertThat(out.get(0)).isEqualTo("/*");
        assertThat(out.get(out.size() - 1)).isEqualTo(" */");
        assertThat(out.get(1)).isEqualTo(" *    /\\_/\\ ");
        assertThat(out.get(3)).isEqualTo(" *");
        assertThat(out).hasSize(6);
    }

    @Test
    void cBlockBreaksTerminatorsInsideThePicture() {
        String out = comment("a */ b\n**/", AsciiArtCommentStyle.C_BLOCK, 1);
        assertThat(out).isEqualTo("/*\n * a +/ b\n * *+/\n */");
        assertThat(out.substring(0, out.length() - 2)).doesNotContain("*/");
    }

    @Test
    void cBlockWithGapZeroDoesNotLetASlashCloseTheComment() {
        String out = comment("/\\", AsciiArtCommentStyle.C_BLOCK, 0);
        assertThat(out).isEqualTo("/*\n +/\\\n */");
    }

    // ---- XML block ----

    @Test
    void xmlBlockBreaksEveryDoubleHyphen() {
        String out = comment("----\n<-- a -->\n\n--", AsciiArtCommentStyle.XML_BLOCK, 2);
        List<String> lines = lines(out);
        assertThat(lines.get(0)).isEqualTo("<!--");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("-->");
        assertThat(lines.get(1)).isEqualTo("  -=-=");
        assertThat(lines.get(2)).isEqualTo("  <-= a -=>");
        assertThat(lines.get(3)).isEmpty();
        assertThat(lines.get(4)).isEqualTo("  -=");
        String body = out.substring("<!--".length(), out.length() - "-->".length());
        assertThat(body).doesNotContain("--");
    }

    @Test
    void xmlBlockKeepsSingleHyphensAndRunLength() {
        assertThat(AsciiArtCopySupport.breakXmlDoubleHyphens("a-b")).isEqualTo("a-b");
        assertThat(AsciiArtCopySupport.breakXmlDoubleHyphens("---")).isEqualTo("-=-");
        assertThat(AsciiArtCopySupport.breakXmlDoubleHyphens("-----")).hasLength(5);
    }

    // ---- JSON strings ----

    @Test
    void jsonStringsParseBackToTheOriginalLines() {
        String art = CAT + "\n" + TORTURE;
        String out = comment(art, AsciiArtCommentStyle.JSON_STRINGS, 2);
        String[] parsed = new Gson().fromJson(out, String[].class);
        assertThat(parsed).asList().containsExactly(" /\\_/\\ ", "( o.o )", "", " > ^ <", TORTURE).inOrder();
        List<String> lines = lines(out);
        assertThat(lines.get(0)).isEqualTo("[");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("]");
        assertThat(lines.get(1)).startsWith("  \"");
        assertThat(lines.get(1)).endsWith("\",");
        assertThat(lines.get(lines.size() - 2)).endsWith("\"");
    }

    @Test
    void jsonEscapingCoversQuotesBackslashesAndControlCharacters() {
        assertThat(AsciiArtCopySupport.escapeJson("a\"b\\c")).isEqualTo("a\\\"b\\\\c");
        assertThat(AsciiArtCopySupport.escapeJson("\t\u0001")).isEqualTo("\\t\\u0001");
    }

    // ---- Print styles: shape ----

    @Test
    void everyPrintStyleEmitsOneStatementPerLineWithTheGapInsideTheString() {
        for (AsciiArtPrintStyle style : AsciiArtPrintStyle.values()) {
            if (style == AsciiArtPrintStyle.NONE || style == AsciiArtPrintStyle.BASH_HEREDOC) {
                continue;
            }
            List<String> out = lines(print("ab\n\ncd", style, 3));
            assertThat(out).hasSize(3);
            assertThat(out.get(0)).contains("   ab");
            assertThat(out.get(2)).contains("   cd");
            assertThat(out.get(1)).doesNotContain("   ");
        }
    }

    @Test
    void blankLinesProduceTheBlankLineStatement() {
        assertThat(print("\na", AsciiArtPrintStyle.BASH_ECHO, 1)).startsWith("echo ''\n");
        assertThat(print("\na", AsciiArtPrintStyle.SH_PRINTF, 1)).startsWith("printf '\\n'\n");
        assertThat(print("\na", AsciiArtPrintStyle.PERL, 1)).startsWith("print \"\\n\";\n");
        assertThat(print("\na", AsciiArtPrintStyle.PYTHON, 1)).startsWith("print()\n");
        assertThat(print("\na", AsciiArtPrintStyle.RUBY, 1)).startsWith("puts\n");
        assertThat(print("\na", AsciiArtPrintStyle.PHP, 1)).startsWith("echo PHP_EOL;\n");
        assertThat(print("\na", AsciiArtPrintStyle.JAVASCRIPT, 1)).startsWith("console.log(\"\");\n");
        assertThat(print("\na", AsciiArtPrintStyle.JAVA, 1)).startsWith("System.out.println();\n");
        assertThat(print("\na", AsciiArtPrintStyle.C, 1)).startsWith("puts(\"\");\n");
        assertThat(print("\na", AsciiArtPrintStyle.CSHARP, 1)).startsWith("Console.WriteLine();\n");
        assertThat(print("\na", AsciiArtPrintStyle.GO, 1)).startsWith("fmt.Println()\n");
        assertThat(print("\na", AsciiArtPrintStyle.RUST, 1)).startsWith("println!();\n");
        assertThat(print("\na", AsciiArtPrintStyle.KOTLIN, 1)).startsWith("println()\n");
        assertThat(print("\na", AsciiArtPrintStyle.SWIFT, 1)).startsWith("print()\n");
        assertThat(print("\na", AsciiArtPrintStyle.LUA, 1)).startsWith("print()\n");
        assertThat(print("\na", AsciiArtPrintStyle.POWERSHELL, 1)).startsWith("Write-Output ''\n");
        assertThat(print("\na", AsciiArtPrintStyle.BATCH, 1)).startsWith("echo(\n");
        assertThat(print("\n", AsciiArtPrintStyle.BATCH, 1)).isEmpty();
    }

    // ---- Print styles: escaping the torture line ----

    private static final String TORTURE_DOUBLE_QUOTED =
        " !\\\"#$%&'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvwxyz{|}~";
    private static final String TORTURE_SINGLE_QUOTED =
        " !\"#$%&\\'()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\\\]^_`abcdefghijklmnopqrstuvwxyz{|}~";
    private static final String TORTURE_SHELL =
        " !\"#$%&'\\''()*+,-./0123456789:;<=>?@ABCDEFGHIJKLMNOPQRSTUVWXYZ[\\]^_`abcdefghijklmnopqrstuvwxyz{|}~";

    @Test
    void theTortureLineHasAllNinetyFivePrintableCharacters() {
        assertThat(TORTURE).hasLength(95);
        assertThat(TORTURE).startsWith(" !\"#");
        assertThat(TORTURE).endsWith("{|}~");
    }

    @Test
    void bashEchoQuotesEveryApostropheWithTheCloseEscapeReopenIdiom() {
        assertThat(print(TORTURE, AsciiArtPrintStyle.BASH_ECHO, 0)).isEqualTo("echo '" + TORTURE_SHELL + "'");
        assertThat(AsciiArtCopySupport.escapeSingleQuotedShell("it's 'x'")).isEqualTo("it'\\''s '\\''x'\\''");
    }

    @Test
    void shPrintfUsesTheSameQuotingWithAFormatString() {
        assertThat(print(TORTURE, AsciiArtPrintStyle.SH_PRINTF, 0))
            .isEqualTo("printf '%s\\n' '" + TORTURE_SHELL + "'");
    }

    @Test
    void heredocLinesStayByteIdenticalAndTheDelimiterIsQuoted() {
        String art = TORTURE + "\n\n" + "\\ '\"`$x ";
        List<String> out = lines(print(art, AsciiArtPrintStyle.BASH_HEREDOC, 0));
        assertThat(out).containsExactly(
            "cat <<'KORTTY_ASCII_ART'", TORTURE, "", "\\ '\"`$x ", "KORTTY_ASCII_ART").inOrder();
        List<String> gapped = lines(print("a", AsciiArtPrintStyle.BASH_HEREDOC, 2));
        assertThat(gapped.get(1)).isEqualTo("  a");
    }

    @Test
    void heredocDelimiterIsLengthenedWhenAPictureLineEqualsIt() {
        List<String> out = lines(print("a\nKORTTY_ASCII_ART\nb", AsciiArtPrintStyle.BASH_HEREDOC, 0));
        assertThat(out.get(0)).isEqualTo("cat <<'KORTTY_ASCII_ART_1'");
        assertThat(out.get(out.size() - 1)).isEqualTo("KORTTY_ASCII_ART_1");
        assertThat(out.get(2)).isEqualTo("KORTTY_ASCII_ART");
        assertThat(AsciiArtCopySupport.uniqueHeredocDelimiter(
            List.of("KORTTY_ASCII_ART", "KORTTY_ASCII_ART_1"))).isEqualTo("KORTTY_ASCII_ART_2");
    }

    @Test
    void perlRubyAndPhpEscapeBackslashAndApostropheOnly() {
        assertThat(print(TORTURE, AsciiArtPrintStyle.PERL, 0))
            .isEqualTo("print '" + TORTURE_SINGLE_QUOTED + "', \"\\n\";");
        assertThat(print(TORTURE, AsciiArtPrintStyle.RUBY, 0)).isEqualTo("puts '" + TORTURE_SINGLE_QUOTED + "'");
        assertThat(print(TORTURE, AsciiArtPrintStyle.PHP, 0))
            .isEqualTo("echo '" + TORTURE_SINGLE_QUOTED + "', PHP_EOL;");
        assertThat(AsciiArtCopySupport.escapeBackslashSingleQuoted("\\'\"")).isEqualTo("\\\\\\'\"");
    }

    @Test
    void doubleQuotedFamilyEscapesBackslashAndQuoteAndNothingElse() {
        assertThat(print(TORTURE, AsciiArtPrintStyle.PYTHON, 0)).isEqualTo("print(\"" + TORTURE_DOUBLE_QUOTED + "\")");
        assertThat(print(TORTURE, AsciiArtPrintStyle.JAVASCRIPT, 0))
            .isEqualTo("console.log(\"" + TORTURE_DOUBLE_QUOTED + "\");");
        assertThat(print(TORTURE, AsciiArtPrintStyle.JAVA, 0))
            .isEqualTo("System.out.println(\"" + TORTURE_DOUBLE_QUOTED + "\");");
        assertThat(print(TORTURE, AsciiArtPrintStyle.CSHARP, 0))
            .isEqualTo("Console.WriteLine(\"" + TORTURE_DOUBLE_QUOTED + "\");");
        assertThat(print(TORTURE, AsciiArtPrintStyle.GO, 0)).isEqualTo("fmt.Println(\"" + TORTURE_DOUBLE_QUOTED + "\")");
        assertThat(print(TORTURE, AsciiArtPrintStyle.SWIFT, 0)).isEqualTo("print(\"" + TORTURE_DOUBLE_QUOTED + "\")");
        assertThat(print(TORTURE, AsciiArtPrintStyle.LUA, 0)).isEqualTo("print(\"" + TORTURE_DOUBLE_QUOTED + "\")");
        assertThat(AsciiArtCopySupport.escapeBackslashDoubleQuoted("\\\"'")).isEqualTo("\\\\\\\"'");
    }

    @Test
    void rustDoublesBracesOnTopOfTheDoubleQuotedEscaping() {
        String expected = TORTURE_DOUBLE_QUOTED.replace("{", "{{").replace("}", "}}");
        assertThat(print(TORTURE, AsciiArtPrintStyle.RUST, 0)).isEqualTo("println!(\"" + expected + "\");");
        assertThat(AsciiArtCopySupport.escapeRustFormat("{x}")).isEqualTo("{{x}}");
    }

    @Test
    void kotlinEscapesTheDollarSignOnTopOfTheDoubleQuotedEscaping() {
        String expected = TORTURE_DOUBLE_QUOTED.replace("$", "\\$");
        assertThat(print(TORTURE, AsciiArtPrintStyle.KOTLIN, 0)).isEqualTo("println(\"" + expected + "\")");
        assertThat(AsciiArtCopySupport.escapeKotlinTemplate("$x ${y}")).isEqualTo("\\$x \\${y}");
    }

    @Test
    void cBreaksTrigraphsAndLeavesALoneQuestionMarkAlone() {
        // The torture line holds a single '?', so it is escaped like the double-quoted family.
        assertThat(print(TORTURE, AsciiArtPrintStyle.C, 0)).isEqualTo("puts(\"" + TORTURE_DOUBLE_QUOTED + "\");");
        assertThat(AsciiArtCopySupport.escapeC("??=")).isEqualTo("?\\?=");
        assertThat(AsciiArtCopySupport.escapeC("???")).isEqualTo("?\\?\\?");
        assertThat(AsciiArtCopySupport.escapeC("a?b")).isEqualTo("a?b");
        assertThat(AsciiArtCopySupport.escapeC("\\\"")).isEqualTo("\\\\\\\"");
    }

    @Test
    void powerShellDoublesApostrophes() {
        assertThat(print(TORTURE, AsciiArtPrintStyle.POWERSHELL, 0))
            .isEqualTo("Write-Output '" + TORTURE.replace("'", "''") + "'");
        assertThat(AsciiArtCopySupport.escapePowerShellSingleQuoted("it's")).isEqualTo("it''s");
    }

    @Test
    void batchCaretEscapesOperatorsAndQuotesAndDoublesPercent() {
        String out = print(TORTURE, AsciiArtPrintStyle.BATCH, 0);
        assertThat(out).startsWith("echo( !^\"#$%%^&'^(^)*+");
        assertThat(out).contains("^<=^>?");
        assertThat(out).contains("[\\]^^_`");
        assertThat(out).endsWith("{^|}~");
        assertThat(AsciiArtCopySupport.escapeBatch("^&|<>()%\"")).isEqualTo("^^^&^|^<^>^(^)%%^\"");
        assertThat(AsciiArtCopySupport.escapeBatch("plain text!")).isEqualTo("plain text!");
    }

    @Test
    void batchNeverEntersQuoteModeSoOperatorsAfterAnOddQuoteStayEscaped() {
        // An unescaped quote would switch cmd into quote mode, where the later carets print verbatim.
        assertThat(AsciiArtCopySupport.escapeBatch("(o\")")).isEqualTo("^(o^\"^)");
        assertThat(AsciiArtCopySupport.escapeBatch("a\"b")).isEqualTo("a^\"b");
        assertThat(print("\"&\" | \"", AsciiArtPrintStyle.BATCH, 1)).isEqualTo("echo( ^\"^&^\" ^| ^\"");
        assertThat(AsciiArtCopySupport.escapeBatch("say \"hi\" & bye")).isEqualTo("say ^\"hi^\" ^& bye");
    }

    // ---- Preview line ----

    @Test
    void previewLineShowsTheFirstContentLine() {
        assertThat(AsciiArtCopySupport.previewLine(" /\\", AsciiArtCommentStyle.HASH, null, 2)).isEqualTo("#   /\\");
        assertThat(AsciiArtCopySupport.previewLine("/\\", AsciiArtCommentStyle.C_BLOCK, null, 4)).isEqualTo(" *    /\\");
        assertThat(AsciiArtCopySupport.previewLine("--", AsciiArtCommentStyle.XML_BLOCK, null, 1)).isEqualTo(" -=");
        assertThat(AsciiArtCopySupport.previewLine("a\"b", AsciiArtCommentStyle.JSON_STRINGS, null, 1))
            .isEqualTo(" \"a\\\"b\"");
        assertThat(AsciiArtCopySupport.previewLine("/\\", null, AsciiArtPrintStyle.PYTHON, 1)).isEqualTo("print(\" /\\\\\")");
        assertThat(AsciiArtCopySupport.previewLine("/\\", null, AsciiArtPrintStyle.BASH_HEREDOC, 1)).isEqualTo(" /\\");
        assertThat(AsciiArtCopySupport.previewLine("/\\\n(x)", null, null, 1)).isEqualTo("/\\");
        assertThat(AsciiArtCopySupport.previewLine("", AsciiArtCommentStyle.HASH, null, 3)).isEqualTo("#");
        assertThat(AsciiArtCopySupport.previewLine(null, null, AsciiArtPrintStyle.RUBY, 3)).isEqualTo("puts");
    }

    // ---- Enum labels ----

    @Test
    void styleLabelsCarryMarkerOrStatementAndAreEmptyForNone() {
        assertThat(AsciiArtCommentStyle.NONE.label()).isEmpty();
        assertThat(AsciiArtCommentStyle.NONE.marker()).isEmpty();
        assertThat(AsciiArtCommentStyle.HASH.label()).isEqualTo("#  -  Bash, Perl, Python, Ruby, YAML, TOML, PowerShell");
        assertThat(AsciiArtCommentStyle.C_BLOCK.isBlock()).isTrue();
        assertThat(AsciiArtCommentStyle.HASH.isBlock()).isFalse();
        assertThat(AsciiArtPrintStyle.NONE.label()).isEmpty();
        assertThat(AsciiArtPrintStyle.PYTHON.label()).isEqualTo("Python - print()");
        assertThat(AsciiArtPrintStyle.BASH_ECHO.label()).isEqualTo("Bash - echo");
        for (AsciiArtPrintStyle style : AsciiArtPrintStyle.values()) {
            if (style != AsciiArtPrintStyle.NONE) {
                assertThat(style.label()).contains(style.languageName());
                assertThat(style.label()).contains(style.statement());
            }
        }
    }
}
