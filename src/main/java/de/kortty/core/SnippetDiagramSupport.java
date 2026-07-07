package de.kortty.core;

import de.kortty.model.SnippetDiagram;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Local helpers for persisted snippet diagrams.
 */
public final class SnippetDiagramSupport {
    private static final String COLOR_SETUP = "#EAF7EF";
    private static final String COLOR_MAIN = "#EAF4FF";
    private static final String COLOR_FAILURE = "#FDECEC";
    /** Dark-mode canvas + palette for {@link #applyDarkMode(String)}. */
    public static final String DARK_BACKGROUND_COLOR = "#1E1E1E";
    private static final String DARK_SETUP = "#26382D";
    private static final String DARK_MAIN = "#22303D";
    private static final String DARK_FAILURE = "#3E2A2A";
    private static final String DARK_FOREGROUND = "#E6E6E6";
    private static final String DARK_LINE = "#B8C2CC";
    private static final String DARK_BORDER = "#5A6673";
    private static final String DARK_PANEL = "#2B2B2B";
    private static final Pattern ACTIVITY_LABEL_PATTERN =
        Pattern.compile("^\\s*(?:#[A-Fa-f0-9]{6})?:(.*?)\\s*;\\s*(?:<<\\s*#[A-Fa-f0-9]{6}\\s*>>)?\\s*$");
    private static final Pattern DECISION_LABEL_PATTERN =
        Pattern.compile("^\\s*if\\s*\\((.*?)\\)\\s*then\\s*\\([^)]*\\)\\s*$", Pattern.CASE_INSENSITIVE);
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("^#[A-Fa-f0-9]{6}$");
    private static final Pattern SKINPARAM_BACKGROUND_PATTERN =
        Pattern.compile("(?im)^\\s*skinparam\\s+backgroundColor\\s+\\S+\\s*(?:\\R|$)");
    private static final Set<String> STOP_WORDS = Set.of(
        "a", "an", "and", "are", "as", "by", "default", "for", "from", "in", "into", "is", "main",
        "of", "or", "path", "snippet", "step", "the", "to", "with");

    private SnippetDiagramSupport() {
    }

    public record CodeReference(String id, String label, int startLine, int endLine, String excerpt) {
    }

    public record SourceCodeReference(String label, int startLine, int endLine) {
        public SourceCodeReference {
            label = label != null ? label.trim() : "";
        }
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

    public static String normalizePlantUml(String source) {
        String value = source != null ? source.trim() : "";
        if (value.isBlank()) {
            return "";
        }
        value = value.replaceAll("(?s)^```(?:plantuml|puml)?\\s*", "")
            .replaceAll("(?s)```\\s*$", "")
            .trim();
        if (!value.startsWith("@startuml")) {
            value = "@startuml\n" + value;
        }
        if (!value.endsWith("@enduml")) {
            value = value + "\n@enduml";
        }
        return value.trim();
    }

    public static boolean isRenderablePlantUml(String source) {
        String value = source != null ? source.trim() : "";
        return value.startsWith("@startuml") && value.endsWith("@enduml");
    }

    public static String applyBackgroundColor(String source, String backgroundColor) {
        String value = normalizePlantUml(source);
        if (value.isBlank()) {
            return value;
        }
        String color = normalizeHexColor(backgroundColor, "#FFFFFF");
        String backgroundLine = "skinparam backgroundColor " + color + "\n";
        Matcher existingBackground = SKINPARAM_BACKGROUND_PATTERN.matcher(value);
        if (existingBackground.find()) {
            return existingBackground.replaceFirst(Matcher.quoteReplacement(backgroundLine)).trim();
        }
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd < 0) {
            return value + "\n" + backgroundLine.trim();
        }
        return (value.substring(0, firstLineEnd + 1)
            + backgroundLine
            + value.substring(firstLineEnd + 1)).trim();
    }

    /**
     * How a diagram viewer picks its light/dark appearance. {@code AUTO} follows the operating system
     * (see {@link SystemThemeDetector}); {@code LIGHT}/{@code DARK} are permanent manual choices.
     */
    public enum DiagramColorMode {
        AUTO, LIGHT, DARK;

        /** Stable lowercase key for persistence in settings. */
        public String key() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** Parses a persisted key, defaulting to {@link #AUTO} for null/unknown values. */
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

        /** @return whether a diagram should render dark for this mode right now (AUTO consults the OS). */
        public boolean isDarkActive() {
            return this == DARK || (this == AUTO && SystemThemeDetector.isSystemDarkMode());
        }
    }

    /**
     * Rewrites {@code source} for a dark canvas: known light activity-node colours are swapped for dark
     * equivalents, and a skinparam block sets a dark background plus light connectors, borders, diamonds,
     * notes and default font so the whole diagram (not just the page padding) reads on dark. Nodes carrying
     * an unknown explicit colour keep it (light node cards stay legible on the dark canvas). Safe to call on
     * any source; returns "" for blank/unrenderable input.
     */
    public static String applyDarkMode(String source) {
        String value = normalizePlantUml(source);
        if (value.isBlank()) {
            return value;
        }
        value = replaceColorIgnoreCase(value, COLOR_SETUP, DARK_SETUP);
        value = replaceColorIgnoreCase(value, COLOR_MAIN, DARK_MAIN);
        value = replaceColorIgnoreCase(value, COLOR_FAILURE, DARK_FAILURE);
        String block = String.join("\n",
            "skinparam backgroundColor " + DARK_BACKGROUND_COLOR,
            "skinparam defaultFontColor " + DARK_FOREGROUND,
            "skinparam ArrowColor " + DARK_LINE,
            "skinparam ArrowFontColor " + DARK_FOREGROUND,
            "skinparam ActivityBackgroundColor " + DARK_PANEL,
            "skinparam ActivityBorderColor " + DARK_BORDER,
            "skinparam ActivityFontColor " + DARK_FOREGROUND,
            "skinparam ActivityDiamondBackgroundColor " + DARK_PANEL,
            "skinparam ActivityDiamondBorderColor " + DARK_BORDER,
            "skinparam ActivityDiamondFontColor " + DARK_FOREGROUND,
            "skinparam ActivityStartColor " + DARK_FOREGROUND,
            "skinparam ActivityEndColor " + DARK_FOREGROUND,
            "skinparam ActivityBarColor " + DARK_FOREGROUND,
            "skinparam NoteBackgroundColor " + DARK_PANEL,
            "skinparam NoteBorderColor " + DARK_BORDER,
            "skinparam NoteFontColor " + DARK_FOREGROUND) + "\n";
        return insertAfterStartLine(value, block);
    }

    private static String replaceColorIgnoreCase(String value, String from, String to) {
        return Pattern.compile(Pattern.quote(from), Pattern.CASE_INSENSITIVE)
            .matcher(value).replaceAll(Matcher.quoteReplacement(to));
    }

    /** Inserts {@code block} right after the first line (the {@code @startuml} line) of a normalized source. */
    private static String insertAfterStartLine(String value, String block) {
        int firstLineEnd = value.indexOf('\n');
        if (firstLineEnd < 0) {
            return (value + "\n" + block).trim();
        }
        return (value.substring(0, firstLineEnd + 1) + block + value.substring(firstLineEnd + 1)).trim();
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

    public static String ensureReadableActivityColors(String source) {
        String value = normalizePlantUml(source);
        if (value.isBlank() || !isActivityDiagram(value)) {
            return value;
        }

        StringBuilder builder = new StringBuilder();
        FlowBranch flowBranch = FlowBranch.NEUTRAL;
        String[] lines = value.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            String trimmed = line.trim();
            if (trimmed.startsWith(":")) {
                builder.append(leadingWhitespace(line))
                    .append(colorizedActivityLine(trimmed, activityColor(trimmed, flowBranch)))
                    .append("\n");
            } else if (isDeprecatedColoredActivityLine(trimmed)) {
                builder.append(leadingWhitespace(line))
                    .append(convertDeprecatedColoredActivityLine(trimmed))
                    .append("\n");
            } else {
                builder.append(line).append("\n");
            }
            flowBranch = nextFlowBranch(trimmed, flowBranch);
        }
        return builder.toString().trim();
    }

    public static String buildFallbackLogicalStructurePlantUml(String content, String snippetLanguage) {
        String normalizedContent = content != null ? content : "";
        String lowerContent = normalizedContent.toLowerCase(Locale.ROOT);
        List<String> actions = new ArrayList<>();

        if (hasAssignments(normalizedContent)) {
            actions.add("Read configured values");
        }
        actions.add("Run main snippet logic");

        StringBuilder builder = new StringBuilder();
        builder.append("@startuml\n");
        builder.append("start\n");
        for (String action : actions) {
            String color = "Run main snippet logic".equals(action) ? COLOR_MAIN : COLOR_SETUP;
            appendColoredActivity(builder, color, action);
        }
        if (hasConditionalFlow(lowerContent)) {
            builder.append("if (Main command succeeds?) then (yes)\n");
            appendColoredActivity(builder, COLOR_SETUP, successAction(lowerContent), "  ");
            builder.append("else (no)\n");
            appendColoredActivity(builder, COLOR_FAILURE, failureAction(lowerContent), "  ");
            builder.append("endif\n");
        }
        builder.append("stop\n");
        builder.append("@enduml");
        return builder.toString();
    }

    public static List<CodeReference> buildCodeReferences(String plantUmlSource, String content) {
        String normalizedContent = content != null ? content : "";
        if (normalizedContent.isBlank()) {
            return List.of();
        }
        List<String> lines = List.of(normalizedContent.split("\\R", -1));
        List<DiagramLabel> labels = extractDiagramLabels(plantUmlSource);
        if (labels.isEmpty()) {
            return List.of();
        }

        List<CodeReference> references = new ArrayList<>();
        for (DiagramLabel label : labels) {
            Optional<LineRange> range = findLineRange(label.label(), label.type(), lines);
            if (range.isPresent()) {
                LineRange lineRange = range.get();
                references.add(new CodeReference(
                    "ref-" + references.size(),
                    label.label(),
                    lineRange.startLine(),
                    lineRange.endLine(),
                    formatExcerpt(lines, lineRange.startLine(), lineRange.endLine())));
            }
        }
        return List.copyOf(references);
    }

    public static List<CodeReference> buildValidatedCodeReferences(
        String plantUmlSource,
        String content,
        List<SourceCodeReference> sourceReferences) {

        String normalizedContent = content != null ? content : "";
        if (normalizedContent.isBlank() || sourceReferences == null || sourceReferences.isEmpty()) {
            return List.of();
        }
        List<String> lines = List.of(normalizedContent.split("\\R", -1));
        Set<String> diagramLabels = new LinkedHashSet<>();
        for (DiagramLabel label : extractDiagramLabels(plantUmlSource)) {
            diagramLabels.add(normalizeDiagramLabel(label.label()));
        }
        if (diagramLabels.isEmpty()) {
            return List.of();
        }

        List<CodeReference> references = new ArrayList<>();
        for (SourceCodeReference sourceReference : sourceReferences) {
            if (sourceReference == null) {
                continue;
            }
            String label = normalizeDiagramLabel(sourceReference.label());
            if (label.isBlank() || !diagramLabels.contains(label)) {
                continue;
            }
            int startLine = sourceReference.startLine();
            int endLine = Math.max(startLine, sourceReference.endLine());
            if (!isValidCodeReferenceRange(lines, startLine, endLine)) {
                continue;
            }
            references.add(new CodeReference(
                "ref-" + references.size(),
                label,
                startLine,
                endLine,
                formatExcerpt(lines, startLine, endLine)));
        }
        return List.copyOf(references);
    }

    public static List<CodeReference> buildExpandedCodeReferences(
        String plantUmlSource,
        String content,
        List<SourceCodeReference> sourceReferences) {

        List<CodeReference> validatedReferences =
            buildValidatedCodeReferences(plantUmlSource, content, sourceReferences);
        List<CodeReference> localReferences = buildCodeReferences(plantUmlSource, content);
        if (validatedReferences.isEmpty()) {
            return localReferences;
        }
        if (localReferences.isEmpty()) {
            return validatedReferences;
        }

        List<CodeReference> mergedReferences = new ArrayList<>(validatedReferences);
        Set<String> coveredLabels = new LinkedHashSet<>();
        for (CodeReference reference : validatedReferences) {
            coveredLabels.add(normalizeDiagramLabel(reference.label()));
        }
        for (CodeReference reference : localReferences) {
            if (coveredLabels.add(normalizeDiagramLabel(reference.label()))) {
                mergedReferences.add(reference);
            }
        }
        return withSequentialIds(mergedReferences);
    }

    static List<String> extractCodeReferenceLabels(String plantUmlSource) {
        return extractDiagramLabels(plantUmlSource).stream()
            .map(DiagramLabel::label)
            .toList();
    }

    private static String colorizedActivityLine(String trimmedActivityLine, String color) {
        if (hasActivityColorStereotype(trimmedActivityLine)) {
            return trimmedActivityLine;
        }
        return trimmedActivityLine + " <<" + color + ">>";
    }

    private static boolean hasActivityColorStereotype(String trimmedActivityLine) {
        return trimmedActivityLine.matches(".*<<\\s*#[A-Fa-f0-9]{6}\\s*>>\\s*$");
    }

    private static boolean isDeprecatedColoredActivityLine(String trimmedLine) {
        return trimmedLine.matches("#[A-Fa-f0-9]{6}:.*");
    }

    private static String convertDeprecatedColoredActivityLine(String trimmedLine) {
        int separatorIndex = trimmedLine.indexOf(':');
        String color = trimmedLine.substring(0, separatorIndex);
        String activity = trimmedLine.substring(separatorIndex);
        return colorizedActivityLine(activity, color);
    }

    private enum FlowBranch {
        NEUTRAL,
        SUCCESS,
        FAILURE
    }

    private static boolean isActivityDiagram(String source) {
        if (!isRenderablePlantUml(source)) {
            return false;
        }
        boolean hasStart = source.lines().anyMatch(line -> "start".equals(line.trim()));
        boolean hasStop = source.lines().anyMatch(line -> "stop".equals(line.trim()));
        boolean hasActivity = source.lines().anyMatch(line -> line.trim().startsWith(":")
            || line.trim().matches("#[A-Za-z0-9_]+:.*"));
        return hasStart && hasStop && hasActivity;
    }

    private static FlowBranch nextFlowBranch(String trimmed, FlowBranch currentBranch) {
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("else")) {
            return lower.contains("no") || lower.contains("fail") || lower.contains("error")
                ? FlowBranch.FAILURE
                : FlowBranch.NEUTRAL;
        }
        if (lower.startsWith("if ") && lower.contains("then")) {
            return lower.contains("yes") || lower.contains("success") || lower.contains("ok")
                ? FlowBranch.SUCCESS
                : FlowBranch.NEUTRAL;
        }
        if (lower.startsWith("endif")) {
            return FlowBranch.NEUTRAL;
        }
        return currentBranch;
    }

    private static String activityColor(String trimmedActivityLine, FlowBranch flowBranch) {
        if (flowBranch == FlowBranch.FAILURE || containsAny(trimmedActivityLine, "fail", "error", "no-result", "no result")) {
            return COLOR_FAILURE;
        }
        if (flowBranch == FlowBranch.SUCCESS || containsAny(trimmedActivityLine, "success", "complete", "ok")) {
            return COLOR_SETUP;
        }
        if (containsAny(trimmedActivityLine, "config", "configuration", "option", "argument", "parse", "load", "read", "init")) {
            return COLOR_SETUP;
        }
        return COLOR_MAIN;
    }

    private static boolean containsAny(String value, String... needles) {
        String lower = value != null ? value.toLowerCase(Locale.ROOT) : "";
        for (String needle : needles) {
            if (lower.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String leadingWhitespace(String value) {
        int index = 0;
        while (index < value.length() && Character.isWhitespace(value.charAt(index))) {
            index++;
        }
        return value.substring(0, index);
    }

    private static void appendColoredActivity(StringBuilder builder, String color, String label) {
        appendColoredActivity(builder, color, label, "");
    }

    private static void appendColoredActivity(StringBuilder builder, String color, String label, String indent) {
        builder.append(indent)
            .append(":")
            .append(safeActivityLabel(label))
            .append("; <<")
            .append(color)
            .append(">>\n");
    }

    private static boolean hasAssignments(String content) {
        return content != null && content.lines()
            .anyMatch(line -> line.matches("\\s*[A-Za-z_][A-Za-z0-9_]*=.*"));
    }

    private static boolean hasConditionalFlow(String lowerContent) {
        return lowerContent != null
            && (lowerContent.contains("\nif ")
            || lowerContent.startsWith("if ")
            || lowerContent.contains("\ncase ")
            || lowerContent.contains(" else"));
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

    private static String safeActivityLabel(String label) {
        String value = label != null ? label : "Run step";
        value = value.replace('\n', ' ').replace('\r', ' ');
        value = value.replace(':', '-').replace(';', ',');
        return value.isBlank() ? "Run step" : value.trim();
    }

    private static List<DiagramLabel> extractDiagramLabels(String plantUmlSource) {
        String normalized = normalizePlantUml(plantUmlSource);
        if (normalized.isBlank()) {
            return List.of();
        }

        List<DiagramLabel> labels = new ArrayList<>();
        String[] lines = normalized.split("\\R");
        for (String line : lines) {
            String trimmed = line.trim();
            Matcher activityMatcher = ACTIVITY_LABEL_PATTERN.matcher(trimmed);
            if (activityMatcher.matches()) {
                String label = normalizeDiagramLabel(activityMatcher.group(1));
                if (!label.isBlank()) {
                    labels.add(new DiagramLabel(label, DiagramLabelType.ACTIVITY));
                }
                continue;
            }

            Matcher decisionMatcher = DECISION_LABEL_PATTERN.matcher(trimmed);
            if (decisionMatcher.matches()) {
                String label = normalizeDiagramLabel(decisionMatcher.group(1));
                if (!label.isBlank()) {
                    labels.add(new DiagramLabel(label, DiagramLabelType.DECISION));
                }
            }
        }
        return List.copyOf(labels);
    }

    private static Optional<LineRange> findLineRange(String label, DiagramLabelType type, List<String> lines) {
        Optional<LineRange> specialized = findSpecializedLineRange(label, type, lines);
        if (specialized.isPresent()) {
            return specialized;
        }
        return findTokenLineRange(label, lines);
    }

    private static Optional<LineRange> findSpecializedLineRange(String label, DiagramLabelType type, List<String> lines) {
        String lowerLabel = label.toLowerCase(Locale.ROOT);
        if (type == DiagramLabelType.DECISION || containsAny(lowerLabel, "succeed", "condition", "test?", "found?", "csv?")) {
            return uniqueLineRange(lines, SnippetDiagramSupport::isConditionalLine, false);
        }
        if (containsAny(lowerLabel, "configured value", "configuration", "config", "default")) {
            return firstAssignmentRange(lines);
        }
        if (containsAny(lowerLabel, "command-line", "command line", "option", "argument")) {
            return uniqueLineRange(lines, SnippetDiagramSupport::isOptionParsingLine, false);
        }
        if (containsAny(lowerLabel, "scan", "directory", "directories", "list files", "find files")) {
            return uniqueLineRange(lines, SnippetDiagramSupport::isDirectoryScanLine, false);
        }
        if (containsAny(lowerLabel, "success", "ok", "complete")) {
            return uniqueLineRange(lines, line -> containsAny(line, "success", "succeeded", "ok", "complete"), false);
        }
        if (containsAny(lowerLabel, "failure", "failed", "error", "no-result", "no result")) {
            return uniqueLineRange(lines, line -> containsAny(line, "failure", "failed", "error", "no-result", "no result"), false);
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
            String line = lines.get(index);
            if (isExecutableLine(line)) {
                return Optional.of(new LineRange(index + 1, index + 1));
            }
        }
        return Optional.empty();
    }

    private static Optional<LineRange> uniqueLineRange(
        List<String> lines,
        java.util.function.Predicate<String> matcher,
        boolean includeBlankLines) {

        List<Integer> matches = new ArrayList<>();
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if ((includeBlankLines || !line.isBlank()) && matcher.test(line)) {
                matches.add(index + 1);
            }
        }
        return matches.size() == 1
            ? Optional.of(new LineRange(matches.get(0), matches.get(0)))
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
        LineScore best = scores.get(0);
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
        String normalized = value != null ? value.toLowerCase(Locale.ROOT) : "";
        normalized = normalized.replaceAll("([a-z])([A-Z])", "$1 $2");
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
        if (value.startsWith("config")) {
            return "config";
        }
        if (value.startsWith("arg")) {
            return "argument";
        }
        if (value.startsWith("dir")) {
            return "directory";
        }
        if (value.startsWith("file")) {
            return "file";
        }
        if (value.startsWith("notif")) {
            return "notification";
        }
        if (value.startsWith("succ")) {
            return "success";
        }
        if (value.startsWith("fail")) {
            return "failure";
        }
        if (value.endsWith("ies") && value.length() > 4) {
            return value.substring(0, value.length() - 3) + "y";
        }
        if (value.endsWith("s") && value.length() > 3) {
            return value.substring(0, value.length() - 1);
        }
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
        return trimmed.startsWith("if ")
            || trimmed.startsWith("if[")
            || trimmed.startsWith("if(")
            || trimmed.startsWith("case ")
            || trimmed.startsWith("when ");
    }

    private static boolean isOptionParsingLine(String line) {
        return containsAny(line, "getopt", "getopts", "@argv", "argv", "$1", "$2", "--", "-h", "-v", "option", "opts");
    }

    private static boolean isDirectoryScanLine(String line) {
        return containsAny(line, "find ", "find(", "ls ", "opendir", "readdir", "glob", "scandir", "listfiles");
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
            && !lower.startsWith("if ")
            && !lower.startsWith("else")
            && !lower.startsWith("case ");
    }

    private static String formatExcerpt(List<String> lines, int startLine, int endLine) {
        int safeStart = Math.max(1, Math.min(startLine, lines.size()));
        int safeEnd = Math.max(safeStart, Math.min(endLine, lines.size()));
        int displayedEnd = Math.min(safeEnd, safeStart + 7);
        int width = String.valueOf(displayedEnd).length();
        StringBuilder builder = new StringBuilder();
        for (int lineNumber = safeStart; lineNumber <= displayedEnd; lineNumber++) {
            if (!builder.isEmpty()) {
                builder.append("\n");
            }
            builder.append(String.format(Locale.ROOT, "%" + width + "d | %s", lineNumber, lines.get(lineNumber - 1)));
        }
        if (displayedEnd < safeEnd) {
            builder.append("\n...");
        }
        return builder.toString();
    }

    private static boolean isValidCodeReferenceRange(List<String> lines, int startLine, int endLine) {
        if (lines == null || lines.isEmpty() || startLine < 1 || endLine < startLine || endLine > lines.size()) {
            return false;
        }
        for (int lineNumber = startLine; lineNumber <= endLine; lineNumber++) {
            if (!lines.get(lineNumber - 1).isBlank()) {
                return true;
            }
        }
        return false;
    }

    private static String normalizeDiagramLabel(String label) {
        String value = label != null ? label : "";
        value = value.replace("\\n", " ");
        value = value.replaceAll("\\s+", " ").trim();
        return value;
    }

    private static List<CodeReference> withSequentialIds(List<CodeReference> references) {
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<CodeReference> renumbered = new ArrayList<>();
        for (CodeReference reference : references) {
            if (reference != null) {
                renumbered.add(new CodeReference(
                    "ref-" + renumbered.size(),
                    reference.label(),
                    reference.startLine(),
                    reference.endLine(),
                    reference.excerpt()));
            }
        }
        return List.copyOf(renumbered);
    }

    private enum DiagramLabelType {
        ACTIVITY,
        DECISION
    }

    private record DiagramLabel(String label, DiagramLabelType type) {
    }

    private record LineRange(int startLine, int endLine) {
    }

    private record LineScore(int line, int score) {
    }
}
