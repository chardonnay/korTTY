package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;


class AiSkillPromptSupportTest {

    @Test
    void appendsEnabledChatSkillsBeforeSystemPrompt() {
        AiSkill skill = skill("Shell Style", true, AiSkillTarget.CHAT, "Use short shell examples.");

        String prompt = new AiSkillPromptSupport(true, List.of(skill)).appendChatSkills("Task rules.");

        assertThat(prompt).contains("User-defined KorTTY AI skills");
        assertThat(prompt).contains("<kortty_ai_skill name=\"Shell Style\" target=\"CHAT\">");
        assertThat(prompt).contains("Use short shell examples.");
        assertThat(prompt).endsWith("Task rules.");
    }

    @Test
    void filtersDisabledGlobalDisabledAndWrongTargetSkills() {
        AiSkill chatSkill = skill("Chat", true, AiSkillTarget.CHAT, "chat");
        AiSkill disabledSkill = skill("Disabled", false, AiSkillTarget.BOTH, "disabled");
        AiSkill blankSkill = skill("Blank", true, AiSkillTarget.BOTH, " ");
        AiSkill agentSkill = skill("Agent", true, AiSkillTarget.AGENT, "agent");

        AiSkillPromptSupport support = new AiSkillPromptSupport(true, List.of(chatSkill, disabledSkill, blankSkill, agentSkill));

        String chatPrompt = support.appendChatSkills("base");
        String agentPrompt = support.appendAgentSkills("base");
        String globallyDisabled = new AiSkillPromptSupport(false, List.of(chatSkill)).appendChatSkills("base");

        assertThat(chatPrompt).contains("chat");
        assertThat(chatPrompt).doesNotContain("disabled");
        assertThat(chatPrompt).doesNotContain("agent");
        assertThat(agentPrompt).contains("agent");
        assertThat(agentPrompt).doesNotContain("chat");
        assertThat(globallyDisabled).isEqualTo("base");
    }

    @Test
    void autoDetectionIncludesOnlyLocallyMatchingSkills() {
        AiSkill linux = skill("linux-sysadmin", true, AiSkillTarget.BOTH, "Use dnf and systemctl carefully.");
        linux.setDescription("Linux system administration guidance.");
        linux.setTags(List.of("linux", "fedora", "dnf", "systemctl"));
        AiSkill poetry = skill("poetry", true, AiSkillTarget.BOTH, "Write literary answers.");
        poetry.setTags(List.of("poetry", "novel"));
        AiRequest request = new AiRequest(
            AiAction.ASK,
            "Fedora 44 host with dnf repositories",
            "fedora",
            "en",
            "which repository should I use for Jenkins with systemctl?");

        String prompt = new AiSkillPromptSupport(true, true, List.of(linux, poetry))
            .appendChatSkills("base", request);

        assertThat(prompt).contains("Use dnf and systemctl carefully.");
        assertThat(prompt).doesNotContain("Write literary answers.");
    }

    @Test
    void autoDetectionDisabledIncludesAllTargetMatchingSkills() {
        AiSkill linux = skill("linux-sysadmin", true, AiSkillTarget.BOTH, "Use dnf.");
        linux.setTags(List.of("linux"));
        AiSkill poetry = skill("poetry", true, AiSkillTarget.BOTH, "Write literary answers.");
        poetry.setTags(List.of("poetry"));

        String prompt = new AiSkillPromptSupport(true, false, List.of(linux, poetry))
            .appendChatSkills("base", new AiRequest(AiAction.ASK, "unrelated", "box", "en", "question"));

        assertThat(prompt).contains("Use dnf.");
        assertThat(prompt).contains("Write literary answers.");
    }

    @Test
    void snippetCompletionUsesChatSkillScopeAndExcludesAgentOnlySkills() {
        AiSkill chatSkill = skill("Chat Skill", true, AiSkillTarget.CHAT, "Use strict Bash.");
        AiSkill agentSkill = skill("Agent Skill", true, AiSkillTarget.AGENT, "Run shellcheck.");
        AiRequest request = new AiRequest(
            AiAction.COMPLETE_SNIPPET_CODE,
            "echo hi",
            null,
            "en",
            null,
            "Snippet language: bash");

        String prompt = new AiSkillPromptSupport(true, false, List.of(chatSkill, agentSkill))
            .appendChatSkills("base", request);

        assertThat(prompt).contains("Use strict Bash.");
        assertThat(prompt).doesNotContain("Run shellcheck.");
    }

    @Test
    void appendAgentSkillsRecordsAndDrainsUsedSkills() {
        AiSkill skill = skill("Agent Skill", true, AiSkillTarget.AGENT, "Prefer safe commands.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, List.of(skill));

        String prompt = support.appendAgentSkills("base");
        List<AiSkillPromptSupport.SkillUsage> usages = support.drainSkillUsages();

        assertThat(prompt).contains("Prefer safe commands.");
        assertThat(usages).hasSize(1);
        assertThat(usages.get(0).name()).isEqualTo("Agent Skill");
        assertThat(usages.get(0).target()).isEqualTo(AiSkillTarget.AGENT);
        assertThat(support.drainSkillUsages()).isEmpty();
    }

    @Test
    void buildSkillBlocksDoNotRecordUsedSkills() {
        AiSkill skill = skill("Agent Skill", true, AiSkillTarget.AGENT, "Prefer safe commands.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, List.of(skill));

        String block = support.buildAgentSkillBlock();

        assertThat(block).contains("Prefer safe commands.");
        assertThat(support.drainSkillUsages()).isEmpty();
    }

    private AiSkill skill(String name, boolean enabled, AiSkillTarget target, String content) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setEnabled(enabled);
        skill.setTarget(target);
        skill.setContent(content);
        return skill;
    }
}
