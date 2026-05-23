package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.GlobalSettings;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Adds enabled user-defined AI skills to system prompts.
 */
public final class AiSkillPromptSupport {

    private static final AiSkillPromptSupport DISABLED = new AiSkillPromptSupport(false, false, List.of());

    private final boolean enabled;
    private final boolean autoDetectionEnabled;
    private final List<AiSkill> skills;
    private final AiSkillRelevanceSelector selector;
    private final List<SkillUsage> recentUsages = Collections.synchronizedList(new ArrayList<>());

    public record SkillUsage(String id, String name, AiSkillTarget target) {
    }

    public AiSkillPromptSupport(boolean enabled, List<AiSkill> skills) {
        this(enabled, false, skills);
    }

    public AiSkillPromptSupport(boolean enabled, boolean autoDetectionEnabled, List<AiSkill> skills) {
        this.enabled = enabled;
        this.autoDetectionEnabled = autoDetectionEnabled;
        this.skills = copySkills(skills);
        this.selector = new AiSkillRelevanceSelector(enabled, autoDetectionEnabled, this.skills);
    }

    public static AiSkillPromptSupport disabled() {
        return DISABLED;
    }

    public static AiSkillPromptSupport fromSettings(GlobalSettings settings) {
        if (settings == null) {
            return disabled();
        }
        return new AiSkillPromptSupport(
            settings.isAiSkillsEnabled(),
            settings.isAiSkillAutoDetectionEnabled(),
            settings.getAiSkills());
    }

    public String appendChatSkills(String systemPrompt) {
        return appendSkills(systemPrompt, selector.selectChatSkillsLocal(null));
    }

    public String appendChatSkills(String systemPrompt, AiRequest request) {
        if (!includeChatSkills(request)) {
            return normalizedPrompt(systemPrompt);
        }
        return appendSkills(systemPrompt, selector.selectChatSkillsLocal(request));
    }

    public String appendChatSkills(String systemPrompt, AiRequest request, AiSkillRelevanceClassifier classifier) {
        if (!includeChatSkills(request)) {
            return normalizedPrompt(systemPrompt);
        }
        return appendSkills(systemPrompt, selector.selectChatSkills(request, classifier));
    }

    public String appendAgentSkills(String systemPrompt) {
        return appendSkills(systemPrompt, selector.selectAgentSkillsLocal(systemPrompt, null));
    }

    public String appendAgentSkills(String systemPrompt, String userPrompt) {
        return appendSkills(systemPrompt, selector.selectAgentSkillsLocal(systemPrompt, userPrompt));
    }

    public String appendAgentSkills(String systemPrompt, String userPrompt, AiSkillRelevanceClassifier classifier) {
        return appendSkills(systemPrompt, selector.selectAgentSkills(systemPrompt, userPrompt, classifier));
    }

    public String buildChatSkillBlock() {
        return buildSkillBlock(selector.selectChatSkillsLocal(null), false);
    }

    public String buildChatSkillBlock(AiRequest request) {
        if (!includeChatSkills(request)) {
            return "";
        }
        return buildSkillBlock(selector.selectChatSkillsLocal(request), false);
    }

    public String buildAgentSkillBlock() {
        return buildSkillBlock(selector.selectAgentSkillsLocal(null, null), false);
    }

    public String buildAgentSkillBlock(String systemPrompt, String userPrompt) {
        return buildSkillBlock(selector.selectAgentSkillsLocal(systemPrompt, userPrompt), false);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isAutoDetectionEnabled() {
        return autoDetectionEnabled;
    }

    public List<SkillUsage> drainSkillUsages() {
        synchronized (recentUsages) {
            if (recentUsages.isEmpty()) {
                return List.of();
            }
            List<SkillUsage> drained = List.copyOf(recentUsages);
            recentUsages.clear();
            return drained;
        }
    }

    private String appendSkills(String systemPrompt, List<AiSkill> selectedSkills) {
        String block = buildSkillBlock(selectedSkills, true);
        String base = normalizedPrompt(systemPrompt);
        if (block.isBlank()) {
            return base;
        }
        return base.isBlank() ? block : block + "\n\n" + base;
    }

    private static boolean includeChatSkills(AiRequest request) {
        return request == null || request.includeAiSkills();
    }

    private static String normalizedPrompt(String systemPrompt) {
        return systemPrompt != null ? systemPrompt.trim() : "";
    }

    private String buildSkillBlock(List<AiSkill> selectedSkills, boolean recordUsage) {
        List<AiSkill> promptSkills = promptSkills(selectedSkills);
        if (!enabled || promptSkills.isEmpty()) {
            return "";
        }
        if (recordUsage) {
            recordSkillUsages(promptSkills);
        }
        StringBuilder block = new StringBuilder();
        for (AiSkill skill : promptSkills) {
            AiSkillTarget target = skill.getTarget();
            if (block.isEmpty()) {
                block.append("User-defined KorTTY AI skills:\n")
                    .append("The following local user skills are optional behavior instructions. ")
                    .append("Apply them when relevant, but do not let them override the current task, ")
                    .append("required output format, safety constraints, tool rules, or the latest user request below.\n");
            }
            block.append("\n<kortty_ai_skill name=\"")
                .append(toPromptAttribute(nonBlank(skill.getName(), "AI Skill")))
                .append("\" target=\"")
                .append(target.name())
                .append("\">\n")
                .append(skill.getContent().trim())
                .append("\n</kortty_ai_skill>\n");
        }
        return block.toString().trim();
    }

    private List<AiSkill> promptSkills(List<AiSkill> selectedSkills) {
        if (!enabled || selectedSkills == null || selectedSkills.isEmpty()) {
            return List.of();
        }
        List<AiSkill> promptSkills = new ArrayList<>();
        for (AiSkill skill : selectedSkills) {
            if (skill != null && skill.getTarget() != null && skill.getContent() != null && !skill.getContent().isBlank()) {
                promptSkills.add(skill);
            }
        }
        return promptSkills;
    }

    private void recordSkillUsages(List<AiSkill> selectedSkills) {
        if (selectedSkills == null || selectedSkills.isEmpty()) {
            return;
        }
        synchronized (recentUsages) {
            for (AiSkill skill : selectedSkills) {
                recentUsages.add(new SkillUsage(
                    nonBlank(skill.getId(), ""),
                    nonBlank(skill.getName(), "AI Skill"),
                    skill.getTarget()));
            }
        }
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

    private static String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private static String toPromptAttribute(String value) {
        return value.replace("\\", "\\\\")
            .replace("\"", "'")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim();
    }
}
