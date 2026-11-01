package de.kortty.stats;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.model.AiTokenizerType;
import java.util.List;
import org.testng.annotations.Test;

/** The size of a skill as the statistics count it: learned rules count only when the prompt carries them. */
public class SkillSizeTest {

    @Test
    void learnedRulesCountOnlyWhenThePromptCarriesThem() {
        AiSkill skill = skill();

        int withRules = SkillSize.of(skill, AiTokenizerType.ESTIMATE, true).tokens();
        int contentOnly = SkillSize.of(skill, AiTokenizerType.ESTIMATE, false).tokens();

        assertThat(contentOnly).isLessThan(withRules);
        assertThat(SkillSize.of(skill, AiTokenizerType.ESTIMATE).tokens()).isEqualTo(withRules);
    }

    @Test
    void theFingerprintDoesNotDependOnTheRuleChoice() {
        AiSkill skill = skill();

        assertThat(SkillSize.of(skill, AiTokenizerType.ESTIMATE, false).fingerprint())
            .isEqualTo(SkillSize.fingerprint(skill));
    }

    private static AiSkill skill() {
        AiSkill skill = new AiSkill();
        skill.setId("perl");
        skill.setName("Perl");
        skill.setContent("Use strict and warnings for every script, and quote every variable.");
        skill.setLearnedRules(List.of(AiSkillLearnedRule.create(
            "Prefer say over print in new code, and keep the output lines short.", AiSkillLearnedRule.SOURCE_CHAT)));
        return skill;
    }
}
