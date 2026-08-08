package de.kortty.core;

import de.kortty.model.AiSkill;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Adds small, mandatory built-in skills that refine one specific AI action. */
final class AiActionSkillPromptSupport {

    static final String MERMAID_SKILL_ID = "builtin.action.snippet-mermaid";
    static final String MERMAID_SKILL_RESOURCE =
        "/builtin-action-ai-skills/builtin.action.snippet-mermaid.md";

    private static volatile AiSkill mermaidSkill;

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
        AiSkill skill = mermaidSkill();
        String block = "Mandatory built-in KorTTY action skill. Apply it after the fixed Mermaid JSON and "
            + "safety contract; the fixed contract wins if any instruction conflicts.\n"
            + "<kortty_required_action_skill id=\"" + skill.getBuiltinId() + "\" name=\""
            + promptAttribute(skill.getName()) + "\">\n"
            + skill.getContent().strip()
            + "\n</kortty_required_action_skill>";
        return AiPromptPipeline.insertSkills(base, block);
    }

    private static AiSkill mermaidSkill() {
        AiSkill loaded = mermaidSkill;
        if (loaded != null) {
            return loaded;
        }
        synchronized (AiActionSkillPromptSupport.class) {
            if (mermaidSkill == null) {
                mermaidSkill = loadRequiredSkill(MERMAID_SKILL_RESOURCE);
            }
            return mermaidSkill;
        }
    }

    static AiSkill loadRequiredSkill(String resourcePath) {
        try (InputStream stream = AiActionSkillPromptSupport.class.getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("Required built-in AI action skill is missing: " + resourcePath);
            }
            String markdown = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
            AiSkill skill = AiSkillMarkdownCodec.loadBundled(resourcePath, markdown).skill();
            if (!MERMAID_SKILL_ID.equals(skill.getBuiltinId())) {
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

    private static String promptAttribute(String value) {
        return (value != null ? value : MERMAID_SKILL_ID)
            .replace("\\", "\\\\")
            .replace("\"", "'")
            .replace("\r", " ")
            .replace("\n", " ")
            .trim();
    }

}
