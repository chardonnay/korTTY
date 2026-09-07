package de.kortty.core;

import de.kortty.core.ScriptLanguageMixSupport.EmbeddedLanguage;
import de.kortty.core.ScriptLanguageMixSupport.LanguageMix;
import de.kortty.core.SnippetAiResponseSupport.CompletionSuggestion;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The model-free half of the snippet editor's completion list: which position the caret is in
 * ({@code for X in ␣}, {@code foreach my $x (␣}, {@code use POSIX ␣}, or an ordinary expression),
 * which arrays, hashes, variables and functions the script itself defines, the ranked candidate
 * list Monaco shows immediately, and the bounded window plus symbol line the AI request is built
 * from. Everything here is pure and runs on the FX thread per request, so the harvest is bounded
 * ({@link #MAX_TEXT_CHARS}, {@link #MAX_LINES}, {@link #WINDOW_LINES}) and every rule is a
 * line-anchored regex evaluated once per line.
 *
 * <p>Candidates carry an absolute {@code replaceStart}: the offset of the token the user has typed
 * so far on the caret line (the caret itself when nothing is typed), so Monaco replaces
 * {@code "$A} with {@code "${ARR[@]}"} instead of appending to it. {@code sortText} is
 * {@code rank_index} (rank 0 primary, 1 secondary, 2 idioms, 3 plain identifiers, 4 AI);
 * {@code filterText} is the bare name followed by the insert text, so a typed {@code "$A} still
 * matches {@code "${ARR[@]}"}.
 */
public final class SnippetCompletionSupport {

    /**
     * Texts longer than this are harvested only in a window around the caret and never scanned for
     * embedded languages.
     */
    public static final int MAX_TEXT_CHARS = 512_000;
    /** Texts with more lines than this are harvested only in a window around the caret. */
    public static final int MAX_LINES = 5_000;
    /** Lines above and below the caret that a windowed harvest still reads. */
    public static final int WINDOW_LINES = 2_500;
    /** Names kept per symbol bucket (arrays, hashes, variables, functions). */
    public static final int MAX_NAMES_PER_BUCKET = 60;
    /** Frequency-ranked plain identifiers kept for the generic bucket. */
    public static final int MAX_IDENTIFIERS = 200;
    /** Candidates returned when the caller passes no positive limit. */
    public static final int DEFAULT_MAX_ITEMS = 40;
    /** Characters of text before the caret the AI prompt window carries at most. */
    public static final int PREFIX_MAX_CHARS = 6_000;
    /** Characters of text after the caret the AI prompt window carries at most. */
    public static final int SUFFIX_MAX_CHARS = 1_500;
    /** Symbol names the prompt's "Known symbols" line lists at most. */
    public static final int MAX_CONTEXT_NAMES = 40;
    /** Characters of an AI candidate's first line shown in its label. */
    public static final int AI_LABEL_MAX_CHARS = 60;
    public static final String AI_LABEL_PREFIX = "✨ ";

    static final int RANK_PRIMARY = 0;
    static final int RANK_SECONDARY = 1;
    static final int RANK_IDIOM = 2;
    static final int RANK_IDENTIFIER = 3;
    static final int RANK_AI = 4;
    /** Shorter words are never offered as plain identifiers. */
    static final int MIN_IDENTIFIER_LENGTH = 3;

    /** The position the caret is in, which decides what the list offers. */
    public enum ContextKind {
        /** {@code for X in ␣} (bash, python, powershell, ruby): an iterable is expected. */
        FOR_IN,
        /** perl {@code foreach my $x (␣}: a list is expected. */
        FOREACH_PAREN,
        /** perl {@code use Module ␣}: an import list is expected. */
        USE_MODULE,
        /** Any other position: functions, variables and plain identifiers. */
        GENERIC,
        /** Nothing to offer (the caret line is a comment). */
        NONE
    }

    /** What a candidate stands for; the UI maps it to an icon and a translated detail label. */
    public enum CandidateKind {
        ARRAY, HASH, VARIABLE, FUNCTION, IDIOM, TEXT, AI
    }

    /**
     * @param language     the effective language the rules ran for
     * @param token        what the user has typed at the completion position so far (may be empty)
     * @param replaceStart absolute offset of {@code token} — always on the caret line
     * @param module       the module of a {@link ContextKind#USE_MODULE} context, otherwise empty
     */
    public record Context(String language, ContextKind kind, String token, int replaceStart, String module) {

        public Context {
            language = language != null ? language : "plain";
            kind = kind != null ? kind : ContextKind.NONE;
            token = token != null ? token : "";
            module = module != null ? module : "";
        }
    }

    /** The names a script defines, per bucket, in order of first appearance; identifiers are frequency-ranked. */
    public record Symbols(List<String> arrays, List<String> hashes, List<String> scalars, List<String> functions,
                          List<String> identifiers) {

        public static final Symbols EMPTY = new Symbols(List.of(), List.of(), List.of(), List.of(), List.of());

        public Symbols {
            arrays = arrays != null ? List.copyOf(arrays) : List.of();
            hashes = hashes != null ? List.copyOf(hashes) : List.of();
            scalars = scalars != null ? List.copyOf(scalars) : List.of();
            functions = functions != null ? List.copyOf(functions) : List.of();
            identifiers = identifiers != null ? List.copyOf(identifiers) : List.of();
        }

        public boolean isEmpty() {
            return arrays.isEmpty() && hashes.isEmpty() && scalars.isEmpty() && functions.isEmpty()
                && identifiers.isEmpty();
        }

        /** The same symbols without the frequency-ranked identifier bucket. */
        public Symbols withoutIdentifiers() {
            return identifiers.isEmpty() ? this : new Symbols(arrays, hashes, scalars, functions, List.of());
        }
    }

    /**
     * One list entry. {@code snippet} marks Monaco snippet syntax ({@code ${1:…}} placeholders) in
     * {@code insertText}; {@code replaceStart} is the absolute offset the insert text replaces from.
     */
    public record Candidate(String label, String insertText, String filterText, CandidateKind kind, boolean snippet,
                            String sortText, int replaceStart, String documentation) {

        public Candidate {
            label = label != null ? label : "";
            insertText = insertText != null ? insertText : "";
            filterText = filterText != null ? filterText : "";
            kind = kind != null ? kind : CandidateKind.TEXT;
            sortText = sortText != null ? sortText : "";
            documentation = documentation != null ? documentation : "";
        }
    }

    /**
     * The text the AI request sends around the caret: {@code before} starts at a line beginning and
     * ends at the caret, {@code after} starts at the caret and ends at a line end; the flags say
     * whether text was cut off. {@code line} and {@code column} are 1-based.
     */
    public record PromptWindow(String before, String after, boolean beforeTruncated, boolean afterTruncated,
                               int line, int column) {
    }

    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]*";
    private static final String PS_NAME = "[A-Za-z_][A-Za-z0-9_\\-]*";

    /**
     * A comment line in any of the snippet languages. A private copy of
     * {@code SnippetDiagramOutline.COMMENT}; deliberately not {@code ScriptLanguage.commentPrefix()},
     * which maps unknown ids (java, typescript, sql, …) to bash.
     */
    private static final Pattern COMMENT_LINE = Pattern.compile("^\\s*(?:#|//|--|;|::|\"\"\"|'''|<#|/\\*|\\*)");

    // ---------------------------------------------------------------- context (caret line up to the caret)

    private static final Pattern BASH_FOR_IN_CONTEXT =
        Pattern.compile("^\\s*(?:for|select)\\s+" + NAME + "\\s+in\\s(.*)$");
    private static final Pattern PERL_FOREACH_CONTEXT =
        Pattern.compile("^\\s*(?:foreach|for)\\s+(?:(?:my|our|state|local)\\s+)?\\$" + NAME + "\\s*\\((.*)$");
    private static final Pattern PERL_FOREACH_BARE_CONTEXT = Pattern.compile("^\\s*foreach\\s*\\((.*)$");
    private static final Pattern PERL_USE_CONTEXT = Pattern.compile("^\\s*use\\s+([A-Za-z_][A-Za-z0-9_:]*)\\s(.*)$");
    private static final Pattern PYTHON_FOR_IN_CONTEXT = Pattern.compile(
        "(?:^|[\\s(\\[{])for\\s+" + NAME + "(?:\\s*,\\s*" + NAME + ")*\\s+in\\s(.*)$");
    private static final Pattern POWERSHELL_FOREACH_CONTEXT =
        Pattern.compile("^\\s*foreach\\s*\\(\\s*\\$" + NAME + "\\s+in\\s(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUBY_FOR_IN_CONTEXT =
        Pattern.compile("^\\s*for\\s+" + NAME + "(?:\\s*,\\s*" + NAME + ")*\\s+in\\s(.*)$");

    // ---------------------------------------------------------------- bash

    private static final Pattern BASH_FUNCTION_KEYWORD = Pattern.compile("^\\s*function\\s+(" + NAME + ")");
    private static final Pattern BASH_FUNCTION_PARENS = Pattern.compile("^\\s*(" + NAME + ")\\s*\\(\\s*\\)");
    private static final Pattern BASH_DECLARE =
        Pattern.compile("^\\s*(declare|local|typeset|readonly|export)\\s+(.*)$");
    private static final Pattern BASH_MAPFILE = Pattern.compile("^\\s*(?:mapfile|readarray)\\s+(.*)$");
    private static final Pattern BASH_READ = Pattern.compile("^\\s*(?:while\\s+)?(?:IFS=\\S*\\s+)?read\\s+(.*)$");
    private static final Pattern BASH_ARRAY_ASSIGN = Pattern.compile("^\\s*(" + NAME + ")\\+?=\\(");
    private static final Pattern BASH_ARRAY_INDEX = Pattern.compile("^\\s*(" + NAME + ")\\[[^\\]]*\\]\\+?=");
    private static final Pattern BASH_SCALAR_ASSIGN = Pattern.compile("^\\s*(" + NAME + ")\\+?=(?!\\()");
    private static final Pattern BASH_FOR = Pattern.compile("^\\s*(?:for|select)\\s+(" + NAME + ")\\s+in\\b");
    private static final Pattern BASH_FOR_C = Pattern.compile("^\\s*for\\s*\\(\\(\\s*(" + NAME + ")\\s*=");
    private static final Pattern BASH_GETOPTS =
        Pattern.compile("^\\s*(?:while\\s+)?getopts\\s+\\S+\\s+(" + NAME + ")");

    // ---------------------------------------------------------------- perl

    private static final Pattern PERL_SUB = Pattern.compile("^\\s*sub\\s+(" + NAME + ")");
    private static final Pattern PERL_DECL = Pattern.compile("^\\s*(?:my|our|local|state)\\s+([$@%])(" + NAME + ")");
    private static final Pattern PERL_DECL_LIST = Pattern.compile("^\\s*(?:my|our|local|state)\\s*\\(([^)]*)\\)");
    private static final Pattern PERL_SIGIL_NAME = Pattern.compile("([$@%])(" + NAME + ")");
    private static final Pattern PERL_ASSIGN = Pattern.compile("^\\s*([$@%])(" + NAME + ")\\s*[.+\\-*/]?=(?![=~>])");
    private static final Pattern PERL_FOREACH_VAR =
        Pattern.compile("^\\s*(?:foreach|for)\\s+(?:(?:my|our|state|local)\\s+)?\\$(" + NAME + ")\\s*\\(");
    private static final Pattern PERL_COND_DECL =
        Pattern.compile("^\\s*(?:while|if|unless|until|elsif)\\s*\\(\\s*(?:my|our)\\s+([$@%])(" + NAME + ")");

    // ---------------------------------------------------------------- python

    private static final Pattern PY_DEF = Pattern.compile("^\\s*(?:async\\s+)?def\\s+(" + NAME + ")\\s*\\((.*)$");
    private static final Pattern PY_CLASS = Pattern.compile("^\\s*class\\s+(" + NAME + ")");
    private static final Pattern PY_TUPLE_ASSIGN =
        Pattern.compile("^\\s*(" + NAME + "(?:\\s*,\\s*" + NAME + ")+)\\s*=(?!=)");
    private static final Pattern PY_ASSIGN = Pattern.compile("^\\s*(" + NAME + ")\\s*(?::\\s*([^=]+?))?\\s*"
        + "(?:[+\\-*/%|&^@]|//|\\*\\*|<<|>>)?=(?!=)\\s*(.*)$");
    private static final Pattern PY_FOR =
        Pattern.compile("^\\s*(?:async\\s+)?for\\s+(" + NAME + "(?:\\s*,\\s*" + NAME + ")*)\\s+in\\b");
    private static final Pattern PY_WITH_AS = Pattern.compile("^\\s*(?:async\\s+)?with\\b.*\\bas\\s+(" + NAME + ")");
    private static final Pattern PY_EXCEPT_AS = Pattern.compile("^\\s*except\\b.*\\bas\\s+(" + NAME + ")");
    private static final Pattern PY_ARRAY_RHS = Pattern.compile("^(?:list|tuple|set|frozenset|sorted|reversed|range"
        + "|enumerate|zip|map|filter|deque|collections\\.deque|os\\.listdir|glob\\.glob|glob|iter)\\s*\\(");
    private static final Pattern PY_ARRAY_CALL =
        Pattern.compile("\\.(?:split|rsplit|splitlines|readlines|keys|values|items)\\s*\\(");
    private static final Pattern PY_HASH_RHS = Pattern.compile("^(?:dict|defaultdict|OrderedDict|Counter"
        + "|collections\\.(?:defaultdict|OrderedDict|Counter)|json\\.loads?|yaml\\.safe_load|yaml\\.load)\\s*\\(");

    // ---------------------------------------------------------------- powershell

    private static final Pattern PS_FUNCTION =
        Pattern.compile("^\\s*(?:function|filter|workflow)\\s+(" + PS_NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_ASSIGN = Pattern.compile(
        "^\\s*(?:\\[([A-Za-z_][A-Za-z0-9_.]*(?:\\[\\])?)\\]\\s*)?\\$(?:script:|global:|local:|private:)?("
            + NAME + ")\\s*[+\\-*/%]?=(?!=)\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_TYPED_DECL = Pattern.compile("^\\s*(?:\\[[^\\]]*\\]\\s*)*"
        + "\\[([A-Za-z_][A-Za-z0-9_.]*(?:\\[\\])?)\\]\\s*\\$(" + NAME + ")\\s*,?\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_FOREACH_VAR =
        Pattern.compile("^\\s*foreach\\s*\\(\\s*\\$(" + NAME + ")\\s+in\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_PARAM_LINE = Pattern.compile("^\\s*param\\s*\\((.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_VAR_REF = Pattern.compile("\\$(" + NAME + ")");
    private static final Pattern PS_ARRAY_RHS = Pattern.compile("^(?:@\\(|\\S+\\.\\.\\S+|[^\"'(@]*,"
        + "|(?:Get-ChildItem|Get-Content|Import-Csv|Get-Process|Get-Service|Get-Item|gci|ls|dir|gc)\\b)",
        Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_HASH_RHS =
        Pattern.compile("^(?:@\\{|\\[ordered\\]\\s*@\\{)", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- ruby

    private static final Pattern RB_DEF = Pattern.compile("^\\s*def\\s+(?:self\\.)?(" + NAME + "[?!]?)");
    private static final Pattern RB_ASSIGN =
        Pattern.compile("^\\s*(@@?|\\$)?(" + NAME + ")\\s*(?:\\|\\||&&|[+\\-*/%])?=(?![=~>])\\s*(.*)$");
    private static final Pattern RB_FOR =
        Pattern.compile("^\\s*for\\s+(" + NAME + "(?:\\s*,\\s*" + NAME + ")*)\\s+in\\b");
    private static final Pattern RB_BLOCK_PARAMS = Pattern.compile("(?:\\bdo|\\{)\\s*\\|([^|]*)\\|");
    private static final Pattern RB_ARRAY_RHS = Pattern.compile(
        "^(?:\\[|%[wiWI]|Array\\.new|Array\\(|\\(?\\d+\\.\\.|ARGV\\b|Dir\\.glob|Dir\\["
            + "|.*\\.(?:split|to_a|map|select|reject|lines|readlines|each_slice|sort|uniq|flatten|compact)\\b)");
    private static final Pattern RB_HASH_RHS =
        Pattern.compile("^(?:\\{|Hash\\.new|Hash\\[|JSON\\.parse|YAML\\.(?:load|safe_load)|.*\\.(?:to_h|group_by)\\b)");

    // ---------------------------------------------------------------- generic (all other languages)

    private static final Pattern GEN_FUNCTION_KEYWORD = Pattern.compile(
        "^\\s*(?:(?:export|pub|public|private|protected|static|async|default|internal)\\s+)*"
            + "(?:function\\*?|def|sub|proc|fn|func|fun|macro|procedure|defun)\\s+(" + NAME + ")\\s*[(<]");
    private static final Pattern GEN_GO_METHOD = Pattern.compile("^\\s*func\\s+\\([^()]*\\)\\s+(" + NAME + ")\\s*\\(");
    private static final Pattern GEN_METHOD = Pattern.compile(
        "^\\s*(?:(?:public|private|protected|internal|static|final|abstract|synchronized|default|override|virtual"
            + "|inline|async|export|unsafe|extern)\\s+)*(?:[A-Za-z_][A-Za-z0-9_.]*(?:<[^<>]*>)?(?:\\[\\])*\\s+)?"
            + "(" + NAME + ")\\s*\\([^()]*\\)\\s*(?:throws\\s+[A-Za-z0-9_.,\\s]+|:\\s*[^{=]+)?\\{\\s*$");
    private static final Pattern GEN_ARROW = Pattern.compile(
        "^\\s*(?:export\\s+(?:default\\s+)?)?(?:const|let|var)\\s+(" + NAME + ")\\s*=\\s*(?:async\\s*)?"
            + "(?:\\([^()]*\\)|" + NAME + ")\\s*(?::\\s*[^=]+)?=>");
    private static final Pattern GEN_FUNC_EXPR = Pattern.compile(
        "^\\s*(?:export\\s+(?:default\\s+)?)?(?:(?:const|let|var)\\s+)?(?:[A-Za-z_][A-Za-z0-9_.]*\\.)?("
            + NAME + ")\\s*[:=]\\s*(?:async\\s+)?function\\b");
    private static final Pattern GEN_SQL_CREATE = Pattern.compile(
        "^\\s*create\\s+(?:or\\s+replace\\s+)?(?:(?:temporary|temp)\\s+)?(function|procedure|trigger|view|table)\\s+"
            + "(?:if\\s+not\\s+exists\\s+)?([A-Za-z_][A-Za-z0-9_.]*)", Pattern.CASE_INSENSITIVE);
    private static final Pattern GEN_VAR = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:(?:const|let|var|val|final|static|private|public|protected|readonly|def|global)\\s+)*"
            + "(?:([A-Za-z_][A-Za-z0-9_.]*(?:<[^<>]*>)?(?:\\[\\])*)\\s+)?(" + NAME + ")"
            + "\\s*(?::\\s*([^=]+?))?\\s*=(?![=>])\\s*(.*)$");
    private static final Pattern GEN_ARRAY_TYPE = Pattern.compile(
        "^(?:[A-Za-z_][A-Za-z0-9_.]*\\[\\]|(?:java\\.util\\.)?"
            + "(?:List|ArrayList|LinkedList|Set|HashSet|TreeSet|LinkedHashSet|Collection|Iterable|Iterator|Deque"
            + "|ArrayDeque|Queue|Vector|Stack|Array|ReadonlyArray|Sequence|Seq|Vec|slice)\\b)");
    private static final Pattern GEN_HASH_TYPE = Pattern.compile(
        "^(?:(?:java\\.util\\.)?(?:Map|HashMap|TreeMap|LinkedHashMap|SortedMap|NavigableMap|ConcurrentHashMap"
            + "|Dictionary|Hashtable|Properties|Record|Object|IDictionary)\\b|\\{)");
    private static final Pattern GEN_ARRAY_RHS = Pattern.compile(
        "^(?:\\[|new\\s+(?:Array|Set|ArrayList|LinkedList|HashSet|TreeSet|LinkedHashSet|ArrayDeque|Vector|Stack)\\b"
            + "|Array\\.(?:from|of)\\b|(?:List|Set|Arrays)\\.(?:of|asList)\\b"
            + "|Collections\\.(?:empty|singleton|unmodifiable)|vec!|make\\(\\[\\])");
    private static final Pattern GEN_HASH_RHS = Pattern.compile("^(?:\\{|new\\s+(?:Map|HashMap|TreeMap|LinkedHashMap"
        + "|ConcurrentHashMap|Hashtable|Properties|Object|Dictionary)\\b|Map\\.of\\b|Object\\.(?:fromEntries|create)\\b"
        + "|make\\(map\\[)");

    // ---------------------------------------------------------------- identifiers

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{2,}");
    private static final Pattern PS_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_\\-]*[A-Za-z0-9_]");
    private static final Pattern WORD = Pattern.compile("^" + NAME + "$");

    /** Reserved words of the snippet languages; never offered as plain identifiers. */
    private static final Set<String> STOP_WORDS = Set.copyOf(List.of((
        "if then else elif elsif fi for foreach while until do done in of case esac select function return exit break"
            + " continue local declare export readonly unset shift true false eval sub my our use no package require"
            + " last next redo unless def class import from as with try except finally raise pass lambda yield global"
            + " nonlocal assert del not and or is none self cls async await var let const new this super typeof"
            + " instanceof void delete switch default throw catch static public private protected final abstract"
            + " interface extends implements enum int long double float boolean char byte short string package goto"
            + " native synchronized transient volatile throws null undefined constructor module exports insert update"
            + " create table where group order by having join left right inner outer on union all distinct into"
            + " values limit offset drop alter index primary key foreign references between like exists desc asc cast"
            + " when end begin rescue ensure nil elseif func fn pub struct impl trait mut match loop param process"
            + " filter type").split(" ")));

    private SnippetCompletionSupport() {
    }

    // ================================================================ entry points

    /**
     * The language whose rules apply at the caret: the declared language normalized (a shebang
     * counts when nothing is declared), unless the caret sits inside a foreign block — a heredoc
     * piped into another interpreter, or a script step of a pipeline document — in which case that
     * block's language wins. The embedded-language scan is skipped above {@link #MAX_TEXT_CHARS}.
     */
    public static String effectiveLanguage(String declaredLanguage, String text, int caretOffset) {
        String value = text != null ? text : "";
        String base = SnippetLanguageSupport.detectSnippetLanguage(declaredLanguage, value);
        if (value.isEmpty() || value.length() > MAX_TEXT_CHARS) {
            return base;
        }
        int caret = clamp(caretOffset, value.length());
        int line = lineNumberAt(value, caret);
        LanguageMix mix = ScriptLanguageMixSupport.detect(declaredLanguage, value);
        for (EmbeddedLanguage embedded : mix.embedded()) {
            if (embedded.startLine() <= line && line <= embedded.endLine()) {
                return SnippetLanguageSupport.normalizeSnippetLanguage(embedded.language());
            }
        }
        return base;
    }

    /** Classifies the caret position from the caret line up to the caret. */
    public static Context classify(String language, String text, int caretOffset) {
        String value = text != null ? text : "";
        String lang = SnippetLanguageSupport.normalizeSnippetLanguage(language);
        int caret = clamp(caretOffset, value.length());
        String lineBefore = value.substring(lineStartAt(value, caret), caret);
        if (COMMENT_LINE.matcher(lineBefore).find()) {
            return new Context(lang, ContextKind.NONE, "", caret, "");
        }
        Matcher matcher;
        switch (lang) {
            case "bash" -> {
                matcher = BASH_FOR_IN_CONTEXT.matcher(lineBefore);
                if (matcher.find() && matcher.group(1).indexOf(';') < 0) {
                    return valueContext(lang, ContextKind.FOR_IN, afterLastWhitespace(matcher.group(1)), caret);
                }
            }
            case "perl" -> {
                matcher = PERL_FOREACH_CONTEXT.matcher(lineBefore);
                if (!matcher.find()) {
                    matcher = PERL_FOREACH_BARE_CONTEXT.matcher(lineBefore);
                    if (!matcher.find()) {
                        matcher = null;
                    }
                }
                if (matcher != null && matcher.group(1).indexOf(')') < 0) {
                    return valueContext(lang, ContextKind.FOREACH_PAREN, afterLastComma(matcher.group(1)), caret);
                }
                matcher = PERL_USE_CONTEXT.matcher(lineBefore);
                if (matcher.find() && matcher.group(2).indexOf(';') < 0) {
                    String token = matcher.group(2).stripLeading();
                    return new Context(lang, ContextKind.USE_MODULE, token, caret - token.length(), matcher.group(1));
                }
            }
            case "python" -> {
                matcher = PYTHON_FOR_IN_CONTEXT.matcher(lineBefore);
                if (matcher.find() && matcher.group(1).indexOf(':') < 0) {
                    return valueContext(lang, ContextKind.FOR_IN, afterLast(matcher.group(1), " \t,(["), caret);
                }
            }
            case "powershell" -> {
                matcher = POWERSHELL_FOREACH_CONTEXT.matcher(lineBefore);
                if (matcher.find() && matcher.group(1).indexOf(')') < 0) {
                    return valueContext(lang, ContextKind.FOR_IN, afterLast(matcher.group(1), " \t,("), caret);
                }
            }
            case "ruby" -> {
                matcher = RUBY_FOR_IN_CONTEXT.matcher(lineBefore);
                if (matcher.find()) {
                    return valueContext(lang, ContextKind.FOR_IN, afterLast(matcher.group(1), " \t,"), caret);
                }
            }
            default -> {
            }
        }
        return genericContext(lang, lineBefore, caret);
    }

    /**
     * The arrays, hashes, variables and functions the script defines (and its frequency-ranked plain
     * identifiers), read with the rules of {@code language}. Comment lines are skipped; a text over
     * {@link #MAX_TEXT_CHARS} or {@link #MAX_LINES} is read only {@link #WINDOW_LINES} above and
     * below the caret.
     */
    public static Symbols harvest(String language, String text, int caretOffset) {
        String value = text != null ? text : "";
        if (value.isBlank()) {
            return Symbols.EMPTY;
        }
        String lang = SnippetLanguageSupport.normalizeSnippetLanguage(language);
        int caret = clamp(caretOffset, value.length());
        String[] lines = value.split("\n", -1);
        int caretLine = lineNumberAt(value, caret) - 1;
        int from = 0;
        int to = lines.length;
        if (value.length() > MAX_TEXT_CHARS || lines.length > MAX_LINES) {
            from = Math.max(0, caretLine - WINDOW_LINES);
            to = Math.min(lines.length, caretLine + WINDOW_LINES + 1);
        }
        Harvester harvester = new Harvester(lang);
        for (int i = from; i < to; i++) {
            String line = lines[i];
            // The caret line is the one being typed: its loop variable must not iterate over itself.
            if (i == caretLine || line.isBlank() || COMMENT_LINE.matcher(line).find()) {
                continue;
            }
            harvester.line(line);
        }
        return harvester.symbols(wordAt(value, caret, lang));
    }

    /** The ranked, deduplicated list for a context; empty for {@link ContextKind#NONE}. */
    public static List<Candidate> candidates(Context ctx, Symbols symbols, int maxItems) {
        if (ctx == null || ctx.kind() == ContextKind.NONE) {
            return List.of();
        }
        Symbols known = symbols != null ? symbols : Symbols.EMPTY;
        int limit = maxItems > 0 ? maxItems : DEFAULT_MAX_ITEMS;
        List<Draft> drafts = new ArrayList<>();
        switch (ctx.kind()) {
            case FOR_IN -> forInDrafts(ctx.language(), known, drafts);
            case FOREACH_PAREN -> perlForeachDrafts(known, drafts);
            case GENERIC -> genericDrafts(ctx.language(), known, drafts);
            default -> {
            }
        }
        for (String idiom : SnippetCompletionIdioms.forContext(ctx.language(), ctx.kind(), ctx.module())) {
            String label = SnippetCompletionIdioms.label(idiom);
            drafts.add(new Draft(label, idiom, label, CandidateKind.IDIOM, true, RANK_IDIOM));
        }
        return finish(drafts, ctx.replaceStart(), limit);
    }

    /** {@link #localCandidates(String, String, int, int, boolean)} with the identifier bucket included. */
    public static List<Candidate> localCandidates(String declaredLanguage, String text, int caretOffset, int maxItems) {
        return localCandidates(declaredLanguage, text, caretOffset, maxItems, true);
    }

    /**
     * Everything the list shows before the AI answers: language at the caret, context, harvest,
     * ranked candidates. {@code includeGenericIdentifiers} is false for languages whose Monaco
     * language service contributes its own word entries to the same list (javascript, typescript,
     * json, css, html); functions, variables and idioms are offered either way.
     */
    public static List<Candidate> localCandidates(String declaredLanguage, String text, int caretOffset, int maxItems,
                                                  boolean includeGenericIdentifiers) {
        String value = text != null ? text : "";
        int caret = clamp(caretOffset, value.length());
        String language = effectiveLanguage(declaredLanguage, value, caret);
        Context ctx = classify(language, value, caret);
        if (ctx.kind() == ContextKind.NONE) {
            return List.of();
        }
        Symbols symbols = harvest(language, value, caret);
        if (!includeGenericIdentifiers) {
            symbols = symbols.withoutIdentifiers();
        }
        return candidates(ctx, symbols, maxItems);
    }

    /**
     * Turns the model's suggestions into list entries behind the local ones: whitespace and line
     * endings normalized, blanks dropped, duplicates of local entries (and of each other) dropped.
     * The model is asked to continue the text after the caret, so with a typed token every entry is
     * re-anchored at the token start: a suggestion that repeats the token — or begins with one of
     * the local expansions of it, e.g. {@code "${ARR[@]}"; do …} for a typed {@code "$A} — replaces
     * the token, any other suggestion gets the token prepended. Either way all entries replace from
     * the same offset as the local ones. The label is {@value #AI_LABEL_PREFIX} plus the first
     * non-blank line, at most {@link #AI_LABEL_MAX_CHARS} characters; {@code documentation} is the
     * model's summary, or {@code aiDetail} when there is none.
     */
    public static List<Candidate> aiCandidates(Context ctx, int caretOffset, List<Candidate> local,
                                               List<CompletionSuggestion> ai, String aiDetail) {
        if (ai == null || ai.isEmpty()) {
            return List.of();
        }
        String token = "";
        int replaceStart = caretOffset;
        if (ctx != null && ctx.kind() != ContextKind.NONE && !ctx.token().isEmpty()
            && ctx.replaceStart() + ctx.token().length() == caretOffset) {
            token = ctx.token();
            replaceStart = ctx.replaceStart();
        }
        Set<String> seen = new HashSet<>();
        List<String> expansions = new ArrayList<>();
        if (local != null) {
            for (Candidate candidate : local) {
                if (candidate != null && seen.add(candidate.insertText()) && !candidate.snippet()
                    && candidate.replaceStart() == replaceStart) {
                    expansions.add(candidate.insertText());
                }
            }
        }
        String detail = aiDetail != null ? aiDetail.trim() : "";
        List<Candidate> out = new ArrayList<>();
        for (CompletionSuggestion suggestion : ai) {
            if (suggestion == null) {
                continue;
            }
            String body = normalizeAiText(suggestion.insertText());
            if (body.isBlank()) {
                continue;
            }
            String insertText = token.isEmpty() || replacesToken(body, token, expansions) ? body : token + body;
            if (!seen.add(insertText)) {
                continue;
            }
            // A continuation that opens with a line break is labelled by its own first line, not by
            // the token it was re-anchored behind.
            String label = AI_LABEL_PREFIX + shortenLabel(firstNonBlankLine(body.startsWith("\n") ? body : insertText));
            String filterText = token.isEmpty() ? insertText : token + " " + insertText;
            String documentation = !suggestion.summary().isBlank() ? suggestion.summary() : detail;
            out.add(new Candidate(label, insertText, filterText, CandidateKind.AI, false,
                sortText(RANK_AI, out.size()), replaceStart, documentation));
        }
        return List.copyOf(out);
    }

    /**
     * Whether Shift+TAB opens the list rather than outdenting: something has been typed on the
     * caret line and nothing but whitespace follows the caret. Mirrors the rule in
     * {@code completion.js}.
     */
    public static boolean shiftTabShouldOpenList(String lineBefore, String lineAfter) {
        return lineBefore != null && !lineBefore.isBlank() && (lineAfter == null || lineAfter.isBlank());
    }

    /** {@link #localContext(Context, Symbols)} for a declared language, text and caret. */
    public static String localContext(String declaredLanguage, String text, int caretOffset) {
        String value = text != null ? text : "";
        int caret = clamp(caretOffset, value.length());
        String language = effectiveLanguage(declaredLanguage, value, caret);
        return localContext(classify(language, value, caret), harvest(language, value, caret));
    }

    /**
     * The "Cursor context" and "Known symbols" lines of the AI prompt: what position the caret is
     * in, what has been typed there, and at most {@link #MAX_CONTEXT_NAMES} symbol names in bucket
     * order. Deterministic for the same inputs; empty when there is nothing to say.
     */
    public static String localContext(Context ctx, Symbols symbols) {
        Symbols known = symbols != null ? symbols : Symbols.EMPTY;
        StringBuilder out = new StringBuilder();
        if (ctx != null && ctx.kind() != ContextKind.NONE) {
            out.append("Cursor context: ").append(ctx.language()).append(", ").append(describe(ctx.kind()));
            if (ctx.kind() == ContextKind.USE_MODULE && !ctx.module().isEmpty()) {
                out.append(" for ").append(ctx.module());
            }
            if (!ctx.token().isEmpty()) {
                out.append(", typed so far: \"").append(ctx.token()).append('"');
            }
            out.append('.');
        }
        List<String> titles = List.of("arrays", "hashes", "variables", "functions", "other identifiers");
        List<List<String>> buckets =
            List.of(known.arrays(), known.hashes(), known.scalars(), known.functions(), known.identifiers());
        int[] shares = shareBudget(buckets, MAX_CONTEXT_NAMES);
        List<String> groups = new ArrayList<>();
        for (int i = 0; i < buckets.size(); i++) {
            if (shares[i] > 0) {
                groups.add(titles.get(i) + " " + String.join(", ", buckets.get(i).subList(0, shares[i])));
            }
        }
        if (!groups.isEmpty()) {
            if (out.length() > 0) {
                out.append('\n');
            }
            out.append("Known symbols: ").append(String.join("; ", groups)).append('.');
        }
        return out.toString();
    }

    /**
     * The text the AI request sends: up to {@link #PREFIX_MAX_CHARS} characters before the caret,
     * cut at a line beginning, and up to {@link #SUFFIX_MAX_CHARS} after it, cut at a line end. A
     * single line longer than the budget is cut mid-line rather than dropped.
     */
    public static PromptWindow promptWindow(String text, int caretOffset) {
        String value = text != null ? text : "";
        int caret = clamp(caretOffset, value.length());
        int start = 0;
        boolean beforeTruncated = false;
        if (caret > PREFIX_MAX_CHARS) {
            int cut = caret - PREFIX_MAX_CHARS;
            int newline = value.indexOf('\n', cut);
            start = newline >= 0 && newline < caret ? newline + 1 : cut;
            beforeTruncated = true;
        }
        int end = value.length();
        boolean afterTruncated = false;
        if (value.length() - caret > SUFFIX_MAX_CHARS) {
            int cut = caret + SUFFIX_MAX_CHARS;
            int newline = value.lastIndexOf('\n', cut);
            end = newline >= caret ? newline : cut;
            if (end > caret && value.charAt(end - 1) == '\r') {
                end--;
            }
            afterTruncated = true;
        }
        int lineStart = lineStartAt(value, caret);
        return new PromptWindow(value.substring(start, caret), value.substring(caret, end), beforeTruncated,
            afterTruncated, lineNumberAt(value, caret), caret - lineStart + 1);
    }

    // ================================================================ context helpers

    private static Context valueContext(String language, ContextKind kind, String token, int caret) {
        return new Context(language, kind, token, caret - token.length(), "");
    }

    /** The trailing identifier (with its sigil, where the language has one) before the caret. */
    private static Context genericContext(String language, String lineBefore, int caret) {
        int start = lineBefore.length();
        while (start > 0 && isIdentifierChar(lineBefore.charAt(start - 1), language)) {
            start--;
        }
        String sigils = sigils(language);
        if (start > 0 && sigils.indexOf(lineBefore.charAt(start - 1)) >= 0) {
            start--;
            if ("ruby".equals(language) && start > 0 && lineBefore.charAt(start) == '@'
                && lineBefore.charAt(start - 1) == '@') {
                start--;
            }
        }
        String token = lineBefore.substring(start);
        return new Context(language, ContextKind.GENERIC, token, caret - token.length(), "");
    }

    private static String sigils(String language) {
        return switch (language) {
            case "bash", "powershell" -> "$";
            case "perl" -> "$@%&";
            case "ruby" -> "@$";
            default -> "";
        };
    }

    private static boolean isIdentifierChar(char c, String language) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_'
            || (c == '-' && "powershell".equals(language));
    }

    private static String afterLastWhitespace(String rest) {
        return afterLast(rest, " \t");
    }

    private static String afterLastComma(String rest) {
        return afterLast(rest, ",").stripLeading();
    }

    private static String afterLast(String rest, String separators) {
        int cut = -1;
        for (int i = 0; i < separators.length(); i++) {
            cut = Math.max(cut, rest.lastIndexOf(separators.charAt(i)));
        }
        return rest.substring(cut + 1);
    }

    private static String describe(ContextKind kind) {
        return switch (kind) {
            case FOR_IN -> "for-in loop header (an iterable is expected)";
            case FOREACH_PAREN -> "foreach list (a list is expected)";
            case USE_MODULE -> "use-module import list";
            default -> "expression";
        };
    }

    /**
     * How many names each bucket may list: handed out one name per bucket per round, so a script
     * with fifty arrays still gets its hashes, variables and functions mentioned.
     */
    private static int[] shareBudget(List<List<String>> buckets, int budget) {
        int[] shares = new int[buckets.size()];
        int remaining = budget;
        boolean progress = true;
        while (remaining > 0 && progress) {
            progress = false;
            for (int i = 0; i < buckets.size() && remaining > 0; i++) {
                if (shares[i] < buckets.get(i).size()) {
                    shares[i]++;
                    remaining--;
                    progress = true;
                }
            }
        }
        return shares;
    }

    // ================================================================ candidate drafting

    private record Draft(String label, String insertText, String filterText, CandidateKind kind, boolean snippet,
                         int rank) {
    }

    private static void forInDrafts(String language, Symbols s, List<Draft> out) {
        switch (language) {
            case "bash" -> {
                symbolDrafts(out, s.arrays(), "\"${%s[@]}\"", CandidateKind.ARRAY, RANK_PRIMARY);
                symbolDrafts(out, s.hashes(), "\"${!%s[@]}\"", CandidateKind.HASH, RANK_PRIMARY);
                symbolDrafts(out, s.scalars(), "\"$%s\"", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
            case "python" -> {
                symbolDrafts(out, s.arrays(), "%s", CandidateKind.ARRAY, RANK_PRIMARY);
                symbolDrafts(out, s.hashes(), "%s.items()", CandidateKind.HASH, RANK_PRIMARY);
                symbolDrafts(out, s.arrays(), "enumerate(%s)", CandidateKind.ARRAY, RANK_SECONDARY);
                symbolDrafts(out, s.hashes(), "%s", CandidateKind.HASH, RANK_SECONDARY);
                symbolDrafts(out, s.arrays(), "range(len(%s))", CandidateKind.ARRAY, RANK_SECONDARY);
                symbolDrafts(out, s.scalars(), "%s", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
            case "powershell" -> {
                symbolDrafts(out, s.arrays(), "$%s", CandidateKind.ARRAY, RANK_PRIMARY);
                symbolDrafts(out, s.hashes(), "$%s.Keys", CandidateKind.HASH, RANK_PRIMARY);
                symbolDrafts(out, s.hashes(), "$%s.GetEnumerator()", CandidateKind.HASH, RANK_SECONDARY);
                symbolDrafts(out, s.scalars(), "$%s", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
            case "ruby" -> {
                symbolDrafts(out, s.arrays(), "%s", CandidateKind.ARRAY, RANK_PRIMARY);
                symbolDrafts(out, s.hashes(), "%s", CandidateKind.HASH, RANK_PRIMARY);
                symbolDrafts(out, s.hashes(), "%s.keys", CandidateKind.HASH, RANK_SECONDARY);
                symbolDrafts(out, s.scalars(), "%s", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
            default -> {
                symbolDrafts(out, s.arrays(), "%s", CandidateKind.ARRAY, RANK_PRIMARY);
                symbolDrafts(out, s.hashes(), "%s", CandidateKind.HASH, RANK_PRIMARY);
                symbolDrafts(out, s.scalars(), "%s", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
        }
    }

    private static void perlForeachDrafts(Symbols s, List<Draft> out) {
        symbolDrafts(out, s.arrays(), "@%s", CandidateKind.ARRAY, RANK_PRIMARY);
        symbolDrafts(out, s.hashes(), "keys %%%s", CandidateKind.HASH, RANK_PRIMARY);
        symbolDrafts(out, s.hashes(), "values %%%s", CandidateKind.HASH, RANK_SECONDARY);
        symbolDrafts(out, s.arrays(), "sort @%s", CandidateKind.ARRAY, RANK_SECONDARY);
    }

    private static void genericDrafts(String language, Symbols s, List<Draft> out) {
        symbolDrafts(out, s.functions(), "%s", CandidateKind.FUNCTION, RANK_PRIMARY);
        switch (language) {
            case "bash" -> {
                symbolDrafts(out, s.arrays(), "${%s[@]}", CandidateKind.ARRAY, RANK_SECONDARY);
                symbolDrafts(out, s.hashes(), "${%s[@]}", CandidateKind.HASH, RANK_SECONDARY);
                symbolDrafts(out, s.scalars(), "$%s", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
            case "perl" -> {
                symbolDrafts(out, s.arrays(), "@%s", CandidateKind.ARRAY, RANK_SECONDARY);
                symbolDrafts(out, s.hashes(), "%%%s", CandidateKind.HASH, RANK_SECONDARY);
                symbolDrafts(out, s.scalars(), "$%s", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
            case "powershell" -> {
                symbolDrafts(out, s.arrays(), "$%s", CandidateKind.ARRAY, RANK_SECONDARY);
                symbolDrafts(out, s.hashes(), "$%s", CandidateKind.HASH, RANK_SECONDARY);
                symbolDrafts(out, s.scalars(), "$%s", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
            default -> {
                symbolDrafts(out, s.arrays(), "%s", CandidateKind.ARRAY, RANK_SECONDARY);
                symbolDrafts(out, s.hashes(), "%s", CandidateKind.HASH, RANK_SECONDARY);
                symbolDrafts(out, s.scalars(), "%s", CandidateKind.VARIABLE, RANK_SECONDARY);
            }
        }
        for (String word : s.identifiers()) {
            out.add(new Draft(word, word, word, CandidateKind.TEXT, false, RANK_IDENTIFIER));
        }
    }

    private static void symbolDrafts(List<Draft> out, List<String> names, String form, CandidateKind kind, int rank) {
        for (String name : names) {
            String insertText = String.format(Locale.ROOT, form, name);
            String filterText = insertText.equals(name) ? name : name + " " + insertText;
            out.add(new Draft(insertText, insertText, filterText, kind, false, rank));
        }
    }

    private static List<Candidate> finish(List<Draft> drafts, int replaceStart, int limit) {
        List<Draft> ordered = new ArrayList<>(drafts);
        ordered.sort(Comparator.comparingInt(Draft::rank));
        Set<String> seen = new HashSet<>();
        List<Candidate> out = new ArrayList<>();
        for (Draft draft : ordered) {
            if (draft.insertText().isBlank() || !seen.add(draft.insertText())) {
                continue;
            }
            out.add(new Candidate(draft.label(), draft.insertText(), draft.filterText(), draft.kind(), draft.snippet(),
                sortText(draft.rank(), out.size()), replaceStart, ""));
            if (out.size() >= limit) {
                break;
            }
        }
        return List.copyOf(out);
    }

    static String sortText(int rank, int index) {
        return String.format(Locale.ROOT, "%d_%03d", rank, index);
    }

    // ================================================================ AI helpers

    /** True when the model repeated the typed token, or began with a local expansion of it. */
    private static boolean replacesToken(String body, String token, List<String> expansions) {
        if (body.startsWith(token)) {
            return true;
        }
        for (String expansion : expansions) {
            if (!expansion.isEmpty() && body.startsWith(expansion)) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeAiText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\r\n", "\n").replace('\r', '\n').stripTrailing();
    }

    private static String firstNonBlankLine(String text) {
        for (String line : text.split("\n", -1)) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    private static String shortenLabel(String line) {
        if (line.length() <= AI_LABEL_MAX_CHARS) {
            return line;
        }
        return line.substring(0, AI_LABEL_MAX_CHARS - 1) + "…";
    }

    // ================================================================ harvesting

    /** Collects the symbols of one language, line by line; buckets keep first-appearance order. */
    private static final class Harvester {

        private final String language;
        private final LinkedHashSet<String> arrays = new LinkedHashSet<>();
        private final LinkedHashSet<String> hashes = new LinkedHashSet<>();
        private final LinkedHashSet<String> scalars = new LinkedHashSet<>();
        private final LinkedHashSet<String> functions = new LinkedHashSet<>();
        /** word → {count, first-appearance order}. */
        private final Map<String, int[]> identifiers = new LinkedHashMap<>();

        Harvester(String language) {
            this.language = language;
        }

        void line(String line) {
            switch (language) {
                case "bash" -> bash(line);
                case "perl" -> perl(line);
                case "python" -> python(line);
                case "powershell" -> powershell(line);
                case "ruby" -> ruby(line);
                default -> generic(line);
            }
            words(line);
        }

        Symbols symbols(String typedWord) {
            if (typedWord != null) {
                int[] count = identifiers.get(typedWord);
                if (count != null) {
                    count[0]--;
                }
            }
            arrays.removeAll(hashes);
            scalars.removeAll(arrays);
            scalars.removeAll(hashes);
            Set<String> known = new HashSet<>();
            for (Set<String> bucket : List.of(arrays, hashes, scalars, functions)) {
                for (String name : bucket) {
                    known.add(bareName(name));
                }
            }
            List<Map.Entry<String, int[]>> ranked = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : identifiers.entrySet()) {
                if (entry.getValue()[0] > 0 && !known.contains(entry.getKey())) {
                    ranked.add(entry);
                }
            }
            ranked.sort(Comparator.<Map.Entry<String, int[]>>comparingInt(e -> -e.getValue()[0])
                .thenComparingInt(e -> e.getValue()[1]));
            List<String> words = new ArrayList<>();
            for (Map.Entry<String, int[]> entry : ranked) {
                if (words.size() >= MAX_IDENTIFIERS) {
                    break;
                }
                words.add(entry.getKey());
            }
            return new Symbols(List.copyOf(arrays), List.copyOf(hashes), List.copyOf(scalars), List.copyOf(functions),
                words);
        }

        private static void add(Set<String> bucket, String name) {
            if (name != null && !name.isEmpty() && bucket.size() < MAX_NAMES_PER_BUCKET) {
                bucket.add(name);
            }
        }

        private void addKind(CandidateKind kind, String name) {
            switch (kind) {
                case ARRAY -> add(arrays, name);
                case HASH -> add(hashes, name);
                case FUNCTION -> add(functions, name);
                default -> add(scalars, name);
            }
        }

        // ------------------------------------------------------------ bash

        private void bash(String line) {
            Matcher m;
            if ((m = BASH_FUNCTION_KEYWORD.matcher(line)).find() || (m = BASH_FUNCTION_PARENS.matcher(line)).find()) {
                add(functions, m.group(1));
                return;
            }
            if ((m = BASH_DECLARE.matcher(line)).find()) {
                bashDeclare(m.group(1), m.group(2));
                return;
            }
            if ((m = BASH_MAPFILE.matcher(line)).find()) {
                String[] tokens = commandArguments(m.group(1)).split("\\s+");
                for (int i = tokens.length - 1; i >= 0; i--) {
                    if (isName(tokens[i])) {
                        add(arrays, tokens[i]);
                        break;
                    }
                }
                return;
            }
            if ((m = BASH_READ.matcher(line)).find()) {
                bashRead(m.group(1));
                return;
            }
            if ((m = BASH_ARRAY_ASSIGN.matcher(line)).find() || (m = BASH_ARRAY_INDEX.matcher(line)).find()) {
                add(arrays, m.group(1));
                return;
            }
            if ((m = BASH_SCALAR_ASSIGN.matcher(line)).find()) {
                add(scalars, m.group(1));
                return;
            }
            if ((m = BASH_FOR.matcher(line)).find() || (m = BASH_FOR_C.matcher(line)).find()
                || (m = BASH_GETOPTS.matcher(line)).find()) {
                add(scalars, m.group(1));
            }
        }

        /** {@code declare -A map}, {@code local -a items=()}, {@code export NAME=value}, {@code local a b}. */
        private void bashDeclare(String keyword, String rest) {
            StringBuilder flags = new StringBuilder();
            for (String token : commandArguments(rest).split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (token.startsWith("-")) {
                    flags.append(token, 1, token.length());
                    continue;
                }
                String name = leadingName(token);
                if (name == null) {
                    continue;
                }
                String options = flags.toString();
                if (options.indexOf('f') >= 0 || options.indexOf('F') >= 0) {
                    continue;
                }
                if (options.indexOf('A') >= 0) {
                    add(hashes, name);
                } else if (options.indexOf('a') >= 0 || token.startsWith(name + "=(")
                    || token.startsWith(name + "+=(")) {
                    add(arrays, name);
                } else {
                    add(scalars, name);
                }
            }
        }

        /** {@code read -r -a words}, {@code read -p "Name: " user}, {@code while read -r line}. */
        private void bashRead(String rest) {
            boolean nextIsArray = false;
            boolean skipNext = false;
            for (String token : commandArguments(rest).split("\\s+")) {
                if (token.isEmpty()) {
                    continue;
                }
                if (skipNext) {
                    skipNext = false;
                    continue;
                }
                if (nextIsArray) {
                    nextIsArray = false;
                    if (isName(token)) {
                        add(arrays, token);
                    }
                    continue;
                }
                if (token.startsWith("-") && token.length() > 1) {
                    char last = token.charAt(token.length() - 1);
                    if (last == 'a') {
                        nextIsArray = true;
                    } else if ("pdnNtui".indexOf(last) >= 0) {
                        skipNext = true;
                    }
                    continue;
                }
                if (token.startsWith("<")) {
                    break;
                }
                if (isName(token)) {
                    add(scalars, token);
                }
            }
        }

        /** The arguments of one shell command: everything before {@code ;}, {@code |}, {@code &} or {@code #}. */
        private static String commandArguments(String rest) {
            int end = rest.length();
            for (char stop : new char[] {';', '|', '&', '#'}) {
                int index = rest.indexOf(stop);
                if (index >= 0 && index < end) {
                    end = index;
                }
            }
            return rest.substring(0, end).trim();
        }

        // ------------------------------------------------------------ perl

        private void perl(String line) {
            Matcher m;
            if ((m = PERL_SUB.matcher(line)).find()) {
                add(functions, m.group(1));
                return;
            }
            if ((m = PERL_DECL_LIST.matcher(line)).find()) {
                Matcher names = PERL_SIGIL_NAME.matcher(m.group(1));
                while (names.find()) {
                    perlSigil(names.group(1), names.group(2));
                }
                return;
            }
            if ((m = PERL_DECL.matcher(line)).find() || (m = PERL_ASSIGN.matcher(line)).find()
                || (m = PERL_COND_DECL.matcher(line)).find()) {
                perlSigil(m.group(1), m.group(2));
                return;
            }
            if ((m = PERL_FOREACH_VAR.matcher(line)).find()) {
                add(scalars, m.group(1));
            }
        }

        private void perlSigil(String sigil, String name) {
            switch (sigil) {
                case "@" -> add(arrays, name);
                case "%" -> add(hashes, name);
                default -> add(scalars, name);
            }
        }

        // ------------------------------------------------------------ python

        private void python(String line) {
            Matcher m;
            if ((m = PY_DEF.matcher(line)).find()) {
                add(functions, m.group(1));
                String params = m.group(2);
                int close = params.indexOf(')');
                for (String param : (close >= 0 ? params.substring(0, close) : params).split(",")) {
                    String name = leadingName(param.strip().replaceFirst("^\\*+", ""));
                    if (name != null && !name.equals("self") && !name.equals("cls")) {
                        add(scalars, name);
                    }
                }
                return;
            }
            if ((m = PY_CLASS.matcher(line)).find()) {
                add(functions, m.group(1));
                return;
            }
            if ((m = PY_FOR.matcher(line)).find()) {
                for (String name : m.group(1).split(",")) {
                    add(scalars, name.strip());
                }
                return;
            }
            if ((m = PY_WITH_AS.matcher(line)).find() || (m = PY_EXCEPT_AS.matcher(line)).find()) {
                add(scalars, m.group(1));
                return;
            }
            if ((m = PY_TUPLE_ASSIGN.matcher(line)).find()) {
                for (String name : m.group(1).split(",")) {
                    add(scalars, name.strip());
                }
                return;
            }
            if ((m = PY_ASSIGN.matcher(line)).find()) {
                addKind(pythonKind(m.group(2), m.group(3)), m.group(1));
            }
        }

        private static CandidateKind pythonKind(String annotation, String rhs) {
            String type = annotation != null ? annotation.strip().toLowerCase(Locale.ROOT) : "";
            if (type.startsWith("dict") || type.startsWith("mapping") || type.startsWith("typing.dict")
                || type.startsWith("defaultdict") || type.startsWith("ordereddict") || type.startsWith("counter")) {
                return CandidateKind.HASH;
            }
            if (type.startsWith("list") || type.startsWith("tuple") || type.startsWith("set")
                || type.startsWith("frozenset") || type.startsWith("sequence") || type.startsWith("iterable")
                || type.startsWith("deque") || type.startsWith("typing.")) {
                return CandidateKind.ARRAY;
            }
            String value = rhs != null ? rhs.strip() : "";
            if (value.startsWith("[")) {
                return CandidateKind.ARRAY;
            }
            if (value.startsWith("{")) {
                return value.startsWith("{}") || value.indexOf(':') >= 0 ? CandidateKind.HASH : CandidateKind.ARRAY;
            }
            if (value.startsWith("(")) {
                return value.indexOf(',') >= 0 || value.contains(" for ")
                    ? CandidateKind.ARRAY : CandidateKind.VARIABLE;
            }
            if (PY_HASH_RHS.matcher(value).find()) {
                return CandidateKind.HASH;
            }
            if (PY_ARRAY_RHS.matcher(value).find() || PY_ARRAY_CALL.matcher(value).find()) {
                return CandidateKind.ARRAY;
            }
            return CandidateKind.VARIABLE;
        }

        // ------------------------------------------------------------ powershell

        private void powershell(String line) {
            Matcher m;
            if ((m = PS_FUNCTION.matcher(line)).find()) {
                add(functions, m.group(1));
                return;
            }
            if ((m = PS_FOREACH_VAR.matcher(line)).find()) {
                add(scalars, m.group(1));
                return;
            }
            if ((m = PS_PARAM_LINE.matcher(line)).find()) {
                Matcher names = PS_VAR_REF.matcher(m.group(1));
                while (names.find()) {
                    add(scalars, names.group(1));
                }
                return;
            }
            if ((m = PS_ASSIGN.matcher(line)).find()) {
                addKind(powershellKind(m.group(1), m.group(3)), m.group(2));
                return;
            }
            if ((m = PS_TYPED_DECL.matcher(line)).find()) {
                addKind(powershellKind(m.group(1), ""), m.group(2));
            }
        }

        private static CandidateKind powershellKind(String type, String rhs) {
            String declared = type != null ? type.toLowerCase(Locale.ROOT) : "";
            if (declared.contains("hashtable") || declared.equals("ordered") || declared.contains("dictionary")) {
                return CandidateKind.HASH;
            }
            if (declared.endsWith("[]") || declared.equals("array") || declared.contains("arraylist")
                || declared.contains("generic.list") || declared.contains("collections.")) {
                return CandidateKind.ARRAY;
            }
            String value = rhs != null ? rhs.strip() : "";
            if (PS_HASH_RHS.matcher(value).find()) {
                return CandidateKind.HASH;
            }
            if (PS_ARRAY_RHS.matcher(value).find()) {
                return CandidateKind.ARRAY;
            }
            return CandidateKind.VARIABLE;
        }

        // ------------------------------------------------------------ ruby

        private void ruby(String line) {
            Matcher m;
            if ((m = RB_DEF.matcher(line)).find()) {
                add(functions, m.group(1));
                return;
            }
            if ((m = RB_FOR.matcher(line)).find()) {
                for (String name : m.group(1).split(",")) {
                    add(scalars, name.strip());
                }
                return;
            }
            if ((m = RB_BLOCK_PARAMS.matcher(line)).find()) {
                for (String param : m.group(1).split(",")) {
                    add(scalars, leadingName(param.strip().replaceFirst("^[*&]+", "")));
                }
            }
            if ((m = RB_ASSIGN.matcher(line)).find()) {
                String sigil = m.group(1) != null ? m.group(1) : "";
                addKind(rubyKind(m.group(3)), sigil + m.group(2));
            }
        }

        private static CandidateKind rubyKind(String rhs) {
            String value = rhs != null ? rhs.strip() : "";
            if (RB_HASH_RHS.matcher(value).find()) {
                return CandidateKind.HASH;
            }
            if (RB_ARRAY_RHS.matcher(value).find()) {
                return CandidateKind.ARRAY;
            }
            return CandidateKind.VARIABLE;
        }

        // ------------------------------------------------------------ generic

        private void generic(String line) {
            Matcher m;
            if ((m = GEN_FUNCTION_KEYWORD.matcher(line)).find() || (m = GEN_GO_METHOD.matcher(line)).find()
                || (m = GEN_ARROW.matcher(line)).find() || (m = GEN_FUNC_EXPR.matcher(line)).find()) {
                add(functions, m.group(1));
                return;
            }
            if ((m = GEN_METHOD.matcher(line)).find()) {
                if (!isStopWord(m.group(1))) {
                    add(functions, m.group(1));
                }
                return;
            }
            if ((m = GEN_SQL_CREATE.matcher(line)).find()) {
                String what = m.group(1).toLowerCase(Locale.ROOT);
                if (what.equals("table") || what.equals("view")) {
                    add(scalars, m.group(2));
                } else {
                    add(functions, m.group(2));
                }
                return;
            }
            if ((m = GEN_VAR.matcher(line)).find() && !isStopWord(m.group(2))) {
                addKind(genericKind(m.group(1), m.group(3), m.group(4)), m.group(2));
            }
        }

        private static CandidateKind genericKind(String type, String annotation, String rhs) {
            for (String declared : new String[] {type, annotation}) {
                if (declared == null || declared.isBlank()) {
                    continue;
                }
                String value = declared.strip();
                if (GEN_HASH_TYPE.matcher(value).find()) {
                    return CandidateKind.HASH;
                }
                if (GEN_ARRAY_TYPE.matcher(value).find()) {
                    return CandidateKind.ARRAY;
                }
            }
            String value = rhs != null ? rhs.strip() : "";
            if (GEN_ARRAY_RHS.matcher(value).find()) {
                return CandidateKind.ARRAY;
            }
            if (GEN_HASH_RHS.matcher(value).find()) {
                return CandidateKind.HASH;
            }
            return CandidateKind.VARIABLE;
        }

        // ------------------------------------------------------------ identifiers

        private void words(String line) {
            Matcher matcher = ("powershell".equals(language) ? PS_IDENTIFIER : IDENTIFIER).matcher(line);
            while (matcher.find()) {
                String word = matcher.group();
                if (word.length() < MIN_IDENTIFIER_LENGTH || isStopWord(word)) {
                    continue;
                }
                identifiers.computeIfAbsent(word, key -> new int[] {0, identifiers.size()})[0]++;
            }
        }
    }

    // ================================================================ small helpers

    private static boolean isStopWord(String word) {
        return STOP_WORDS.contains(word.toLowerCase(Locale.ROOT));
    }

    private static boolean isName(String token) {
        return token != null && WORD.matcher(token).matches();
    }

    /** The identifier a token starts with (before {@code =}, {@code :}, whitespace …), or null. */
    private static String leadingName(String token) {
        int end = 0;
        while (end < token.length() && (Character.isLetterOrDigit(token.charAt(end)) || token.charAt(end) == '_')) {
            end++;
        }
        String name = token.substring(0, end);
        return isName(name) ? name : null;
    }

    private static String bareName(String name) {
        int start = 0;
        while (start < name.length() && "$@%&".indexOf(name.charAt(start)) >= 0) {
            start++;
        }
        return name.substring(start);
    }

    /** The word around the caret, so the one being typed does not suggest itself. */
    private static String wordAt(String text, int caret, String language) {
        int start = caret;
        while (start > 0 && isIdentifierChar(text.charAt(start - 1), language)) {
            start--;
        }
        int end = caret;
        while (end < text.length() && isIdentifierChar(text.charAt(end), language)) {
            end++;
        }
        return start < end ? text.substring(start, end) : null;
    }

    private static int clamp(int offset, int length) {
        return Math.max(0, Math.min(offset, length));
    }

    private static int lineStartAt(String text, int caret) {
        return caret <= 0 ? 0 : text.lastIndexOf('\n', caret - 1) + 1;
    }

    /** 1-based line number of an offset. */
    private static int lineNumberAt(String text, int caret) {
        int line = 1;
        for (int i = 0; i < caret; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }
}
