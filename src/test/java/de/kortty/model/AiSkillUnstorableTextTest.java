package de.kortty.model;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiSkillUnstorableTextTest {

    @Test
    void reportsTheFirstFieldInEditorOrderAndItsFirstCharacter() {
        AiSkill skill = new AiSkill();
        skill.setName("Fine");
        skill.setDescription("Also fine");
        skill.setTags(List.of("ok", "bad￿"));
        skill.setContent("Bell\u0007");

        AiSkill.UnstorableText text = skill.firstUnstorableText();

        assertThat(text.field()).isEqualTo(AiSkill.TextField.TAGS);
        assertThat(text.codePointLabel()).isEqualTo("U+FFFF");
    }

    @Test
    void namesAControlCharacterInTheContentWithFourHexDigits() {
        AiSkill skill = new AiSkill();
        skill.setName("Skill");
        skill.setContent("echo hi\u0007");

        assertThat(skill.firstUnstorableText())
            .isEqualTo(new AiSkill.UnstorableText(AiSkill.TextField.CONTENT, 0x7));
        assertThat(skill.firstUnstorableText().codePointLabel()).isEqualTo("U+0007");
    }

    @Test
    void aLoneSurrogateIsReportedButAPairIsNot() {
        AiSkill skill = new AiSkill();
        skill.setName("Rocket 🚀");
        assertThat(skill.firstUnstorableText()).isNull();

        skill.setDescription("half \uDC00");
        assertThat(skill.firstUnstorableText())
            .isEqualTo(new AiSkill.UnstorableText(AiSkill.TextField.DESCRIPTION, 0xDC00));
    }

    @Test
    void tabLineFeedAndCarriageReturnAreStorable() {
        AiSkill skill = new AiSkill();
        skill.setName("Skill");
        skill.setContent("a\tb\nc\r\nd");

        assertThat(skill.firstUnstorableText()).isNull();
        assertThat(skill.stripUnstorableText()).isFalse();
        assertThat(skill.getContent()).isEqualTo("a\tb\nc\r\nd");
    }

    @Test
    void strippingRemovesOnlyTheUnstorableCharactersAndReportsIt() {
        AiSkill skill = new AiSkill();
        skill.setName("\u0001");
        skill.setContent("keep\u0000 this\uD800");
        skill.setTags(List.of("\u0002"));

        assertThat(skill.stripUnstorableText()).isTrue();

        assertThat(skill.getName()).isEmpty();
        assertThat(skill.getContent()).isEqualTo("keep this");
        assertThat(skill.getTags()).isEmpty();
        assertThat(skill.firstUnstorableText()).isNull();
    }

    @Test
    void anIdThatStripsToNothingIsReplaced() {
        AiSkill skill = new AiSkill();
        skill.setId("\u0001");

        skill.stripUnstorableText();

        assertThat(skill.getId()).isNotEmpty();
        assertThat(XmlStorableText.isStorable(skill.getId())).isTrue();
    }
}
