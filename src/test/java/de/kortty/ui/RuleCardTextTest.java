package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleSource;
import de.kortty.memory.RuleTarget;
import de.kortty.model.AiSkill;
import java.util.List;
import org.testng.annotations.Test;

class RuleCardTextTest {

    private static final String RULE = "Verwende in Perl immer Tabs.";
    private static final BuiltinAiSkillCatalog CATALOG = BuiltinAiSkillCatalog.load();

    @Test
    void theGlobalPreviewShowsTheEntryAsItWillBeStored() {
        String preview = RuleCardText.preview(RuleTarget.global(), RULE, List.of(), CATALOG);

        assertThat(preview).endsWith("\n- " + RULE);
    }

    @Test
    void theSkillPreviewNamesTheSkillAndNewSkillsAreNamedToo() {
        AiSkill skill = new AiSkill();
        skill.setId("skill-perl");
        skill.setName("Perl-Stil");

        assertThat(RuleCardText.preview(RuleTarget.skill("skill-perl"), RULE, List.of(skill), CATALOG))
            .contains("Perl-Stil");
        assertThat(RuleCardText.preview(RuleTarget.newSkill("elixir", "Elixir (korTTY)"), RULE, List.of(), CATALOG))
            .contains("Elixir (korTTY)");
    }

    @Test
    void noTargetPreviewsNothing() {
        assertThat(RuleCardText.preview(RuleTarget.none(), RULE, List.of(), CATALOG)).isEmpty();
    }

    @Test
    void aConnectionIsNamedByItsNameOrItsHost() {
        RuleProposal named = new RuleProposal(RULE, RuleSource.CHAT, "c1", "Build", "build.example.org", null);
        RuleProposal hostOnly = new RuleProposal(RULE, RuleSource.CHAT, "c2", null, "build.example.org", null);

        assertThat(RuleCardText.savedMessage(RuleTarget.connection("c1"), named, List.of(), CATALOG)).contains("Build");
        assertThat(RuleCardText.savedMessage(RuleTarget.connection("c2"), hostOnly, List.of(), CATALOG))
            .contains("build.example.org");
    }

    @Test
    void aFailedSaveWithoutARefusalSaysThatTheRuleCouldNotBeSaved() {
        assertThat(RuleCardText.refusalText(new IllegalStateException("disk"))).isEqualTo(I18n.get("ai.learn.refused.failed"));
    }
}
