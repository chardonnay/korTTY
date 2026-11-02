package de.kortty.ui;

import de.kortty.core.AiSkillRepository;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillLearnedRule;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The save of the skills pane merges the editor's copy with what korTTY stored meanwhile. These cases pin the merge the
 * pane relies on: a stored change the editor did not touch survives, an undone rule stays removed, and a skill added
 * meanwhile is kept.
 */
class AiSkillsPaneMergeTest {

    private static AiSkill skill(String id, String name, String content) {
        AiSkill skill = new AiSkill();
        skill.setId(id);
        skill.setName(name);
        skill.setContent(content);
        skill.setLearnedRules(new ArrayList<>());
        return skill;
    }

    @Test
    void aSaveKeepsADescriptionKorttyChangedMeanwhile() {
        AiSkill base = skill("deploy", "Deploy", "# Deploy");
        base.setDescription("old description");
        AiSkill editor = new AiSkill(base);
        editor.setContent("# Deploy, edited in the editor");
        AiSkill stored = new AiSkill(base);
        stored.setDescription("new description from a rule");

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(List.of(base), List.of(editor), List.of(stored));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getDescription()).isEqualTo("new description from a rule");
        assertThat(merged.get(0).getContent()).isEqualTo("# Deploy, edited in the editor");
    }

    @Test
    void aRuleKorttyRemovedMeanwhileStaysRemovedAfterTheSave() {
        AiSkill base = skill("perl", "Perl", "# Perl");
        base.getLearnedRules().add(AiSkillLearnedRule.create("Use strict", AiSkillLearnedRule.SOURCE_CHAT));
        AiSkill editor = new AiSkill(base);
        editor.setContent("# Perl, edited");
        AiSkill stored = new AiSkill(base);
        stored.getLearnedRules().clear();

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(List.of(base), List.of(editor), List.of(stored));

        assertThat(merged).hasSize(1);
        assertThat(merged.get(0).getLearnedRules()).isEmpty();
    }

    @Test
    void aSkillAddedMeanwhileIsKeptAfterTheEditorsSkills() {
        AiSkill existing = skill("perl", "Perl", "# Perl");
        AiSkill added = skill("elixir", "Elixir", "# Elixir");

        List<AiSkill> merged = AiSkillRepository.threeWayMerge(
            List.of(existing), List.of(new AiSkill(existing)), List.of(new AiSkill(existing), added));

        assertThat(merged).hasSize(2);
        assertThat(merged.get(0).getId()).isEqualTo("perl");
        assertThat(merged.get(1).getId()).isEqualTo("elixir");
    }
}
