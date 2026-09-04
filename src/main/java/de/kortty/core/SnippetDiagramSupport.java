package de.kortty.core;

import de.kortty.model.SnippetDiagram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local helpers for the restricted Mermaid flowcharts generated for snippets.
 *
 * <p>This is intentionally not a general Mermaid parser. AI-generated snippet diagrams use a small,
 * deterministic dialect so source mappings remain stable and untrusted diagrams cannot opt into
 * Mermaid features that load resources, attach callbacks, or inject their own styles.</p>
 */
public final class SnippetDiagramSupport {
    public static final int MAX_MERMAID_SOURCE_BYTES = 32 * 1024;
    public static final int MAX_MERMAID_EDGES = 300;
    /**
     * Compactness cap for a generated flowchart of a short snippet: the number of action and
     * decision nodes it may declare. The cap grows with the snippet (see
     * {@link #maxGeneratedNonterminalNodes(String)}); this base value applies up to
     * {@link #NODE_CAP_BASE_LINES} lines and to every content-free validation.
     */
    public static final int MAX_GENERATED_NONTERMINAL_NODES = 12;
    /** Cap for a long script — reached at {@link #NODE_CAP_MAX_LINES} lines and never exceeded. */
    public static final int MAX_GENERATED_NONTERMINAL_NODES_LONG_SNIPPET = 24;
    /** Snippets up to this many lines keep the base cap. */
    public static final int NODE_CAP_BASE_LINES = 200;
    /** Snippets of at least this many lines get the long-snippet cap; in between it grows linearly. */
    public static final int NODE_CAP_MAX_LINES = 1_000;
    public static final String DARK_BACKGROUND_COLOR = "#1E1E1E";

    private static final Set<String> SEMANTIC_CLASSES = Set.of("setup", "work", "success", "failure");
    private static final Map<String, Set<String>> DECISION_OUTCOME_LABELS = Map.of(
        "de", Set.of("ja", "nein"),
        "en", Set.of("yes", "no"),
        "es", Set.of("sí", "no"),
        "fr", Set.of("oui", "non"),
        "hr", Set.of("da", "ne"),
        "it", Set.of("sì", "no"),
        "nl", Set.of("ja", "nee"),
        "pt", Set.of("sim", "não"));
    /** The same pairs in order (affirmative first), for translating and completing labels. */
    private static final Map<String, List<String>> ORDERED_OUTCOME_LABELS = Map.of(
        "en", List.of("yes", "no"),
        "de", List.of("ja", "nein"),
        "es", List.of("sí", "no"),
        "fr", List.of("oui", "non"),
        "hr", List.of("da", "ne"),
        "it", List.of("sì", "no"),
        "nl", List.of("ja", "nee"),
        "pt", List.of("sim", "não"));
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?i)^flowchart\\s+TD\\s*;?$");
    // A hyphen inside an id must be followed by a word character, so `a-->b` written without
    // spaces still parses as an edge from `a` rather than an id that swallowed the arrow.
    private static final String NODE_ID = "[A-Za-z][A-Za-z0-9_]{0,63}(?:-[A-Za-z0-9_]{1,63})*";
    private static final String QUOTED_LABEL = "\"((?:\\\\.|[^\"\\\\])*)\"";
    /** The three allowed shapes; groups in order: action label, decision label, terminal label. */
    private static final String NODE_SHAPE = "(?:\\[\\s*" + QUOTED_LABEL + "\\s*]|\\{\\s*" + QUOTED_LABEL
        + "\\s*}|\\(\\[\\s*" + QUOTED_LABEL + "\\s*]\\))";
    /** Mermaid's inline class shorthand ({@code node:::setup}): the same assignment a class statement makes. */
    private static final String INLINE_CLASS = "(?:\\s*:::\\s*(" + NODE_ID + "))?";
    // Groups: 1 id, 2 action label, 3 decision label, 4 terminal label, 5 inline class.
    private static final Pattern NODE_PATTERN = Pattern.compile(
        "^(" + NODE_ID + ")\\s*" + NODE_SHAPE + INLINE_CLASS + "\\s*;?$");
    /**
     * One end of an edge statement, as Mermaid allows it: a bare id, or a node declared right
     * there with its shape ({@code start_1(["Start"]) --> n1["Print header"]}) — which is how
     * models write flowcharts, and what a separate declaration line means anyway.
     */
    private static final Pattern BARE_INLINE_CLASS_PATTERN = Pattern.compile(
        "^(" + NODE_ID + ")\\s*:::\\s*(" + NODE_ID + ")\\s*;?$");
    private static final Pattern EDGE_ENDPOINT_PATTERN = Pattern.compile(
        "(" + NODE_ID + ")(?:\\s*" + NODE_SHAPE + ")?" + INLINE_CLASS);
    /** {@code -->}, {@code -->|label|} and the equivalent {@code -- label -->}; groups 1 and 2 carry the label. */
    private static final Pattern EDGE_OPERATOR_PATTERN = Pattern.compile(
        "\\s*(?:-->\\s*(?:\\|\\s*\"?([^|\"]*?)\"?\\s*\\|\\s*)?|--\\s*\"?([^\"|]*?)\"?\\s*-->\\s*)");
    private static final int UNSUPPORTED_LINE_EXCERPT_CHARS = 80;
    /** Every yes/no word of every supported language, for the label slips below. */
    private static final String OUTCOME_WORDS = DECISION_OUTCOME_LABELS.values().stream()
        .flatMap(Set::stream).map(Pattern::quote)
        .collect(java.util.stream.Collectors.joining("|"));
    /** {@code -->|yes| --> target}: a labelled arrow followed by a second, bare one. */
    private static final Pattern DOUBLED_ARROW_AFTER_LABEL = Pattern.compile("(-->\\s*\\|[^|]*\\|)\\s*-->\\s*");
    /** {@code -->|no]}: the closing pipe typed as a bracket. */
    private static final Pattern MISCLOSED_LABEL_PIPE = Pattern.compile("-->\\s*\\|([^|\\]]*?)]\\s*");
    /** {@code n{"Ready?" --> x}: a shape whose closing bracket was dropped before the arrow. */
    private static final Pattern UNCLOSED_SHAPE_BEFORE_ARROW = Pattern.compile(
        "(\\(\\[|\\[|\\{)\\s*(\"(?:\\\\.|[^\"\\\\])*\")\\s*(?=--)");
    /** {@code a --> yes|b} and {@code a --> yes --> b}: a label that lost its pipes, when the word is an outcome. */
    private static final Pattern MISPLACED_OUTCOME_LABEL = Pattern.compile(
        "(?i)-->\\s*\"?(" + OUTCOME_WORDS + ")\"?\\s*(?:\\||-->)\\s*");
    /** {@code {""Ready?""}}: a label quoted twice. */
    private static final Pattern DOUBLED_LABEL_QUOTES = Pattern.compile("(\\(\\[|\\[|\\{)\"\"([^\"]*)\"\"(]\\)|]|})");
    /** MiniMax-M3's recurring slip on the stadium shape: {@code (["Start")]} for {@code (["Start"])}. */
    private static final Pattern MISPLACED_STADIUM_CLOSE = Pattern.compile("\\(\\[\"((?:\\\\.|[^\"\\\\])*)\"\\)]");
    // Unquoted shapes, applied only to the parts of a line outside quotes so a quoted label is
    // never touched and a freshly quoted one is never wrapped twice.
    private static final Pattern UNQUOTED_STADIUM = Pattern.compile("\\(\\[\\s*([^\\[\\]()|\"]+?)\\s*]\\)");
    private static final Pattern UNQUOTED_ROUND = Pattern.compile("\\(\\s*([^()\\[\\]{}|\"]+?)\\s*\\)");
    private static final Pattern UNQUOTED_RECT = Pattern.compile("\\[\\s*([^\\[\\]|\"]+?)\\s*]");
    private static final Pattern UNQUOTED_DECISION = Pattern.compile("\\{\\s*([^{}|\"]+?)\\s*}");
    /**
     * Mermaid's other shapes — subroutine, cylinder, parallelogram/trapezoid, circle, hexagon,
     * flag — all read as an action: korTTY's dialect knows action, decision and terminal, and none
     * of these is a decision or an end. Quoted forms first (they span a quoted label), then the
     * unquoted ones on the parts of a line outside quotes.
     */
    private static final Pattern QUOTED_EXOTIC_SHAPE = Pattern.compile(
        "(?:\\[\\[|\\[\\(|\\[[/\\\\]|\\(\\(\\(?|\\{\\{|>)\\s*(\"(?:\\\\.|[^\"\\\\])*\")\\s*(?:]]|\\)]|[/\\\\]]|\\)\\)\\)?|}}|])");
    private static final Pattern UNQUOTED_EXOTIC_SHAPE = Pattern.compile(
        "(?:\\[\\[|\\[\\(|\\[[/\\\\]|\\(\\(\\(?|\\{\\{|>)\\s*([^\\[\\](){}|\"/\\\\>]+?)\\s*(?:]]|\\)]|[/\\\\]]|\\)\\)\\)?|}}|])");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "(?i)^class\\s+([A-Za-z][A-Za-z0-9_-]{0,63}(?:\\s*,\\s*[A-Za-z][A-Za-z0-9_-]{0,63})*)\\s+(setup|work|success|failure)\\s*;?$");
    private static final Pattern CONDITIONAL_FLOW_PATTERN = Pattern.compile(
        "(?im)^\\s*(?:}\\s*)?(?:if|unless|elif|elsif|else(?:\\s+if)?|case|switch|when)\\b");
    /** Pure presentation statements a model adds to "define" the semantic classes it was told to assign. */
    private static final Pattern PRESENTATION_STATEMENT_PATTERN = Pattern.compile(
        "(?i)^\\s*(?:classDef|style|linkStyle)\\b");
    /** The same statements inside a raw answer, whose line breaks may still be JSON-escaped. */
    private static final Pattern PRESENTATION_STATEMENT_IN_ANSWER_PATTERN = Pattern.compile(
        "(?i)(?:^|\\n|\\\\n)\\s*(?:classDef|style|linkStyle)\\b");
    private static final Pattern FORBIDDEN_DIRECTIVE_PATTERN = Pattern.compile(
        "(?im)^\\s*(?:---\\s*$|%%\\{|click\\b|href\\b|style\\b|classDef\\b|linkStyle\\b)");
    // Real resource references only: a bare scheme word followed by a colon is ordinary label
    // text ("use File::Glob", "read data: sysstat") and used to cost a whole diagram.
    private static final Pattern FORBIDDEN_URL_PATTERN = Pattern.compile(
        "(?i)(?:https?|ftp|file)://|\\bdata:[a-z]+/|javascript:\\s*\\S|\\bwww\\.|\\burl\\s*\\(");
    private static final Pattern FORBIDDEN_MEDIA_PATTERN = Pattern.compile(
        "(?i)@\\{|\\b(?:img|icon)\\s*:");
    private static final Pattern FORBIDDEN_HTML_PATTERN = Pattern.compile("<\\s*/?\\s*[A-Za-z!]");
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[A-Fa-f0-9]{6}$");
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "by", "default", "for", "from", "in", "into", "is", "main",
        "of", "or", "path", "snippet", "step", "the", "to", "with");

    private SnippetDiagramSupport() {
    }

    /** A validated mapping used by viewers and exporters. */
    public record CodeReference(
        String id,
        String nodeId,
        String label,
        int startLine,
        int endLine,
        String excerpt) {
    }

    /** The source mapping returned by the AI. Node ids, rather than SVG text, are the stable key. */
    public record SourceCodeReference(String nodeId, String label, int startLine, int endLine) {
        public SourceCodeReference {
            nodeId = nodeId != null ? nodeId.trim() : "";
            label = label != null ? label.trim() : "";
        }
    }

    /** Result of the local security and shape validation performed before Mermaid is invoked. */
    public record MermaidValidation(boolean valid, String diagramType, String message) {
        private static MermaidValidation success() {
            return new MermaidValidation(true, "flowchart", "");
        }

        /** Success without a family claim, for the shared screens and the non-flowchart grammars. */
        static MermaidValidation commonSuccess() {
            return new MermaidValidation(true, "", "");
        }

        private static MermaidValidation failure(String message) {
            return new MermaidValidation(false, "", message != null ? message : "Invalid Mermaid diagram.");
        }
    }

    /**
     * The number of action and decision nodes a freshly generated flowchart may declare for
     * {@code content}. Twelve nodes describe a typical snippet, but a 4,000-line script squeezed
     * into twelve nodes is either generic or — far more often — rejected outright because the
     * model transcribed the script instead. The cap therefore grows linearly from
     * {@link #MAX_GENERATED_NONTERMINAL_NODES} at {@link #NODE_CAP_BASE_LINES} lines to
     * {@link #MAX_GENERATED_NONTERMINAL_NODES_LONG_SNIPPET} at {@link #NODE_CAP_MAX_LINES} lines.
     * The diagram prompt states the same number, so the model and the validator always agree.
     */
    public static int maxGeneratedNonterminalNodes(String content) {
        return maxGeneratedNonterminalNodes(countLines(content));
    }

    /** The hard limit behind a stated cap: twice it, so an over-drawn summary keeps its diagram and only a transcription is refused. */
    public static int toleratedNonterminalNodes(int maxNonterminalNodes) {
        return maxNonterminalNodes * 2;
    }

    /** See {@link #maxGeneratedNonterminalNodes(String)}; {@code lineCount} is the snippet's line count. */
    public static int maxGeneratedNonterminalNodes(int lineCount) {
        if (lineCount <= NODE_CAP_BASE_LINES) {
            return MAX_GENERATED_NONTERMINAL_NODES;
        }
        if (lineCount >= NODE_CAP_MAX_LINES) {
            return MAX_GENERATED_NONTERMINAL_NODES_LONG_SNIPPET;
        }
        double progress = (lineCount - NODE_CAP_BASE_LINES)
            / (double) (NODE_CAP_MAX_LINES - NODE_CAP_BASE_LINES);
        int range = MAX_GENERATED_NONTERMINAL_NODES_LONG_SNIPPET - MAX_GENERATED_NONTERMINAL_NODES;
        return MAX_GENERATED_NONTERMINAL_NODES + (int) Math.round(range * progress);
    }

    /** Counts the lines of a snippet the way the line-numbered prompt numbers them; blank content has none. */
    public static int countLines(String content) {
        if (content == null || content.isBlank()) {
            return 0;
        }
        return content.split("\\R", -1).length;
    }

    /**
     * Which action and decision nodes of a generated flowchart carry a valid source reference.
     * An incomplete mapping no longer rejects a diagram — the affected nodes merely lose their
     * hover reference — but it is worth a log line, because it is the model ignoring the contract.
     */
    public record SourceMappingReport(int expectedNodes, List<String> unmappedNodeIds) {
        public SourceMappingReport {
            unmappedNodeIds = unmappedNodeIds != null ? List.copyOf(unmappedNodeIds) : List.of();
        }

        public int mappedNodes() {
            return expectedNodes - unmappedNodeIds.size();
        }

        public boolean complete() {
            return unmappedNodeIds.isEmpty();
        }
    }

    public static SourceMappingReport reportSourceMapping(
        String mermaidSource,
        String snippetContent,
        List<SourceCodeReference> sourceReferences) {

        ParsedDiagram parsed = parseRestrictedFlowchart(normalizeMermaid(mermaidSource));
        if (!parsed.validation().valid()) {
            return new SourceMappingReport(0, List.of());
        }
        Set<String> mappedNodeIds = new LinkedHashSet<>();
        buildValidatedCodeReferences(mermaidSource, snippetContent, sourceReferences)
            .forEach(reference -> mappedNodeIds.add(reference.nodeId()));
        int expectedNodes = 0;
        List<String> unmappedNodeIds = new ArrayList<>();
        for (NodeDefinition node : parsed.nodes().values()) {
            if (node.type() == NodeType.TERMINAL) {
                continue;
            }
            expectedNodes++;
            if (!mappedNodeIds.contains(node.id())) {
                unmappedNodeIds.add(node.id());
            }
        }
        return new SourceMappingReport(expectedNodes, unmappedNodeIds);
    }

    /**
     * The size of a generated flowchart, for the log lines that explain why it was accepted or
     * rejected. Counted line by line so that an oversized answer — the usual reason for a
     * rejection — is still measured after the strict parser has given up on it.
     */
    public record FlowchartStatistics(int actionNodes, int decisionNodes, int edges) {
        public int nonterminalNodes() {
            return actionNodes + decisionNodes;
        }

        @Override
        public String toString() {
            return "nodes=" + nonterminalNodes() + " (decisions=" + decisionNodes + "), edges=" + edges;
        }
    }

    public static FlowchartStatistics flowchartStatistics(String mermaidSource) {
        Set<String> actionIds = new LinkedHashSet<>();
        Set<String> decisionIds = new LinkedHashSet<>();
        int edges = 0;
        for (String rawLine : normalizeMermaid(mermaidSource).split("\\R")) {
            String line = normalizeShapeShorthand(rawLine.trim());
            Matcher nodeMatcher = NODE_PATTERN.matcher(line);
            if (nodeMatcher.matches()) {
                if (nodeMatcher.group(3) != null) {
                    decisionIds.add(nodeMatcher.group(1));
                } else if (nodeMatcher.group(4) == null) {
                    actionIds.add(nodeMatcher.group(1));
                }
            } else {
                EdgeStatement statement = parseEdgeStatement(line);
                if (statement != null) {
                    edges += statement.edges().size();
                    for (NodeDefinition node : statement.declaredNodes()) {
                        if (node.type() == NodeType.DECISION) {
                            decisionIds.add(node.id());
                        } else if (node.type() == NodeType.ACTION) {
                            actionIds.add(node.id());
                        }
                    }
                }
            }
        }
        return new FlowchartStatistics(actionIds.size(), decisionIds.size(), edges);
    }

    /**
     * Drops {@code classDef}, {@code style} and {@code linkStyle} lines from a freshly generated
     * diagram. The contract tells the model to assign four semantic classes and korTTY styles
     * those itself, but a model that reads "class" reaches for "classDef" and defines them with
     * colors — and the security screen then rejected a complete, correct diagram for its
     * decoration. Removing the lines cannot add capability; everything that remains still passes
     * the same screen, and the theme-aware class styling applies as designed. Only this
     * generation path strips; a persisted diagram is validated unchanged.
     */
    public static String stripPresentationStatements(String source) {
        if (source == null || source.isBlank()) {
            return source != null ? source : "";
        }
        StringBuilder kept = new StringBuilder(source.length());
        for (String line : source.split("\\R", -1)) {
            if (PRESENTATION_STATEMENT_PATTERN.matcher(line).find()) {
                continue;
            }
            if (kept.length() > 0) {
                kept.append('\n');
            }
            kept.append(line);
        }
        return kept.toString();
    }

    /** How many presentation statements {@link #stripPresentationStatements} would remove from an answer. */
    public static int countPresentationStatements(String answer) {
        if (answer == null || answer.isBlank()) {
            return 0;
        }
        Matcher matcher = PRESENTATION_STATEMENT_IN_ANSWER_PATTERN.matcher(answer);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    /**
     * Rewrites an accepted generated flowchart into the strict dialect: one declaration per node,
     * one edge per line, and a class statement for every node. The parser accepts the shorthand
     * models write — inline declarations, chained edges, {@code -- yes -->} labels,
     * {@code :::class} — but what gets rendered and saved is the canonical form, so every node
     * carries its color class explicitly and older readers of a persisted diagram see the dialect
     * they were written for. Returns the normalized source unchanged when it does not parse.
     */
    public static String canonicalizeGeneratedFlowchart(String source) {
        return canonicalizeGeneratedFlowchart(source, null);
    }

    public static String canonicalizeGeneratedFlowchart(String source, String languageCode) {
        String normalized = normalizeMermaid(source);
        ParsedDiagram parsed = parseRestrictedFlowchart(normalized, languageCode);
        if (!parsed.validation().valid()) {
            return normalized;
        }
        StringBuilder builder = new StringBuilder("flowchart TD\n");
        for (NodeDefinition node : parsed.nodes().values()) {
            builder.append("    ").append(node.id());
            String label = escapeLabel(node.label());
            switch (node.type()) {
                case DECISION -> builder.append("{\"").append(label).append("\"}");
                case TERMINAL -> builder.append("([\"").append(label).append("\"])");
                default -> builder.append("[\"").append(label).append("\"]");
            }
            builder.append('\n');
        }
        for (EdgeDefinition edge : parsed.edges()) {
            builder.append("    ").append(edge.from())
                .append(edge.label().isBlank() ? " --> " : " -->|" + edge.label() + "| ")
                .append(edge.to()).append('\n');
        }
        Map<String, List<String>> byClass = new LinkedHashMap<>();
        for (String semanticClass : List.of("setup", "work", "success", "failure")) {
            byClass.put(semanticClass, new ArrayList<>());
        }
        parsed.nodes().values().forEach(node -> byClass.get(node.semanticClass()).add(node.id()));
        byClass.forEach((semanticClass, ids) -> {
            if (!ids.isEmpty()) {
                builder.append("    class ").append(String.join(",", ids)).append(' ').append(semanticClass).append('\n');
            }
        });
        return builder.toString().stripTrailing();
    }

    public static String contentHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available.", e);
        }
    }

    public static boolean isStale(SnippetDiagram diagram, String currentContent) {
        if (diagram == null) {
            return false;
        }
        String savedHash = diagram.getSourceContentSha256();
        return savedHash != null && !savedHash.isBlank() && !savedHash.equals(contentHash(currentContent));
    }

    /** Removes only an optional Mermaid code fence; it never repairs or broadens invalid syntax. */
    public static String normalizeMermaid(String source) {
        String value = source != null ? source.trim() : "";
        if (value.isBlank()) {
            return "";
        }
        Matcher fence = Pattern.compile("(?is)^```mermaid\\s*\\R(.*?)\\R?```$").matcher(value);
        return fence.matches() ? fence.group(1).trim() : value;
    }

    public static boolean isRenderableMermaid(String source) {
        return validateMermaid(source).valid();
    }

    /**
     * Validates korTTY's restricted snippet-flowchart dialect and rejects network, media, callback,
     * frontmatter, directive and custom-style syntax before it reaches Mermaid.
     */
    public static MermaidValidation validateMermaid(String source) {
        MermaidValidation security = validateCommonSecurity(source);
        if (!security.valid()) {
            return security;
        }
        ParsedDiagram parsed = parseRestrictedFlowchart(normalizeMermaid(source));
        return parsed.validation();
    }

    /**
     * The shared security screen applied to every generated snippet diagram regardless of its
     * Mermaid family: size and NUL caps plus the forbidden directive/URL/media/HTML syntax that
     * could load resources, attach callbacks, or inject styles. Family grammars build on top.
     */
    static MermaidValidation validateCommonSecurity(String source) {
        if ((source != null ? source : "").getBytes(StandardCharsets.UTF_8).length > MAX_MERMAID_SOURCE_BYTES) {
            return MermaidValidation.failure("Mermaid source exceeds the 32 KiB limit.");
        }
        String value = normalizeMermaid(source);
        if (value.isBlank()) {
            return MermaidValidation.failure("Mermaid source is empty.");
        }
        if (value.indexOf('\0') >= 0) {
            return MermaidValidation.failure("Mermaid source contains an invalid NUL character.");
        }
        if (FORBIDDEN_DIRECTIVE_PATTERN.matcher(value).find()) {
            return MermaidValidation.failure("Mermaid directives, callbacks and custom styles are not allowed.");
        }
        if (FORBIDDEN_URL_PATTERN.matcher(value).find()) {
            return MermaidValidation.failure("Mermaid diagrams must not contain external or executable URLs.");
        }
        if (FORBIDDEN_MEDIA_PATTERN.matcher(value).find()) {
            return MermaidValidation.failure("Mermaid image and icon shapes are not allowed.");
        }
        if (FORBIDDEN_HTML_PATTERN.matcher(value).find()) {
            return MermaidValidation.failure("HTML labels are not allowed in Mermaid diagrams.");
        }
        return MermaidValidation.commonSuccess();
    }

    /**
     * Applies strict topology and compactness rules to a newly generated AI diagram, with the base
     * node cap of a short snippet. The general renderer deliberately uses
     * {@link #validateMermaid(String)} so diagrams saved by older korTTY versions remain
     * renderable when they are still safe restricted Mermaid.
     */
    public static MermaidValidation validateGeneratedMermaid(String source) {
        return validateGeneratedMermaid(source, MAX_GENERATED_NONTERMINAL_NODES);
    }

    /**
     * Applies strict topology and compactness rules to a newly generated AI diagram.
     *
     * @param maxNonterminalNodes the cap for action and decision nodes, normally
     *     {@link #maxGeneratedNonterminalNodes(String)} of the snippet the diagram describes
     */
    public static MermaidValidation validateGeneratedMermaid(String source, int maxNonterminalNodes) {
        return validateGeneratedMermaid(source, maxNonterminalNodes, null);
    }

    /** See {@link #validateGeneratedMermaid(String, int)}; {@code languageCode} completes a lone outcome label. */
    public static MermaidValidation validateGeneratedMermaid(String source, int maxNonterminalNodes, String languageCode) {
        MermaidValidation validation = validateMermaid(source);
        if (!validation.valid()) {
            return validation;
        }
        ParsedDiagram parsed = parseRestrictedFlowchart(normalizeMermaid(source), languageCode);
        String topologyError = validateFlowTopology(parsed.nodes(), parsed.edges());
        if (topologyError != null) {
            return MermaidValidation.failure(topologyError);
        }
        long nonterminalNodes = parsed.nodes().values().stream()
            .filter(node -> node.type() != NodeType.TERMINAL)
            .count();
        // The cap the prompt states is the target; the validator only stops a runaway. A model
        // that draws 14 nodes where 12 were asked for has still summarized the script, and
        // discarding that diagram for the generic fallback served nobody.
        int tolerated = toleratedNonterminalNodes(maxNonterminalNodes);
        if (nonterminalNodes > tolerated) {
            return MermaidValidation.failure(
                "Generated Mermaid flowcharts may use at most " + maxNonterminalNodes
                    + " non-terminal nodes for this snippet (" + tolerated + " tolerated), but "
                    + nonterminalNodes + " were declared; related behavior must be grouped.");
        }
        return validation;
    }

    /**
     * Validates a fresh AI diagram for the snippet it describes: topology, the snippet-sized node
     * cap and the localized decision labels. This intentionally avoids guessing source-language
     * semantics; behavior grouping remains the mandatory action skill's responsibility.
     *
     * <p>The source mapping is deliberately not a pass/fail criterion any more. A diagram whose
     * nodes lack a valid reference used to be discarded as a whole and silently replaced by the
     * generic local fallback; now the diagram is kept and only the unmapped nodes lose their
     * hover reference — {@link #reportSourceMapping} tells the caller which ones.</p>
     */
    public static MermaidValidation validateMermaidForSnippet(
        String source,
        String snippetContent,
        List<SourceCodeReference> sourceReferences,
        String responseLanguageCode) {

        MermaidValidation validation = validateGeneratedMermaid(
            source, maxGeneratedNonterminalNodes(snippetContent), responseLanguageCode);
        if (!validation.valid()) {
            return validation;
        }
        ParsedDiagram parsed = parseRestrictedFlowchart(normalizeMermaid(source), responseLanguageCode);
        Set<String> expectedOutcomeLabels = decisionOutcomeLabels(responseLanguageCode);
        if (!expectedOutcomeLabels.isEmpty()) {
            for (NodeDefinition node : parsed.nodes().values()) {
                if (node.type() != NodeType.DECISION) {
                    continue;
                }
                Set<String> actualLabels = new LinkedHashSet<>();
                parsed.edges().stream()
                    .filter(edge -> node.id().equals(edge.from()))
                    .map(edge -> normalizeDiagramLabel(edge.label()).toLowerCase(Locale.ROOT))
                    .forEach(actualLabels::add);
                if (!actualLabels.equals(expectedOutcomeLabels)) {
                    return MermaidValidation.failure(
                        "Generated Mermaid decision edges must use the localized yes/no labels for language "
                            + normalizeLanguageCode(responseLanguageCode) + ".");
                }
            }
        }
        return validation;
    }

    private static Set<String> decisionOutcomeLabels(String languageCode) {
        return DECISION_OUTCOME_LABELS.getOrDefault(normalizeLanguageCode(languageCode), Set.of());
    }

    private static String normalizeLanguageCode(String languageCode) {
        String value = languageCode != null ? languageCode.trim().replace('_', '-') : "";
        String normalized = Locale.forLanguageTag(value).getLanguage();
        return !normalized.isBlank() ? normalized : "en";
    }

    /**
     * Creates a deterministic local fallback using only quoted nodes, stable ids and korTTY's four
     * semantic classes. The unused language argument is retained because callers already supply it.
     */
    public static String buildFallbackLogicalStructureMermaid(String content, String snippetLanguage) {
        String normalizedContent = content != null ? content : "";
        String lowerContent = normalizedContent.toLowerCase(Locale.ROOT);
        boolean assignments = hasAssignments(normalizedContent);
        boolean conditional = hasConditionalFlow(lowerContent);

        List<NodeDefinition> nodes = new ArrayList<>();
        nodes.add(new NodeDefinition("start_1", "Start", NodeType.TERMINAL, "setup"));
        if (assignments) {
            nodes.add(new NodeDefinition("setup_1", "Read configured values", NodeType.ACTION, "setup"));
        }
        nodes.add(new NodeDefinition("work_1", "Run main snippet logic", NodeType.ACTION, "work"));
        if (conditional) {
            nodes.add(new NodeDefinition("decision_1", "Main command succeeds?", NodeType.DECISION, "work"));
            nodes.add(new NodeDefinition("success_1", successAction(lowerContent), NodeType.ACTION, "success"));
            nodes.add(new NodeDefinition("failure_1", failureAction(lowerContent), NodeType.ACTION, "failure"));
        }
        nodes.add(new NodeDefinition("stop_1", "Stop", NodeType.TERMINAL, "setup"));

        StringBuilder builder = new StringBuilder("flowchart TD\n");
        for (NodeDefinition node : nodes) {
            builder.append("    ").append(node.id());
            if (node.type() == NodeType.DECISION) {
                builder.append("{\"").append(escapeLabel(node.label())).append("\"}");
            } else if (node.type() == NodeType.TERMINAL) {
                builder.append("([\"").append(escapeLabel(node.label())).append("\"])");
            } else {
                builder.append("[\"").append(escapeLabel(node.label())).append("\"]");
            }
            builder.append('\n');
        }

        builder.append(assignments ? "    start_1 --> setup_1\n" : "    start_1 --> work_1\n");
        if (assignments) {
            builder.append("    setup_1 --> work_1\n");
        }
        if (conditional) {
            builder.append("    work_1 --> decision_1\n")
                .append("    decision_1 -->|yes| success_1\n")
                .append("    decision_1 -->|no| failure_1\n")
                .append("    success_1 --> stop_1\n")
                .append("    failure_1 --> stop_1\n");
        } else {
            builder.append("    work_1 --> stop_1\n");
        }
        for (NodeDefinition node : nodes) {
            builder.append("    class ").append(node.id()).append(' ').append(node.semanticClass()).append('\n');
        }
        return builder.toString().stripTrailing();
    }

    public static List<CodeReference> buildCodeReferences(String mermaidSource, String content) {
        String normalizedContent = content != null ? content : "";
        if (normalizedContent.isBlank()) {
            return List.of();
        }
        ParsedDiagram diagram = parseRestrictedFlowchart(normalizeMermaid(mermaidSource));
        if (!diagram.validation().valid()) {
            return List.of();
        }
        List<String> lines = List.of(normalizedContent.split("\\R", -1));
        List<CodeReference> references = new ArrayList<>();
        for (NodeDefinition node : diagram.nodes().values()) {
            if (node.type() == NodeType.TERMINAL) {
                continue;
            }
            Optional<LineRange> range = findLineRange(node.label(), node.type(), lines);
            range.ifPresent(lineRange -> references.add(new CodeReference(
                "ref-" + references.size(),
                node.id(),
                node.label(),
                lineRange.startLine(),
                lineRange.endLine(),
                formatExcerpt(lines, lineRange.startLine(), lineRange.endLine()))));
        }
        return List.copyOf(references);
    }

    public static List<CodeReference> buildValidatedCodeReferences(
        String mermaidSource,
        String content,
        List<SourceCodeReference> sourceReferences) {

        String normalizedContent = content != null ? content : "";
        if (normalizedContent.isBlank() || sourceReferences == null || sourceReferences.isEmpty()) {
            return List.of();
        }
        ParsedDiagram diagram = parseRestrictedFlowchart(normalizeMermaid(mermaidSource));
        if (!diagram.validation().valid()) {
            return List.of();
        }
        List<String> lines = List.of(normalizedContent.split("\\R", -1));
        List<CodeReference> references = new ArrayList<>();
        Set<String> usedNodeIds = new LinkedHashSet<>();
        for (SourceCodeReference sourceReference : sourceReferences) {
            if (sourceReference == null || usedNodeIds.contains(sourceReference.nodeId())) {
                continue;
            }
            NodeDefinition node = diagram.nodes().get(sourceReference.nodeId());
            if (node == null || node.type() == NodeType.TERMINAL
                || !normalizeDiagramLabel(node.label()).equals(normalizeDiagramLabel(sourceReference.label()))) {
                continue;
            }
            int startLine = sourceReference.startLine();
            int endLine = Math.max(startLine, sourceReference.endLine());
            if (!isValidCodeReferenceRange(lines, startLine, endLine)) {
                continue;
            }
            usedNodeIds.add(sourceReference.nodeId());
            references.add(new CodeReference(
                "ref-" + references.size(),
                node.id(),
                node.label(),
                startLine,
                endLine,
                formatExcerpt(lines, startLine, endLine)));
        }
        return List.copyOf(references);
    }

    /**
     * Filters an AI mapping to declared non-terminal nodes with exact labels and structurally valid
     * positive line ranges. Bounds against the actual snippet are checked later when references are built.
     */
    static List<SourceCodeReference> filterValidSourceReferences(
        String mermaidSource, List<SourceCodeReference> sourceReferences) {

        if (sourceReferences == null || sourceReferences.isEmpty()) {
            return List.of();
        }
        ParsedDiagram diagram = parseRestrictedFlowchart(normalizeMermaid(mermaidSource));
        if (!diagram.validation().valid()) {
            return List.of();
        }
        List<SourceCodeReference> filtered = new ArrayList<>();
        Set<String> usedNodeIds = new LinkedHashSet<>();
        for (SourceCodeReference reference : sourceReferences) {
            if (reference == null || usedNodeIds.contains(reference.nodeId())) {
                continue;
            }
            NodeDefinition node = diagram.nodes().get(reference.nodeId());
            if (node == null || node.type() == NodeType.TERMINAL
                || !normalizeDiagramLabel(node.label()).equals(normalizeDiagramLabel(reference.label()))
                || reference.startLine() < 1 || reference.endLine() < reference.startLine()) {
                continue;
            }
            usedNodeIds.add(reference.nodeId());
            filtered.add(new SourceCodeReference(
                node.id(), node.label(), reference.startLine(), reference.endLine()));
        }
        return List.copyOf(filtered);
    }

    public static List<CodeReference> buildExpandedCodeReferences(
        String mermaidSource,
        String content,
        List<SourceCodeReference> sourceReferences) {

        List<CodeReference> validated = buildValidatedCodeReferences(mermaidSource, content, sourceReferences);
        List<CodeReference> local = buildCodeReferences(mermaidSource, content);
        if (validated.isEmpty()) {
            return local;
        }
        if (local.isEmpty()) {
            return validated;
        }
        List<CodeReference> merged = new ArrayList<>(validated);
        Set<String> coveredNodeIds = new LinkedHashSet<>();
        validated.forEach(reference -> coveredNodeIds.add(reference.nodeId()));
        for (CodeReference reference : local) {
            if (coveredNodeIds.add(reference.nodeId())) {
                merged.add(reference);
            }
        }
        return withSequentialIds(merged);
    }

    static List<String> extractCodeReferenceLabels(String mermaidSource) {
        ParsedDiagram diagram = parseRestrictedFlowchart(normalizeMermaid(mermaidSource));
        return diagram.validation().valid()
            ? diagram.nodes().values().stream()
                .filter(node -> node.type() != NodeType.TERMINAL)
                .map(NodeDefinition::label)
                .toList()
            : List.of();
    }

    static Map<String, String> extractNodeLabels(String mermaidSource) {
        ParsedDiagram diagram = parseRestrictedFlowchart(normalizeMermaid(mermaidSource));
        if (!diagram.validation().valid()) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        diagram.nodes().forEach((id, node) -> labels.put(id, node.label()));
        return Map.copyOf(labels);
    }

    /** How a diagram viewer picks its light/dark appearance. */
    public enum DiagramColorMode {
        AUTO, LIGHT, DARK;

        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static DiagramColorMode fromKey(String key) {
            if (key != null) {
                for (DiagramColorMode mode : values()) {
                    if (mode.key().equalsIgnoreCase(key.trim())) {
                        return mode;
                    }
                }
            }
            return AUTO;
        }

        public boolean isDarkActive() {
            return this == DARK || (this == AUTO && SystemThemeDetector.isSystemDarkMode());
        }
    }

    public static String normalizeHexColor(String color, String fallback) {
        String value = color != null ? color.trim() : "";
        if (HEX_COLOR_PATTERN.matcher(value).matches()) {
            return value.toUpperCase(Locale.ROOT);
        }
        String fallbackValue = fallback != null ? fallback.trim() : "";
        if (HEX_COLOR_PATTERN.matcher(fallbackValue).matches()) {
            return fallbackValue.toUpperCase(Locale.ROOT);
        }
        return "#FFFFFF";
    }

    private static ParsedDiagram parseRestrictedFlowchart(String source) {
        return parseRestrictedFlowchart(source, null);
    }

    /**
     * @param languageCode the response language, which decides the complement of a lone outcome
     *     label ("no" is English, Spanish and Italian alike); {@code null} prefers English
     */
    private static ParsedDiagram parseRestrictedFlowchart(String source, String languageCode) {
        if (source == null || source.isBlank()) {
            return ParsedDiagram.failure("Mermaid source is empty.");
        }
        String[] lines = source.split("\\R", -1);
        int headerIndex = -1;
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].isBlank()) {
                headerIndex = index;
                break;
            }
        }
        if (headerIndex < 0 || !HEADER_PATTERN.matcher(lines[headerIndex].trim()).matches()) {
            return ParsedDiagram.failure("Snippet diagrams must start with 'flowchart TD'.");
        }

        Map<String, NodeDefinition> nodes = new LinkedHashMap<>();
        Map<String, String> classes = new LinkedHashMap<>();
        List<EdgeDefinition> edges = new ArrayList<>();
        for (int index = headerIndex + 1; index < lines.length; index++) {
            String rawLine = lines[index].trim();
            String line = normalizeShapeShorthand(rawLine);
            // Plain comments render nothing and models write them despite the contract; the
            // %%{...}%% directive form was already refused by the security screen.
            if (line.isBlank() || line.startsWith("%%")) {
                continue;
            }
            Matcher nodeMatcher = NODE_PATTERN.matcher(line);
            if (nodeMatcher.matches()) {
                String id = nodeMatcher.group(1);
                boolean decision = nodeMatcher.group(3) != null;
                boolean terminal = nodeMatcher.group(4) != null;
                String label = unescapeLabel(
                    terminal ? nodeMatcher.group(4) : decision ? nodeMatcher.group(3) : nodeMatcher.group(2));
                if (label.isBlank()) {
                    return ParsedDiagram.failure("Mermaid nodes must have a visible label.");
                }
                NodeType type = terminal ? NodeType.TERMINAL : decision ? NodeType.DECISION : NodeType.ACTION;
                nodes.putIfAbsent(id, new NodeDefinition(id, label, type, ""));
                String inlineClass = nodeMatcher.group(5);
                if (inlineClass != null) {
                    String semanticClass = inlineClass.toLowerCase(Locale.ROOT);
                    if (!SEMANTIC_CLASSES.contains(semanticClass)) {
                        return ParsedDiagram.failure("Unsupported Mermaid semantic class: " + semanticClass);
                    }
                    classes.putIfAbsent(id, semanticClass);
                }
                continue;
            }
            Matcher bareClass = BARE_INLINE_CLASS_PATTERN.matcher(line);
            if (bareClass.matches()) {
                // `start_1:::setup` on its own line is a class assignment, nothing more.
                String semanticClass = bareClass.group(2).toLowerCase(Locale.ROOT);
                if (SEMANTIC_CLASSES.contains(semanticClass)) {
                    classes.putIfAbsent(bareClass.group(1), semanticClass);
                }
                continue;
            }
            EdgeStatement statement = parseEdgeStatement(line);
            if (statement != null) {
                for (NodeDefinition declared : statement.declaredNodes()) {
                    // A second declaration of the same id — the model re-labelling a node inline —
                    // does not change the flow; the first declaration stands.
                    nodes.putIfAbsent(declared.id(), declared);
                }
                for (Map.Entry<String, String> inlineClass : statement.inlineClasses().entrySet()) {
                    if (!SEMANTIC_CLASSES.contains(inlineClass.getValue())) {
                        return ParsedDiagram.failure("Unsupported Mermaid semantic class: " + inlineClass.getValue());
                    }
                    classes.putIfAbsent(inlineClass.getKey(), inlineClass.getValue());
                }
                for (EdgeDefinition edge : statement.edges()) {
                    // A repeated identical edge is a model stutter, not a second path.
                    if (!edges.contains(edge)) {
                        edges.add(edge);
                    }
                }
                if (edges.size() > MAX_MERMAID_EDGES) {
                    return ParsedDiagram.failure("Mermaid diagram exceeds the 300-edge limit.");
                }
                continue;
            }
            Matcher classMatcher = CLASS_PATTERN.matcher(line);
            if (classMatcher.matches()) {
                String semanticClass = classMatcher.group(2).toLowerCase(Locale.ROOT);
                if (!SEMANTIC_CLASSES.contains(semanticClass)) {
                    return ParsedDiagram.failure("Unsupported Mermaid semantic class: " + semanticClass);
                }
                for (String id : classMatcher.group(1).split("\\s*,\\s*")) {
                    // A second class for the same node is a color slip, not a broken diagram: the
                    // first assignment stands.
                    classes.putIfAbsent(id, semanticClass);
                }
                continue;
            }
            // A class statement the grammar cannot read only ever concerned colors; the nodes it
            // meant keep the neutral default instead of costing the diagram.
            if (line.startsWith("class ")) {
                continue;
            }
            // The line is quoted because "line 16" alone told nobody what the model had written.
            return ParsedDiagram.failure("Unsupported Mermaid syntax on line " + (index + 1) + ": "
                + excerpt(rawLine) + ".");
        }
        if (nodes.isEmpty()) {
            return ParsedDiagram.failure("Mermaid flowchart must contain at least one node.");
        }
        // Mermaid creates a node for an id that only ever appears in an edge, labelled with the
        // id; so does this parser, rather than losing the diagram to one undeclared reference.
        for (EdgeDefinition edge : List.copyOf(edges)) {
            for (String id : List.of(edge.from(), edge.to())) {
                nodes.putIfAbsent(id, new NodeDefinition(id, id, NodeType.ACTION, ""));
            }
        }
        // A model that ends its flow in its own terminal ("Report complete") and leaves stop_1
        // dangling still drew the right flow: the stray terminal is an action, and every dead end
        // continues to stop_1 — which is what a reader assumes of an ending anyway. Conversely the
        // stable ids are terminals by contract, whatever shape — or none — the model gave them.
        for (Map.Entry<String, NodeDefinition> entry : nodes.entrySet()) {
            NodeDefinition node = entry.getValue();
            boolean stableId = "start_1".equals(node.id()) || "stop_1".equals(node.id());
            if (node.type() == NodeType.TERMINAL && !stableId) {
                entry.setValue(new NodeDefinition(node.id(), node.label(), NodeType.ACTION, node.semanticClass()));
            } else if (stableId && node.type() != NodeType.TERMINAL) {
                String label = node.label().equals(node.id()) ? ("start_1".equals(node.id()) ? "Start" : "Stop") : node.label();
                entry.setValue(new NodeDefinition(node.id(), label, NodeType.TERMINAL, node.semanticClass()));
            }
        }
        // A flow without the stable ids still has an entry and exits. When exactly one node has
        // no incoming edge, start_1 leads to it; when nodes dead-end, stop_1 collects them.
        if (!nodes.containsKey("start_1")) {
            Set<String> withIncoming = new LinkedHashSet<>();
            edges.forEach(edge -> withIncoming.add(edge.to()));
            List<String> entries = nodes.keySet().stream().filter(id -> !withIncoming.contains(id)).toList();
            if (entries.size() == 1) {
                Map<String, NodeDefinition> reordered = new LinkedHashMap<>();
                reordered.put("start_1", new NodeDefinition("start_1", "Start", NodeType.TERMINAL, ""));
                reordered.putAll(nodes);
                nodes.clear();
                nodes.putAll(reordered);
                edges.add(0, new EdgeDefinition("start_1", "", entries.get(0)));
            }
        }
        if (!nodes.containsKey("stop_1")) {
            Set<String> withOutgoing = new LinkedHashSet<>();
            edges.forEach(edge -> withOutgoing.add(edge.from()));
            List<String> exits = nodes.values().stream()
                .filter(node -> node.type() != NodeType.DECISION && !withOutgoing.contains(node.id()))
                .map(NodeDefinition::id).toList();
            if (!exits.isEmpty()) {
                nodes.put("stop_1", new NodeDefinition("stop_1", "Stop", NodeType.TERMINAL, ""));
                exits.forEach(id -> edges.add(new EdgeDefinition(id, "", "stop_1")));
            }
        }
        if (isTerminalNode(nodes.get("stop_1"))) {
            Set<String> withOutgoing = new LinkedHashSet<>();
            edges.forEach(edge -> withOutgoing.add(edge.from()));
            for (NodeDefinition node : nodes.values()) {
                if (!"stop_1".equals(node.id()) && node.type() != NodeType.DECISION && !withOutgoing.contains(node.id())) {
                    edges.add(new EdgeDefinition(node.id(), "", "stop_1"));
                }
            }
        }
        // Parallel branches out of one action cannot be drawn in this dialect. Rather than lose the
        // diagram, the branch that reaches the most of it stands for the path and the others go;
        // whatever only they reached is pruned below, and the caller logs the difference.
        Map<String, List<String>> forward = new LinkedHashMap<>();
        edges.forEach(edge -> forward.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge.to()));
        Set<EdgeDefinition> keptFanOut = new LinkedHashSet<>();
        for (NodeDefinition node : nodes.values()) {
            List<EdgeDefinition> outgoing = edges.stream().filter(edge -> edge.from().equals(node.id())).toList();
            if (node.type() == NodeType.DECISION || outgoing.size() < 2) {
                continue;
            }
            EdgeDefinition best = outgoing.get(0);
            int bestReach = -1;
            for (EdgeDefinition candidate : outgoing) {
                int reach = reachableNodes(candidate.to(), forward).size();
                if (reach > bestReach) {
                    best = candidate;
                    bestReach = reach;
                }
            }
            keptFanOut.add(best);
            EdgeDefinition chosen = best;
            edges.removeIf(edge -> edge.from().equals(node.id()) && !edge.equals(chosen));
        }
        // A node nothing leads to — a mistyped id that got its own declaration, a stray phase —
        // cannot be placed in the flow; it is dropped with its outgoing edges rather than costing
        // the whole diagram. start_1 is the flow's source and is never pruned.
        boolean pruned = true;
        while (pruned) {
            pruned = false;
            Set<String> withIncoming = new LinkedHashSet<>();
            edges.forEach(edge -> withIncoming.add(edge.to()));
            for (String id : List.copyOf(nodes.keySet())) {
                if (!"start_1".equals(id) && !withIncoming.contains(id)) {
                    nodes.remove(id);
                    edges.removeIf(edge -> edge.from().equals(id));
                    pruned = true;
                }
            }
        }
        // A diamond with a single exit is an action drawn in the wrong shape, not a decision.
        for (Map.Entry<String, NodeDefinition> entry : nodes.entrySet()) {
            NodeDefinition node = entry.getValue();
            if (node.type() != NodeType.DECISION) {
                continue;
            }
            List<EdgeDefinition> outgoing = edges.stream().filter(edge -> edge.from().equals(node.id())).toList();
            if (outgoing.size() == 1) {
                entry.setValue(new NodeDefinition(node.id(), node.label(), NodeType.ACTION, node.semanticClass()));
                EdgeDefinition only = outgoing.get(0);
                edges.set(edges.indexOf(only), new EdgeDefinition(only.from(), "", only.to()));
            }
        }
        inferMissingOutcomeLabels(nodes, edges, languageCode);
        rewriteNonBinaryDecisions(nodes, edges, languageCode);
        // A label on an action's edge ("done", "next") names nothing the dialect draws; dropped.
        for (int index = 0; index < edges.size(); index++) {
            EdgeDefinition edge = edges.get(index);
            NodeDefinition origin = nodes.get(edge.from());
            if (!edge.label().isBlank() && origin != null && origin.type() != NodeType.DECISION) {
                edges.set(index, new EdgeDefinition(edge.from(), "", edge.to()));
            }
        }
        if (!isTerminalNode(nodes.get("start_1")) || !isTerminalNode(nodes.get("stop_1"))) {
            return ParsedDiagram.failure(
                "Snippet flowcharts must declare stable start_1 and stop_1 terminal nodes.");
        }
        for (EdgeDefinition edge : edges) {
            if (!nodes.containsKey(edge.from()) || !nodes.containsKey(edge.to())) {
                return ParsedDiagram.failure("Mermaid edges must reference declared node ids.");
            }
        }
        if (edges.stream().noneMatch(edge -> "start_1".equals(edge.from()))
            || edges.stream().noneMatch(edge -> "stop_1".equals(edge.to()))) {
            return ParsedDiagram.failure("Snippet flowcharts must connect start_1 and stop_1.");
        }
        // The semantic class only colors a node. A class assigned to an id that was never declared
        // and a node the model forgot to class are both model slips that leave the structure
        // intact, so the first is ignored and the second gets the neutral default — a correct
        // diagram used to be discarded for either.
        Map<String, NodeDefinition> classifiedNodes = new LinkedHashMap<>();
        nodes.forEach((id, node) -> classifiedNodes.put(
            id, new NodeDefinition(id, node.label(), node.type(),
                classes.getOrDefault(id, node.type() == NodeType.TERMINAL ? "setup" : "work"))));
        return new ParsedDiagram(
            MermaidValidation.success(),
            Collections.unmodifiableMap(new LinkedHashMap<>(classifiedNodes)),
            List.copyOf(edges));
    }

    /**
     * A decision drawn with one labelled branch and one bare one means the bare branch is the
     * other outcome — {@code check -- no --> fallback} beside {@code check --> continue} — so the
     * bare edge receives the complementary label of the same language pair.
     */
    private static void inferMissingOutcomeLabels(
        Map<String, NodeDefinition> nodes, List<EdgeDefinition> edges, String languageCode) {
        for (NodeDefinition node : nodes.values()) {
            if (node.type() != NodeType.DECISION) {
                continue;
            }
            List<Integer> outgoing = new ArrayList<>();
            for (int index = 0; index < edges.size(); index++) {
                if (edges.get(index).from().equals(node.id())) {
                    outgoing.add(index);
                }
            }
            if (outgoing.size() != 2) {
                continue;
            }
            EdgeDefinition first = edges.get(outgoing.get(0));
            EdgeDefinition second = edges.get(outgoing.get(1));
            if (first.label().isBlank() == second.label().isBlank()) {
                continue;
            }
            int bareIndex = first.label().isBlank() ? outgoing.get(0) : outgoing.get(1);
            String complement = complementaryOutcome(
                first.label().isBlank() ? second.label() : first.label(), languageCode);
            if (complement != null) {
                EdgeDefinition bare = edges.get(bareIndex);
                edges.set(bareIndex, new EdgeDefinition(bare.from(), complement, bare.to()));
            }
        }
    }

    /**
     * Brings every decision to the two localized outcomes the dialect draws. A pair written in
     * another language is translated in place ({@code yes/no} under a German interface becomes
     * {@code ja/nein}). Branches that are not outcomes at all — {@code |upgrade|}, {@code |revoke|},
     * {@code |other|} — become a chain of binary decisions, each asking for one branch, which is
     * exactly what the contract tells the model to draw for a multi-way branch.
     */
    private static void rewriteNonBinaryDecisions(
        Map<String, NodeDefinition> nodes, List<EdgeDefinition> edges, String languageCode) {

        List<String> target = ORDERED_OUTCOME_LABELS.getOrDefault(
            normalizeLanguageCode(languageCode), ORDERED_OUTCOME_LABELS.get("en"));
        Map<String, NodeDefinition> rewritten = new LinkedHashMap<>();
        for (NodeDefinition node : List.copyOf(nodes.values())) {
            rewritten.put(node.id(), node);
            if (node.type() != NodeType.DECISION) {
                continue;
            }
            List<EdgeDefinition> outgoing = edges.stream().filter(edge -> edge.from().equals(node.id())).toList();
            if (outgoing.size() < 2) {
                continue;
            }
            List<String> labels = outgoing.stream()
                .map(edge -> normalizeDiagramLabel(edge.label()).toLowerCase(Locale.ROOT)).toList();
            if (outgoing.size() == 2 && new LinkedHashSet<>(labels).equals(new LinkedHashSet<>(target))) {
                continue;
            }
            if (outgoing.size() == 2 && labels.get(0).isBlank() && labels.get(1).isBlank()) {
                // Two bare branches: the first drawn is the affirmative one, as models order them.
                for (int index = 0; index < 2; index++) {
                    EdgeDefinition edge = outgoing.get(index);
                    edges.set(edges.indexOf(edge), new EdgeDefinition(edge.from(), target.get(index), edge.to()));
                }
                continue;
            }
            List<String> foreignPair = outgoing.size() == 2 ? knownPairContaining(labels) : null;
            if (foreignPair != null) {
                for (EdgeDefinition edge : outgoing) {
                    String translated = target.get(foreignPair.indexOf(
                        normalizeDiagramLabel(edge.label()).toLowerCase(Locale.ROOT)));
                    edges.set(edges.indexOf(edge), new EdgeDefinition(edge.from(), translated, edge.to()));
                }
                continue;
            }
            // A chain of binary questions, one per branch, in the order the model listed them.
            edges.removeAll(outgoing);
            String question = node.label().endsWith("?")
                ? node.label().substring(0, node.label().length() - 1).trim() : node.label();
            String currentId = node.id();
            for (int index = 0; index < outgoing.size(); index++) {
                EdgeDefinition branch = outgoing.get(index);
                String branchName = branch.label().isBlank() ? "#" + (index + 1) : branch.label();
                rewritten.put(currentId, new NodeDefinition(
                    currentId, question + " — " + branchName + "?", NodeType.DECISION, node.semanticClass()));
                edges.add(new EdgeDefinition(currentId, target.get(0), branch.to()));
                if (index == outgoing.size() - 2) {
                    edges.add(new EdgeDefinition(currentId, target.get(1), outgoing.get(index + 1).to()));
                    break;
                }
                String nextId = node.id() + "_" + (index + 2);
                edges.add(new EdgeDefinition(currentId, target.get(1), nextId));
                currentId = nextId;
            }
        }
        nodes.clear();
        nodes.putAll(rewritten);
    }

    private static List<String> knownPairContaining(List<String> labels) {
        for (List<String> pair : ORDERED_OUTCOME_LABELS.values()) {
            if (pair.containsAll(labels) && new LinkedHashSet<>(labels).size() == 2) {
                return pair;
            }
        }
        return null;
    }

    private static String complementaryOutcome(String label, String languageCode) {
        String known = normalizeDiagramLabel(label).toLowerCase(Locale.ROOT);
        List<Set<String>> candidates = new ArrayList<>();
        Set<String> preferred = DECISION_OUTCOME_LABELS.get(normalizeLanguageCode(languageCode));
        if (preferred != null) {
            candidates.add(preferred);
        }
        candidates.add(DECISION_OUTCOME_LABELS.get("en"));
        candidates.addAll(DECISION_OUTCOME_LABELS.values());
        for (Set<String> pair : candidates) {
            if (pair.contains(known)) {
                for (String other : pair) {
                    if (!other.equals(known)) {
                        return other;
                    }
                }
            }
        }
        return null;
    }

    /** What one edge statement contributes: nodes declared inline, their classes, and the edges. */
    private record EdgeStatement(
        List<NodeDefinition> declaredNodes,
        Map<String, String> inlineClasses,
        List<EdgeDefinition> edges) {
    }

    /**
     * Parses an edge statement in the forms Mermaid allows and models actually write: bare ids or
     * inline declarations at either end, a chain {@code a --> b --> c}, and a label as
     * {@code -->|yes|} or {@code -- yes -->}. Each form is plain shorthand for the separate
     * statements the grammar always accepted, so nothing here widens what a diagram may express.
     * Returns {@code null} when the line is not an edge statement at all.
     */
    private static EdgeStatement parseEdgeStatement(String line) {
        String value = line.endsWith(";") ? line.substring(0, line.length() - 1).trim() : line;
        List<NodeDefinition> declaredNodes = new ArrayList<>();
        Map<String, String> inlineClasses = new LinkedHashMap<>();
        List<EdgeDefinition> edges = new ArrayList<>();
        Matcher endpoint = EDGE_ENDPOINT_PATTERN.matcher(value);
        Matcher operator = EDGE_OPERATOR_PATTERN.matcher(value);
        if (!endpoint.lookingAt()) {
            return null;
        }
        String from = registerEndpoint(endpoint, declaredNodes, inlineClasses);
        int position = endpoint.end();
        while (position < value.length()) {
            operator.region(position, value.length());
            if (!operator.lookingAt()) {
                return null;
            }
            String label = operator.group(1) != null ? operator.group(1).trim()
                : operator.group(2) != null ? operator.group(2).trim() : "";
            endpoint.region(operator.end(), value.length());
            if (!endpoint.lookingAt()) {
                return null;
            }
            String to = registerEndpoint(endpoint, declaredNodes, inlineClasses);
            edges.add(new EdgeDefinition(from, label, to));
            from = to;
            position = endpoint.end();
        }
        return edges.isEmpty() ? null : new EdgeStatement(declaredNodes, inlineClasses, edges);
    }

    private static String registerEndpoint(
        Matcher endpoint, List<NodeDefinition> declaredNodes, Map<String, String> inlineClasses) {

        String id = endpoint.group(1);
        boolean action = endpoint.group(2) != null;
        boolean decision = endpoint.group(3) != null;
        boolean terminal = endpoint.group(4) != null;
        if (action || decision || terminal) {
            String label = unescapeLabel(action ? endpoint.group(2) : decision ? endpoint.group(3) : endpoint.group(4));
            NodeType type = terminal ? NodeType.TERMINAL : decision ? NodeType.DECISION : NodeType.ACTION;
            declaredNodes.add(new NodeDefinition(id, label, type, ""));
        }
        if (endpoint.group(5) != null) {
            inlineClasses.put(id, endpoint.group(5).toLowerCase(Locale.ROOT));
        }
        return id;
    }

    /**
     * Rewrites the label shorthand models use into the quoted shapes the grammar reads:
     * {@code id[Text]}, {@code id{Text?}}, {@code id([Text])} and the rounded {@code id(Text)}
     * (an action), plus the {@code (["Text")]} bracket slip. Unquoted forms are only touched on a
     * line that carries no quote at all, so text inside a quoted label is never rewritten.
     */
    static String normalizeShapeShorthand(String line) {
        String value = DOUBLED_LABEL_QUOTES.matcher(line).replaceAll("$1\"$2\"$3");
        value = MISPLACED_STADIUM_CLOSE.matcher(value).replaceAll("([\"$1\"])");
        value = MISPLACED_OUTCOME_LABEL.matcher(value).replaceAll("-->|$1| ");
        value = MISCLOSED_LABEL_PIPE.matcher(value).replaceAll("-->|$1| ");
        value = DOUBLED_ARROW_AFTER_LABEL.matcher(value).replaceAll("$1 ");
        Matcher unclosed = UNCLOSED_SHAPE_BEFORE_ARROW.matcher(value);
        StringBuilder closed = new StringBuilder();
        while (unclosed.find()) {
            String opener = unclosed.group(1);
            String closer = opener.equals("([") ? "\"])" : opener.equals("[") ? "\"]" : "\"}";
            unclosed.appendReplacement(closed, Matcher.quoteReplacement(
                opener + unclosed.group(2).substring(0, unclosed.group(2).length() - 1) + closer + " "));
        }
        unclosed.appendTail(closed);
        value = closed.toString();
        value = QUOTED_EXOTIC_SHAPE.matcher(value).replaceAll("[$1]");
        StringBuilder result = new StringBuilder(value.length() + 8);
        StringBuilder outside = new StringBuilder();
        boolean inString = false;
        boolean escaped = false;
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (inString) {
                result.append(character);
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
            } else if (character == '"') {
                result.append(quoteUnquotedShapes(outside));
                outside.setLength(0);
                result.append(character);
                inString = true;
            } else {
                outside.append(character);
            }
        }
        return result.append(quoteUnquotedShapes(outside)).toString();
    }

    private static String quoteUnquotedShapes(CharSequence segment) {
        String value = UNQUOTED_EXOTIC_SHAPE.matcher(segment).replaceAll("[\"$1\"]");
        value = UNQUOTED_STADIUM.matcher(value).replaceAll("([\"$1\"])");
        value = UNQUOTED_ROUND.matcher(value).replaceAll("[\"$1\"]");
        value = UNQUOTED_RECT.matcher(value).replaceAll("[\"$1\"]");
        return UNQUOTED_DECISION.matcher(value).replaceAll("{\"$1\"}");
    }

    private static String excerpt(String line) {
        String value = line.replaceAll("\\s+", " ").trim();
        return value.length() > UNSUPPORTED_LINE_EXCERPT_CHARS
            ? "'" + value.substring(0, UNSUPPORTED_LINE_EXCERPT_CHARS) + "…'"
            : "'" + value + "'";
    }

    private static String validateFlowTopology(
        Map<String, NodeDefinition> nodes,
        List<EdgeDefinition> edges) {

        Map<String, List<EdgeDefinition>> outgoing = new LinkedHashMap<>();
        Map<String, List<EdgeDefinition>> incoming = new LinkedHashMap<>();
        Map<String, List<String>> forward = new LinkedHashMap<>();
        Map<String, List<String>> reverse = new LinkedHashMap<>();
        for (String nodeId : nodes.keySet()) {
            outgoing.put(nodeId, new ArrayList<>());
            incoming.put(nodeId, new ArrayList<>());
            forward.put(nodeId, new ArrayList<>());
            reverse.put(nodeId, new ArrayList<>());
        }

        Set<String> uniqueEdges = new LinkedHashSet<>();
        for (EdgeDefinition edge : edges) {
            if (edge.from().equals(edge.to())) {
                return "Mermaid flowcharts must not contain self-edges.";
            }
            String edgeKey = edge.from() + "\u0000" + edge.label().toLowerCase(Locale.ROOT)
                + "\u0000" + edge.to();
            if (!uniqueEdges.add(edgeKey)) {
                return "Mermaid flowcharts must not contain duplicate edges.";
            }
            outgoing.get(edge.from()).add(edge);
            incoming.get(edge.to()).add(edge);
            forward.get(edge.from()).add(edge.to());
            reverse.get(edge.to()).add(edge.from());
        }

        if (!incoming.get("start_1").isEmpty()) {
            return "Snippet flowchart start_1 must not have an incoming edge.";
        }
        if (outgoing.get("start_1").size() != 1) {
            return "Snippet flowchart start_1 must have exactly one outgoing edge.";
        }
        if (!outgoing.get("stop_1").isEmpty()) {
            return "Snippet flowchart stop_1 must not have an outgoing edge.";
        }
        if (incoming.get("stop_1").isEmpty()) {
            return "Snippet flowchart stop_1 must have at least one incoming edge.";
        }

        for (NodeDefinition node : nodes.values()) {
            if (node.type() == NodeType.TERMINAL
                && !"start_1".equals(node.id()) && !"stop_1".equals(node.id())) {
                return "Snippet flowcharts may only use start_1 and stop_1 as terminal nodes.";
            }
            if (node.type() == NodeType.DECISION) {
                List<EdgeDefinition> decisionEdges = outgoing.get(node.id());
                Set<String> labels = new LinkedHashSet<>();
                Set<String> targets = new LinkedHashSet<>();
                for (EdgeDefinition edge : decisionEdges) {
                    labels.add(edge.label().toLowerCase(Locale.ROOT));
                    targets.add(edge.to());
                }
                labels.remove("");
                // Both outcomes may lead to the same node — a decision whose branches converge
                // at once is pointless, not broken, and models draw it for "either way, go on".
                if (decisionEdges.size() != 2 || labels.size() != 2) {
                    return "Every Mermaid decision must have two distinctly labeled outgoing paths.";
                }
            } else if (!"stop_1".equals(node.id())) {
                if (outgoing.get(node.id()).size() != 1) {
                    return "Every non-decision Mermaid node except stop_1 must have exactly one outgoing edge.";
                }
            }
        }

        Set<String> reachableFromStart = reachableNodes("start_1", forward);
        if (!reachableFromStart.containsAll(nodes.keySet())) {
            return "Every Mermaid node must be reachable from start_1.";
        }
        Set<String> leadingToStop = reachableNodes("stop_1", reverse);
        if (!leadingToStop.containsAll(nodes.keySet())) {
            return "Every Mermaid node must have a path to stop_1.";
        }
        return null;
    }

    private static Set<String> reachableNodes(String startId, Map<String, List<String>> adjacency) {
        Set<String> visited = new LinkedHashSet<>();
        ArrayDeque<String> pending = new ArrayDeque<>();
        pending.add(startId);
        while (!pending.isEmpty()) {
            String current = pending.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            for (String next : adjacency.getOrDefault(current, List.of())) {
                if (!visited.contains(next)) {
                    pending.addLast(next);
                }
            }
        }
        return visited;
    }

    private static boolean hasAssignments(String content) {
        return content != null && content.lines()
            .anyMatch(line -> line.matches("\\s*(?:[A-Za-z_][A-Za-z0-9_]*|\\$[A-Za-z_][A-Za-z0-9_]*)\\s*=.*"));
    }

    private static boolean hasConditionalFlow(String lowerContent) {
        return lowerContent != null && CONDITIONAL_FLOW_PATTERN.matcher(lowerContent).find();
    }

    private static String successAction(String lowerContent) {
        return lowerContent != null && lowerContent.contains("mail")
            ? "Send success notification"
            : "Handle success path";
    }

    private static String failureAction(String lowerContent) {
        return lowerContent != null && lowerContent.contains("mail")
            ? "Send failure notification"
            : "Handle failure path";
    }

    private static String escapeLabel(String label) {
        String value = label != null ? label : "Run step";
        value = value.replace('\n', ' ').replace('\r', ' ').trim();
        return value.replace("&", "&amp;").replace("\"", "&quot;");
    }

    private static String unescapeLabel(String label) {
        String value = label != null ? label : "";
        return value.replace("&quot;", "\"").replace("&amp;", "&").replace("\\\"", "\"").trim();
    }

    private static String normalizeDiagramLabel(String label) {
        String value = label != null ? label : "";
        return value.replace("\\n", " ").replaceAll("\\s+", " ").trim();
    }

    private static Optional<LineRange> findLineRange(String label, NodeType type, List<String> lines) {
        Optional<LineRange> specialized = findSpecializedLineRange(label, type, lines);
        return specialized.isPresent() ? specialized : findTokenLineRange(label, lines);
    }

    private static Optional<LineRange> findSpecializedLineRange(String label, NodeType type, List<String> lines) {
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        if (type == NodeType.DECISION || containsAny(lowerLabel, "succeed", "condition", "test?", "found?", "csv?")) {
            return uniqueLineRange(lines, SnippetDiagramSupport::isConditionalLine);
        }
        if (containsAny(lowerLabel, "configured value", "configuration", "config", "default")) {
            return firstAssignmentRange(lines);
        }
        if (containsAny(lowerLabel, "command-line", "command line", "option", "argument")) {
            return uniqueLineRange(lines, SnippetDiagramSupport::isOptionParsingLine);
        }
        if (containsAny(lowerLabel, "scan", "directory", "directories", "list files", "find files")) {
            return uniqueLineRange(lines, SnippetDiagramSupport::isDirectoryScanLine);
        }
        if (containsAny(lowerLabel, "success", "ok", "complete")) {
            return uniqueLineRange(lines,
                line -> !isConditionalLine(line)
                    && containsAny(line, "success", "succeeded", "ok", "complete"));
        }
        if (containsAny(lowerLabel, "failure", "failed", "error", "no-result", "no result")) {
            return uniqueLineRange(lines,
                line -> !isConditionalLine(line)
                    && containsAny(line, "failure", "failed", "error", "no-result", "no result"));
        }
        if (containsAny(lowerLabel, "main snippet logic", "main logic")) {
            return firstExecutableRange(lines);
        }
        return Optional.empty();
    }

    private static Optional<LineRange> firstAssignmentRange(List<String> lines) {
        int first = -1;
        int last = -1;
        for (int index = 0; index < lines.size(); index++) {
            if (isAssignmentLine(lines.get(index))) {
                if (first < 0) {
                    first = index + 1;
                }
                last = index + 1;
            } else if (first >= 0 && !lines.get(index).isBlank()) {
                break;
            }
        }
        return first > 0 ? Optional.of(new LineRange(first, last)) : Optional.empty();
    }

    private static Optional<LineRange> firstExecutableRange(List<String> lines) {
        for (int index = 0; index < lines.size(); index++) {
            if (isExecutableLine(lines.get(index))) {
                return Optional.of(new LineRange(index + 1, index + 1));
            }
        }
        return Optional.empty();
    }

    private static Optional<LineRange> uniqueLineRange(
        List<String> lines, java.util.function.Predicate<String> matcher) {

        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (!line.isBlank() && matcher.test(line)) {
                matches.add(index + 1);
            }
        }
        return matches.size() == 1
            ? Optional.of(new LineRange(matches.getFirst(), matches.getFirst()))
            : Optional.empty();
    }

    private static Optional<LineRange> findTokenLineRange(String label, List<String> lines) {
        Set<String> labelTokens = meaningfulTokens(label);
        if (labelTokens.isEmpty()) {
            return Optional.empty();
        }
        int requiredScore = Math.min(2, labelTokens.size());
        List<LineScore> scores = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            int score = tokenMatchScore(labelTokens, meaningfulTokens(line));
            if (score >= requiredScore) {
                scores.add(new LineScore(index + 1, score));
            }
        }
        if (scores.isEmpty()) {
            return Optional.empty();
        }
        scores.sort(Comparator.comparingInt(LineScore::score).reversed());
        LineScore best = scores.getFirst();
        if (scores.size() > 1 && scores.get(1).score() == best.score()) {
            return Optional.empty();
        }
        return Optional.of(new LineRange(best.line(), best.line()));
    }

    private static int tokenMatchScore(Set<String> labelTokens, Set<String> lineTokens) {
        int score = 0;
        for (String token : labelTokens) {
            if (lineTokens.stream().anyMatch(lineToken -> tokenVariants(token).contains(lineToken))) {
                score++;
            }
        }
        return score;
    }

    private static Set<String> meaningfulTokens(String value) {
        String normalized = value != null ? value : "";
        normalized = normalized.replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(Locale.ROOT);
        String[] rawTokens = normalized.split("[^a-z0-9]+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String rawToken : rawTokens) {
            String token = normalizeToken(rawToken);
            if (token.length() >= 2 && !STOP_WORDS.contains(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalizeToken(String token) {
        if (token == null || token.isBlank()) {
            return "";
        }
        String value = token.toLowerCase(Locale.ROOT);
        if (value.startsWith("config")) return "config";
        if (value.startsWith("arg")) return "argument";
        if (value.startsWith("dir")) return "directory";
        if (value.startsWith("file")) return "file";
        if (value.startsWith("notif")) return "notification";
        if (value.startsWith("succ")) return "success";
        if (value.startsWith("fail")) return "failure";
        if (value.endsWith("ies") && value.length() > 4) return value.substring(0, value.length() - 3) + "y";
        if (value.endsWith("s") && value.length() > 3) return value.substring(0, value.length() - 1);
        return value;
    }

    private static Set<String> tokenVariants(String token) {
        return switch (token) {
            case "scan" -> Set.of("scan", "find", "list", "read", "glob", "readdir", "opendir");
            case "directory" -> Set.of("directory", "dir", "path", "folder");
            case "option" -> Set.of("option", "opts", "getopt", "argv", "argument", "arg");
            case "print" -> Set.of("print", "printf", "echo", "write");
            default -> Set.of(token);
        };
    }

    private static boolean isAssignmentLine(String line) {
        return line != null && line.matches("\\s*(?:[A-Za-z_][A-Za-z0-9_]*|\\$[A-Za-z_][A-Za-z0-9_]*)\\s*=.*");
    }

    private static boolean isConditionalLine(String line) {
        String trimmed = line != null ? line.trim().toLowerCase(Locale.ROOT) : "";
        return trimmed.startsWith("if ") || trimmed.startsWith("if[") || trimmed.startsWith("if(")
            || trimmed.startsWith("case ") || trimmed.startsWith("when ");
    }

    private static boolean isOptionParsingLine(String line) {
        return containsAny(
            line, "getopt", "getopts", "@argv", "argv", "$1", "$2", "--", "-h", "-v", "option", "opts");
    }

    private static boolean isDirectoryScanLine(String line) {
        return containsAny(line, "find ", "find(", "ls ", "glob", "readdir", "opendir", "scandir", "listfiles");
    }

    private static boolean isExecutableLine(String line) {
        String trimmed = line != null ? line.trim() : "";
        if (trimmed.isBlank() || trimmed.startsWith("#") || trimmed.startsWith("//") || trimmed.startsWith("/*")) {
            return false;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        return !isAssignmentLine(trimmed)
            && !lower.equals("then")
            && !lower.equals("do")
            && !lower.equals("done")
            && !lower.equals("fi")
            && !lower.equals("esac")
            && !lower.equals("end")
            && !lower.equals("}")
            && !lower.startsWith("if ")
            && !lower.startsWith("else")
            && !lower.startsWith("case ");
    }

    private static boolean isValidCodeReferenceRange(List<String> lines, int startLine, int endLine) {
        if (lines == null || lines.isEmpty() || startLine < 1 || endLine < startLine || endLine > lines.size()) {
            return false;
        }
        for (int line = startLine; line <= endLine; line++) {
            if (!lines.get(line - 1).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String formatExcerpt(List<String> lines, int startLine, int endLine) {
        int safeStart = Math.max(1, Math.min(startLine, lines.size()));
        int safeEnd = Math.max(safeStart, Math.min(endLine, lines.size()));
        int displayedEnd = Math.min(safeEnd, safeStart + 7);
        int width = String.valueOf(displayedEnd).length();
        StringBuilder excerpt = new StringBuilder();
        for (int line = safeStart; line <= displayedEnd; line++) {
            if (!excerpt.isEmpty()) {
                excerpt.append('\n');
            }
            excerpt.append(String.format(Locale.ROOT, "%" + width + "d | %s", line, lines.get(line - 1)));
        }
        if (displayedEnd < safeEnd) {
            excerpt.append("\n...");
        }
        return excerpt.toString();
    }

    private static boolean containsAny(String value, String... needles) {
        String lower = value != null ? value.toLowerCase(Locale.ROOT) : "";
        for (String needle : needles) {
            if (lower.contains(needle)) return true;
        }
        return false;
    }

    private static List<CodeReference> withSequentialIds(List<CodeReference> references) {
        List<CodeReference> result = new ArrayList<>();
        for (CodeReference reference : references) {
            result.add(new CodeReference(
                "ref-" + result.size(), reference.nodeId(), reference.label(),
                reference.startLine(), reference.endLine(), reference.excerpt()));
        }
        return List.copyOf(result);
    }

    private static boolean isTerminalNode(NodeDefinition node) {
        return node != null && node.type() == NodeType.TERMINAL;
    }

    private enum NodeType { ACTION, DECISION, TERMINAL }

    private record NodeDefinition(String id, String label, NodeType type, String semanticClass) {
    }

    private record EdgeDefinition(String from, String label, String to) {
    }

    private record ParsedDiagram(
        MermaidValidation validation,
        Map<String, NodeDefinition> nodes,
        List<EdgeDefinition> edges) {

        private static ParsedDiagram failure(String message) {
            return new ParsedDiagram(MermaidValidation.failure(message), Map.of(), List.of());
        }
    }

    private record LineRange(int startLine, int endLine) {
    }

    private record LineScore(int line, int score) {
    }
}
