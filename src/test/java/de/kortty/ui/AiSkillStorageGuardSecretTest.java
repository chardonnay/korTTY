package de.kortty.ui;

import de.kortty.model.AiSkill;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The editor refuses a skill it changed that holds a possible secret, and only that skill. */
class AiSkillStorageGuardSecretTest {

    private static final String SECRET_TEXT = "Das Passwort ist Sonnenblume42";

    private static AiSkill skill(String id, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName(id);
        skill.setContent(content);
        return skill;
    }

    @Test
    void aLegacySecretDoesNotBlockAnUnrelatedSave() {
        AiSkill legacy = skill("legacy", SECRET_TEXT);
        AiSkill edited = skill("other", "Clean text.");
        List<AiSkill> base = List.of(new AiSkill(legacy), new AiSkill(edited));
        List<AiSkill> working = List.of(new AiSkill(legacy), skill("other", "Edited clean text."));

        assertThat(AiSkillStorageGuard.firstSecretRefusal(working, base)).isNull();
    }

    @Test
    void aChangedSkillWithASecretIsRefusedAndNamed() {
        AiSkill original = skill("deploy", "Clean text.");
        List<AiSkill> base = List.of(new AiSkill(original));
        List<AiSkill> working = List.of(skill("deploy", "Clean text.\n" + SECRET_TEXT));

        AiSkillStorageGuard.SecretRefusal refusal = AiSkillStorageGuard.firstSecretRefusal(working, base);

        assertThat(refusal).isNotNull();
        assertThat(refusal.skill().getId()).isEqualTo("deploy");
        assertThat(refusal.message()).contains("2");
        assertThat(refusal.message()).doesNotContain("Sonnenblume42");
    }

    @Test
    void aNewSkillWithASecretIsRefused() {
        AiSkillStorageGuard.SecretRefusal refusal = AiSkillStorageGuard.firstSecretRefusal(
            List.of(skill("fresh", SECRET_TEXT)), List.of());

        assertThat(refusal).isNotNull();
        assertThat(refusal.skill().getId()).isEqualTo("fresh");
    }
}
