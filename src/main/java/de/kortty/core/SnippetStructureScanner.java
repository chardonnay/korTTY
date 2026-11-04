package de.kortty.core;

import de.kortty.core.SnippetCompletionSupport.CandidateKind;
import de.kortty.core.SnippetCompletionSupport.Flavor;
import de.kortty.core.SnippetCompletionSupport.Harvester;
import de.kortty.core.SnippetCompletionSupport.ScopeHint;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The structure of a snippet for the editor's structure panel: an outline of its classes, modules,
 * methods and functions (headings of a Markdown or AsciiDoc text, objects of an SQL script, stages
 * of a Dockerfile), and the variables it defines with their kind, scope, line and number of uses.
 *
 * <p>The rules are line-based regexes over a copy of the text whose comments and strings are
 * blanked ({@link SnippetCodeMask}); variable definitions come from the same per-language rules as
 * the editor's completion list ({@link SnippetCompletionSupport.Harvester}), plus the few forms only
 * this view needs (fields, parameters, constants). Nesting follows indentation for Python and Ruby
 * and braces for the other languages. It is a reading aid, not a parser: a construct the rules do
 * not know is left out rather than guessed. Pure Java; one call reads the whole text once per rule.
 */
public final class SnippetStructureScanner {

    /** Texts longer than this are not scanned. */
    public static final int MAX_TEXT_CHARS = SnippetCompletionSupport.MAX_TEXT_CHARS;
    /** Outline nodes and variables kept at most (each); more set {@link Analysis#truncated()}. */
    public static final int MAX_ENTRIES = 2_000;
    /** Lines a header may wait for the brace that opens its body. */
    private static final int PENDING_LINES = 6;
    private static final int UNOPENED = Integer.MIN_VALUE;
    /** The open depth of a Perl {@code package Name;}: closed by the next package, never by a brace. */
    private static final int STATEMENT = -1;

    /** What an outline entry stands for. */
    public enum NodeKind {
        MODULE, CLASS, METHOD, FUNCTION, SECTION, SQL_OBJECT, STAGE
    }

    /** What a variable is, as far as the written form tells. */
    public enum VariableKind {
        VARIABLE, ARRAY, HASH, CONSTANT, PARAMETER, EXPORTED, FIELD
    }

    /** The shape of a variable's value, independent of its kind (a constant array is CONSTANT + ARRAY). */
    public enum Container {
        SCALAR, ARRAY, HASH
    }

    /** Whether the text was scanned. */
    public enum Status {
        OK, UNSUPPORTED, TOO_LARGE
    }

    /**
     * One outline entry. {@code line}/{@code endLine} are 1-based and inclusive; {@code nameStart}
     * and {@code nameEnd} are offsets of the name in the text. {@code detail} is a short addition
     * shown beside the name: a parameter list, {@code interface}, {@code table}, an image.
     */
    public record OutlineNode(NodeKind kind, String name, String detail, String qualifiedName, int line,
                              int endLine, int nameStart, int nameEnd, List<OutlineNode> children) {

        public OutlineNode {
            name = name != null ? name : "";
            detail = detail != null ? detail : "";
            qualifiedName = qualifiedName != null ? qualifiedName : name;
            children = children != null ? List.copyOf(children) : List.of();
        }

        public boolean contains(int lineNumber) {
            return line <= lineNumber && lineNumber <= endLine;
        }
    }

    /**
     * One variable, listed once per scope. {@code scope} is the qualified name of the function,
     * method or class it belongs to, empty for a global one; {@code references} counts its uses
     * apart from the definition. {@code name} is shown as the language writes it ({@code $count} in
     * Perl and PowerShell, {@code @items} for a Perl array, {@code @name} for a Ruby instance variable).
     */
    public record Variable(String name, VariableKind kind, Container container, String scope, int line,
                           int nameStart, int nameEnd, int references) {

        public Variable {
            name = name != null ? name : "";
            kind = kind != null ? kind : VariableKind.VARIABLE;
            container = container != null ? container : Container.SCALAR;
            scope = scope != null ? scope : "";
        }
    }

    /** The result for one text; {@code language} is the effective language the rules ran for. */
    public record Analysis(String language, Status status, List<OutlineNode> outline, List<Variable> variables,
                           boolean truncated) {

        public Analysis {
            language = language != null ? language : "plain";
            status = status != null ? status : Status.UNSUPPORTED;
            outline = outline != null ? List.copyOf(outline) : List.of();
            variables = variables != null ? List.copyOf(variables) : List.of();
        }

        public boolean isEmpty() {
            return outline.isEmpty() && variables.isEmpty();
        }

        /** The innermost outline node whose lines contain {@code lineNumber} (1-based), or null. */
        public OutlineNode nodeAt(int lineNumber) {
            OutlineNode found = null;
            List<OutlineNode> level = outline;
            while (true) {
                // Siblings may overlap (a Lua table spans the functions defined between its
                // methods): the one that starts last is the innermost.
                OutlineNode next = null;
                for (OutlineNode node : level) {
                    if (node.contains(lineNumber) && (next == null || node.line() > next.line())) {
                        next = node;
                    }
                }
                if (next == null) {
                    return found;
                }
                found = next;
                level = next.children();
            }
        }
    }

    private static final Set<String> SUPPORTED = Set.of("bash", "python", "perl", "ruby", "lua", "powershell", "java",
        "groovy", "javascript", "typescript", "sql", "markdown", "asciidoctor", "dockerfile");
    /** Languages whose assignments directly in a class body declare fields. */
    private static final Set<String> CLASS_BODY_FIELDS = Set.of("python", "java", "groovy", "javascript",
        "typescript", "powershell");
    /** Languages whose references are plain words (no sigil), where a field is used as {@code .name}. */
    private static final Set<String> WORD_LANGUAGES = Set.of("python", "javascript", "typescript", "java", "groovy",
        "lua");
    /** Languages whose plain assignment inside a function still sets a global variable. */
    private static final Set<String> GLOBAL_BY_DEFAULT = Set.of("bash", "perl", "lua");

    private static final String ID = "[A-Za-z_$][\\w$]*";
    private static final String NAME = "[A-Za-z_][A-Za-z0-9_]*";
    private static final Set<String> CONTROL_WORDS = Set.of("if", "for", "while", "switch", "catch", "synchronized",
        "return", "new", "throw", "else", "do", "try", "super", "this", "assert", "foreach", "until", "elseif",
        "function", "param", "process", "begin", "end", "using", "with", "await", "typeof", "delete", "void",
        "yield", "case", "when", "in", "of", "not", "and", "or");
    private static final Set<String> NON_TYPES = Set.of("return", "new", "throw", "else", "case", "yield", "await",
        "delete", "typeof", "instanceof", "goto", "assert", "break", "continue", "package", "import", "export");

    // ---------------------------------------------------------------- outline rules

    private static final Pattern BASH_FUNCTION = Pattern.compile(
        "^\\s*(?:function\\s+([A-Za-z_][\\w:.-]*)(?:\\s*\\(\\s*\\))?|([A-Za-z_][\\w:.-]*)\\s*\\(\\s*\\))");
    private static final Pattern PERL_SUB = Pattern.compile("^\\s*sub\\s+([A-Za-z_][\\w:]*)");
    private static final Pattern PERL_PACKAGE = Pattern.compile("^\\s*package\\s+([A-Za-z_][\\w:]*)\\s*(;|\\{)?");
    private static final Pattern PS_CLASS =
        Pattern.compile("^\\s*(class|enum)\\s+(" + NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_METHOD = Pattern.compile(
        "^\\s*(?:(?:static|hidden)\\s+)*(?:\\[[^\\]]+\\]\\s*)?(" + NAME + ")\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern JAVA_CLASS = Pattern.compile(
        "^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|static|final|abstract|sealed"
            + "|non-sealed|strictfp)\\s+)*(class|interface|enum|record|@interface|trait)\\s+(" + ID + ")");
    private static final Pattern JAVA_METHOD = Pattern.compile(
        "^\\s*(?:@[\\w.]+(?:\\([^)]*\\))?\\s+)*(?:(?:public|private|protected|static|final|abstract|synchronized"
            + "|native|default|strictfp|override)\\s+)*(?:<[^>]*>\\s+)?(?:([\\w.$]+(?:<[^()]*>)?(?:\\[\\])*)\\s+)?("
            + ID + ")\\s*\\(");
    private static final Pattern JS_CLASS = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:abstract\\s+)?"
            + "(class|interface|enum|namespace|module)\\s+(" + ID + "(?:\\." + ID + ")*)");
    private static final Pattern JS_FUNCTION = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:default\\s+)?(?:declare\\s+)?(?:async\\s+)?function\\s*\\*?\\s*(" + ID + ")\\s*[(<]");
    private static final Pattern JS_METHOD = Pattern.compile(
        "^\\s*(?:(?:public|private|protected|static|async|readonly|abstract|override|declare|get|set)\\s+)*"
            + "\\*?\\s*(#?" + ID + ")\\s*[?!]?\\s*(?:<[^>]*>)?\\s*\\(");
    private static final Pattern JS_ARROW_MEMBER = Pattern.compile(
        "^\\s*(?:(?:public|private|protected|static|readonly|override)\\s+)*(#?" + ID + ")\\s*(?::[^=]+)?="
            + "\\s*(?:async\\s*)?(?:\\([^()]*\\)|" + ID + ")\\s*(?::[^=]+)?=>");
    /** What may stand between a function's name and its parameter list: blanks and generic parameters. */
    private static final Pattern PARAMS_OPEN = Pattern.compile("\\s*(?:<[^<>()]*>\\s*)?\\(");
    private static final Pattern RUBY_CLASS =
        Pattern.compile("^\\s*class\\s+(?!<<)([A-Z]\\w*(?:::[A-Z]\\w*)*)");
    private static final Pattern RUBY_MODULE = Pattern.compile("^\\s*module\\s+([A-Z]\\w*(?:::[A-Z]\\w*)*)");
    private static final Pattern RUBY_DEF = Pattern.compile("^\\s*def\\s+(self\\.)?(" + NAME + "[?!=]?)");
    private static final Pattern RUBY_END = Pattern.compile("^\\s*end\\b");
    private static final Pattern MD_FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})");
    private static final Pattern MD_HEADING = Pattern.compile("^ {0,3}(#{1,6})[ \\t]+(.*?)(?:[ \\t]+#+)?[ \\t]*$");
    private static final Pattern ADOC_HEADING = Pattern.compile("^(={1,6}|#{1,6})[ \\t]+(\\S.*?)[ \\t]*$");
    private static final Pattern ADOC_DELIMITER =
        Pattern.compile("^(-{4,}|\\.{4,}|={4,}|\\*{4,}|\\+{4,}|/{4,}|_{4,}|`{3,})[ \\t]*$");
    /** The words that open ({@code function if do repeat}) and close ({@code end until}) a Lua block. */
    private static final Pattern LUA_BLOCK_WORD = Pattern.compile("\\b(function|if|do|repeat|end|until)\\b");
    /** After {@code function}: the name of a named function, {@code a.b:c}. */
    private static final Pattern LUA_FUNCTION_NAME =
        Pattern.compile("\\s+(" + NAME + "(?:[.:]" + NAME + ")*)\\s*\\(");
    /** Before {@code function}: {@code local f = }, {@code M.f = }, {@code name = } in a table constructor. */
    private static final Pattern LUA_ASSIGNED_FUNCTION =
        Pattern.compile("(?:^|[\\s,{;(])(?:local\\s+)?(" + NAME + "(?:\\." + NAME + ")*)\\s*=\\s*$");
    /** A top-level table that functions may be defined on: {@code local M = {}}, {@code M = M or {}}. */
    private static final Pattern LUA_TABLE_DECL = Pattern.compile("^\\s*(?:local\\s+)?(" + NAME
        + ")\\s*=\\s*(?:\\{|setmetatable\\s*\\(\\s*\\{|" + NAME + "\\s+or\\s+\\{)");
    private static final Pattern LUA_SELF_FIELD = Pattern.compile("^\\s*self\\.(" + NAME + ")\\s*=(?!=)");
    private static final Pattern LUA_TABLE_FIELD =
        Pattern.compile("^\\s*(" + NAME + ")\\.(" + NAME + ")\\s*=(?!=)\\s*(.*)$");
    private static final Pattern DOCKER_FROM = Pattern.compile(
        "^\\s*FROM\\s+(?:--\\S+\\s+)*(\\S+)(?:\\s+AS\\s+(\\S+))?", Pattern.CASE_INSENSITIVE);

    // ---------------------------------------------------------------- variable rules only this view needs

    private static final Pattern PY_SELF_FIELD =
        Pattern.compile("^\\s*self\\.(" + NAME + ")\\s*(?::[^=]+)?(?:[+\\-*/%|&^@]|//|\\*\\*)?=(?!=)");
    private static final Pattern JS_THIS_FIELD =
        Pattern.compile("^\\s*this\\.(#?" + ID + ")\\s*(?:[+\\-*/%|&^]|\\?\\?|\\|\\||&&)?=(?![=>])");
    private static final Pattern JS_PRIVATE_FIELD =
        Pattern.compile("^\\s*(?:static\\s+)?(#" + ID + ")\\s*(?:[;=]|$)");
    private static final Pattern TS_FIELD_DECL = Pattern.compile(
        "^\\s*(?:(?:public|private|protected|static|readonly|declare|override|accessor)\\s+)*(" + ID + ")"
            + "\\s*[?!]?\\s*:\\s*[^=;(]+;");
    /** {@code int a;}, {@code private final List<String> names = …;}, {@code int a, b;} — the first declarator. */
    private static final Pattern JAVA_DECL = Pattern.compile(
        "^\\s*(?:(?:public|private|protected|static|final|transient|volatile|def)\\s+)*([\\w.$]+(?:<[^()]*>)?"
            + "(?:\\[\\])*)\\s+(" + ID + ")\\s*(?:\\[\\s*\\]\\s*)*([=;,])");
    /** Further declarators of a declaration without initializers: {@code , b, c;}. */
    private static final Pattern JAVA_MORE_DECLARATORS = Pattern.compile("\\G\\s*(" + ID + ")\\s*([,;])");
    /** {@code const x = …}, {@code let y: number = …}, {@code var z;} — names the completion rules skip as stop words. */
    private static final Pattern JS_DECL = Pattern.compile(
        "^\\s*(?:export\\s+)?(?:const|let|var)\\s+(" + ID + ")\\s*(?::[^=;]+)?\\s*[=;]");
    private static final Pattern PERL_CONSTANT = Pattern.compile("^\\s*use\\s+constant\\s+(" + NAME + ")\\s*=>");
    private static final Pattern PS_ENV_ASSIGN =
        Pattern.compile("\\$env:(" + NAME + ")\\s*[+]?=(?!=)", Pattern.CASE_INSENSITIVE);
    private static final Pattern PS_PARAM_BLOCK = Pattern.compile("\\bparam\\s*\\(", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_DECLARE = Pattern.compile("\\bDECLARE\\s+(@" + NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_SET = Pattern.compile("^\\s*SET\\s+(@" + NAME + ")\\s*=", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCKER_ARG = Pattern.compile("^\\s*ARG\\s+(" + NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCKER_ENV = Pattern.compile("^\\s*ENV\\s+(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DOCKER_ENV_PAIR = Pattern.compile("(?:^|\\s)(" + NAME + ")=");
    private static final Pattern ALL_CAPS = Pattern.compile("^[A-Z][A-Z0-9_]*[A-Z0-9]$");
    private static final Pattern ANNOTATION = Pattern.compile("@[\\w.]+(?:\\([^)]*\\))?");
    private static final Pattern GENERIC_ARGUMENTS = Pattern.compile("<[^<>]*>");

    // ---------------------------------------------------------------- references

    private static final Pattern SHELL_REFERENCE = Pattern.compile("\\$\\{?[#!]?(" + NAME + ")");
    private static final Pattern PERL_REFERENCE = Pattern.compile("(?:\\$#|[$@%])\\{?(" + NAME + ")");
    private static final Pattern PS_REFERENCE = Pattern.compile(
        "\\$(?:(?:script|global|local|private|env|using):)?(" + NAME + ")", Pattern.CASE_INSENSITIVE);
    private static final Pattern RUBY_REFERENCE = Pattern.compile("(@@|@|\\$)?(" + NAME + "[?!]?)");
    private static final Pattern WORD_REFERENCE = Pattern.compile("[#@]?[A-Za-z_$][\\w$]*");

    private SnippetStructureScanner() {
    }

    /** Whether the structure view has rules for {@code language} (a shebang counts for plain text). */
    public static boolean supports(String language, String text) {
        return SUPPORTED.contains(SnippetLanguageSupport.detectSnippetLanguage(language, text));
    }

    /** Scans {@code text} with the rules of {@code language}. */
    public static Analysis analyze(String language, String text) {
        String value = text != null ? text : "";
        String lang = SnippetLanguageSupport.detectSnippetLanguage(language, value);
        if (!SUPPORTED.contains(lang)) {
            return new Analysis(lang, Status.UNSUPPORTED, List.of(), List.of(), false);
        }
        if (value.length() > MAX_TEXT_CHARS) {
            return new Analysis(lang, Status.TOO_LARGE, List.of(), List.of(), false);
        }
        return new Scan(lang, value).run();
    }

    // ================================================================ one scan

    /** An outline entry while it is being built. */
    private static final class Node {
        /** Not final: a Lua table becomes a CLASS once a {@code Table:method} joins it. */
        NodeKind kind;
        final String name;
        String detail;
        final int line;
        final int nameStart;
        final int nameEnd;
        final int indent;
        Node parent;
        final List<Node> children = new ArrayList<>();
        int endLine;
        int openDepth = UNOPENED;
        /** A header that may stand without a body (an abstract method, an expression arrow function). */
        boolean allowLeaf;
        /** Its body brace must be on the header line (an arrow function), not further down. */
        boolean sameLineOnly;
        /** Offset of the {@code (} that opens the parameter list, or -1. */
        int paramsOpen = -1;

        Node(NodeKind kind, String name, String detail, int line, int nameStart, int nameEnd, int indent) {
            this.kind = kind;
            this.name = name;
            this.detail = detail;
            this.line = line;
            this.nameStart = nameStart;
            this.nameEnd = nameEnd;
            this.indent = indent;
            this.endLine = line;
        }

        boolean isFunction() {
            return kind == NodeKind.FUNCTION || kind == NodeKind.METHOD;
        }

        boolean isType() {
            return kind == NodeKind.CLASS || kind == NodeKind.MODULE;
        }

        boolean contains(int lineNumber) {
            return line <= lineNumber && lineNumber <= endLine;
        }
    }

    /** A header found on a line: the node, and the column after which its body brace may follow. */
    private record Header(Node node, int column) {
    }

    /** One variable definition as found, before its scope is resolved. */
    private record Def(int line, int start, CandidateKind kind, String name, Flavor flavor, ScopeHint scope,
                       int priority, Node owner) {

        /** {@code owner}: the class or table a field belongs to when the line does not say it by nesting. */
        Def(int line, int start, CandidateKind kind, String name, Flavor flavor, ScopeHint scope, int priority) {
            this(line, start, kind, name, flavor, scope, priority, null);
        }
    }

    /** A variable while it is being built; {@code rawName} is the name the rules and the lookups use. */
    private static final class Var {
        final String rawName;
        final String displayName;
        final VariableKind kind;
        final Container container;
        final Node scope;
        final int line;
        final int nameStart;
        final int nameEnd;
        int references;

        Var(String rawName, String displayName, VariableKind kind, Container container, Node scope, int line,
            int nameStart, int nameEnd) {
            this.rawName = rawName;
            this.displayName = displayName;
            this.kind = kind;
            this.container = container;
            this.scope = scope;
            this.line = line;
            this.nameStart = nameStart;
            this.nameEnd = nameEnd;
        }
    }

    private static final class Scan {

        private final String language;
        private final String[] lines;
        private final int[] starts;
        private final String[] code;
        private final String[] refs;
        private final String codeText;
        private final List<Node> roots = new ArrayList<>();
        private final List<Node> allNodes = new ArrayList<>();
        private final List<Def> defs = new ArrayList<>();
        private final List<Var> variables = new ArrayList<>();
        private final Map<String, Var> byScopeAndName = new HashMap<>();
        private boolean truncated;

        Scan(String language, String text) {
            this.language = language;
            this.lines = text.split("\n", -1);
            this.starts = new int[lines.length];
            int offset = 0;
            for (int i = 0; i < lines.length; i++) {
                starts[i] = offset;
                offset += lines[i].length() + 1;
            }
            SnippetCodeMask.Masked masked = SnippetCodeMask.mask(language, text);
            this.code = masked.code();
            this.refs = masked.references();
            this.codeText = String.join("\n", code);
        }

        Analysis run() {
            switch (language) {
                case "python", "ruby" -> indentOutline();
                case "lua" -> luaOutline();
                case "markdown" -> markdownOutline();
                case "asciidoctor" -> asciidocOutline();
                case "sql" -> sqlOutline();
                case "dockerfile" -> dockerOutline();
                default -> braceOutline();
            }
            switch (language) {
                case "markdown", "asciidoctor" -> {
                }
                case "dockerfile" -> dockerVariables();
                case "sql" -> sqlVariables();
                default -> {
                    parameters();
                    harvested();
                    extraDefinitions();
                }
            }
            resolveDefinitions();
            countReferences();
            List<Variable> out = new ArrayList<>();
            for (Var var : variables) {
                out.add(new Variable(var.displayName, var.kind, var.container,
                    var.scope != null ? qualifiedName(var.scope) : "", var.line, var.nameStart, var.nameEnd,
                    var.references));
            }
            out.sort(Comparator.comparingInt(Variable::line).thenComparingInt(Variable::nameStart));
            return new Analysis(language, Status.OK, build(roots), out, truncated);
        }

        // ------------------------------------------------------------ outline: indentation (Python, Ruby)

        private void indentOutline() {
            Deque<Node> stack = new ArrayDeque<>();
            int brackets = 0;
            int lastNonBlank = 0;
            boolean ruby = "ruby".equals(language);
            for (int i = 0; i < lines.length; i++) {
                String line = code[i];
                boolean continuation = brackets > 0;
                brackets = Math.max(0, brackets + bracketDelta(line));
                if (line.isBlank()) {
                    continue;
                }
                if (continuation) {
                    lastNonBlank = i + 1;
                    continue;
                }
                int indent = indentOf(line);
                while (!stack.isEmpty() && indent <= stack.peek().indent) {
                    Node done = stack.pop();
                    boolean closedByEnd = ruby && indent == done.indent && RUBY_END.matcher(line).find();
                    done.endLine = closedByEnd ? i + 1 : Math.max(done.line, lastNonBlank);
                }
                Node header = ruby ? rubyHeader(i, line, stack.peek(), indent) : pythonHeader(i, line, stack.peek(), indent);
                if (header != null && attach(header, stack.peek())) {
                    stack.push(header);
                }
                lastNonBlank = i + 1;
            }
            while (!stack.isEmpty()) {
                Node done = stack.pop();
                done.endLine = Math.max(done.line, lastNonBlank);
            }
        }

        private Node pythonHeader(int i, String line, Node parent, int indent) {
            Matcher m = SnippetCompletionSupport.PY_CLASS.matcher(line);
            if (m.find()) {
                return node(NodeKind.CLASS, m, 1, i, indent, "");
            }
            m = SnippetCompletionSupport.PY_DEF.matcher(line);
            if (m.find()) {
                Node def = node(methodOrFunction(parent), m, 1, i, indent, "");
                def.paramsOpen = starts[i] + line.indexOf('(', m.end(1));
                return def;
            }
            return null;
        }

        private Node rubyHeader(int i, String line, Node parent, int indent) {
            Matcher m = RUBY_CLASS.matcher(line);
            if (m.find()) {
                return node(NodeKind.CLASS, m, 1, i, indent, "");
            }
            m = RUBY_MODULE.matcher(line);
            if (m.find()) {
                return node(NodeKind.MODULE, m, 1, i, indent, "module");
            }
            m = RUBY_DEF.matcher(line);
            if (m.find()) {
                Node def = node(methodOrFunction(parent), m, 2, i, indent, m.group(1) != null ? "self" : "");
                int after = m.end(2);
                int open = after;
                while (open < line.length() && line.charAt(open) == ' ') {
                    open++;
                }
                if (open < line.length() && line.charAt(open) == '(') {
                    def.paramsOpen = starts[i] + open;
                }
                return def;
            }
            return null;
        }

        private static NodeKind methodOrFunction(Node parent) {
            return parent != null && parent.isType() ? NodeKind.METHOD : NodeKind.FUNCTION;
        }

        // ------------------------------------------------------------ outline: braces

        private void braceOutline() {
            Deque<Node> stack = new ArrayDeque<>();
            int depth = 0;
            Node pending = null;
            Node pendingParent = null;
            int pendingLine = -1;
            int pendingColumn = 0;
            int lastNonBlank = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = code[i];
                if (line.isBlank()) {
                    continue;
                }
                if (pending != null && i - pendingLine > PENDING_LINES) {
                    leafOrDrop(pending, pendingParent, pendingLine);
                    pending = null;
                }
                Node open = stack.peek();
                if ("perl".equals(language) && depth == 0) {
                    Matcher pkg = PERL_PACKAGE.matcher(line);
                    if (pkg.find() && !"{".equals(pkg.group(2))) {
                        while (!stack.isEmpty()) {
                            Node done = stack.pop();
                            done.endLine = Math.max(done.line, lastNonBlank);
                        }
                        Node module = node(NodeKind.MODULE, pkg, 1, i, 0, "package");
                        if (attach(module, null)) {
                            module.openDepth = STATEMENT;
                            stack.push(module);
                        }
                        lastNonBlank = i + 1;
                        continue;
                    }
                }
                Header header = braceHeader(i, line, open, depth);
                if (header != null) {
                    if (pending != null) {
                        leafOrDrop(pending, pendingParent, pendingLine);
                    }
                    pending = header.node();
                    pendingParent = open;
                    pendingLine = i;
                    pendingColumn = header.column();
                }
                for (int col = 0; col < line.length(); col++) {
                    char ch = line.charAt(col);
                    boolean afterHeader = pending != null && (i > pendingLine || col >= pendingColumn);
                    if (ch == '{') {
                        if (afterHeader) {
                            if (attach(pending, pendingParent)) {
                                pending.openDepth = depth;
                                stack.push(pending);
                            }
                            pending = null;
                        }
                        depth++;
                    } else if (ch == '}') {
                        depth = Math.max(0, depth - 1);
                        while (!stack.isEmpty() && stack.peek().openDepth >= depth) {
                            stack.pop().endLine = i + 1;
                        }
                    } else if (ch == ';' && afterHeader) {
                        leafOrDrop(pending, pendingParent, i);
                        pending = null;
                    }
                }
                if (pending != null && pending.sameLineOnly) {
                    leafOrDrop(pending, pendingParent, i);
                    pending = null;
                }
                lastNonBlank = i + 1;
            }
            if (pending != null) {
                leafOrDrop(pending, pendingParent, pendingLine);
            }
            while (!stack.isEmpty()) {
                Node done = stack.pop();
                done.endLine = Math.max(done.line, lastNonBlank);
            }
        }

        // ------------------------------------------------------------ outline: Lua keyword blocks

        /**
         * Lua nests by keywords: {@code function}, {@code if}, {@code do} (also of {@code for} and
         * {@code while}) and {@code repeat} open a block, {@code end} and {@code until} close one. A
         * named function closes with the block it opened. {@code function M.name} and
         * {@code function M:name} at the top level are grouped under the table {@code M} — at its
         * {@code local M = {}} when there is one — which counts as a class once it has a method.
         */
        private void luaOutline() {
            Deque<Node> stack = new ArrayDeque<>();
            Map<String, int[]> tables = new HashMap<>();
            Map<String, Node> groups = new HashMap<>();
            int depth = 0;
            int lastNonBlank = 0;
            for (int i = 0; i < lines.length; i++) {
                String line = code[i];
                if (line.isBlank()) {
                    continue;
                }
                if (depth == 0) {
                    Matcher table = LUA_TABLE_DECL.matcher(line);
                    if (table.find()) {
                        tables.putIfAbsent(table.group(1),
                            new int[] {i, starts[i] + table.start(1), starts[i] + table.end(1)});
                    }
                }
                Matcher word = LUA_BLOCK_WORD.matcher(line);
                while (word.find()) {
                    int col = word.start();
                    if (col > 0 && (line.charAt(col - 1) == '.' || line.charAt(col - 1) == ':')) {
                        continue;
                    }
                    switch (word.group(1)) {
                        case "function" -> {
                            Node function = luaFunction(i, line, col, word.end(), stack.peek(), tables, groups);
                            if (function != null) {
                                function.openDepth = depth;
                                stack.push(function);
                            }
                            depth++;
                        }
                        case "if", "do", "repeat" -> depth++;
                        default -> {
                            depth = Math.max(0, depth - 1);
                            while (!stack.isEmpty() && stack.peek().openDepth >= depth) {
                                closeLua(stack.pop(), i + 1);
                            }
                        }
                    }
                }
                lastNonBlank = i + 1;
            }
            while (!stack.isEmpty()) {
                Node done = stack.pop();
                closeLua(done, Math.max(done.line, lastNonBlank));
            }
            roots.sort(Comparator.comparingInt(node -> node.line));
        }

        private static void closeLua(Node node, int endLine) {
            node.endLine = endLine;
            if (node.parent != null && node.parent.isType()) {
                node.parent.endLine = Math.max(node.parent.endLine, endLine);
            }
        }

        /** The named function whose {@code function} keyword spans {@code [start, end)}; null when it is anonymous. */
        private Node luaFunction(int i, String line, int start, int end, Node open, Map<String, int[]> tables,
                                 Map<String, Node> groups) {
            String full;
            int nameColumn;
            int paramsFrom;
            Matcher after = LUA_FUNCTION_NAME.matcher(line).region(end, line.length());
            if (after.lookingAt()) {
                full = after.group(1);
                nameColumn = after.start(1);
                paramsFrom = after.end(1);
            } else {
                Matcher before = LUA_ASSIGNED_FUNCTION.matcher(line.substring(0, start));
                if (!before.find()) {
                    return null;
                }
                full = before.group(1);
                nameColumn = before.start(1);
                paramsFrom = end;
            }
            int separator = Math.max(full.lastIndexOf('.'), full.lastIndexOf(':'));
            boolean method = separator >= 0 && full.charAt(separator) == ':';
            Node parent = open;
            String name = full;
            int nameStart = starts[i] + nameColumn;
            if (separator >= 0 && open == null) {
                String table = full.substring(0, separator);
                Node group = groups.get(table);
                if (group == null) {
                    int[] declared = tables.get(table);
                    group = declared != null
                        ? new Node(NodeKind.MODULE, table, "table", declared[0] + 1, declared[1], declared[2], 0)
                        : new Node(NodeKind.MODULE, table, "table", i + 1, nameStart, nameStart + table.length(), 0);
                    if (!attach(group, null)) {
                        return null;
                    }
                    groups.put(table, group);
                }
                if (method) {
                    group.kind = NodeKind.CLASS;
                }
                parent = group;
                name = full.substring(separator + 1);
                nameStart += separator + 1;
            }
            Node function = new Node(method ? NodeKind.METHOD : NodeKind.FUNCTION, name, "", i + 1, nameStart,
                nameStart + name.length(), 0);
            int paren = line.indexOf('(', paramsFrom);
            if (paren >= 0) {
                function.paramsOpen = starts[i] + paren;
            }
            return attach(function, parent) ? function : null;
        }

        /** A top-level class or table node named {@code name}, or null. */
        private Node rootType(String name) {
            for (Node node : roots) {
                if (node.isType() && node.name.equals(name)) {
                    return node;
                }
            }
            return null;
        }

        private void leafOrDrop(Node node, Node parent, int endIndex) {
            if (node.allowLeaf && attach(node, parent)) {
                node.endLine = Math.max(node.line, endIndex + 1);
            }
        }

        /** The header on {@code line}, given the innermost open node and the brace depth at the line start. */
        private Header braceHeader(int i, String line, Node open, int depth) {
            boolean classBody = open != null && open.kind == NodeKind.CLASS && depth == open.openDepth + 1;
            Matcher m;
            switch (language) {
                case "bash" -> {
                    m = BASH_FUNCTION.matcher(line);
                    if (m.find()) {
                        int group = m.group(1) != null ? 1 : 2;
                        Node function = node(NodeKind.FUNCTION, m, group, i, 0, "");
                        function.allowLeaf = true;
                        return new Header(function, m.end());
                    }
                }
                case "perl" -> {
                    m = PERL_PACKAGE.matcher(line);
                    if (m.find() && "{".equals(m.group(2))) {
                        return new Header(node(NodeKind.MODULE, m, 1, i, 0, "package"), m.end(1));
                    }
                    m = PERL_SUB.matcher(line);
                    if (m.find()) {
                        Node sub = node(NodeKind.FUNCTION, m, 1, i, 0, "");
                        return withParams(sub, i, line, m.end(1));
                    }
                }
                case "powershell" -> {
                    m = PS_CLASS.matcher(line);
                    if (m.find()) {
                        String keyword = m.group(1).toLowerCase(Locale.ROOT);
                        return new Header(node(NodeKind.CLASS, m, 2, i, 0, "enum".equals(keyword) ? "enum" : ""),
                            m.end(2));
                    }
                    m = SnippetCompletionSupport.PS_FUNCTION.matcher(line);
                    if (m.find()) {
                        Node function = node(NodeKind.FUNCTION, m, 1, i, 0, "");
                        return withParams(function, i, line, m.end(1));
                    }
                    if (classBody) {
                        m = PS_METHOD.matcher(line);
                        if (m.find() && !CONTROL_WORDS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                            Node method = node(NodeKind.METHOD, m, 1, i, 0, "");
                            return withParams(method, i, line, m.end(1));
                        }
                    }
                }
                case "java", "groovy" -> {
                    return javaHeader(i, line, open, classBody);
                }
                case "javascript", "typescript" -> {
                    return scriptHeader(i, line, classBody);
                }
                default -> {
                }
            }
            return null;
        }

        private Header javaHeader(int i, String line, Node open, boolean classBody) {
            Matcher m = JAVA_CLASS.matcher(line);
            if (m.find()) {
                String keyword = m.group(1);
                return new Header(node(NodeKind.CLASS, m, 2, i, 0, "class".equals(keyword) ? "" : keyword),
                    m.end(2));
            }
            // A Groovy script may define functions outside any class; Java only has methods.
            boolean scriptFunction = "groovy".equals(language) && open == null;
            if (!classBody && !scriptFunction) {
                return null;
            }
            m = JAVA_METHOD.matcher(line);
            if (!m.find()) {
                return null;
            }
            String type = m.group(1);
            String name = m.group(2);
            if (CONTROL_WORDS.contains(name) || type != null && NON_TYPES.contains(type)) {
                return null;
            }
            boolean constructor = type == null && open != null && name.equals(open.name);
            if (type == null && !constructor) {
                return null;
            }
            Node method = node(classBody ? NodeKind.METHOD : NodeKind.FUNCTION, m, 2, i, 0, "");
            method.allowLeaf = classBody;
            return withParams(method, i, line, m.end(2));
        }

        private Header scriptHeader(int i, String line, boolean classBody) {
            Matcher m = JS_CLASS.matcher(line);
            if (m.find()) {
                String keyword = m.group(1);
                NodeKind kind = "namespace".equals(keyword) || "module".equals(keyword) ? NodeKind.MODULE : NodeKind.CLASS;
                return new Header(node(kind, m, 2, i, 0, "class".equals(keyword) ? "" : keyword), m.end(2));
            }
            m = JS_FUNCTION.matcher(line);
            if (m.find()) {
                Node function = node(NodeKind.FUNCTION, m, 1, i, 0, "");
                function.allowLeaf = true;
                return withParams(function, i, line, m.end(1));
            }
            m = SnippetCompletionSupport.GEN_ARROW.matcher(line);
            if (m.find()) {
                Node function = node(NodeKind.FUNCTION, m, 1, i, 0, "");
                function.allowLeaf = true;
                function.sameLineOnly = true;
                arrowParams(function, i, line, m.end(1), m.end());
                return new Header(function, m.end());
            }
            m = SnippetCompletionSupport.GEN_FUNC_EXPR.matcher(line);
            if (m.find()) {
                Node function = node(NodeKind.FUNCTION, m, 1, i, 0, "");
                return withParams(function, i, line, m.end());
            }
            if (classBody) {
                m = JS_ARROW_MEMBER.matcher(line);
                if (m.find()) {
                    Node method = node(NodeKind.METHOD, m, 1, i, 0, "");
                    method.allowLeaf = true;
                    method.sameLineOnly = true;
                    arrowParams(method, i, line, m.end(1), m.end());
                    return new Header(method, m.end());
                }
                m = JS_METHOD.matcher(line);
                if (m.find() && !CONTROL_WORDS.contains(m.group(1))) {
                    Node method = node(NodeKind.METHOD, m, 1, i, 0, "");
                    method.allowLeaf = true;
                    return withParams(method, i, line, m.end(1));
                }
            }
            return null;
        }

        /**
         * Records the parameter list that opens after {@code from} on the header line and returns the
         * header with its body column after the closing parenthesis (a destructured parameter's brace
         * must not open the body).
         */
        private Header withParams(Node node, int i, String line, int from) {
            Matcher open = PARAMS_OPEN.matcher(line);
            int column = from;
            if (from <= line.length() && open.region(from, line.length()).lookingAt()) {
                int paren = open.end() - 1;
                node.paramsOpen = starts[i] + paren;
                int close = matchingParen(line, paren);
                column = close >= 0 ? close + 1 : line.length();
            }
            return new Header(node, column);
        }

        /** An arrow function's parameter list: a {@code (} between the name and the {@code =>}. */
        private void arrowParams(Node node, int i, String line, int nameEnd, int arrowEnd) {
            int open = line.indexOf('(', nameEnd);
            if (open >= 0 && open < arrowEnd) {
                node.paramsOpen = starts[i] + open;
            }
        }

        private static int matchingParen(String line, int open) {
            int depth = 0;
            for (int i = open; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '(') {
                    depth++;
                } else if (c == ')' && --depth == 0) {
                    return i;
                }
            }
            return -1;
        }

        // ------------------------------------------------------------ outline: SQL, Markdown, AsciiDoc, Dockerfile

        private void sqlOutline() {
            for (int i = 0; i < lines.length; i++) {
                Matcher m = SnippetCompletionSupport.GEN_SQL_CREATE.matcher(code[i]);
                if (!m.find()) {
                    continue;
                }
                Node object = node(NodeKind.SQL_OBJECT, m, 2, i, 0, m.group(1).toLowerCase(Locale.ROOT));
                int end = i;
                while (end < lines.length && code[end].indexOf(';', end == i ? m.end() : 0) < 0) {
                    end++;
                }
                object.endLine = Math.min(lines.length, end + 1);
                attach(object, null);
            }
        }

        private void markdownOutline() {
            String fence = null;
            Deque<Node> stack = new ArrayDeque<>();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                Matcher f = MD_FENCE.matcher(line);
                if (f.find()) {
                    String marker = f.group(1);
                    if (fence == null) {
                        fence = marker;
                    } else if (marker.charAt(0) == fence.charAt(0) && marker.length() >= fence.length()
                        && line.strip().equals(marker)) {
                        fence = null;
                    }
                    continue;
                }
                if (fence != null) {
                    continue;
                }
                Matcher m = MD_HEADING.matcher(line);
                if (m.find() && !m.group(2).isBlank()) {
                    section(stack, m, i, m.group(1).length());
                }
            }
            closeSections(stack);
        }

        private void asciidocOutline() {
            String block = null;
            Deque<Node> stack = new ArrayDeque<>();
            for (int i = 0; i < lines.length; i++) {
                String line = lines[i];
                Matcher d = ADOC_DELIMITER.matcher(line);
                if (d.find()) {
                    String marker = line.strip();
                    if (block == null) {
                        block = marker;
                    } else if (marker.equals(block)) {
                        block = null;
                    }
                    continue;
                }
                if (block != null) {
                    continue;
                }
                Matcher m = ADOC_HEADING.matcher(line);
                if (m.find()) {
                    section(stack, m, i, m.group(1).length());
                }
            }
            closeSections(stack);
        }

        /** A heading of {@code level} at line {@code i}: closes same or deeper sections and opens one. */
        private void section(Deque<Node> stack, Matcher m, int i, int level) {
            while (!stack.isEmpty() && stack.peek().indent >= level) {
                stack.pop().endLine = i;
            }
            Node heading = node(NodeKind.SECTION, m, 2, i, level, "");
            if (attach(heading, stack.peek())) {
                stack.push(heading);
            }
        }

        private void closeSections(Deque<Node> stack) {
            while (!stack.isEmpty()) {
                stack.pop().endLine = lines.length;
            }
        }

        private void dockerOutline() {
            Node previous = null;
            for (int i = 0; i < lines.length; i++) {
                Matcher m = DOCKER_FROM.matcher(code[i]);
                if (!m.find()) {
                    continue;
                }
                if (previous != null) {
                    previous.endLine = Math.max(previous.line, i);
                }
                boolean alias = m.group(2) != null;
                Node stage = node(NodeKind.STAGE, m, alias ? 2 : 1, i, 0, alias ? m.group(1) : "");
                stage.endLine = lines.length;
                previous = attach(stage, null) ? stage : null;
            }
        }

        // ------------------------------------------------------------ nodes

        private Node node(NodeKind kind, Matcher m, int group, int i, int indent, String detail) {
            int start = starts[i] + m.start(group);
            int end = starts[i] + m.end(group);
            return new Node(kind, m.group(group), detail, i + 1, start, end, indent);
        }

        private boolean attach(Node node, Node parent) {
            if (allNodes.size() >= MAX_ENTRIES) {
                truncated = true;
                return false;
            }
            node.parent = parent;
            (parent != null ? parent.children : roots).add(node);
            allNodes.add(node);
            return true;
        }

        private String separator() {
            return switch (language) {
                case "perl" -> "::";
                case "markdown", "asciidoctor" -> " › ";
                default -> ".";
            };
        }

        private String qualifiedName(Node node) {
            StringBuilder name = new StringBuilder(node.name);
            for (Node parent = node.parent; parent != null; parent = parent.parent) {
                name.insert(0, parent.name + separator());
            }
            return name.toString();
        }

        private List<OutlineNode> build(List<Node> nodes) {
            List<OutlineNode> out = new ArrayList<>(nodes.size());
            for (Node node : nodes) {
                out.add(new OutlineNode(node.kind, node.name, node.detail, qualifiedName(node), node.line,
                    Math.max(node.line, node.endLine), node.nameStart, node.nameEnd, build(node.children)));
            }
            return out;
        }

        /** The innermost node whose lines contain {@code lineNumber} (1-based), or null. */
        private Node nodeAt(int lineNumber) {
            Node found = null;
            List<Node> level = roots;
            while (true) {
                Node next = null;
                for (Node node : level) {
                    if (node.contains(lineNumber) && (next == null || node.line > next.line)) {
                        next = node;
                    }
                }
                if (next == null) {
                    return found;
                }
                found = next;
                level = next.children;
            }
        }

        // ------------------------------------------------------------ definitions

        /** Parameters of every function and method that has a parameter list. */
        private void parameters() {
            for (Node node : allNodes) {
                if (node.isFunction() && node.paramsOpen >= 0) {
                    List<String> names = parameterList(node.paramsOpen);
                    if (!names.isEmpty() || node.detail.isEmpty()) {
                        String shown = String.join(", ", names);
                        node.detail = node.detail.isEmpty() ? "(" + shown + ")" : node.detail + " (" + shown + ")";
                    }
                }
            }
            if ("powershell".equals(language)) {
                Matcher m = PS_PARAM_BLOCK.matcher(codeText);
                while (m.find()) {
                    parameterList(m.end() - 1);
                }
            }
        }

        /** Reads the parameter list opening at {@code open}, adds a PARAMETER definition per name, returns the names. */
        private List<String> parameterList(int open) {
            List<String> names = new ArrayList<>();
            int depth = 0;
            int pieceStart = open + 1;
            boolean generics = "java".equals(language) || "groovy".equals(language) || "typescript".equals(language);
            int limit = Math.min(codeText.length(), open + 4_000);
            for (int i = open; i < limit; i++) {
                char c = codeText.charAt(i);
                if (c == '(' || c == '[' || c == '{' || generics && c == '<') {
                    depth++;
                } else if (c == ')' || c == ']' || c == '}' || generics && c == '>') {
                    depth--;
                    if (depth == 0) {
                        parameter(pieceStart, i, names);
                        return names;
                    }
                } else if (c == ',' && depth == 1) {
                    parameter(pieceStart, i, names);
                    pieceStart = i + 1;
                }
            }
            return names;
        }

        private void parameter(int from, int to, List<String> names) {
            if (from >= to) {
                return;
            }
            String piece = codeText.substring(from, to);
            Matcher m = switch (language) {
                case "python" -> Pattern.compile("^\\s*\\**(" + NAME + ")").matcher(piece);
                case "lua" -> Pattern.compile("^\\s*(" + NAME + ")").matcher(piece);
                case "ruby" -> Pattern.compile("^\\s*[*&]*(" + NAME + ")").matcher(piece);
                case "javascript", "typescript" -> Pattern.compile(
                    "^\\s*(?:(?:public|private|protected|readonly|override)\\s+)*(?:\\.\\.\\.)?(" + ID + ")")
                    .matcher(piece);
                case "java", "groovy" -> Pattern.compile("(" + ID + ")\\s*(?:\\[\\s*\\]\\s*)*(?:=.*)?$",
                    Pattern.DOTALL).matcher(stripAnnotations(piece));
                case "powershell", "perl" -> Pattern.compile("[$@%](" + NAME + ")").matcher(piece);
                default -> null;
            };
            if (m == null || !m.find()) {
                return;
            }
            String name = m.group(1);
            if ("self".equals(name) || "cls".equals(name) || "this".equals(name)) {
                return;
            }
            // stripAnnotations keeps every length, so the match offset is an offset in the piece.
            int offset = from + m.start(1);
            if (!codeText.startsWith(name, offset)) {
                offset = from + Math.max(0, piece.indexOf(name));
            }
            names.add(name);
            int line = lineIndexAt(offset);
            defs.add(new Def(line, offset, CandidateKind.VARIABLE, name, Flavor.PARAMETER, ScopeHint.LOCAL, 0));
        }

        /** Blanks annotations, {@code final} and generic arguments, keeping the length. */
        private static String stripAnnotations(String piece) {
            String text = blankMatches(ANNOTATION.matcher(piece));
            text = text.replaceAll("\\bfinal\\b", "     ");
            return blankMatches(GENERIC_ARGUMENTS.matcher(text));
        }

        private static String blankMatches(Matcher matcher) {
            return matcher.replaceAll(match -> " ".repeat(match.group().length()));
        }

        /** Definitions from the completion rules, one masked line at a time. */
        private void harvested() {
            int[] current = new int[1];
            Harvester harvester = new Harvester(language, (kind, name, flavor, scope) -> {
                if (kind != CandidateKind.FUNCTION) {
                    defs.add(new Def(current[0], -1, kind, name, flavor, scope, 2));
                }
            });
            for (int i = 0; i < lines.length; i++) {
                if (!code[i].isBlank()) {
                    current[0] = i;
                    harvester.line(code[i]);
                }
            }
        }

        /** The forms the completion rules leave out: fields, Java declarations without a value, … */
        private void extraDefinitions() {
            for (int i = 0; i < lines.length; i++) {
                String line = code[i];
                if (line.isBlank()) {
                    continue;
                }
                Matcher m;
                switch (language) {
                    case "python" -> {
                        if ((m = PY_SELF_FIELD.matcher(line)).find()) {
                            def(i, m, 1, CandidateKind.VARIABLE, Flavor.FIELD, ScopeHint.DEFAULT);
                        }
                    }
                    case "javascript", "typescript" -> {
                        if ((m = JS_THIS_FIELD.matcher(line)).find()) {
                            def(i, m, 1, CandidateKind.VARIABLE, Flavor.FIELD, ScopeHint.DEFAULT);
                        } else if ((m = JS_PRIVATE_FIELD.matcher(line)).find() || (m = TS_FIELD_DECL.matcher(line)).find()) {
                            Node at = nodeAt(i + 1);
                            if (at != null && at.kind == NodeKind.CLASS) {
                                def(i, m, 1, CandidateKind.VARIABLE, Flavor.FIELD, ScopeHint.DEFAULT);
                            }
                        } else if ((m = JS_DECL.matcher(line)).find()) {
                            fallback(i, m, 1);
                        }
                    }
                    case "java", "groovy" -> {
                        m = JAVA_DECL.matcher(line);
                        if (m.find() && !NON_TYPES.contains(m.group(1)) && !CONTROL_WORDS.contains(m.group(1))) {
                            fallback(i, m, 2);
                            if (",".equals(m.group(3))) {
                                Matcher more = JAVA_MORE_DECLARATORS.matcher(line);
                                more.region(m.end(), line.length());
                                while (more.find()) {
                                    fallback(i, more, 1);
                                    if (";".equals(more.group(2))) {
                                        break;
                                    }
                                }
                            }
                        }
                    }
                    case "lua" -> {
                        if ((m = LUA_SELF_FIELD.matcher(line)).find()) {
                            def(i, m, 1, CandidateKind.VARIABLE, Flavor.FIELD, ScopeHint.DEFAULT);
                        } else if ((m = LUA_TABLE_FIELD.matcher(line)).find() && !m.group(3).startsWith("function")) {
                            Node owner = rootType(m.group(1));
                            if (owner != null) {
                                CandidateKind kind = m.group(3).startsWith("{") ? CandidateKind.HASH : CandidateKind.VARIABLE;
                                defs.add(new Def(i, starts[i] + m.start(2), kind, m.group(2), Flavor.FIELD,
                                    ScopeHint.DEFAULT, 1, owner));
                            }
                        }
                    }
                    case "perl" -> {
                        if ((m = PERL_CONSTANT.matcher(line)).find()) {
                            def(i, m, 1, CandidateKind.VARIABLE, Flavor.CONSTANT, ScopeHint.GLOBAL);
                        }
                    }
                    case "powershell" -> {
                        if ((m = PS_ENV_ASSIGN.matcher(line)).find()) {
                            def(i, m, 1, CandidateKind.VARIABLE, Flavor.EXPORTED, ScopeHint.GLOBAL);
                        }
                    }
                    default -> {
                    }
                }
            }
        }

        private void sqlVariables() {
            for (int i = 0; i < lines.length; i++) {
                Matcher m = SQL_DECLARE.matcher(code[i]);
                while (m.find()) {
                    def(i, m, 1, CandidateKind.VARIABLE, Flavor.PLAIN, ScopeHint.GLOBAL);
                }
                m = SQL_SET.matcher(code[i]);
                if (m.find()) {
                    def(i, m, 1, CandidateKind.VARIABLE, Flavor.PLAIN, ScopeHint.GLOBAL);
                }
            }
        }

        private void dockerVariables() {
            for (int i = 0; i < lines.length; i++) {
                Matcher m = DOCKER_ARG.matcher(code[i]);
                if (m.find()) {
                    def(i, m, 1, CandidateKind.VARIABLE, Flavor.PARAMETER, ScopeHint.LOCAL);
                    continue;
                }
                m = DOCKER_ENV.matcher(code[i]);
                if (!m.find()) {
                    continue;
                }
                String rest = m.group(1);
                int restStart = m.start(1);
                if (rest.indexOf('=') < 0) {
                    Matcher legacy = Pattern.compile("^\\s*(" + NAME + ")").matcher(rest);
                    if (legacy.find()) {
                        defs.add(new Def(i, starts[i] + restStart + legacy.start(1), CandidateKind.VARIABLE,
                            legacy.group(1), Flavor.EXPORTED, ScopeHint.LOCAL, 1));
                    }
                    continue;
                }
                Matcher pair = DOCKER_ENV_PAIR.matcher(rest);
                while (pair.find()) {
                    defs.add(new Def(i, starts[i] + restStart + pair.start(1), CandidateKind.VARIABLE, pair.group(1),
                        Flavor.EXPORTED, ScopeHint.LOCAL, 1));
                }
            }
        }

        private void def(int i, Matcher m, int group, CandidateKind kind, Flavor flavor, ScopeHint scope) {
            defs.add(new Def(i, starts[i] + m.start(group), kind, m.group(group), flavor, scope, 1));
        }

        /**
         * A declaration the completion rules may have skipped (their stop-word list holds names such
         * as {@code limit} or {@code type}). Read after them, so their array/hash kind wins when both
         * found the name.
         */
        private void fallback(int i, Matcher m, int group) {
            defs.add(new Def(i, starts[i] + m.start(group), CandidateKind.VARIABLE, m.group(group), Flavor.PLAIN,
                ScopeHint.LOCAL, 3));
        }

        // ------------------------------------------------------------ resolving scope and kind

        private void resolveDefinitions() {
            defs.sort(Comparator.comparingInt(Def::line).thenComparingInt(Def::priority));
            for (Def def : defs) {
                if (variables.size() >= MAX_ENTRIES) {
                    truncated = true;
                    return;
                }
                resolve(def);
            }
        }

        private void resolve(Def def) {
            String raw = def.name();
            int lineNumber = def.line() + 1;
            Node enclosing = nodeAt(lineNumber);
            Node function = enclosing;
            while (function != null && !function.isFunction()) {
                function = function.parent;
            }
            Node type = enclosing;
            while (type != null && !type.isType()) {
                type = type.parent;
            }
            if (enclosing != null && enclosing.line == lineNumber && enclosing.name.equals(raw)
                && def.flavor() != Flavor.PARAMETER) {
                return; // const handler = (…) => …: the function's own name, already in the outline
            }
            boolean classBody = function == null && enclosing != null && enclosing.isType()
                && CLASS_BODY_FIELDS.contains(language);
            if ("dockerfile".equals(language)) {
                function = enclosing;
                classBody = false;
            }
            Container container = switch (def.kind()) {
                case ARRAY -> Container.ARRAY;
                case HASH -> Container.HASH;
                default -> Container.SCALAR;
            };
            Node scope;
            VariableKind kind;
            switch (def.flavor()) {
                case PARAMETER -> {
                    kind = VariableKind.PARAMETER;
                    scope = function;
                }
                case FIELD -> {
                    kind = VariableKind.FIELD;
                    scope = def.owner() != null ? def.owner() : type;
                }
                case EXPORTED -> {
                    kind = VariableKind.EXPORTED;
                    scope = def.scope() == ScopeHint.LOCAL ? function : null;
                }
                case CONSTANT -> {
                    kind = VariableKind.CONSTANT;
                    scope = def.scope() == ScopeHint.GLOBAL ? null : function;
                }
                default -> {
                    if (def.scope() == ScopeHint.DEFAULT && visible(raw, function, type) != null) {
                        return;
                    }
                    if (def.scope() == ScopeHint.GLOBAL) {
                        scope = null;
                    } else if (function != null) {
                        scope = def.scope() == ScopeHint.DEFAULT && GLOBAL_BY_DEFAULT.contains(language) ? null : function;
                    } else {
                        scope = classBody ? type : null;
                    }
                    kind = classBody && scope != null ? VariableKind.FIELD : switch (container) {
                        case ARRAY -> VariableKind.ARRAY;
                        case HASH -> VariableKind.HASH;
                        default -> VariableKind.VARIABLE;
                    };
                }
            }
            if (kind != VariableKind.PARAMETER && kind != VariableKind.EXPORTED && looksConstant(raw)) {
                kind = VariableKind.CONSTANT;
            }
            String key = key(scope, raw);
            if (byScopeAndName.containsKey(key)) {
                return;
            }
            int start = def.start() >= 0 ? def.start() : nameOffset(def.line(), raw);
            // `use constant NAME => …` is a bareword, not a sigil variable.
            String shown = def.flavor() == Flavor.CONSTANT && "perl".equals(language) ? raw : displayName(raw, container);
            Var var = new Var(raw, shown, kind, container, scope, lineNumber, start, start + raw.length());
            byScopeAndName.put(key, var);
            variables.add(var);
        }

        /** The variable a plain assignment refers to: in the enclosing functions, the class, or global. */
        private Var visible(String raw, Node function, Node type) {
            for (Node n = function; n != null; n = n.parent) {
                if (n.isFunction()) {
                    Var var = byScopeAndName.get(key(n, raw));
                    if (var != null) {
                        return var;
                    }
                }
            }
            if (type != null && !"python".equals(language) && !"ruby".equals(language)) {
                Var var = byScopeAndName.get(key(type, raw));
                if (var != null) {
                    return var;
                }
            }
            return byScopeAndName.get(key(null, raw));
        }

        private String key(Node scope, String raw) {
            String name = "powershell".equals(language) ? raw.toLowerCase(Locale.ROOT) : raw;
            return (scope != null ? System.identityHashCode(scope) + "#" + scope.line : "") + "\u0000" + name;
        }

        private boolean looksConstant(String raw) {
            return switch (language) {
                case "python", "javascript", "typescript", "java", "groovy", "lua" -> ALL_CAPS.matcher(raw).matches();
                case "ruby" -> !raw.isEmpty() && Character.isUpperCase(raw.charAt(0));
                default -> false;
            };
        }

        private String displayName(String raw, Container container) {
            return switch (language) {
                case "perl" -> (container == Container.ARRAY ? "@" : container == Container.HASH ? "%" : "$") + raw;
                case "powershell" -> "$" + raw;
                default -> raw;
            };
        }

        /** Offset of {@code raw} as a whole word on line {@code i}; the line start when it is not found. */
        private int nameOffset(int i, String raw) {
            Matcher m = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(raw) + "(?![A-Za-z0-9_])").matcher(code[i]);
            return starts[i] + (m.find() ? m.start() : 0);
        }

        private int lineIndexAt(int offset) {
            int low = 0;
            int high = starts.length - 1;
            while (low < high) {
                int mid = (low + high + 1) >>> 1;
                if (starts[mid] <= offset) {
                    low = mid;
                } else {
                    high = mid - 1;
                }
            }
            return low;
        }

        // ------------------------------------------------------------ references

        private void countReferences() {
            if (variables.isEmpty()) {
                return;
            }
            Map<String, List<Integer>> plain = new HashMap<>();
            Map<String, List<Integer>> dotted = new HashMap<>();
            for (int i = 0; i < refs.length; i++) {
                index(refs[i], i, plain, dotted);
            }
            Map<String, List<Var>> sameName = new HashMap<>();
            for (Var var : variables) {
                sameName.computeIfAbsent(lookupName(var.rawName), k -> new ArrayList<>()).add(var);
            }
            for (Var var : variables) {
                // A field is used as self.x / this.x; Java and Groovy also use it by its bare name.
                boolean dottedUses = var.kind == VariableKind.FIELD && WORD_LANGUAGES.contains(language);
                boolean plainUses = !dottedUses || "java".equals(language) || "groovy".equals(language);
                String name = lookupName(var.rawName);
                int count = 0;
                if (plainUses) {
                    for (int line : plain.getOrDefault(name, List.of())) {
                        int lineNumber = line + 1;
                        if ((var.scope == null || var.scope.contains(lineNumber))
                            && !shadowed(var, lineNumber, sameName.get(name))) {
                            count++;
                        }
                    }
                }
                if (dottedUses) {
                    // An explicit this.x / self.x is the field even where a local of that name exists.
                    for (int line : dotted.getOrDefault(name, List.of())) {
                        if (var.scope == null || var.scope.contains(line + 1)) {
                            count++;
                        }
                    }
                }
                var.references = Math.max(0, count - (definitionCounted(var, plainUses, dottedUses) ? 1 : 0));
            }
        }

        /** Whether the definition itself is among the counted occurrences (and so is not a use). */
        private boolean definitionCounted(Var var, boolean plainUses, boolean dottedUses) {
            int at = var.nameStart;
            char before = at > 0 && at <= codeText.length() ? codeText.charAt(at - 1) : ' ';
            return switch (language) {
                case "bash", "dockerfile" -> false;
                case "perl" -> "$@%{#".indexOf(before) >= 0;
                case "powershell" -> before == '$' || before == ':';
                case "ruby" -> true;
                default -> {
                    int i = at - 1;
                    while (i >= 0 && codeText.charAt(i) == ' ') {
                        i--;
                    }
                    boolean dotted = i >= 0 && codeText.charAt(i) == '.';
                    yield dotted ? dottedUses : plainUses;
                }
            };
        }

        /** True when another same-named variable, scoped inside {@code var}'s scope, covers the line. */
        private static boolean shadowed(Var var, int lineNumber, List<Var> sameName) {
            if (sameName == null || sameName.size() < 2) {
                return false;
            }
            for (Var other : sameName) {
                if (other == var || other.scope == null || other.scope == var.scope || !other.scope.contains(lineNumber)) {
                    continue;
                }
                if (var.scope == null || isInside(other.scope, var.scope)) {
                    return true;
                }
            }
            return false;
        }

        private static boolean isInside(Node inner, Node outer) {
            for (Node n = inner.parent; n != null; n = n.parent) {
                if (n == outer) {
                    return true;
                }
            }
            return false;
        }

        private String lookupName(String raw) {
            return "powershell".equals(language) ? raw.toLowerCase(Locale.ROOT) : raw;
        }

        private void index(String line, int i, Map<String, List<Integer>> plain, Map<String, List<Integer>> dotted) {
            if (line.isBlank()) {
                return;
            }
            Matcher m;
            switch (language) {
                case "bash", "dockerfile" -> {
                    m = SHELL_REFERENCE.matcher(line);
                    while (m.find()) {
                        plain.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(i);
                    }
                }
                case "perl" -> {
                    m = PERL_REFERENCE.matcher(line);
                    while (m.find()) {
                        plain.computeIfAbsent(m.group(1), k -> new ArrayList<>()).add(i);
                    }
                }
                case "powershell" -> {
                    m = PS_REFERENCE.matcher(line);
                    while (m.find()) {
                        plain.computeIfAbsent(m.group(1).toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(i);
                    }
                }
                case "ruby" -> {
                    m = RUBY_REFERENCE.matcher(line);
                    while (m.find()) {
                        int start = m.start();
                        if (start > 0 && (line.charAt(start - 1) == '.' || line.charAt(start - 1) == ':'
                            || Character.isLetterOrDigit(line.charAt(start - 1)) || line.charAt(start - 1) == '_')) {
                            continue;
                        }
                        String sigil = m.group(1) != null ? m.group(1) : "";
                        plain.computeIfAbsent(sigil + m.group(2), k -> new ArrayList<>()).add(i);
                    }
                }
                default -> {
                    m = WORD_REFERENCE.matcher(line);
                    while (m.find()) {
                        int start = m.start();
                        if (start > 0 && (Character.isLetterOrDigit(line.charAt(start - 1))
                            || line.charAt(start - 1) == '_')) {
                            continue;
                        }
                        int after = m.end();
                        while (after < line.length() && line.charAt(after) == ' ') {
                            after++;
                        }
                        if (after < line.length() && line.charAt(after) == '(') {
                            continue; // a call or a method header, not a use of a variable
                        }
                        int before = start - 1;
                        while (before >= 0 && line.charAt(before) == ' ') {
                            before--;
                        }
                        boolean dot = before >= 0 && line.charAt(before) == '.';
                        (dot ? dotted : plain).computeIfAbsent(m.group(), k -> new ArrayList<>()).add(i);
                    }
                }
            }
        }

        // ------------------------------------------------------------ small helpers

        private static int bracketDelta(String line) {
            int delta = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '(' || c == '[' || c == '{') {
                    delta++;
                } else if (c == ')' || c == ']' || c == '}') {
                    delta--;
                }
            }
            return delta;
        }

        private static int indentOf(String line) {
            int column = 0;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == ' ') {
                    column++;
                } else if (c == '\t') {
                    column = (column / 8 + 1) * 8;
                } else {
                    break;
                }
            }
            return column;
        }
    }
}
