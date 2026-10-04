package de.kortty.ui;

import de.kortty.model.AiSkill;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;

class AiSkillStorageGuardTest {

    private static AiSkill skill(String id, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName(id);
        skill.setContent(content);
        return skill;
    }

    @Test
    void refusesTheFirstSkillWithTextTheFileCannotStore() {
        AiSkill fine = skill("fine", "ok");
        AiSkill bell = skill("bell", "ring\u0007");
        AiSkill nul = skill("nul", "\u0000");

        AiSkillStorageGuard.Refusal refusal = AiSkillStorageGuard.firstRefusal(List.of(fine, bell, nul));

        assertThat(refusal.skill()).isSameInstanceAs(bell);
        assertThat(refusal.text().field()).isEqualTo(AiSkill.TextField.CONTENT);
        assertThat(refusal.message()).isEqualTo(I18n.get(AiSkillStorageGuard.MESSAGE_KEY, "U+0007"));
        assertThat(refusal.message()).contains("U+0007");
        assertThat(AiSkillStorageGuard.firstRefusal(List.of(fine))).isNull();
    }

    @Test
    void theFieldMessageNamesTheCharacter() {
        assertThat(AiSkillStorageGuard.messageFor("plain text")).isNull();
        assertThat(AiSkillStorageGuard.messageFor(null)).isNull();
        assertThat(AiSkillStorageGuard.messageFor("x￾")).contains("U+FFFE");
        assertThat(AiSkillStorageGuard.messageFor("\u001b[0m")).contains("U+001B");
    }

    @Test
    void onCloseARefusedSkillKeepsItsStoredVersionAndANewOneIsLeftOut() {
        AiSkill storedVersion = skill("a", "stored text");
        AiSkill editedA = skill("a", "edited\u0007");
        AiSkill editedB = skill("b", "fine edit");
        AiSkill newBroken = skill("c", "\u0001");

        List<AiSkill> kept = AiSkillStorageGuard.withStoredVersionOfRefused(
            List.of(editedA, editedB, newBroken), List.of(storedVersion));

        assertThat(kept.stream().map(AiSkill::getId).toList()).containsExactly("a", "b").inOrder();
        assertThat(kept.get(0).getContent()).isEqualTo("stored text");
        assertThat(kept.get(0)).isNotSameInstanceAs(storedVersion);
        assertThat(kept.get(1)).isSameInstanceAs(editedB);
    }

    @Test
    void everyBundleTranslatesTheMessageAndQuotesTheCharacter() throws IOException {
        for (String suffix : List.of("", "_de", "_es", "_fr", "_hr", "_it", "_nl", "_pt")) {
            Properties bundle = new Properties();
            try (InputStream in = AiSkillStorageGuardTest.class.getResourceAsStream(
                    "/i18n/messages" + suffix + ".properties")) {
                assertThat(in).isNotNull();
                bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            String message = bundle.getProperty(AiSkillStorageGuard.MESSAGE_KEY);
            assertThat(message).isNotNull();
            assertThat(message).contains("{0}");
            if (!suffix.isEmpty()) {
                assertThat(message).isNotEqualTo(
                    "This text contains the invisible character {0}, which the settings file cannot store. Remove it to save the skill.");
            }
        }
    }
}
