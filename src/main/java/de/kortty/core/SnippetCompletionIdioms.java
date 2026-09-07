package de.kortty.core;

import de.kortty.core.SnippetCompletionSupport.ContextKind;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The fixed idiom table behind the snippet editor's completion list: what a {@code for … in},
 * {@code foreach (} or {@code use Module} position commonly takes when no symbol of the script
 * itself fits. The rows sit behind the harvested symbols in the list (rank 2), so a script that
 * defines an array still offers that array first.
 *
 * <p>Every row is Monaco snippet text: {@code ${n:default}} placeholders numbered from 1 (a plain
 * {@code ${n}} for an empty one), {@code \$} for a literal dollar sign in front of a letter, because
 * Monaco would otherwise read {@code $args} as a snippet variable and drop it. A dollar sign in front
 * of anything else ({@code $(}, {@code $@}) is literal by itself.
 */
final class SnippetCompletionIdioms {

    /** Module key of the row offered for a perl module the table does not know. */
    static final String ANY_MODULE = "*";

    /** Upper bound the table must stay under; {@link SnippetCompletionIdiomsTest} enforces it. */
    static final int MAX_ROWS = 40;

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{(\\d+)(?::((?:[^{}\\\\]|\\\\.)*))?\\}");
    private static final Pattern ESCAPE = Pattern.compile("\\\\([$}\\\\])");

    record Idiom(String language, ContextKind kind, String module, String insertText) {
    }

    static final List<Idiom> ALL = List.of(
        // bash: for NAME in ␣
        row("bash", ContextKind.FOR_IN, "\"$@\""),
        row("bash", ContextKind.FOR_IN, "$(${1:command})"),
        row("bash", ContextKind.FOR_IN, "*.${1:txt}"),
        row("bash", ContextKind.FOR_IN, "{1..${1:10}}"),
        row("bash", ContextKind.FOR_IN, "$(seq 1 ${1:10})"),
        // perl: foreach my $x (␣
        row("perl", ContextKind.FOREACH_PAREN, "@ARGV"),
        row("perl", ContextKind.FOREACH_PAREN, "1..${1:10}"),
        row("perl", ContextKind.FOREACH_PAREN, "glob(\"${1:*.txt}\")"),
        row("perl", ContextKind.FOREACH_PAREN, "sort keys %${1:hash}"),
        row("perl", ContextKind.FOREACH_PAREN, "split(/${1:,}/, \\$${2:line})"),
        // perl: use Module ␣ — POSIX first, in exactly this order
        module("POSIX", "qw(strftime floor ceil)"),
        module("POSIX", "qw(strftime)"),
        module("POSIX", "qw(floor ceil)"),
        module("POSIX", "qw(:sys_wait_h)"),
        module("Getopt::Long", "qw(GetOptions)"),
        module("Getopt::Long", "qw(:config no_ignore_case bundling)"),
        module("List::Util", "qw(first sum max min)"),
        module("List::Util", "qw(any all none)"),
        module("List::Util", "qw(uniq shuffle)"),
        module("File::Basename", "qw(basename dirname)"),
        module("File::Basename", "qw(fileparse)"),
        module("Scalar::Util", "qw(blessed reftype looks_like_number)"),
        module("Time::HiRes", "qw(time sleep)"),
        module("Time::HiRes", "qw(gettimeofday tv_interval)"),
        module("Cwd", "qw(getcwd abs_path)"),
        module("File::Temp", "qw(tempfile tempdir)"),
        module(ANY_MODULE, "qw(${1})"),
        // python: for x in ␣
        row("python", ContextKind.FOR_IN, "range(${1:10})"),
        row("python", ContextKind.FOR_IN, "enumerate(${1:items})"),
        row("python", ContextKind.FOR_IN, "sys.argv[1:]"),
        row("python", ContextKind.FOR_IN, "os.listdir(${1:'.'})"),
        // powershell: foreach ($x in ␣
        row("powershell", ContextKind.FOR_IN, "1..${1:10}"),
        row("powershell", ContextKind.FOR_IN, "Get-ChildItem ${1:.}"),
        row("powershell", ContextKind.FOR_IN, "\\$args"),
        row("powershell", ContextKind.FOR_IN, "(Get-Content ${1:file})"),
        // ruby: for x in ␣
        row("ruby", ContextKind.FOR_IN, "(1..${1:10})"),
        row("ruby", ContextKind.FOR_IN, "ARGV"),
        row("ruby", ContextKind.FOR_IN, "Dir.glob(${1:'*'})"));

    private SnippetCompletionIdioms() {
    }

    private static Idiom row(String language, ContextKind kind, String insertText) {
        return new Idiom(language, kind, "", insertText);
    }

    private static Idiom module(String module, String insertText) {
        return new Idiom("perl", ContextKind.USE_MODULE, module, insertText);
    }

    /**
     * The snippet texts for one context, in table order. A {@link ContextKind#USE_MODULE} context
     * gets the rows of its module, or the {@link #ANY_MODULE} row when the module is unknown; any
     * other kind gets the rows of its language. Empty when the table has nothing for the context.
     */
    static List<String> forContext(String language, ContextKind kind, String module) {
        List<String> result = new ArrayList<>();
        if (language == null || kind == null) {
            return result;
        }
        if (kind == ContextKind.USE_MODULE) {
            String key = module != null ? module.trim() : "";
            collect(language, kind, key, result);
            if (result.isEmpty()) {
                collect(language, kind, ANY_MODULE, result);
            }
            return result;
        }
        collect(language, kind, "", result);
        return result;
    }

    private static void collect(String language, ContextKind kind, String module, List<String> into) {
        for (Idiom idiom : ALL) {
            if (idiom.language().equals(language) && idiom.kind() == kind && idiom.module().equals(module)) {
                into.add(idiom.insertText());
            }
        }
    }

    /**
     * The list label for a snippet row: placeholders replaced by their default text (an empty
     * placeholder shows as {@code …}) and escapes resolved, e.g. {@code $(${1:command})} →
     * {@code $(command)}.
     */
    static String label(String insertText) {
        if (insertText == null || insertText.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        Matcher matcher = PLACEHOLDER.matcher(insertText);
        int last = 0;
        while (matcher.find()) {
            out.append(insertText, last, matcher.start());
            String fallback = matcher.group(2);
            out.append(fallback != null && !fallback.isEmpty() ? fallback : "…");
            last = matcher.end();
        }
        out.append(insertText, last, insertText.length());
        return ESCAPE.matcher(out).replaceAll("$1");
    }

    /** The placeholder numbers used by a snippet row, in order of appearance (duplicates kept). */
    static List<Integer> placeholderNumbers(String insertText) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = PLACEHOLDER.matcher(insertText != null ? insertText : "");
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group(1)));
        }
        return numbers;
    }
}
