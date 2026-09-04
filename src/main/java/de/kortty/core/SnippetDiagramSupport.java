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
    private static final Pattern HEADER_PATTERN = Pattern.compile("(?i)^flowchart\\s+TD\\s*;?$");
    private static final Pattern NODE_PATTERN = Pattern.compile(
        "^([A-Za-z][A-Za-z0-9_-]{0,63})\\s*(?:\\[\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*]|\\{\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*}|\\(\\[\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*]\\))\\s*;?$");
    private static final Pattern EDGE_PATTERN = Pattern.compile(
        "^([A-Za-z][A-Za-z0-9_-]{0,63})\\s*-->\\s*(?:\\|\\s*\"?([^|\"]*)\"?\\s*\\|\\s*)?([A-Za-z][A-Za-z0-9_-]{0,63})\\s*;?$");
    private static final Pattern CLASS_PATTERN = Pattern.compile(
        "(?i)^class\\s+([A-Za-z][A-Za-z0-9_-]{0,63}(?:\\s*,\\s*[A-Za-z][A-Za-z0-9_-]{0,63})*)\\s+(setup|work|success|failure)\\s*;?$");
    private static final Pattern CONDITIONAL_FLOW_PATTERN = Pattern.compile(
        "(?im)^\\s*(?:}\\s*)?(?:if|unless|elif|elsif|else(?:\\s+if)?|case|switch|when)\\b");
    private static final Pattern FORBIDDEN_DIRECTIVE_PATTERN = Pattern.compile(
        "(?im)^\\s*(?:---\\s*$|%%\\{|click\\b|href\\b|style\\b|classDef\\b|linkStyle\\b)");
    private static final Pattern FORBIDDEN_URL_PATTERN = Pattern.compile(
        "(?i)(?:https?|ftp|file|data|javascript):|\\bwww\\.|\\burl\\s*\\(");
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
        int actionNodes = 0;
        int decisionNodes = 0;
        int edges = 0;
        for (String rawLine : normalizeMermaid(mermaidSource).split("\\R")) {
            String line = rawLine.trim();
            Matcher nodeMatcher = NODE_PATTERN.matcher(line);
            if (nodeMatcher.matches()) {
                if (nodeMatcher.group(3) != null) {
                    decisionNodes++;
                } else if (nodeMatcher.group(4) == null) {
                    actionNodes++;
                }
            } else if (EDGE_PATTERN.matcher(line).matches()) {
                edges++;
            }
        }
        return new FlowchartStatistics(actionNodes, decisionNodes, edges);
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
        MermaidValidation validation = validateMermaid(source);
        if (!validation.valid()) {
            return validation;
        }
        ParsedDiagram parsed = parseRestrictedFlowchart(normalizeMermaid(source));
        String topologyError = validateFlowTopology(parsed.nodes(), parsed.edges());
        if (topologyError != null) {
            return MermaidValidation.failure(topologyError);
        }
        long nonterminalNodes = parsed.nodes().values().stream()
            .filter(node -> node.type() != NodeType.TERMINAL)
            .count();
        if (nonterminalNodes > maxNonterminalNodes) {
            return MermaidValidation.failure(
                "Generated Mermaid flowcharts may use at most " + maxNonterminalNodes
                    + " non-terminal nodes for this snippet, but " + nonterminalNodes
                    + " were declared; related behavior must be grouped.");
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
            source, maxGeneratedNonterminalNodes(snippetContent));
        if (!validation.valid()) {
            return validation;
        }
        ParsedDiagram parsed = parseRestrictedFlowchart(normalizeMermaid(source));
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
            String line = lines[index].trim();
            if (line.isBlank()) {
                continue;
            }
            Matcher nodeMatcher = NODE_PATTERN.matcher(line);
            if (nodeMatcher.matches()) {
                String id = nodeMatcher.group(1);
                if (nodes.containsKey(id)) {
                    return ParsedDiagram.failure("Mermaid node id is duplicated: " + id);
                }
                boolean decision = nodeMatcher.group(3) != null;
                boolean terminal = nodeMatcher.group(4) != null;
                String label = unescapeLabel(
                    terminal ? nodeMatcher.group(4) : decision ? nodeMatcher.group(3) : nodeMatcher.group(2));
                if (label.isBlank()) {
                    return ParsedDiagram.failure("Mermaid nodes must have a visible label.");
                }
                NodeType type = terminal ? NodeType.TERMINAL : decision ? NodeType.DECISION : NodeType.ACTION;
                nodes.put(id, new NodeDefinition(id, label, type, ""));
                continue;
            }
            Matcher edgeMatcher = EDGE_PATTERN.matcher(line);
            if (edgeMatcher.matches()) {
                edges.add(new EdgeDefinition(
                    edgeMatcher.group(1),
                    edgeMatcher.group(2) != null ? edgeMatcher.group(2).trim() : "",
                    edgeMatcher.group(3)));
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
                    if (classes.putIfAbsent(id, semanticClass) != null) {
                        return ParsedDiagram.failure("Mermaid node has more than one semantic class: " + id);
                    }
                }
                continue;
            }
            return ParsedDiagram.failure("Unsupported Mermaid syntax on line " + (index + 1) + ".");
        }
        if (nodes.isEmpty()) {
            return ParsedDiagram.failure("Mermaid flowchart must contain at least one node.");
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
        for (String id : classes.keySet()) {
            if (!nodes.containsKey(id)) {
                return ParsedDiagram.failure("Mermaid class assignment references an unknown node id: " + id);
            }
        }
        if (!classes.keySet().containsAll(nodes.keySet())) {
            return ParsedDiagram.failure("Every Mermaid node must use a semantic setup, work, success, or failure class.");
        }
        Map<String, NodeDefinition> classifiedNodes = new LinkedHashMap<>();
        nodes.forEach((id, node) -> classifiedNodes.put(
            id, new NodeDefinition(id, node.label(), node.type(), classes.get(id))));
        return new ParsedDiagram(
            MermaidValidation.success(),
            Collections.unmodifiableMap(new LinkedHashMap<>(classifiedNodes)),
            List.copyOf(edges));
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
                if (decisionEdges.size() != 2 || labels.size() != 2 || targets.size() != 2) {
                    return "Every Mermaid decision must have two distinctly labeled outgoing paths.";
                }
            } else if (!"stop_1".equals(node.id())) {
                if (outgoing.get(node.id()).size() != 1) {
                    return "Every non-decision Mermaid node except stop_1 must have exactly one outgoing edge.";
                }
                if (!outgoing.get(node.id()).getFirst().label().isBlank()) {
                    return "Only Mermaid decision edges may have labels.";
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
