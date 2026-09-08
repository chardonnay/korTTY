package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.SnippetDiagramType;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * Composition rules plus one worked example for the SVG drawings behind
     * {@link AiAction#GENERATE_ASCII_ART}. Kept out of the fixed contract because it is advice a
     * model may weigh against the subject, whereas the contract is what the converter enforces.
     */
    static final String ASCII_ART_SKILL_ID = "builtin.action.ascii-art-svg";
    static final String ASCII_ART_SKILL_RESOURCE =
        "/builtin-action-ai-skills/builtin.action.ascii-art-svg.md";

    private static final String SVG_FENCE_OPENING = "```svg";
    private static final String FENCE = "```";

    private static final Map<SnippetDiagramType, AiSkill> LOADED_SKILLS = new ConcurrentHashMap<>();
    /** Cached apart from the diagram map: it is keyed by nothing and loaded on the first picture. */
    private static final AtomicReference<AiSkill> ASCII_ART_SKILL = new AtomicReference<>();
    private static final AtomicReference<String> ASCII_ART_EXAMPLE_SVG = new AtomicReference<>();

    private AiActionSkillPromptSupport() {
    }

    /**
     * Appends the action skill independently of user skill settings. The fixed action contract remains
     * authoritative; this block only adds quality criteria that are specific to the requested result.
     * The ASCII-art skill applies to the SVG contract only: the legacy direct-typing fallback has no
     * shapes to compose, and its composition advice would only distract a model that is already
     * struggling to align characters.
     */
    static String appendToSystemPrompt(String systemPrompt, AiRequest request) {
        String base = systemPrompt != null ? systemPrompt.trim() : "";
        if (request == null) {
            return base;
        }
        if (request.action() == AiAction.GENERATE_SNIPPET_MERMAID) {
            return AiPromptPipeline.insertSkills(base, skillBlock(
                "Mandatory built-in KorTTY action skill. Apply it after the fixed Mermaid JSON and "
                    + "safety contract; the fixed contract wins if any instruction conflicts.",
                diagramSkill(request.diagramType())));
        }
        if (request.action() == AiAction.GENERATE_ASCII_ART
            && AsciiArtRequestOptions.orDefault(request.asciiArtOptions()).isSvg()) {
            return AiPromptPipeline.insertSkills(base, skillBlock(
                "Mandatory built-in KorTTY action skill. Apply it after the fixed SVG contract; the "
                    + "fixed contract wins if any instruction conflicts.",
                asciiArtSkill()));
        }
        return base;
    }

    private static String skillBlock(String preamble, AiSkill skill) {
        return preamble + "\n"
            + "<kortty_required_action_skill id=\"" + skill.getBuiltinId() + "\" name=\""
            + promptAttribute(skill.getName(), skill.getBuiltinId()) + "\">\n"
            + skill.getContent().strip()
            + "\n</kortty_required_action_skill>";
    }

    /**
     * The worked example shipped in the ASCII-art skill, as the bare SVG document. The picture
     * pipeline renders it as its self-test fixture and compares every answer against it, because
     * very small models return the example instead of the subject.
     */
    static String asciiArtExampleSvg() {
        // Read-then-compareAndSet rather than updateAndGet: the JDK may re-apply an update
        // function under contention, which would parse the bundled Markdown twice for nothing.
        String cached = ASCII_ART_EXAMPLE_SVG.get();
        if (cached == null) {
            ASCII_ART_EXAMPLE_SVG.compareAndSet(null, firstSvgFenceBody(asciiArtSkill().getContent()));
            cached = ASCII_ART_EXAMPLE_SVG.get();
        }
        return cached;
    }

    private static AiSkill asciiArtSkill() {
        AiSkill cached = ASCII_ART_SKILL.get();
        if (cached == null) {
            ASCII_ART_SKILL.compareAndSet(null, loadRequiredSkill(ASCII_ART_SKILL_RESOURCE, ASCII_ART_SKILL_ID));
            cached = ASCII_ART_SKILL.get();
        }
        return cached;
    }

    private static String firstSvgFenceBody(String markdown) {
        int opening = markdown.indexOf(SVG_FENCE_OPENING);
        if (opening < 0) {
            throw new IllegalStateException("Built-in ASCII-art skill has no ```svg example: " + ASCII_ART_SKILL_RESOURCE);
        }
        int bodyStart = markdown.indexOf('\n', opening);
        int closing = bodyStart < 0 ? -1 : markdown.indexOf(FENCE, bodyStart);
        if (closing < 0) {
            throw new IllegalStateException("Built-in ASCII-art skill example is not closed: " + ASCII_ART_SKILL_RESOURCE);
        }
        return markdown.substring(bodyStart + 1, closing).strip();
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
