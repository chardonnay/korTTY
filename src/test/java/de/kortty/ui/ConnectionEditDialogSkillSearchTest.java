package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.AiSkill;
import java.util.List;
import org.testng.annotations.Test;

/** The search field above the connection skills filters by name, description and tags. */
public class ConnectionEditDialogSkillSearchTest {

    @Test
    void matchesNameDescriptionAndTagsIgnoringCase() {
        AiSkill skill = new AiSkill();
        skill.setName("Ansible");
        skill.setDescription("Playbooks and inventories");
        skill.setTags(List.of("automation"));

        assertThat(ConnectionEditDialog.aiSkillMatchesSearch(skill, null)).isTrue();
        assertThat(ConnectionEditDialog.aiSkillMatchesSearch(skill, "  ")).isTrue();
        assertThat(ConnectionEditDialog.aiSkillMatchesSearch(skill, "ansi")).isTrue();
        assertThat(ConnectionEditDialog.aiSkillMatchesSearch(skill, " PLAYBOOK ")).isTrue();
        assertThat(ConnectionEditDialog.aiSkillMatchesSearch(skill, "automation")).isTrue();
        assertThat(ConnectionEditDialog.aiSkillMatchesSearch(skill, "kubernetes")).isFalse();
    }
}
