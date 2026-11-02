package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiSkillOrigin;
import de.kortty.model.AiSkillTarget;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Imports and exports KorTTY AI skills as Markdown files with simple front matter.
 */
public final class AiSkillMarkdownCodec {

    private static final String MARKER_KEY = "kortty-ai-skill";
    private static final String FORMAT_VERSION = "1";
    private static final String BUILTIN_ID_KEY = "kortty-builtin-id";
    private static final String BUILTIN_VERSION_KEY = "kortty-builtin-version";
    private static final String BUILTIN_TOPICS_KEY = "kortty-builtin-topics";
    private static final String ORIGIN_KEY = "kortty-origin";
    private static final String CREATED_AT_KEY = "kortty-created-at";
    private static final String LEARNED_RULES_START = "<!-- kortty:learned-rules -->";
    private static final String LEARNED_RULES_END = "<!-- /kortty:learned-rules -->";
    private static final java.util.regex.Pattern BUILTIN_ID_PATTERN =
        java.util.regex.Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    /** A skill parsed from a bundled resource, together with its shipped delivery version. */
    public record BundledAiSkill(AiSkill skill, int version) {
    }

    private AiSkillMarkdownCodec() {
    }

    /**
     * Trusted load path for skills bundled with the application. Unlike
     * {@link #importFromMarkdown(Path)} the authored {@code enabled}/{@code target} values are
     * honored and the {@code kortty-builtin-*} keys are required. User imports never reach this
     * method, so a builtin id cannot be hijacked via the import dialog.
     */
    public static BundledAiSkill loadBundled(String resourceName, String markdown) throws IOException {
        ParsedMarkdown parsed = parse(markdown);
        Map<String, String> frontMatter = parsed.frontMatter();
        if (!FORMAT_VERSION.equals(frontMatter.get(MARKER_KEY))) {
            throw new IOException("Bundled AI skill " + resourceName + " is missing the "
                + MARKER_KEY + ": " + FORMAT_VERSION + " marker.");
        }
        String builtinId = nonBlank(frontMatter.get(BUILTIN_ID_KEY), "");
        if (!BUILTIN_ID_PATTERN.matcher(builtinId).matches()) {
            throw new IOException("Bundled AI skill " + resourceName + " has an invalid "
                + BUILTIN_ID_KEY + ": " + builtinId);
        }
        int version = parseBuiltinVersion(resourceName, frontMatter.get(BUILTIN_VERSION_KEY));
        AiSkill skill = new AiSkill();
        skill.setName(nonBlank(frontMatter.get("name"), builtinId));
        skill.setDescription(frontMatter.get("description"));
        skill.setTags(parseTags(frontMatter.get("tags")));
        skill.setEnabled(parseEnabled(frontMatter.get("enabled")));
        skill.setTarget(parseTarget(frontMatter.get("target")));
        skill.setContent(parsed.body());
        skill.setBuiltinId(builtinId);
        skill.setBuiltinTopics(parseTags(frontMatter.get(BUILTIN_TOPICS_KEY)));
        return new BundledAiSkill(skill, version);
    }

    private static int parseBuiltinVersion(String resourceName, String rawVersion) throws IOException {
        String value = nonBlank(rawVersion, "1");
        try {
            int version = Integer.parseInt(value);
            if (version < 1) {
                throw new NumberFormatException("must be >= 1");
            }
            return version;
        } catch (NumberFormatException e) {
            throw new IOException("Bundled AI skill " + resourceName + " has an invalid "
                + BUILTIN_VERSION_KEY + ": " + rawVersion, e);
        }
    }

    /**
     * Reads a skill from a Markdown file the user picked. Characters {@code global-settings.xml} cannot
     * hold — a control character such as BEL, U+FFFE, U+FFFF or a lone surrogate — are removed from the
     * name, description, tags and content: they are invisible, and kept they would be refused when the
     * skills are saved. A skill holding a possible secret is refused with an {@link IOException} instead.
     */
    public static AiSkill importFromMarkdown(Path file) throws IOException {
        if (file == null) {
            throw new IOException("No AI skill Markdown file selected.");
        }
        return importFromMarkdownText(Files.readString(file, StandardCharsets.UTF_8), nameFromFile(file));
    }

    /**
     * {@link #importFromMarkdown(Path)} for text that did not come from a file, such as a SKILL.md
     * downloaded from an external provider.
     *
     * @param fallbackName the skill name when the front matter has none
     */
    public static AiSkill importFromMarkdownText(String text, String fallbackName) throws IOException {
        String markdown = text != null ? text : "";
        String defaultName = nonBlank(fallbackName, "AI Skill");
        ParsedMarkdown parsed = parse(markdown);
        AiSkill skill = new AiSkill();
        if (parsed.frontMatter().isEmpty()) {
            skill.setName(defaultName);
            skill.setEnabled(false);
            skill.setTarget(AiSkillTarget.BOTH);
            skill.setContent(markdown);
            return storable(skill);
        }

        Map<String, String> frontMatter = parsed.frontMatter();
        if (!FORMAT_VERSION.equals(frontMatter.get(MARKER_KEY))) {
            skill.setName(nonBlank(frontMatter.get("name"), defaultName));
            skill.setDescription(frontMatter.get("description"));
            skill.setTags(parseTags(frontMatter.get("tags")));
            skill.setEnabled(false);
            skill.setTarget(AiSkillTarget.BOTH);
            skill.setContent(parsed.body());
            return storable(skill);
        }
        skill.setName(nonBlank(frontMatter.get("name"), defaultName));
        skill.setDescription(frontMatter.get("description"));
        skill.setTags(parseTags(frontMatter.get("tags")));
        skill.setEnabled(parseEnabled(frontMatter.get("enabled")));
        skill.setTarget(parseTarget(frontMatter.get("target")));
        LearnedSection learned = splitLearnedRules(parsed.body());
        skill.setContent(learned.content());
        skill.setLearnedRules(learned.rules());
        applyProvenance(skill, frontMatter);
        return storable(skill);
    }

    /**
     * {@link #importFromMarkdownText(String, String)} for a document a provider served. Such a document cannot
     * claim to be korTTY's, and it cannot add rules: the rules of its learned-rules block are dropped, so only
     * the user can confirm a rule.
     */
    public static AiSkill importFromExternalMarkdownText(String text, String fallbackName) throws IOException {
        AiSkill skill = importFromMarkdownText(text, fallbackName);
        skill.setLearnedRules(List.of());
        skill.setOrigin(null);
        skill.setOriginAcknowledged(false);
        skill.setCreatedAt(null);
        return skill;
    }

    /**
     * Restores korTTY's provenance from an exported file. The user chose to import the skill, so it
     * counts as seen, and its rules too.
     */
    private static void applyProvenance(AiSkill skill, Map<String, String> frontMatter) {
        if (AiSkillOrigin.KORTTY.name().equalsIgnoreCase(frontMatter.get(ORIGIN_KEY))) {
            skill.setOrigin(AiSkillOrigin.KORTTY);
            skill.setOriginAcknowledged(true);
        }
        skill.setCreatedAt(parseCreatedAt(frontMatter.get(CREATED_AT_KEY)));
    }

    private static String parseCreatedAt(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate.trim()).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /**
     * Splits an exported body into its content and the rules after it. The rules section is the last
     * marked block; the separator written before it is removed again, so the content comes back exactly.
     */
    private static LearnedSection splitLearnedRules(String body) {
        int start = body.lastIndexOf(LEARNED_RULES_START);
        int end = start < 0 ? -1 : body.indexOf(LEARNED_RULES_END, start + LEARNED_RULES_START.length());
        if (end < 0) {
            return new LearnedSection(body, List.of());
        }
        String before = body.substring(0, start);
        if (before.endsWith("\n\n")) {
            before = before.substring(0, before.length() - 2);
        }
        String after = body.substring(end + LEARNED_RULES_END.length());
        if (after.equals("\n") || after.equals("\r\n")) {
            after = "";
        }
        List<AiSkillLearnedRule> rules = new ArrayList<>();
        for (String line : body.substring(start + LEARNED_RULES_START.length(), end).split("\\R")) {
            String trimmed = line.strip();
            String text = trimmed.startsWith("- ") ? trimmed.substring(2).strip() : "";
            if (!text.isEmpty()) {
                AiSkillLearnedRule rule = AiSkillLearnedRule.create(text, AiSkillLearnedRule.SOURCE_MANUAL);
                rule.setAcknowledged(true);
                rules.add(rule);
            }
        }
        return new LearnedSection(before + after, rules);
    }

    /**
     * The skill without characters the settings file cannot hold. A skill that holds a possible secret is refused:
     * the message names the line and the kind of secret, never the value.
     */
    private static AiSkill storable(AiSkill skill) throws IOException {
        skill.stripUnstorableText();
        Optional<String> refusal = AiSkillSecretCheck.refusal(skill);
        if (refusal.isPresent()) {
            throw new AiSkillSecretCheck.RefusedException(refusal.get());
        }
        return skill;
    }

    public static void exportToMarkdown(Path file, AiSkill skill) throws IOException {
        if (file == null) {
            throw new IOException("No export target selected.");
        }
        if (skill == null) {
            throw new IOException("No AI skill selected.");
        }
        StringBuilder markdown = new StringBuilder();
        markdown.append("---\n");
        markdown.append(MARKER_KEY).append(": ").append(FORMAT_VERSION).append("\n");
        if (skill.isBuiltin()) {
            // Informational provenance only: importFromMarkdown ignores these keys, so a
            // re-imported built-in cleanly degrades to an independent user skill.
            markdown.append(BUILTIN_ID_KEY).append(": ").append(skill.getBuiltinId()).append("\n");
            if (skill.getBuiltinBaseline() != null) {
                markdown.append(BUILTIN_VERSION_KEY).append(": ")
                    .append(skill.getBuiltinBaseline().getVersion()).append("\n");
            }
        }
        if (skill.getOrigin() != null) {
            markdown.append(ORIGIN_KEY).append(": ").append(skill.getOrigin().name()).append("\n");
            String createdAt = parseCreatedAt(skill.getCreatedAt());
            if (createdAt != null) {
                markdown.append(CREATED_AT_KEY).append(": ").append(createdAt).append("\n");
            }
        }
        markdown.append("name: ").append(quoteFrontMatter(nonBlank(skill.getName(), "AI Skill"))).append("\n");
        appendOptionalFrontMatter(markdown, "description", skill.getDescription());
        if (!skill.getTags().isEmpty()) {
            markdown.append("tags: ").append(formatTags(skill.getTags())).append("\n");
        }
        markdown.append("enabled: ").append(skill.isEnabled()).append("\n");
        markdown.append("target: ").append(skill.getTarget().name()).append("\n");
        markdown.append("---\n\n");
        markdown.append(skill.getContent() != null ? skill.getContent() : "");
        appendLearnedRules(markdown, skill.getLearnedRules());
        Files.writeString(file, markdown.toString(), StandardCharsets.UTF_8);
    }

    /** Appends the rules as one "- " line each in a marked block; nothing is written for no rules. */
    private static void appendLearnedRules(StringBuilder markdown, List<AiSkillLearnedRule> rules) {
        boolean any = false;
        for (AiSkillLearnedRule rule : rules) {
            if (rule.getText() != null && !rule.getText().isBlank()) {
                any = true;
            }
        }
        if (!any) {
            return;
        }
        markdown.append("\n\n").append(LEARNED_RULES_START).append("\n");
        for (AiSkillLearnedRule rule : rules) {
            if (rule.getText() != null && !rule.getText().isBlank()) {
                markdown.append("- ").append(rule.getText().strip().replaceAll("\\s*\\R\\s*", " ")).append("\n");
            }
        }
        markdown.append(LEARNED_RULES_END).append("\n");
    }

    private static ParsedMarkdown parse(String text) {
        String content = text != null ? text : "";
        if (!content.startsWith("---\n") && !content.startsWith("---\r\n")) {
            return new ParsedMarkdown(Map.of(), content);
        }
        int firstLineEnd = content.indexOf('\n');
        int searchFrom = firstLineEnd >= 0 ? firstLineEnd + 1 : 0;
        int frontMatterEnd = findFrontMatterEnd(content, searchFrom);
        if (frontMatterEnd < 0) {
            return new ParsedMarkdown(Map.of(), content);
        }
        String frontMatterText = content.substring(searchFrom, frontMatterEnd);
        int bodyStart = frontMatterEnd + 3;
        if (bodyStart < content.length() && content.charAt(bodyStart) == '\r') {
            bodyStart++;
        }
        if (bodyStart < content.length() && content.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        if (bodyStart < content.length() && content.charAt(bodyStart) == '\r') {
            bodyStart++;
        }
        if (bodyStart < content.length() && content.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        return new ParsedMarkdown(parseFrontMatter(frontMatterText), content.substring(bodyStart));
    }

    private static int findFrontMatterEnd(String content, int searchFrom) {
        int index = searchFrom;
        while (index >= 0 && index < content.length()) {
            int lineEnd = content.indexOf('\n', index);
            String line = lineEnd >= 0 ? content.substring(index, lineEnd) : content.substring(index);
            if ("---".equals(line.strip())) {
                return index;
            }
            if (lineEnd < 0) {
                return -1;
            }
            index = lineEnd + 1;
        }
        return -1;
    }

    private static Map<String, String> parseFrontMatter(String text) {
        Map<String, String> values = new LinkedHashMap<>();
        String[] lines = text.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon <= 0) {
                continue;
            }
            String key = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (">".equals(value) || "|".equals(value)) {
                BlockValue block = parseBlockValue(lines, i + 1, ">".equals(value));
                values.put(key, block.value());
                i = block.lastLineIndex();
                continue;
            }
            values.put(key, unquoteFrontMatter(value));
        }
        return values;
    }

    private static BlockValue parseBlockValue(String[] lines, int startIndex, boolean folded) {
        StringBuilder builder = new StringBuilder();
        int last = startIndex - 1;
        for (int i = startIndex; i < lines.length; i++) {
            String line = lines[i];
            if (!line.isBlank() && !Character.isWhitespace(line.charAt(0))) {
                break;
            }
            String trimmed = line.strip();
            if (!trimmed.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(folded ? ' ' : '\n');
                }
                builder.append(trimmed);
            }
            last = i;
        }
        return new BlockValue(builder.toString(), last);
    }

    private static boolean parseEnabled(String rawEnabled) throws IOException {
        String value = nonBlank(rawEnabled, "false").toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return true;
        }
        if ("false".equals(value)) {
            return false;
        }
        throw new IOException("Invalid AI skill enabled value: " + rawEnabled);
    }

    private static AiSkillTarget parseTarget(String rawTarget) throws IOException {
        String value = nonBlank(rawTarget, AiSkillTarget.BOTH.name()).toUpperCase(Locale.ROOT);
        try {
            return AiSkillTarget.valueOf(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid AI skill target: " + rawTarget, e);
        }
    }

    private static List<String> parseTags(String rawTags) {
        if (rawTags == null || rawTags.isBlank()) {
            return List.of();
        }
        String value = rawTags.trim();
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        List<String> tags = new ArrayList<>();
        for (String raw : value.split(",")) {
            String tag = unquoteFrontMatter(raw.trim());
            if (!tag.isBlank()) {
                tags.add(tag);
            }
        }
        return tags;
    }

    private static void appendOptionalFrontMatter(StringBuilder markdown, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        String normalized = value.trim();
        if (normalized.contains("\n") || normalized.length() > 100) {
            markdown.append(key).append(": >\n");
            for (String line : normalized.split("\\R")) {
                markdown.append("  ").append(line.trim()).append("\n");
            }
            return;
        }
        markdown.append(key).append(": ").append(quoteFrontMatter(normalized)).append("\n");
    }

    private static String formatTags(List<String> tags) {
        List<String> quoted = new ArrayList<>();
        for (String tag : tags != null ? tags : List.<String>of()) {
            String trimmed = tag != null ? tag.trim() : "";
            if (!trimmed.isBlank()) {
                quoted.add(quoteFrontMatter(trimmed));
            }
        }
        return "[" + String.join(", ", quoted) + "]";
    }

    private static String nameFromFile(Path file) {
        String name = file.getFileName() != null ? file.getFileName().toString() : "AI Skill";
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".markdown")) {
            return name.substring(0, name.length() - ".markdown".length());
        }
        if (lower.endsWith(".md")) {
            return name.substring(0, name.length() - ".md".length());
        }
        return name;
    }

    private static String quoteFrontMatter(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String unquoteFrontMatter(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            String inner = value.substring(1, value.length() - 1);
            return inner.replace("\\\"", "\"").replace("\\\\", "\\");
        }
        return value;
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private record ParsedMarkdown(Map<String, String> frontMatter, String body) {
    }

    private record LearnedSection(String content, List<AiSkillLearnedRule> rules) {
    }

    private record BlockValue(String value, int lastLineIndex) {
    }
}
