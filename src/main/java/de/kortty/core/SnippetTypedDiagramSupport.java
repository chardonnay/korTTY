package de.kortty.core;

import de.kortty.model.SnippetDiagramType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Restricted per-family grammars for the AI-generated snippet diagrams beyond the logical-structure
 * flowchart. Like {@link SnippetDiagramSupport}, this is deliberately not a general Mermaid parser:
 * every non-blank line must match one of the family's allowlisted statement shapes, on top of the
 * shared security screen that rejects directives, URLs, media, HTML and oversized sources.
 *
 * <p>{@link SnippetDiagramType#LOGICAL_STRUCTURE} keeps its stricter topology dialect in
 * {@link SnippetDiagramSupport}; this class dispatches to it so callers can stay type-agnostic.</p>
 */
public final class SnippetTypedDiagramSupport {

    public static final int MAX_SEQUENCE_PARTICIPANTS = 12;
    public static final int MAX_SEQUENCE_MESSAGES = 60;
    public static final int MAX_STATES = 12;
    public static final int MAX_CLASSES = 12;
    public static final int MAX_CLASS_MEMBERS = 20;
    public static final int MAX_ER_ENTITIES = 12;
    public static final int MAX_ER_ATTRIBUTES = 40;

    private static final String ID = "[A-Za-z][A-Za-z0-9_-]{0,63}";
    private static final String LABEL_TEXT = "[^\"<>{}\\[\\]|;]{1,120}";

    // ---- sequence ------------------------------------------------------------------------------
    private static final Pattern SEQUENCE_HEADER = Pattern.compile("(?i)^sequenceDiagram$");
    private static final Pattern SEQUENCE_PARTICIPANT = Pattern.compile(
        "^(?:participant|actor)\\s+(" + ID + ")(?:\\s+as\\s+" + LABEL_TEXT + ")?$");
    private static final Pattern SEQUENCE_MESSAGE = Pattern.compile(
        "^(" + ID + ")\\s*--?>>\\s*(" + ID + ")\\s*:\\s*" + LABEL_TEXT + "$");
    private static final Pattern SEQUENCE_BLOCK = Pattern.compile(
        "^(?:alt|else|opt|loop|par|and)(?:\\s+" + LABEL_TEXT + ")?$");
    private static final Pattern SEQUENCE_BLOCK_END = Pattern.compile("^end$");
    private static final Pattern SEQUENCE_NOTE = Pattern.compile(
        "^(?i:note)\\s+(?i:left of|right of|over)\\s+(" + ID + ")(?:\\s*,\\s*(" + ID + "))?"
            + "\\s*:\\s*" + LABEL_TEXT + "$");

    // ---- state ---------------------------------------------------------------------------------
    private static final Pattern STATE_HEADER = Pattern.compile("(?i)^stateDiagram-v2$");
    private static final Pattern STATE_TRANSITION = Pattern.compile(
        "^(\\[\\*]|" + ID + ")\\s*-->\\s*(\\[\\*]|" + ID + ")(?:\\s*:\\s*" + LABEL_TEXT + ")?$");
    private static final Pattern STATE_DESCRIPTION = Pattern.compile(
        "^(" + ID + ")\\s*:\\s*" + LABEL_TEXT + "$");
    private static final Pattern STATE_ALIAS = Pattern.compile(
        "^state\\s+\"" + LABEL_TEXT + "\"\\s+as\\s+(" + ID + ")$");

    // ---- class ---------------------------------------------------------------------------------
    private static final Pattern CLASS_HEADER = Pattern.compile("(?i)^classDiagram$");
    private static final Pattern CLASS_DECLARATION = Pattern.compile(
        "^class\\s+(" + ID + ")\\s*(\\{)?$");
    private static final Pattern CLASS_BLOCK_END = Pattern.compile("^}$");
    private static final Pattern CLASS_MEMBER = Pattern.compile(
        "^[+\\-#~]?\\s*[A-Za-z_$][A-Za-z0-9_$~,.()\\[\\] *-]{0,119}$");
    private static final Pattern CLASS_RELATION = Pattern.compile(
        "^(" + ID + ")\\s*(?:\"" + LABEL_TEXT + "\"\\s*)?"
            + "(<\\|--|<\\|\\.\\.|\\*--|o--|-->|--\\*|--o|\\.\\.>|\\.\\.\\|>|--\\|>|--|\\.\\.)"
            + "\\s*(?:\"" + LABEL_TEXT + "\"\\s*)?(" + ID + ")(?:\\s*:\\s*" + LABEL_TEXT + ")?$");

    // ---- er ------------------------------------------------------------------------------------
    private static final Pattern ER_HEADER = Pattern.compile("(?i)^erDiagram$");
    private static final Pattern ER_RELATION = Pattern.compile(
        "^(" + ID + ")\\s+(?:\\|\\||\\|o|o\\||}\\||}o)(?:--|\\.\\.)(?:\\|\\||o\\||o\\{|\\|\\{)\\s+("
            + ID + ")\\s*:\\s*(?:\"" + LABEL_TEXT + "\"|" + LABEL_TEXT + ")$");
    private static final Pattern ER_ENTITY_START = Pattern.compile("^(" + ID + ")\\s*\\{$");
    private static final Pattern ER_BLOCK_END = Pattern.compile("^}$");
    private static final Pattern ER_ATTRIBUTE = Pattern.compile(
        "^[A-Za-z_][A-Za-z0-9_()\\[\\]]{0,63}\\s+[A-Za-z_][A-Za-z0-9_]{0,63}"
            + "(?:\\s+(?:PK|FK|UK)(?:\\s*,\\s*(?:PK|FK|UK))*)?(?:\\s+\"" + LABEL_TEXT + "\")?$");

    /** Scan window for {@link #extractDiagramSource}; a runaway answer must not cost O(n²). */
    private static final int MAX_SALVAGE_CHARS = 256 * 1024;
    /** How many trailing lines of JSON debris the salvage may strip before giving up. */
    private static final int MAX_SALVAGE_TRIM_LINES = 60;
    private static final Pattern JSON_KEY_LINE = Pattern.compile("^\"[A-Za-z][A-Za-z0-9_]{0,63}\"\\s*:");
    /** A trailing line that is JSON envelope, not Mermaid: quotes, braces, brackets, commas, a key. */
    private static final Pattern ENVELOPE_DEBRIS_LINE = Pattern.compile(
        "^(?:[\\s\"\\\\,\\]\\[}{]*|\"[A-Za-z][A-Za-z0-9_]{0,63}\"\\s*:.*)$");
    private static final Pattern DOUBLE_ESCAPED_SHAPE_QUOTE = Pattern.compile(
        "(\\[|\\{|\\(\\[)\\\\\"|\\\\\"(]|}|]\\))");
    /** A stray backslash left at a shape edge after unescaping ({@code [\"Label"\]}): removed. */
    private static final Pattern STRAY_BACKSLASH_AT_SHAPE_EDGE = Pattern.compile(
        "(?<=[\\[{(])\\\\(?=\")|\\\\(?=[\\]})])");
    /** Where the envelope resumes after the diagram string: {@code ","codeReferences":} — impossible inside a label. */
    private static final Pattern JSON_NEXT_FIELD = Pattern.compile(
        "\"\\s*,\\s*\"[A-Za-z][A-Za-z0-9_]{0,63}\"\\s*:");
    /** The envelope's end when the diagram was its last field. */
    private static final Pattern JSON_OBJECT_END = Pattern.compile("\"\\s*}\\s*$");

    private SnippetTypedDiagramSupport() {
    }

    /**
     * Recovers the diagram from an answer whose JSON could not be parsed, without asking the model
     * again.
     *
     * <p>The JSON envelope is the fragile part of the contract, not the diagram: korTTY's own
     * grammar requires quoted labels ({@code node_1["Read config"]}), so every one of those quotes
     * has to survive as {@code \"} inside a JSON string, and over a few thousand characters models
     * routinely lose that escaping — the object then parses in neither strict nor lenient mode and
     * a complete, perfectly good diagram is thrown away. The diagram itself is self-delimiting: it
     * starts at the family's header line and every following line must match the restricted
     * grammar. So the header is located, JSON string escapes are undone when the answer used them,
     * and trailing debris is stripped until the family grammar accepts the block — which is also
     * what keeps this safe, because nothing reaches a renderer that the normal validation would
     * have rejected.</p>
     *
     * @return the recovered diagram source, or an empty string when nothing usable was found
     */
    public static String extractDiagramSource(SnippetDiagramType type, String answer) {
        SnippetDiagramType safeType = type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE;
        if (answer == null || answer.isBlank()) {
            return "";
        }
        // A model that thinks inline mentions the header in its reasoning before it draws anything;
        // the reasoning is dropped first so the search does not start inside a sentence.
        String sanitized = AiResponseSanitizer.sanitizeForDisplay(answer);
        String source = indexOfIgnoreCase(sanitized, header(safeType)) >= 0 ? sanitized : answer;
        String text = source.length() > MAX_SALVAGE_CHARS ? source.substring(0, MAX_SALVAGE_CHARS) : source;
        int start = indexOfIgnoreCase(text, header(safeType));
        if (start < 0) {
            return "";
        }
        // A compact, single-line envelope continues right after the diagram string — no line
        // break separates `class fail_1 failure","codeReferences":[…` — so the continuation is
        // cut on the still-escaped text, where the next field's `","key":` is unmistakable.
        String candidate = cutJsonContinuation(text.substring(start));
        if (candidate.contains("\\n")) {
            candidate = unescapeJsonStringBody(candidate);
        }
        // A single node written with doubled escapes (`[\\"Label\\"]` beside `[\"Label\"]`) leaves a
        // stray backslash at the shape boundary after unescaping; the grammar wants the bare quote.
        candidate = DOUBLE_ESCAPED_SHAPE_QUOTE.matcher(candidate).replaceAll("$1\"$2");
        candidate = STRAY_BACKSLASH_AT_SHAPE_EDGE.matcher(candidate).replaceAll("");
        candidate = SnippetDiagramSupport.stripPresentationStatements(candidate);
        List<String> lines = new ArrayList<>();
        for (String line : candidate.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("```") || JSON_KEY_LINE.matcher(trimmed).find()) {
                break;
            }
            lines.add(line);
        }
        for (int attempt = 0; attempt <= MAX_SALVAGE_TRIM_LINES && !lines.isEmpty(); attempt++) {
            String accepted = firstAcceptedVariant(safeType, lines);
            if (accepted != null) {
                return accepted;
            }
            // Only envelope debris may go. Trimming a real statement would turn an invalid
            // diagram into a valid stub of its first lines — observed as a two-node "flow".
            if (!ENVELOPE_DEBRIS_LINE.matcher(lines.get(lines.size() - 1).trim()).matches()) {
                return "";
            }
            lines.remove(lines.size() - 1);
        }
        return "";
    }

    /**
     * Tries the block as it stands and, failing that, with the JSON envelope stripped from its last
     * line — {@code class work_1 work"}} is one valid statement plus two characters of envelope.
     * That is only a second attempt because an ER attribute comment legitimately ends in a quote.
     */
    private static String firstAcceptedVariant(SnippetDiagramType type, List<String> lines) {
        String source = String.join("\n", lines).strip();
        if (!source.isBlank() && validate(type, source).valid()) {
            return source;
        }
        String lastLine = lines.get(lines.size() - 1);
        String stripped = lastLine.replaceAll("\\\\?\"\\s*[,\\}\\]]*\\s*$", "");
        if (stripped.equals(lastLine)) {
            return null;
        }
        List<String> variant = new ArrayList<>(lines.subList(0, lines.size() - 1));
        variant.add(stripped);
        String variantSource = String.join("\n", variant).strip();
        return !variantSource.isBlank() && validate(type, variantSource).valid() ? variantSource : null;
    }

    private static String cutJsonContinuation(String candidate) {
        Matcher nextField = JSON_NEXT_FIELD.matcher(candidate);
        if (nextField.find()) {
            return candidate.substring(0, nextField.start());
        }
        Matcher objectEnd = JSON_OBJECT_END.matcher(candidate);
        return objectEnd.find() ? candidate.substring(0, objectEnd.start()) : candidate;
    }

    private static int indexOfIgnoreCase(String text, String needle) {
        return text.toLowerCase(Locale.ROOT).indexOf(needle.toLowerCase(Locale.ROOT));
    }

    /** Undoes the JSON string escapes of a diagram that was emitted as a (broken) JSON value. */
    private static String unescapeJsonStringBody(String value) {
        StringBuilder builder = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character != '\\' || index + 1 >= value.length()) {
                builder.append(character);
                continue;
            }
            char escaped = value.charAt(++index);
            switch (escaped) {
                case 'n' -> builder.append('\n');
                case 't' -> builder.append('\t');
                case 'r' -> { }
                case '"' -> builder.append('"');
                case '\\' -> builder.append('\\');
                case '/' -> builder.append('/');
                default -> builder.append('\\').append(escaped);
            }
        }
        return builder.toString();
    }

    /** The exact Mermaid header line the family must start with; used by prompts and messages. */
    public static String header(SnippetDiagramType type) {
        return switch (type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE) {
            case LOGICAL_STRUCTURE -> "flowchart TD";
            case SEQUENCE -> "sequenceDiagram";
            case STATE -> "stateDiagram-v2";
            case CLASS -> "classDiagram";
            case ER -> "erDiagram";
        };
    }

    /** Only the logical-structure flowchart has a deterministic locally built fallback. */
    public static boolean hasDeterministicFallback(SnippetDiagramType type) {
        return type == null || type == SnippetDiagramType.LOGICAL_STRUCTURE;
    }

    /**
     * Validates a diagram of the given family for rendering. Lenient counterpart of
     * {@link #validateGenerated(SnippetDiagramType, String)}: persisted diagrams saved by other
     * korTTY versions stay renderable as long as they are still safe restricted Mermaid.
     */
    public static SnippetDiagramSupport.MermaidValidation validate(SnippetDiagramType type, String source) {
        SnippetDiagramType safeType = type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE;
        if (safeType == SnippetDiagramType.LOGICAL_STRUCTURE) {
            return SnippetDiagramSupport.validateMermaid(source);
        }
        SnippetDiagramSupport.MermaidValidation security = SnippetDiagramSupport.validateCommonSecurity(source);
        if (!security.valid()) {
            return security;
        }
        return parse(safeType, SnippetDiagramSupport.normalizeMermaid(source)).validation();
    }

    /** Validates a freshly AI-generated diagram with the compactness caps of a short snippet. */
    public static SnippetDiagramSupport.MermaidValidation validateGenerated(
        SnippetDiagramType type, String source) {
        return validateGenerated(type, source, null);
    }

    /**
     * Validates a freshly AI-generated diagram, applying the family's compactness caps. The
     * flowchart's node cap follows the length of {@code snippetContent}
     * ({@link SnippetDiagramSupport#maxGeneratedNonterminalNodes(String)}); the other families
     * keep their fixed caps.
     */
    public static SnippetDiagramSupport.MermaidValidation validateGenerated(
        SnippetDiagramType type, String source, String snippetContent) {

        SnippetDiagramType safeType = type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE;
        if (safeType == SnippetDiagramType.LOGICAL_STRUCTURE) {
            return SnippetDiagramSupport.validateGeneratedMermaid(
                source, SnippetDiagramSupport.maxGeneratedNonterminalNodes(snippetContent));
        }
        SnippetDiagramSupport.MermaidValidation validation = validate(safeType, source);
        if (!validation.valid()) {
            return validation;
        }
        Parsed parsed = parse(safeType, SnippetDiagramSupport.normalizeMermaid(source));
        String capError = validateCaps(safeType, parsed);
        return capError != null ? failure(capError) : validation;
    }

    /** A one-line size summary of a generated diagram for the log. */
    public static String summarize(SnippetDiagramType type, String source) {
        SnippetDiagramType safeType = type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE;
        if (safeType == SnippetDiagramType.LOGICAL_STRUCTURE) {
            return SnippetDiagramSupport.flowchartStatistics(source).toString();
        }
        Parsed parsed = parse(safeType, SnippetDiagramSupport.normalizeMermaid(source));
        return "elements=" + parsed.elementIds().size() + ", edges=" + parsed.edgeCount();
    }

    /**
     * Validates a fresh AI diagram for its snippet. Every family treats {@code codeReferences} as
     * optional: the flowchart reports mapping gaps through
     * {@link SnippetDiagramSupport#reportSourceMapping} instead of failing, and the other families
     * were already reduced to declared elements by {@link #filterValidSourceReferences}.
     */
    public static SnippetDiagramSupport.MermaidValidation validateForSnippet(
        SnippetDiagramType type,
        String source,
        String snippetContent,
        List<SnippetDiagramSupport.SourceCodeReference> sourceReferences,
        String responseLanguageCode) {

        SnippetDiagramType safeType = type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE;
        if (safeType == SnippetDiagramType.LOGICAL_STRUCTURE) {
            return SnippetDiagramSupport.validateMermaidForSnippet(
                source, snippetContent, sourceReferences, responseLanguageCode);
        }
        return validateGenerated(safeType, source);
    }

    /**
     * Filters an AI source mapping to declared diagram elements with structurally valid positive
     * line ranges. Unlike the flowchart mapping this is relaxed: an incomplete mapping never fails
     * validation, and unmatched entries are simply dropped.
     */
    public static List<SnippetDiagramSupport.SourceCodeReference> filterValidSourceReferences(
        SnippetDiagramType type,
        String mermaidSource,
        List<SnippetDiagramSupport.SourceCodeReference> sourceReferences) {

        SnippetDiagramType safeType = type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE;
        if (safeType == SnippetDiagramType.LOGICAL_STRUCTURE) {
            return SnippetDiagramSupport.filterValidSourceReferences(mermaidSource, sourceReferences);
        }
        if (sourceReferences == null || sourceReferences.isEmpty()) {
            return List.of();
        }
        Set<String> elementIds = declaredElementIds(safeType, mermaidSource);
        if (elementIds.isEmpty()) {
            return List.of();
        }
        List<SnippetDiagramSupport.SourceCodeReference> filtered = new ArrayList<>();
        Set<String> usedIds = new LinkedHashSet<>();
        for (SnippetDiagramSupport.SourceCodeReference reference : sourceReferences) {
            if (reference == null
                || !elementIds.contains(reference.nodeId())
                || usedIds.contains(reference.nodeId())
                || reference.label().isBlank()
                || reference.startLine() < 1
                || reference.endLine() < reference.startLine()) {
                continue;
            }
            usedIds.add(reference.nodeId());
            filtered.add(reference);
        }
        return List.copyOf(filtered);
    }

    /**
     * The stable element ids a source mapping may reference: participants (sequence), named states,
     * class names, or entity names. Empty when the source does not parse as the given family.
     */
    public static Set<String> declaredElementIds(SnippetDiagramType type, String mermaidSource) {
        SnippetDiagramType safeType = type != null ? type : SnippetDiagramType.LOGICAL_STRUCTURE;
        if (safeType == SnippetDiagramType.LOGICAL_STRUCTURE) {
            return SnippetDiagramSupport.extractNodeLabels(mermaidSource).keySet();
        }
        Parsed parsed = parse(safeType, SnippetDiagramSupport.normalizeMermaid(mermaidSource));
        return parsed.validation().valid() ? parsed.elementIds() : Set.of();
    }

    // ---- family parsers ------------------------------------------------------------------------

    private static Parsed parse(SnippetDiagramType type, String source) {
        String[] lines = nonHeaderLines(type, source);
        if (lines == null) {
            return Parsed.failure("Snippet " + type.id() + " diagrams must start with '" + header(type) + "'.");
        }
        return switch (type) {
            case SEQUENCE -> parseSequence(lines);
            case STATE -> parseState(lines);
            case CLASS -> parseClass(lines);
            case ER -> parseEr(lines);
            case LOGICAL_STRUCTURE -> throw new IllegalStateException("handled by SnippetDiagramSupport");
        };
    }

    /** Returns the trimmed lines after the family header, or {@code null} on a header mismatch. */
    private static String[] nonHeaderLines(SnippetDiagramType type, String source) {
        if (source == null || source.isBlank()) {
            return null;
        }
        Pattern headerPattern = switch (type) {
            case SEQUENCE -> SEQUENCE_HEADER;
            case STATE -> STATE_HEADER;
            case CLASS -> CLASS_HEADER;
            case ER -> ER_HEADER;
            case LOGICAL_STRUCTURE -> throw new IllegalStateException("handled by SnippetDiagramSupport");
        };
        String[] lines = source.split("\\R", -1);
        int headerIndex = -1;
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].isBlank()) {
                headerIndex = index;
                break;
            }
        }
        if (headerIndex < 0 || !headerPattern.matcher(lines[headerIndex].trim()).matches()) {
            return null;
        }
        String[] rest = new String[lines.length - headerIndex - 1];
        for (int index = headerIndex + 1; index < lines.length; index++) {
            rest[index - headerIndex - 1] = lines[index].trim();
        }
        return rest;
    }

    private static Parsed parseSequence(String[] lines) {
        Set<String> participants = new LinkedHashSet<>();
        int messages = 0;
        int blockDepth = 0;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            Matcher participant = SEQUENCE_PARTICIPANT.matcher(line);
            if (participant.matches()) {
                if (!participants.add(participant.group(1))) {
                    return Parsed.failure("Sequence participant id is duplicated: " + participant.group(1));
                }
                continue;
            }
            Matcher message = SEQUENCE_MESSAGE.matcher(line);
            if (message.matches()) {
                if (!participants.contains(message.group(1)) || !participants.contains(message.group(2))) {
                    return Parsed.failure("Sequence messages must use declared participant ids.");
                }
                messages++;
                continue;
            }
            if (SEQUENCE_BLOCK.matcher(line).matches()) {
                if (!line.startsWith("else") && !line.startsWith("and")) {
                    blockDepth++;
                } else if (blockDepth <= 0) {
                    return Parsed.failure("Sequence 'else'/'and' must appear inside an open block.");
                }
                continue;
            }
            if (SEQUENCE_BLOCK_END.matcher(line).matches()) {
                if (--blockDepth < 0) {
                    return Parsed.failure("Sequence 'end' without an open block.");
                }
                continue;
            }
            Matcher note = SEQUENCE_NOTE.matcher(line);
            if (note.matches()) {
                if (!participants.contains(note.group(1))
                    || (note.group(2) != null && !participants.contains(note.group(2)))) {
                    return Parsed.failure("Sequence notes must reference declared participant ids.");
                }
                continue;
            }
            return unsupportedLine("sequence", index);
        }
        if (blockDepth != 0) {
            return Parsed.failure("Sequence blocks must be closed with 'end'.");
        }
        if (participants.isEmpty() || messages == 0) {
            return Parsed.failure("Sequence diagrams need at least one participant and one message.");
        }
        return Parsed.success(participants, messages);
    }

    private static Parsed parseState(String[] lines) {
        Set<String> states = new LinkedHashSet<>();
        int transitions = 0;
        boolean initialSeen = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            Matcher alias = STATE_ALIAS.matcher(line);
            if (alias.matches()) {
                states.add(alias.group(1));
                continue;
            }
            Matcher transition = STATE_TRANSITION.matcher(line);
            if (transition.matches()) {
                if ("[*]".equals(transition.group(1))) {
                    initialSeen = true;
                } else {
                    states.add(transition.group(1));
                }
                if (!"[*]".equals(transition.group(2))) {
                    states.add(transition.group(2));
                }
                transitions++;
                continue;
            }
            Matcher description = STATE_DESCRIPTION.matcher(line);
            if (description.matches()) {
                states.add(description.group(1));
                continue;
            }
            return unsupportedLine("state", index);
        }
        if (states.isEmpty() || transitions == 0 || !initialSeen) {
            return Parsed.failure("State diagrams need an initial [*] transition and at least one state.");
        }
        return Parsed.success(states, transitions);
    }

    private static Parsed parseClass(String[] lines) {
        Set<String> classes = new LinkedHashSet<>();
        int relations = 0;
        String openClass = null;
        int openMembers = 0;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            if (openClass != null) {
                if (CLASS_BLOCK_END.matcher(line).matches()) {
                    openClass = null;
                    continue;
                }
                if (CLASS_MEMBER.matcher(line).matches()) {
                    if (++openMembers > MAX_CLASS_MEMBERS) {
                        return Parsed.failure("Class diagrams may declare at most "
                            + MAX_CLASS_MEMBERS + " members per class.");
                    }
                    continue;
                }
                return unsupportedLine("class", index);
            }
            Matcher declaration = CLASS_DECLARATION.matcher(line);
            if (declaration.matches()) {
                classes.add(declaration.group(1));
                if (declaration.group(2) != null) {
                    openClass = declaration.group(1);
                    openMembers = 0;
                }
                continue;
            }
            Matcher relation = CLASS_RELATION.matcher(line);
            if (relation.matches()) {
                classes.add(relation.group(1));
                classes.add(relation.group(3));
                relations++;
                continue;
            }
            return unsupportedLine("class", index);
        }
        if (openClass != null) {
            return Parsed.failure("Class blocks must be closed with '}'.");
        }
        if (classes.isEmpty()) {
            return Parsed.failure("Class diagrams need at least one class.");
        }
        return Parsed.success(classes, relations);
    }

    private static Parsed parseEr(String[] lines) {
        Set<String> entities = new LinkedHashSet<>();
        int relations = 0;
        int attributes = 0;
        boolean inEntity = false;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (line.isBlank()) {
                continue;
            }
            if (inEntity) {
                if (ER_BLOCK_END.matcher(line).matches()) {
                    inEntity = false;
                    continue;
                }
                if (ER_ATTRIBUTE.matcher(line).matches()) {
                    attributes++;
                    continue;
                }
                return unsupportedLine("er", index);
            }
            Matcher relation = ER_RELATION.matcher(line);
            if (relation.matches()) {
                entities.add(relation.group(1));
                entities.add(relation.group(2));
                relations++;
                continue;
            }
            Matcher entityStart = ER_ENTITY_START.matcher(line);
            if (entityStart.matches()) {
                entities.add(entityStart.group(1));
                inEntity = true;
                continue;
            }
            return unsupportedLine("er", index);
        }
        if (inEntity) {
            return Parsed.failure("ER entity blocks must be closed with '}'.");
        }
        if (entities.isEmpty()) {
            return Parsed.failure("ER diagrams need at least one entity.");
        }
        return new Parsed(SnippetDiagramSupport.MermaidValidation.commonSuccess(),
            Set.copyOf(entities), relations, attributes);
    }

    private static String validateCaps(SnippetDiagramType type, Parsed parsed) {
        if (!parsed.validation().valid()) {
            return parsed.validation().message();
        }
        return switch (type) {
            case SEQUENCE -> {
                if (parsed.elementIds().size() > MAX_SEQUENCE_PARTICIPANTS) {
                    yield "Generated sequence diagrams may declare at most "
                        + MAX_SEQUENCE_PARTICIPANTS + " participants.";
                }
                yield parsed.edgeCount() > MAX_SEQUENCE_MESSAGES
                    ? "Generated sequence diagrams may use at most " + MAX_SEQUENCE_MESSAGES + " messages."
                    : null;
            }
            case STATE -> parsed.elementIds().size() > MAX_STATES
                ? "Generated state diagrams may declare at most " + MAX_STATES + " states."
                : null;
            case CLASS -> parsed.elementIds().size() > MAX_CLASSES
                ? "Generated class diagrams may declare at most " + MAX_CLASSES + " classes."
                : null;
            case ER -> {
                if (parsed.elementIds().size() > MAX_ER_ENTITIES) {
                    yield "Generated ER diagrams may declare at most " + MAX_ER_ENTITIES + " entities.";
                }
                yield parsed.attributeCount() > MAX_ER_ATTRIBUTES
                    ? "Generated ER diagrams may declare at most " + MAX_ER_ATTRIBUTES + " attributes."
                    : null;
            }
            case LOGICAL_STRUCTURE -> null;
        };
    }

    private static Parsed unsupportedLine(String family, int lineIndex) {
        return Parsed.failure("Unsupported " + family + " diagram syntax on line " + (lineIndex + 2) + ".");
    }

    private static SnippetDiagramSupport.MermaidValidation failure(String message) {
        return new SnippetDiagramSupport.MermaidValidation(false, "", message);
    }

    private record Parsed(
        SnippetDiagramSupport.MermaidValidation validation,
        Set<String> elementIds,
        int edgeCount,
        int attributeCount) {

        private static Parsed success(Set<String> elementIds, int edgeCount) {
            return new Parsed(SnippetDiagramSupport.MermaidValidation.commonSuccess(),
                Set.copyOf(elementIds), edgeCount, 0);
        }

        private static Parsed failure(String message) {
            return new Parsed(new SnippetDiagramSupport.MermaidValidation(false, "", message),
                Set.of(), 0, 0);
        }
    }
}
