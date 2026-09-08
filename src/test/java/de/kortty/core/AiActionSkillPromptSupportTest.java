package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class AiActionSkillPromptSupportTest {

    @Test
    void mermaidActionAlwaysIncludesCompactQualitySkill() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "if ready; then run; fi",
            null,
            "de",
            null,
            "Snippet language: bash",
            false);

        String prompt = AiPromptBuilder.buildSystemPrompt(request);

        assertThat(prompt).contains("<kortty_required_action_skill id=\"builtin.action.snippet-mermaid\"");
        assertThat(prompt).contains("never exceed the node limit stated in the fixed contract");
        assertThat(prompt).contains("Never create one node per variable, command, print statement");
        assertThat(prompt).contains("Every declared node must be reachable from `start_1`");
        assertThat(prompt).contains("`stop_1` has no outgoing edge");
        assertThat(prompt).contains("Every decision has exactly two explicit outgoing outcomes");
        assertThat(prompt).contains("explicit positive outcomes as `success`");
        assertThat(prompt).contains("smallest source range");
        assertThat(prompt).contains("</kortty_required_action_skill>");
        assertThat(countOccurrences(prompt, AiActionSkillPromptSupport.MERMAID_SKILL_ID)).isEqualTo(1);
        assertThat(prompt.indexOf("Return exactly one JSON object"))
            .isLessThan(prompt.indexOf("<kortty_required_action_skill"));
        // The fixed contract grew by the rules a real model kept breaking (node cap, JSON escaping,
        // class definitions, single path); each one is there because its absence cost a diagram.
        assertThat(prompt.length()).isLessThan(5_300);
    }

    @Test
    void allOtherActionsExcludeMermaidSkill() {
        for (AiAction action : AiAction.values()) {
            if (action == AiAction.GENERATE_SNIPPET_MERMAID || action == AiAction.GENERATE_ASCII_ART) {
                continue;
            }
            String prompt = AiPromptBuilder.buildSystemPrompt(
                new AiRequest(action, "echo ok", null, "en"));
            assertThat(prompt).doesNotContain("kortty_required_action_skill");
            assertThat(prompt).doesNotContain(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
            assertThat(prompt).doesNotContain(AiActionSkillPromptSupport.ASCII_ART_SKILL_ID);
        }
    }

    // ---- ASCII art ----

    @Test
    void asciiArtSvgRequestIncludesTheCompositionSkill() {
        AiRequest request = new AiRequest(AiAction.GENERATE_ASCII_ART, "lighthouse", null, "en");

        String prompt = AiPromptBuilder.buildSystemPrompt(request);

        assertThat(prompt).contains("<kortty_required_action_skill id=\"builtin.action.ascii-art-svg\"");
        assertThat(prompt).contains("Apply it after the fixed SVG contract");
        // One of the eight composition rules and one line of the worked example.
        assertThat(prompt).contains("Draw layers from back to front");
        assertThat(prompt).contains("<svg viewBox=\"0 0 100 100\">");
        assertThat(prompt).contains("</kortty_required_action_skill>");
        assertThat(countOccurrences(prompt, AiActionSkillPromptSupport.ASCII_ART_SKILL_ID)).isEqualTo(1);
        assertThat(prompt).doesNotContain(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
        // The skill refines the contract, so it follows it.
        assertThat(prompt.indexOf("Return the SVG immediately"))
            .isLessThan(prompt.indexOf("<kortty_required_action_skill"));
    }

    @Test
    void asciiArtLegacyModeCarriesNoSkill() {
        AiRequest request = new AiRequest(AiAction.GENERATE_ASCII_ART, "lighthouse", null, "en")
            .withAsciiArtOptions(AsciiArtRequestOptions.ascii(de.kortty.model.AsciiArtPictureSize.MEDIUM));

        String prompt = AiPromptBuilder.buildSystemPrompt(request);

        // Composition advice about shapes has nothing to say to a model typing characters.
        assertThat(prompt).doesNotContain("kortty_required_action_skill");
        assertThat(prompt).doesNotContain(AiActionSkillPromptSupport.ASCII_ART_SKILL_ID);
    }

    @Test
    void theAsciiArtExampleSurvivesLoading() {
        String example = AiActionSkillPromptSupport.asciiArtExampleSvg();

        // The front-matter parser must hand the fenced example through untouched: the converter
        // renders it as its fixture and the copied-example detector compares answers against it.
        assertThat(example).isNotNull();
        assertThat(example).startsWith("<svg viewBox=\"0 0 100 100\">");
        assertThat(example).endsWith("</svg>");
        assertThat(example).doesNotContain("```");
        int shapes = 0;
        for (String element : List.of("<rect", "<circle", "<ellipse", "<line", "<polyline", "<polygon", "<path")) {
            shapes += countOccurrences(example, element);
        }
        assertThat(shapes).isEqualTo(11);
        // Every tone of the contract appears, including the white window on the black lamp room.
        for (String tone : List.of("fill=\"black\"", "fill=\"#555\"", "fill=\"#aaa\"", "fill=\"white\"")) {
            assertThat(example).contains(tone);
        }
        assertThat(example).contains("stroke-width=\"3\"");
        // Cached: the second call is the same instance.
        assertThat(AiActionSkillPromptSupport.asciiArtExampleSvg()).isSameInstanceAs(example);
    }

    @Test
    void configurableSkillsRemainExcludedWhileMandatoryActionSkillRemains() {
        AiSkill configurable = new AiSkill();
        configurable.setName("Bash style");
        configurable.setEnabled(true);
        configurable.setTarget(AiSkillTarget.BOTH);
        configurable.setContent("Use one node for every shell command.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, List.of(configurable));
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "echo ok",
            null,
            "en");

        String prompt = support.appendChatSkills(AiPromptBuilder.buildSystemPrompt(request), request);

        assertThat(prompt).contains(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
        assertThat(prompt).doesNotContain("Bash style");
        assertThat(prompt).doesNotContain("Use one node for every shell command");
        assertThat(support.drainSkillUsages()).isEmpty();
    }

    @Test
    void disabledConfigurableSkillSupportDoesNotRemoveMandatoryActionSkill() {
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "echo ok",
            null,
            "en",
            null,
            null,
            false);

        String prompt = AiSkillPromptSupport.disabled()
            .appendChatSkills(AiPromptBuilder.buildSystemPrompt(request), request);

        assertThat(prompt).contains(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
    }

    @Test
    void mermaidActionNeverInvokesConfigurableSkillClassifier() {
        AiSkill configurable = new AiSkill();
        configurable.setName("Flow rules");
        configurable.setEnabled(true);
        configurable.setTarget(AiSkillTarget.CHAT);
        configurable.setContent("Configurable instructions.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, true, List.of(configurable));
        AiRequest request = new AiRequest(
            AiAction.GENERATE_SNIPPET_MERMAID,
            "echo ok",
            null,
            "en");
        AtomicBoolean classifierCalled = new AtomicBoolean();

        String prompt = support.appendChatSkills(
            AiPromptBuilder.buildSystemPrompt(request),
            request,
            (context, skills) -> {
                classifierCalled.set(true);
                return List.of();
            });

        assertThat(classifierCalled.get()).isFalse();
        assertThat(prompt).contains(AiActionSkillPromptSupport.MERMAID_SKILL_ID);
        assertThat(prompt).doesNotContain("Configurable instructions");
    }

    @Test
    void everyDiagramFamilyLoadsExactlyItsOwnMandatorySkill() {
        java.util.Map<de.kortty.model.SnippetDiagramType, String> expectedIds = java.util.Map.of(
            de.kortty.model.SnippetDiagramType.LOGICAL_STRUCTURE, "builtin.action.snippet-mermaid",
            de.kortty.model.SnippetDiagramType.SEQUENCE, "builtin.action.snippet-sequence",
            de.kortty.model.SnippetDiagramType.STATE, "builtin.action.snippet-state",
            de.kortty.model.SnippetDiagramType.CLASS, "builtin.action.snippet-class",
            de.kortty.model.SnippetDiagramType.ER, "builtin.action.snippet-er");
        for (var entry : expectedIds.entrySet()) {
            AiRequest request = new AiRequest(
                AiAction.GENERATE_SNIPPET_MERMAID,
                "echo ok",
                null,
                "en",
                null,
                null,
                false,
                null,
                null,
                null,
                entry.getKey());

            String prompt = AiPromptBuilder.buildSystemPrompt(request);

            assertThat(AiActionSkillPromptSupport.diagramSkillId(entry.getKey())).isEqualTo(entry.getValue());
            assertThat(prompt).contains("<kortty_required_action_skill id=\"" + entry.getValue() + "\"");
            assertThat(countOccurrences(prompt, "<kortty_required_action_skill")).isEqualTo(1);
            for (String otherId : expectedIds.values()) {
                if (!otherId.equals(entry.getValue())) {
                    assertThat(prompt).doesNotContain(otherId);
                }
            }
        }
    }

    @Test
    void missingRequiredSkillFailsAsCatchableExceptionInsteadOfInitializerError() {
        IllegalStateException failure = expectThrows(
            IllegalStateException.class,
            () -> AiActionSkillPromptSupport.loadRequiredSkill(
                "/missing-action-skill.md", "missing-action-skill"));

        assertThat(failure).hasMessageThat().contains("Required built-in AI action skill is missing");
    }

    private static int countOccurrences(String value, String needle) {
        int count = 0;
        int offset = 0;
        while ((offset = value.indexOf(needle, offset)) >= 0) {
            count++;
            offset += needle.length();
        }
        return count;
    }
}
