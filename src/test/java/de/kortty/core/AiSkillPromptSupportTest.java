package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;


class AiSkillPromptSupportTest {

    @Test
    void appendsEnabledChatSkillsAfterTheActionContract() {
        AiSkill skill = skill("Shell Style", true, AiSkillTarget.CHAT, "Use short shell examples.");

        String prompt = new AiSkillPromptSupport(true, List.of(skill)).appendChatSkills("Task rules.");

        assertThat(prompt).contains("User-defined KorTTY AI skills");
        assertThat(prompt).contains("<kortty_ai_skill name=\"Shell Style\" target=\"CHAT\">");
        assertThat(prompt).contains("Use short shell examples.");
        assertThat(prompt).startsWith("Task rules.");
        assertThat(prompt.indexOf("Task rules.")).isLessThan(prompt.indexOf("User-defined KorTTY AI skills"));
    }

    @Test
    void strictJsonCodeActionUsesHardenedPreambleWhileChatKeepsSoftOne() {
        AiSkill skill = skill("Perl Style", true, AiSkillTarget.BOTH, "Prefer strict Perl.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, List.of(skill));

        // Chat action → soft preamble.
        String chat = support.appendChatSkills("base",
            new AiRequest(AiAction.ASK, "code", "box", "en", "question"));
        assertThat(chat).contains("optional behavior instructions");
        assertThat(chat).doesNotContain("cannot change the output format");

        // Strict-JSON code action → hardened preamble; skills stay usable for content.
        String code = support.appendChatSkills("base",
            new AiRequest(AiAction.APPLY_SNIPPET_IMPROVEMENTS, "code", "box", "en"));
        assertThat(code).contains("cannot change the output format");
        assertThat(code).contains("never a placeholder or empty value");
        assertThat(code).doesNotContain("optional behavior instructions");
        assertThat(code).contains("Prefer strict Perl.");
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
    void requestCanDisableChatSkills() {
        AiSkill chatSkill = skill("Chat Skill", true, AiSkillTarget.CHAT, "Use strict Bash.");
        AiRequest request = new AiRequest(
            AiAction.ASSIST_SNIPPET_CODE,
            "echo hi",
            null,
            "en",
            "Add logging",
            "Snippet language: bash",
            false);
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, false, List.of(chatSkill));

        String prompt = support.appendChatSkills("base", request);
        String block = support.buildChatSkillBlock(request);

        assertThat(prompt).isEqualTo("base");
        assertThat(block).isEmpty();
        assertThat(support.drainSkillUsages()).isEmpty();
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

    @Test
    void connectionSkillsRequireAssignmentToTheConnection() {
        AiSkill connectionSkill = skill("Conn Skill", true, AiSkillTarget.CONNECTION, "Connection rules.");
        connectionSkill.setId("skill-conn");
        AiSkill globalSkill = skill("Global Skill", true, AiSkillTarget.BOTH, "Global rules.");
        globalSkill.setId("skill-global");
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        settings.setAiSkillsEnabled(true);
        settings.setAiSkillAutoDetectionEnabled(false);
        settings.setAiSkills(new java.util.ArrayList<>(List.of(connectionSkill, globalSkill)));

        String withoutAssignment = AiSkillPromptSupport.fromSettings(settings).appendChatSkills("base");
        String otherAssignment = AiSkillPromptSupport.fromSettings(settings, List.of("other-skill")).appendChatSkills("base");
        String withAssignment = AiSkillPromptSupport.fromSettings(settings, List.of("skill-conn")).appendChatSkills("base");

        assertThat(withoutAssignment).doesNotContain("Connection rules.");
        assertThat(withoutAssignment).contains("Global rules.");
        assertThat(otherAssignment).doesNotContain("Connection rules.");
        assertThat(withAssignment).contains("Connection rules.");
        assertThat(withAssignment).contains("Global rules.");
    }

    @Test
    void assignedSkillsBypassRelevanceAutoDetection() {
        AiSkill assignedSkill = skill("poetry", true, AiSkillTarget.BOTH, "Write literary answers.");
        assignedSkill.setId("skill-poetry");
        assignedSkill.setTags(List.of("poetry", "novel"));
        AiSkill unrelatedSkill = skill("cooking", true, AiSkillTarget.BOTH, "Share recipes.");
        unrelatedSkill.setId("skill-cooking");
        unrelatedSkill.setTags(List.of("cooking"));
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        settings.setAiSkillsEnabled(true);
        settings.setAiSkillAutoDetectionEnabled(true);
        settings.setAiSkills(new java.util.ArrayList<>(List.of(assignedSkill, unrelatedSkill)));
        AiRequest request = new AiRequest(AiAction.ASK, "kernel update on fedora", "host", "en", "how to update the kernel?");

        String withAssignment = AiSkillPromptSupport.fromSettings(settings, List.of("skill-poetry"))
            .appendChatSkills("base", request);
        String withoutAssignment = AiSkillPromptSupport.fromSettings(settings)
            .appendChatSkills("base", request);

        assertThat(withAssignment).contains("Write literary answers.");
        assertThat(withAssignment).doesNotContain("Share recipes.");
        assertThat(withoutAssignment).doesNotContain("Write literary answers.");
    }

    @Test
    void assignedConnectionSkillAppliesToChatAndAgent() {
        AiSkill connectionSkill = skill("Conn Skill", true, AiSkillTarget.CONNECTION, "Connection rules.");
        connectionSkill.setId("skill-conn");
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        settings.setAiSkillsEnabled(true);
        settings.setAiSkillAutoDetectionEnabled(false);
        settings.setAiSkills(new java.util.ArrayList<>(List.of(connectionSkill)));

        AiSkillPromptSupport support = AiSkillPromptSupport.fromSettings(settings, List.of("skill-conn"));

        assertThat(support.appendChatSkills("base")).contains("Connection rules.");
        assertThat(support.appendAgentSkills("base")).contains("Connection rules.");
    }

    @Test
    void fromSettingsExcludesHiddenAndOverriddenBuiltins() {
        AiSkill hiddenBuiltin = skill("Bash", true, AiSkillTarget.BOTH, "Quote everything.");
        hiddenBuiltin.setBuiltinId("builtin.shell.bash");
        hiddenBuiltin.setBuiltinTopics(List.of("bash"));
        hiddenBuiltin.setHidden(true);
        AiSkill overriddenBuiltin = skill("Perl (Perl 5)", true, AiSkillTarget.BOTH, "Use strict.");
        overriddenBuiltin.setBuiltinId("builtin.lang.perl");
        overriddenBuiltin.setBuiltinTopics(List.of("perl"));
        AiSkill overridingUser = skill("My Perl Quality", true, AiSkillTarget.BOTH, "My own perl rules.");
        overridingUser.setTags(List.of("perl"));
        AiSkill activeBuiltin = skill("Python", true, AiSkillTarget.BOTH, "Use docstrings.");
        activeBuiltin.setBuiltinId("builtin.lang.python");
        activeBuiltin.setBuiltinTopics(List.of("python"));
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        settings.setAiSkillsEnabled(true);
        settings.setAiSkillAutoDetectionEnabled(false);
        settings.setAiSkills(new java.util.ArrayList<>(
            List.of(hiddenBuiltin, overriddenBuiltin, overridingUser, activeBuiltin)));

        String chat = AiSkillPromptSupport.fromSettings(settings).appendChatSkills("base");
        String agent = AiSkillPromptSupport.fromSettings(settings).appendAgentSkills("base");

        assertThat(chat).doesNotContain("Quote everything.");
        assertThat(chat).doesNotContain("Use strict.");
        assertThat(chat).contains("My own perl rules.");
        assertThat(chat).contains("Use docstrings.");
        assertThat(agent).doesNotContain("Quote everything.");
        assertThat(agent).doesNotContain("Use strict.");
        assertThat(agent).contains("My own perl rules.");
    }

    @Test
    void overriddenBuiltinAssignedToConnectionIsNotPinnedIn() {
        AiSkill overriddenBuiltin = skill("Perl (Perl 5)", true, AiSkillTarget.BOTH, "Use strict.");
        overriddenBuiltin.setId("skill-builtin-perl");
        overriddenBuiltin.setBuiltinId("builtin.lang.perl");
        overriddenBuiltin.setBuiltinTopics(List.of("perl"));
        AiSkill overridingUser = skill("My Perl Quality", true, AiSkillTarget.BOTH, "My own perl rules.");
        overridingUser.setTags(List.of("perl"));
        de.kortty.model.GlobalSettings settings = new de.kortty.model.GlobalSettings();
        settings.setAiSkillsEnabled(true);
        settings.setAiSkillAutoDetectionEnabled(false);
        settings.setAiSkills(new java.util.ArrayList<>(List.of(overriddenBuiltin, overridingUser)));

        // User precedence is global: even a pinned connection assignment must not resurrect it.
        String prompt = AiSkillPromptSupport.fromSettings(settings, List.of("skill-builtin-perl"))
            .appendChatSkills("base");

        assertThat(prompt).doesNotContain("Use strict.");
        assertThat(prompt).contains("My own perl rules.");
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
