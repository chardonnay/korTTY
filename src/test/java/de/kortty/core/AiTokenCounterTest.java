package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.AiTokenizerType;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiTokenCounterTest {

    @Test
    void countsOnlyLocallySelectedAutoDetectedSkills() {
        AiSkill matching = skill("linux-sysadmin", "Linux guidance.", List.of("linux", "fedora"), "Use dnf and systemctl.");
        AiSkill unrelated = skill("creative-writing", "Creative prose.", List.of("novel"), "Write a poem.");
        AiRequest request = new AiRequest(AiAction.ASK, "Fedora dnf output", "fedora", "en", "systemctl status");

        int withoutSkills = AiTokenCounter.countRequestTokens(request, AiTokenizerType.ESTIMATE);
        int withSkills = AiTokenCounter.countRequestTokens(
            request,
            AiTokenizerType.ESTIMATE,
            new AiSkillPromptSupport(true, true, List.of(matching, unrelated)));
        int withAllSkills = AiTokenCounter.countRequestTokens(
            request,
            AiTokenizerType.ESTIMATE,
            new AiSkillPromptSupport(true, false, List.of(matching, unrelated)));

        assertThat(withSkills).isGreaterThan(withoutSkills);
        assertThat(withSkills).isLessThan(withAllSkills);
    }

    @Test
    void tokenPreviewDoesNotRecordUsedSkills() {
        AiSkill matching = skill("linux-sysadmin", "Linux guidance.", List.of("linux", "fedora"), "Use dnf and systemctl.");
        AiSkillPromptSupport support = new AiSkillPromptSupport(true, true, List.of(matching));
        AiRequest request = new AiRequest(AiAction.ASK, "Fedora dnf output", "fedora", "en", "systemctl status");

        int tokens = AiTokenCounter.countRequestTokens(request, AiTokenizerType.ESTIMATE, support);

        assertThat(tokens).isGreaterThan(0);
        assertThat(support.drainSkillUsages()).isEmpty();
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
