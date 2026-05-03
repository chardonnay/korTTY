package de.kortty.model;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;


class AiSkillTest {

    @Test
    void copyConstructorNormalizesIdAndDefaultTarget() {
        AiSkill source = new AiSkill() {
            @Override
            public String getId() {
                return "  copied-id  ";
            }

            @Override
            public AiSkillTarget getTarget() {
                return null;
            }
        };

        AiSkill copy = new AiSkill(source);

        assertThat(copy.getId()).isEqualTo("copied-id");
        assertThat(copy.getTarget()).isEqualTo(AiSkill.DEFAULT_TARGET);
    }
}
