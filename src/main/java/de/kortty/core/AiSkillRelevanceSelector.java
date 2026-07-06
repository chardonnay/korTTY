package de.kortty.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Selects only those enabled AI skills that match the current request.
 */
public final class AiSkillRelevanceSelector {

    private static final int LOCAL_MATCH_THRESHOLD = 4;
    private static final Pattern WORD_SPLIT = Pattern.compile("[^\\p{L}\\p{N}#+._-]+");
    private static final Set<String> STOP_WORDS = Set.of(
        "about", "after", "also", "and", "are", "ask", "but", "can", "code", "das", "der", "die", "ein", "eine",
        "for", "from", "how", "ich", "ist", "mit", "not", "oder", "please", "prompt", "soll", "the", "this",
        "und", "use", "was", "welche", "what", "when", "where", "wie", "with", "you", "zur");

    private final boolean enabled;
    private final boolean autoDetectionEnabled;
    private final List<AiSkill> skills;
    private final Set<String> pinnedSkillIds;

    public AiSkillRelevanceSelector(boolean enabled, boolean autoDetectionEnabled, List<AiSkill> skills) {
        this(enabled, autoDetectionEnabled, skills, Set.of());
    }

    /**
     * @param pinnedSkillIds ids of skills that are always part of the selection (when applicable),
     *                       bypassing the relevance auto-detection — e.g. skills assigned to the
     *                       active connection.
     */
    public AiSkillRelevanceSelector(
        boolean enabled,
        boolean autoDetectionEnabled,
        List<AiSkill> skills,
        Set<String> pinnedSkillIds) {
        this.enabled = enabled;
        this.autoDetectionEnabled = autoDetectionEnabled;
        this.skills = copySkills(skills);
        this.pinnedSkillIds = pinnedSkillIds != null ? Set.copyOf(pinnedSkillIds) : Set.of();
    }

    public List<AiSkill> selectChatSkills(AiRequest request, AiSkillRelevanceClassifier classifier) {
        return select(new SelectionContext(true, request, null, null), classifier);
    }

    public List<AiSkill> selectChatSkillsLocal(AiRequest request) {
        return select(new SelectionContext(true, request, null, null), null);
    }

    public List<AiSkill> selectAgentSkills(String systemPrompt, String userPrompt, AiSkillRelevanceClassifier classifier) {
        return select(new SelectionContext(false, null, systemPrompt, userPrompt), classifier);
    }

    public List<AiSkill> selectAgentSkillsLocal(String systemPrompt, String userPrompt) {
        return select(new SelectionContext(false, null, systemPrompt, userPrompt), null);
    }

    public List<AiSkill> select(SelectionContext context, AiSkillRelevanceClassifier classifier) {
        if (!enabled) {
            return List.of();
        }
        List<AiSkill> candidates = applicableSkills(context);
        List<AiSkill> pinned = pinnedSkills();
        if (candidates.isEmpty()) {
            // Forced/pinned skills apply even when nothing else is relevant for this target.
            return pinned;
        }
        if (!autoDetectionEnabled) {
            return withPinnedSkills(candidates, pinned);
        }

        LocalSelection localSelection = selectLocal(context, candidates);
        if (!shouldUseHybrid(localSelection) || classifier == null) {
            return withPinnedSkills(localSelection.skills(), pinned);
        }

        try {
            List<String> selectedIds = classifier.classify(context, metadataFor(candidates));
            List<AiSkill> hybridSelection = byClassifierIds(candidates, selectedIds);
            if (!hybridSelection.isEmpty()) {
                return withPinnedSkills(hybridSelection, pinned);
            }
        } catch (Exception ignored) {
            // Hybrid classification must not block the main AI request.
        }
        return withPinnedSkills(localSelection.skills(), pinned);
    }

    /**
     * Pinned skills always survive auto-detection AND bypass the chat/agent target filter — e.g. skills
     * assigned to the active connection, or skills the user explicitly forced for a snippet AI run.
     */
    private List<AiSkill> pinnedSkills() {
        if (pinnedSkillIds.isEmpty()) {
            return List.of();
        }
        List<AiSkill> result = new ArrayList<>();
        for (AiSkill skill : skills) {
            if (skill != null
                && skill.isEnabled()
                && skill.getId() != null
                && pinnedSkillIds.contains(skill.getId())
                && skill.getContent() != null
                && !skill.getContent().isBlank()) {
                result.add(skill);
            }
        }
        return List.copyOf(result);
    }

    private static List<AiSkill> withPinnedSkills(List<AiSkill> selected, List<AiSkill> pinned) {
        if (pinned.isEmpty()) {
            return List.copyOf(selected);
        }
        List<AiSkill> result = new ArrayList<>(selected);
        for (AiSkill skill : pinned) {
            if (result.stream().noneMatch(existing -> skill.getId().equals(existing.getId()))) {
                result.add(skill);
            }
        }
        return List.copyOf(result);
    }

    public List<SkillMetadata> metadataForSelection(boolean chatTarget) {
        return metadataFor(applicableSkills(new SelectionContext(chatTarget, null, null, null)));
    }

    public static String classificationSystemPrompt() {
        return "Select relevant KorTTY AI skill IDs for the current request. "
            + "Use only the provided skill metadata and request context. "
            + "Return exactly one JSON object with key skillIds containing an array of strings. "
            + "Return an empty array when no skill is clearly relevant.";
    }

    public static String buildClassificationUserPrompt(SelectionContext context, List<SkillMetadata> skills) {
        JsonObject root = new JsonObject();
        root.addProperty("target", context != null && context.chatTarget() ? "CHAT" : "AGENT");
        root.addProperty("requestContext", context != null ? context.relevanceText() : "");
        JsonArray array = new JsonArray();
        for (SkillMetadata skill : skills != null ? skills : List.<SkillMetadata>of()) {
            JsonObject item = new JsonObject();
            item.addProperty("id", skill.id());
            item.addProperty("name", skill.name());
            item.addProperty("description", skill.description());
            JsonArray tags = new JsonArray();
            for (String tag : skill.tags()) {
                tags.add(tag);
            }
            item.add("tags", tags);
            item.addProperty("target", skill.target());
            array.add(item);
        }
        root.add("skills", array);
        return root.toString();
    }

    public static List<String> parseClassifierResponse(String content) {
        String candidate = extractJson(content);
        if (candidate == null) {
            return List.of();
        }
        try {
            JsonObject root = JsonParser.parseString(candidate).getAsJsonObject();
            JsonArray ids = root.getAsJsonArray("skillIds");
            if (ids == null) {
                ids = root.getAsJsonArray("skill_ids");
            }
            if (ids == null) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (JsonElement element : ids) {
                if (element != null && element.isJsonPrimitive()) {
                    String id = element.getAsString();
                    if (id != null && !id.isBlank()) {
                        result.add(id.trim());
                    }
                }
            }
            return result;
        } catch (Exception ignored) {
            return List.of();
        }
    }

    private List<AiSkill> applicableSkills(SelectionContext context) {
        if (!enabled || skills.isEmpty()) {
            return List.of();
        }
        boolean chatTarget = context == null || context.chatTarget();
        List<AiSkill> applicable = new ArrayList<>();
        for (AiSkill skill : skills) {
            if (isApplicable(skill, chatTarget)) {
                applicable.add(skill);
            }
        }
        return List.copyOf(applicable);
    }

    private LocalSelection selectLocal(SelectionContext context, List<AiSkill> candidates) {
        String relevanceText = normalize(context != null ? context.relevanceText() : "");
        Set<String> contextTerms = terms(relevanceText);
        List<AiSkill> selected = new ArrayList<>();
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (AiSkill skill : candidates) {
            int score = score(skill, relevanceText, contextTerms);
            if (score >= LOCAL_MATCH_THRESHOLD) {
                selected.add(skill);
                scores.put(skill.getId(), score);
            }
        }
        return new LocalSelection(List.copyOf(selected), scores);
    }

    private boolean shouldUseHybrid(LocalSelection selection) {
        if (selection.skills().isEmpty()) {
            return true;
        }
        if (selection.skills().size() < 2) {
            return false;
        }
        int high = 0;
        int low = Integer.MAX_VALUE;
        for (Integer score : selection.scores().values()) {
            high = Math.max(high, score);
            low = Math.min(low, score);
        }
        return high - low <= 2;
    }

    private int score(AiSkill skill, String relevanceText, Set<String> contextTerms) {
        int score = 0;
        score += scoreTerms(skill.getTags(), relevanceText, contextTerms, 6, 4);
        score += scoreText(skill.getName(), relevanceText, contextTerms, 6, 2);
        score += scoreText(skill.getDescription(), relevanceText, contextTerms, 5, 1);
        score += scoreTerms(markdownHeadings(skill.getContent()), relevanceText, contextTerms, 4, 1);
        return score;
    }

    private int scoreText(String text, String relevanceText, Set<String> contextTerms, int phraseWeight, int termWeight) {
        String normalized = normalize(text);
        if (normalized.isBlank()) {
            return 0;
        }
        int score = relevanceText.contains(normalized) ? phraseWeight : 0;
        for (String term : terms(normalized)) {
            if (contextTerms.contains(term)) {
                score += termWeight;
            }
        }
        return score;
    }

    private int scoreTerms(List<String> values, String relevanceText, Set<String> contextTerms, int phraseWeight, int termWeight) {
        int score = 0;
        for (String value : values != null ? values : List.<String>of()) {
            String normalized = normalize(value);
            if (normalized.isBlank()) {
                continue;
            }
            if (relevanceText.contains(normalized)) {
                score += phraseWeight;
            }
            for (String term : terms(normalized)) {
                if (contextTerms.contains(term)) {
                    score += termWeight;
                }
            }
        }
        return score;
    }

    private List<String> markdownHeadings(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> headings = new ArrayList<>();
        for (String line : content.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("#")) {
                String heading = trimmed.replaceFirst("^#+\\s*", "").trim();
                if (!heading.isBlank()) {
                    headings.add(heading);
                }
            }
        }
        return headings;
    }

    private List<SkillMetadata> metadataFor(List<AiSkill> candidates) {
        List<SkillMetadata> metadata = new ArrayList<>();
        for (AiSkill skill : candidates != null ? candidates : List.<AiSkill>of()) {
            metadata.add(new SkillMetadata(
                skill.getId(),
                nonBlank(skill.getName(), "AI Skill"),
                nonBlank(skill.getDescription(), ""),
                List.copyOf(skill.getTags()),
                skill.getTarget().name()));
        }
        return List.copyOf(metadata);
    }

    private List<AiSkill> byClassifierIds(List<AiSkill> candidates, List<String> selectedIds) {
        if (selectedIds == null || selectedIds.isEmpty()) {
            return List.of();
        }
        Set<String> allowed = new HashSet<>();
        for (String id : selectedIds) {
            if (id != null && !id.isBlank()) {
                allowed.add(id.trim());
            }
        }
        List<AiSkill> selected = new ArrayList<>();
        for (AiSkill skill : candidates) {
            if (allowed.contains(skill.getId())) {
                selected.add(skill);
            }
        }
        return List.copyOf(selected);
    }

    private boolean isApplicable(AiSkill skill, boolean chatTarget) {
        if (skill == null || !skill.isEnabled()) {
            return false;
        }
        String content = skill.getContent();
        if (content == null || content.isBlank()) {
            return false;
        }
        AiSkillTarget target = skill.getTarget();
        if (target == null) {
            return false;
        }
        return chatTarget ? target.appliesToChat() : target.appliesToAgent();
    }

    private static List<AiSkill> copySkills(List<AiSkill> source) {
        if (source == null || source.isEmpty()) {
            return List.of();
        }
        List<AiSkill> copy = new ArrayList<>();
        for (AiSkill skill : source) {
            if (skill != null) {
                copy.add(new AiSkill(skill));
            }
        }
        return List.copyOf(copy);
    }

    private Set<String> terms(String text) {
        Set<String> result = new HashSet<>();
        for (String raw : WORD_SPLIT.split(normalize(text))) {
            String term = raw.trim();
            if (isUsefulTerm(term)) {
                result.add(term);
            }
        }
        return result;
    }

    private boolean isUsefulTerm(String term) {
        if (term == null || term.length() < 3) {
            return false;
        }
        return !STOP_WORDS.contains(term);
    }

    private static String normalize(String value) {
        return value != null ? value.toLowerCase(Locale.ROOT).replace('_', '-').trim() : "";
    }

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private static String extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        int firstBrace = content.indexOf('{');
        int lastBrace = content.lastIndexOf('}');
        if (firstBrace >= 0 && lastBrace > firstBrace) {
            return content.substring(firstBrace, lastBrace + 1);
        }
        return null;
    }

    public record SelectionContext(boolean chatTarget, AiRequest request, String systemPrompt, String userPrompt) {
        String relevanceText() {
            StringBuilder builder = new StringBuilder();
            if (request != null) {
                append(builder, request.action() != null ? request.action().name() : null);
                append(builder, request.userPrompt());
                append(builder, request.selectedText());
                append(builder, request.connectionDisplayName());
                append(builder, request.conversationContext());
            }
            append(builder, systemPrompt);
            append(builder, userPrompt);
            return builder.toString().trim();
        }

        private static void append(StringBuilder builder, String value) {
            if (value != null && !value.isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append('\n');
                }
                builder.append(value);
            }
        }
    }

    public record SkillMetadata(String id, String name, String description, List<String> tags, String target) {
    }

    private record LocalSelection(List<AiSkill> skills, Map<String, Integer> scores) {
    }
}
