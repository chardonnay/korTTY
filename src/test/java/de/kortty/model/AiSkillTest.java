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

    @Test
    void copyConstructorDeepCopiesBuiltinFields() {
        AiSkill source = new AiSkill();
        source.setName("Perl (Perl 5)");
        source.setBuiltinId("builtin.lang.perl");
        source.setHidden(true);
        source.setBuiltinTopics(java.util.List.of("perl"));
        AiSkillBuiltinBaseline baseline = new AiSkillBuiltinBaseline();
        baseline.setName("Perl (Perl 5)");
        baseline.setContent("Use strict and warnings.");
        baseline.setTags(java.util.List.of("perl code", "cpan"));
        baseline.setVersion(3);
        source.setBuiltinBaseline(baseline);

        AiSkill copy = new AiSkill(source);

        assertThat(copy.getBuiltinId()).isEqualTo("builtin.lang.perl");
        assertThat(copy.isBuiltin()).isTrue();
        assertThat(copy.isHidden()).isTrue();
        assertThat(copy.getBuiltinTopics()).containsExactly("perl");
        assertThat(copy.getBuiltinBaseline()).isNotNull();
        assertThat(copy.getBuiltinBaseline().getVersion()).isEqualTo(3);

        // The baseline must be an independent copy, not a shared reference.
        copy.getBuiltinBaseline().setContent("changed");
        assertThat(source.getBuiltinBaseline().getContent()).isEqualTo("Use strict and warnings.");
    }

    @Test
    void builtinIdIsTrimmedToNull() {
        AiSkill skill = new AiSkill();
        assertThat(skill.isBuiltin()).isFalse();

        skill.setBuiltinId("  builtin.shell.bash  ");
        assertThat(skill.getBuiltinId()).isEqualTo("builtin.shell.bash");
        assertThat(skill.isBuiltin()).isTrue();

        skill.setBuiltinId("   ");
        assertThat(skill.getBuiltinId()).isNull();
        assertThat(skill.isBuiltin()).isFalse();
    }
}
