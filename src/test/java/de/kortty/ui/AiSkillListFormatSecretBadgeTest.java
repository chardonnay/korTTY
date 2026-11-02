package de.kortty.ui;

import de.kortty.core.AiSecretScanRegistry;
import de.kortty.model.AiSkill;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiSkillListFormatSecretBadgeTest {

    private static AiSkill skill(String id) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName(id);
        skill.setContent("content");
        return skill;
    }

    @Test
    void aFlaggedSkillShowsThePossibleSecretBadgeAndAnotherSkillDoesNot() {
        AiSecretScanRegistry registry = new AiSecretScanRegistry();
        registry.replaceSkills(List.of("flagged"));
        String badge = I18n.get("settings.aiSkills.badge.possibleSecret");

        assertThat(AiSkillListFormat.listText(skill("flagged"), null, registry)).contains(badge);
        assertThat(AiSkillListFormat.listText(skill("clean"), null, registry)).doesNotContain(badge);
    }
}
