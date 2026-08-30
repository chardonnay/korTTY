package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.SnippetDiagramType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Adds small, mandatory built-in skills that refine one specific AI action. */
final class AiActionSkillPromptSupport {

    static final String MERMAID_SKILL_ID = "builtin.action.snippet-mermaid";
    static final String MERMAID_SKILL_RESOURCE =
        "/builtin-action-ai-skills/builtin.action.snippet-mermaid.md";

    /** One mandatory skill per generated diagram family; the flowchart keeps its historical id. */
    private static final Map<SnippetDiagramType, String> DIAGRAM_SKILL_IDS = new EnumMap<>(Map.of(
        SnippetDiagramType.LOGICAL_STRUCTURE, MERMAID_SKILL_ID,
        SnippetDiagramType.SEQUENCE, "builtin.action.snippet-sequence",
        SnippetDiagramType.STATE, "builtin.action.snippet-state",
        SnippetDiagramType.CLASS, "builtin.action.snippet-class",
        SnippetDiagramType.ER, "builtin.action.snippet-er"));

    private static final Map<SnippetDiagramType, AiSkill> LOADED_SKILLS = new ConcurrentHashMap<>();

    private AiActionSkillPromptSupport() {
    }

    /**
     * Appends the action skill independently of user skill settings. The fixed action contract remains
     * authoritative; this block only adds quality criteria that are specific to the requested result.
     */
    static String appendToSystemPrompt(String systemPrompt, AiRequest request) {
        String base = systemPrompt != null ? systemPrompt.trim() : "";
        if (request == null || request.action() != AiAction.GENERATE_SNIPPET_MERMAID) {
            return base;
        }
        AiSkill skill = diagramSkill(request.diagramType());
        String block = "Mandatory built-in KorTTY action skill. Apply it after the fixed Mermaid JSON and "
            + "safety contract; the fixed contract wins if any instruction conflicts.\n"
            + "<kortty_required_action_skill id=\"" + skill.getBuiltinId() + "\" name=\""
            + promptAttribute(skill.getName(), skill.getBuiltinId()) + "\">\n"
            + skill.getContent().strip()
            + "\n</kortty_required_action_skill>";
        return AiPromptPipeline.insertSkills(base, block);
    }

    static String diagramSkillId(SnippetDiagramType diagramType) {
        return DIAGRAM_SKILL_IDS.get(diagramType != null ? diagramType : SnippetDiagramType.LOGICAL_STRUCTURE);
    }

    static String diagramSkillResource(SnippetDiagramType diagramType) {
        return "/builtin-action-ai-skills/" + diagramSkillId(diagramType) + ".md";
    }

    private static AiSkill diagramSkill(SnippetDiagramType diagramType) {
        SnippetDiagramType type = diagramType != null ? diagramType : SnippetDiagramType.LOGICAL_STRUCTURE;
        return LOADED_SKILLS.computeIfAbsent(type,
            key -> loadRequiredSkill(diagramSkillResource(key), diagramSkillId(key)));
    }

    static AiSkill loadRequiredSkill(String resourcePath, String expectedId) {
        try (InputStream stream = AiActionSkillPromptSupport.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Required built-in AI action skill is missing: " + resourcePath);
            }
            String markdown = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            AiSkill skill = AiSkillMarkdownCodec.loadBundled(resourcePath, markdown).skill();
            if (!expectedId.equals(skill.getBuiltinId())) {
                throw new IllegalStateException("Required built-in AI action skill has unexpected id: "
                    + skill.getBuiltinId());
            }
            if (skill.getContent() == null || skill.getContent().isBlank()) {
                throw new IllegalStateException("Required built-in AI action skill is empty: " + resourcePath);
            }
            return skill;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load built-in AI action skill: " + resourcePath, e);
        }
    }

    private static String promptAttribute(String value, String fallback) {
        return (value != null ? value : fallback)
            .replace("\\", "\\\\")
            .replace("\"", "'")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim();
    }

}
