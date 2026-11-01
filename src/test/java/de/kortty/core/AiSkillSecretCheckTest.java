package de.kortty.core;

import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import de.kortty.ui.I18n;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class AiSkillSecretCheckTest {

    private static final String SECRET = "Sonnenblume42";

    private static AiSkill skill(String content) {
        AiSkill skill = new AiSkill();
        skill.setId("skill-1");
        skill.setName("Deploy");
        skill.setContent(content);
        return skill;
    }

    @Test
    void aCleanSkillHasNoFindingsAndNoRefusal() {
        AiSkill clean = skill("Use `shift` to read the arguments. Keep the scripts short.");

        assertThat(AiSkillSecretCheck.findings(clean)).isEmpty();
        assertThat(AiSkillSecretCheck.refusal(clean)).isEmpty();
    }

    @Test
    void theRefusalNamesTheLineAndTheKindOfSecretButNotTheValue() {
        AiSkill secret = skill("Read the notes first.\nDas Passwort ist " + SECRET + "\n");

        List<AiSkillSecretCheck.Finding> found = AiSkillSecretCheck.findings(secret);
        Optional<String> refusal = AiSkillSecretCheck.refusal(secret);

        assertThat(found).isNotEmpty();
        assertThat(found.get(0).field()).isEqualTo(AiSkill.TextField.CONTENT);
        assertThat(found.get(0).finding().line()).isEqualTo(2);
        assertThat(refusal).isPresent();
        assertThat(refusal.get()).contains("2");
        assertThat(refusal.get()).contains(I18n.get("settings.aiSkills.secret.category.prose"));
        assertThat(refusal.get()).doesNotContain(SECRET);
    }

    @Test
    void aLearnedRuleIsCheckedToo() {
        AiSkill skill = skill("Clean text.");
        skill.setLearnedRules(List.of(AiSkillLearnedRule.create("Das Passwort ist " + SECRET,
            AiSkillLearnedRule.SOURCE_CHAT)));

        assertThat(AiSkillSecretCheck.findings(skill).get(0).field()).isEqualTo(AiSkill.TextField.LEARNED_RULE);
        assertThat(AiSkillSecretCheck.refusal(skill).orElseThrow()).doesNotContain(SECRET);
    }

    @Test
    void theDefaultValidatorRefusesSecretsAfterTheStorabilityCheck() {
        AiSkill secret = skill("The password is " + SECRET + " and nothing else.");
        AiSkill storableButSecret = skill("Passwort ist " + SECRET);

        assertThat(AiSkillRepository.storableOrSecretRefusal(secret)).isPresent();
        assertThat(AiSkillRepository.storableOrSecretRefusal(storableButSecret)).isPresent();
        assertThat(AiSkillRepository.storableOrSecretRefusal(skill("Plain text."))).isEmpty();
    }

    /** The shipped skills must not trip the check: the startup scan would badge them otherwise. */
    @Test
    void everyBuiltinSkillPassesTheCheck() {
        for (AiSkillMarkdownCodec.BundledAiSkill bundled : BuiltinAiSkillCatalog.load().entries()) {
            assertWithMessage(bundled.skill().getBuiltinId())
                .that(AiSkillSecretCheck.findings(bundled.skill()))
                .isEmpty();
        }
    }
}
