package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

class AiSkillRelevanceSelectorTest {

    @Test
    void localSelectionMatchesLinuxSkillForFedoraSystemctlRequest() {
        AiSkill linux = skill("linux-sysadmin", "Linux system administration guidance.", List.of("linux", "fedora", "dnf", "systemctl"), "Linux rules");
        AiSkill unrelated = skill("creative-writing", "Creative prose.", List.of("novel"), "Write prose");
        AiSkillRelevanceSelector selector = new AiSkillRelevanceSelector(true, true, List.of(linux, unrelated));
        AiRequest request = new AiRequest(
            AiAction.ASK,
            "Fedora 44 /etc/yum.repos.d output",
            "fedora",
            "en",
            "which repository can I use to install Jenkins and manage it with systemctl?");

        List<AiSkill> selected = selector.selectChatSkillsLocal(request);

        assertThat(selected.stream().map(AiSkill::getName).toList()).containsExactly("linux-sysadmin");
    }

    @Test
    void localSelectionDoesNotMatchClearlyUnrelatedRequest() {
        AiSkill linux = skill("linux-sysadmin", "Linux system administration guidance.", List.of("linux", "fedora", "dnf", "systemctl"), "Linux rules");
        AiSkillRelevanceSelector selector = new AiSkillRelevanceSelector(true, true, List.of(linux));

        List<AiSkill> selected = selector.selectChatSkillsLocal(new AiRequest(AiAction.ASK, "", "box", "en", "write a product tagline"));

        assertThat(selected).isEmpty();
    }

    @Test
    void hybridClassifierReceivesMetadataOnlyAndCanSelectSkill() {
        AiSkill skill = skill("linux-sysadmin", "Linux guidance.", List.of("linux"), "SECRET SKILL CONTENT");
        AiSkillRelevanceSelector selector = new AiSkillRelevanceSelector(true, true, List.of(skill));
        AtomicReference<List<AiSkillRelevanceSelector.SkillMetadata>> captured = new AtomicReference<>();

        List<AiSkill> selected = selector.selectChatSkills(
            new AiRequest(AiAction.ASK, "", "box", "en", "ambiguous request"),
            (context, metadata) -> {
                captured.set(metadata);
                return List.of(skill.getId());
            });

        assertThat(selected.stream().map(AiSkill::getName).toList()).containsExactly("linux-sysadmin");
        assertThat(captured.get()).hasSize(1);
        assertThat(captured.get().get(0).description()).isEqualTo("Linux guidance.");
        assertThat(captured.get().toString()).doesNotContain("SECRET SKILL CONTENT");
    }

    @Test
    void hybridFailureFallsBackToLocalSelection() {
        AiSkill skill = skill("linux-sysadmin", "Linux guidance.", List.of("linux", "fedora"), "Use Linux rules.");
        AiSkill second = skill("fedora-admin", "Fedora guidance.", List.of("linux", "fedora"), "Use Fedora rules.");
        AiSkillRelevanceSelector selector = new AiSkillRelevanceSelector(true, true, List.of(skill, second));
        AiRequest request = new AiRequest(AiAction.ASK, "Fedora dnf", "box", "en", "systemctl status");

        List<AiSkill> selected = selector.selectChatSkills(request, (context, metadata) -> {
            throw new java.io.IOException("timeout");
        });

        assertThat(selected.stream().map(AiSkill::getName).toList()).containsExactly("linux-sysadmin", "fedora-admin").inOrder();
    }

    @Test
    void parsesClassifierJsonResponse() {
        List<String> ids = AiSkillRelevanceSelector.parseClassifierResponse("```json\n{\"skillIds\":[\"a\",\"b\"]}\n```");

        assertThat(ids).containsExactly("a", "b").inOrder();
    }

    @Test
    void pinnedSkillBypassesTargetFilterForChat() {
        AiSkill agentSkill = skill("agent-only", "Agent guidance.", List.of("agent"), "Agent rules");
        agentSkill.setTarget(AiSkillTarget.AGENT);
        AiSkill otherAgent = skill("agent-two", "More agent guidance.", List.of("agent"), "More rules");
        otherAgent.setTarget(AiSkillTarget.AGENT);
        AiRequest chatRequest = new AiRequest(AiAction.ASK, "code", "box", "en", "review this");

        // Without pinning an AGENT-target skill is never part of a chat selection.
        AiSkillRelevanceSelector unpinned = new AiSkillRelevanceSelector(true, true, List.of(agentSkill, otherAgent));
        assertThat(unpinned.selectChatSkillsLocal(chatRequest)).isEmpty();

        // Pinned (forced) the AGENT-target skill is included in the chat selection despite the mismatch.
        AiSkillRelevanceSelector pinned = new AiSkillRelevanceSelector(
            true, true, List.of(agentSkill, otherAgent), java.util.Set.of(agentSkill.getId()));
        List<AiSkill> selected = pinned.selectChatSkillsLocal(chatRequest);

        assertThat(selected.stream().map(AiSkill::getName).toList()).containsExactly("agent-only");
    }

    @Test
    void disabledMasterSwitchDropsEvenPinnedSkills() {
        AiSkill agentSkill = skill("agent-only", "Agent guidance.", List.of("agent"), "Agent rules");
        agentSkill.setTarget(AiSkillTarget.AGENT);
        AiSkillRelevanceSelector disabled = new AiSkillRelevanceSelector(
            false, true, List.of(agentSkill), java.util.Set.of(agentSkill.getId()));

        assertThat(disabled.selectChatSkillsLocal(new AiRequest(AiAction.ASK, "code", "box", "en", "review this")))
            .isEmpty();
    }

    private AiSkill skill(String name, String description, List<String> tags, String content) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setDescription(description);
        skill.setTags(tags);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent(content);
        skill.setEnabled(true);
        return skill;
    }
}
