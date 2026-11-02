package de.kortty.memory;

import de.kortty.core.AiSkillMarkdownCodec;
import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.core.BuiltinAiSkillSupport;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillExternalSource;
import de.kortty.model.AiSkillTarget;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class RuleRouterTest {

    private static final BuiltinAiSkillCatalog CATALOG = BuiltinAiSkillCatalog.load();
    private static final RuleTargetPolicy ALL = new RuleTargetPolicy(true, true, 1500);
    private static final RuleTargetPolicy NO_SKILLS = new RuleTargetPolicy(true, false, 1500);
    private static final RuleTargetPolicy READ_ONLY = new RuleTargetPolicy(false, true, 1500);
    private static final RuleTargetPolicy NOTHING = new RuleTargetPolicy(false, false, 1500);

    private final RuleRouter router = new RuleRouter();

    @Test
    void perlRuleGoesToTheBuiltInPerlSkillWhateverTheSpelling() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        List<AiSkill> skills = List.of(perl);

        for (String spelling : List.of("Perl", "PERL", "perl")) {
            RuleProposal proposal = RuleProposal.of(spelling + ": statische Variablen nur mit state deklarieren",
                RuleSource.CHAT);
            assertThat(router.suggest(proposal, skills, CATALOG, ALL)).isEqualTo(RuleTarget.skill(perl.getId()));
        }
    }

    @Test
    void perlTopicFoundInTheTextOrByHintGoesToThePerlSkill() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        List<AiSkill> skills = List.of(perl);

        RuleProposal inText = RuleProposal.of("Declare static variables with state in PERL", RuleSource.AGENT);
        RuleProposal byHint = new RuleProposal("Declare static variables with state", RuleSource.AGENT,
            null, null, null, "Perl");

        assertThat(router.suggest(inText, skills, CATALOG, ALL)).isEqualTo(RuleTarget.skill(perl.getId()));
        assertThat(router.suggest(byHint, skills, CATALOG, ALL)).isEqualTo(RuleTarget.skill(perl.getId()));
    }

    @Test
    void userSkillTaggedWithThePerlTopicOverridesTheBuiltInSkill() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        AiSkill team = userSkill("Team Perl", List.of("perl"), "# Team\n\nUse Moose.");

        RuleTarget target = router.suggest(RuleProposal.of("Perl: use Moose", RuleSource.CHAT),
            List.of(perl, team), CATALOG, ALL);

        assertThat(target).isEqualTo(RuleTarget.skill(team.getId()));
    }

    @Test
    void ruleNamingTheConnectionHostGoesToTheConnectionMemory() {
        RuleProposal proposal = new RuleProposal("Auf 10.0.0.12 immer zuerst den Status prüfen", RuleSource.CHAT,
            "conn-7", "Produktion", "10.0.0.12", null);

        assertThat(router.suggest(proposal, List.of(), CATALOG, ALL)).isEqualTo(RuleTarget.connection("conn-7"));
    }

    @Test
    void ruleNamingTheConnectionDisplayNameMatchesCaseInsensitively() {
        RuleProposal proposal = new RuleProposal("Always take a snapshot before touching PRODUKTION", RuleSource.MANUAL,
            "conn-7", "Produktion", "10.0.0.12", null);

        assertThat(router.suggest(proposal, List.of(), CATALOG, ALL)).isEqualTo(RuleTarget.connection("conn-7"));
    }

    @Test
    void connectionIsNotSuggestedWhenTheMemoryIsReadOnlyAndNoSkillFits() {
        RuleProposal proposal = new RuleProposal("Auf 10.0.0.12 immer zuerst den Status prüfen", RuleSource.CHAT,
            "conn-7", "Produktion", "10.0.0.12", null);

        assertThat(router.suggest(proposal, List.of(), CATALOG, READ_ONLY)).isEqualTo(RuleTarget.none());
    }

    @Test
    void shortConnectionNamesDoNotMatchEveryRule() {
        RuleProposal proposal = new RuleProposal("Antworte immer kurz", RuleSource.CHAT, "conn-1", "DB", "db", null);

        assertThat(router.suggest(proposal, List.of(), CATALOG, ALL)).isEqualTo(RuleTarget.global());
    }

    @Test
    void unknownLanguageWithoutSkillGoesToANewKorttySkill() {
        RuleProposal proposal = RuleProposal.of("Elixir: Module immer mit @moduledoc dokumentieren", RuleSource.CHAT);

        assertThat(router.suggest(proposal, List.of(), CATALOG, ALL))
            .isEqualTo(RuleTarget.newSkill("elixir", "Elixir (korTTY)"));
    }

    @Test
    void newSkillIsNotSuggestedWhileAnyStoredSkillCarriesTheTopic() {
        AiSkill disabled = userSkill("Elixir basics", List.of("elixir"), "# Basics");
        disabled.setEnabled(false);

        RuleTarget target = router.suggest(RuleProposal.of("Elixir: Module dokumentieren", RuleSource.CHAT),
            List.of(disabled), CATALOG, ALL);

        assertThat(target).isEqualTo(RuleTarget.global());
    }

    @Test
    void skillCreationOffRoutesSkillRulesToGlobal() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");

        assertThat(router.suggest(RuleProposal.of("Perl: state verwenden", RuleSource.CHAT), List.of(perl), CATALOG,
            NO_SKILLS)).isEqualTo(RuleTarget.global());
        assertThat(router.suggest(RuleProposal.of("Elixir: Module dokumentieren", RuleSource.CHAT), List.of(), CATALOG,
            NO_SKILLS)).isEqualTo(RuleTarget.global());
    }

    @Test
    void memoryReadOnlyAndSkillCreationOffGiveNoTarget() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");

        assertThat(router.suggest(RuleProposal.of("Perl: state verwenden", RuleSource.CHAT), List.of(perl), CATALOG,
            NOTHING)).isEqualTo(RuleTarget.none());
        assertThat(router.allowedTargets(RuleProposal.of("Perl: state", RuleSource.CHAT), List.of(perl), CATALOG,
            NOTHING)).isEmpty();
    }

    @Test
    void readOnlyMemoryStillAllowsTheSkillOfTheTopic() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");

        assertThat(router.suggest(RuleProposal.of("Perl: state verwenden", RuleSource.CHAT), List.of(perl), CATALOG,
            READ_ONLY)).isEqualTo(RuleTarget.skill(perl.getId()));
    }

    @Test
    void ruleWithoutTopicGoesToGlobal() {
        assertThat(router.suggest(RuleProposal.of("Antworte immer auf Deutsch", RuleSource.CHAT), List.of(), CATALOG,
            ALL)).isEqualTo(RuleTarget.global());
    }

    @Test
    void shortWordsAreNotSearchedSoGoInEnglishIsNotTheGoSkill() {
        AiSkill go = storedBuiltin("builtin.lang.go");

        assertThat(router.suggest(RuleProposal.of("Always go through the bastion host", RuleSource.CHAT),
            List.of(go), CATALOG, ALL)).isEqualTo(RuleTarget.global());
    }

    @Test
    void userSkillWhoseTagMatchesTheTopicIsChosenByScore() {
        AiSkill deploy = userSkill("Deployment checklist", List.of("deploy", "rollout"), "# Checklist");

        RuleTarget target = router.suggest(new RuleProposal("Vor jedem Deploy die Checkliste durchgehen",
            RuleSource.CHAT, null, null, null, "deploy"), List.of(deploy), CATALOG, ALL);

        assertThat(target).isEqualTo(RuleTarget.skill(deploy.getId()));
    }

    @Test
    void weakRelevanceDoesNotChooseAUserSkill() {
        AiSkill recipes = userSkill("Kochrezepte", List.of("recipes"), "# Rezepte");

        assertThat(router.suggest(RuleProposal.of("Deploy: Checkliste nutzen", RuleSource.CHAT), List.of(recipes),
            CATALOG, ALL)).isEqualTo(RuleTarget.global());
    }

    @Test
    void disabledAndExternalSkillsAreNeverChosen() {
        AiSkill disabled = userSkill("Deploy disabled", List.of("deploy"), "# Off");
        disabled.setEnabled(false);
        AiSkill external = userSkill("Deploy external", List.of("deploy"), "# External");
        external.setExternalSource(new AiSkillExternalSource());

        assertThat(router.suggest(RuleProposal.of("Deploy: Checkliste", RuleSource.CHAT), List.of(disabled, external),
            CATALOG, ALL)).isEqualTo(RuleTarget.global());
    }

    @Test
    void allowedTargetsOffersMemorySkillsAndANewSkillAndTheSuggestionIsAmongThem() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        AiSkill deploy = userSkill("Deployment checklist", List.of("deploy"), "# Checklist");
        RuleProposal proposal = new RuleProposal("Elixir: Module dokumentieren", RuleSource.CHAT, "conn-7",
            "Produktion", "10.0.0.12", null);

        List<RuleTarget> allowed = router.allowedTargets(proposal, List.of(perl, deploy), CATALOG, ALL);
        RuleTarget suggested = router.suggest(proposal, List.of(perl, deploy), CATALOG, ALL);

        assertThat(allowed).containsExactly(
            RuleTarget.connection("conn-7"),
            RuleTarget.global(),
            RuleTarget.skill(deploy.getId()),
            RuleTarget.newSkill("elixir", "Elixir (korTTY)"))
            .inOrder();
        assertThat(allowed).contains(suggested);
    }

    @Test
    void allowedTargetsWithoutSkillCreationHasOnlyTheMemory() {
        AiSkill deploy = userSkill("Deployment checklist", List.of("deploy"), "# Checklist");

        assertThat(router.allowedTargets(RuleProposal.of("Deploy: Checkliste", RuleSource.CHAT), List.of(deploy),
            CATALOG, NO_SKILLS)).containsExactly(RuleTarget.global());
    }

    @Test
    void rulesNeverPickAnotherTargetThanThePolicyAllows() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        RuleProposal proposal = RuleProposal.of("Perl: state verwenden", RuleSource.CHAT);

        for (RuleTargetPolicy policy : List.of(ALL, NO_SKILLS, READ_ONLY, NOTHING)) {
            RuleTarget target = router.suggest(proposal, List.of(perl), CATALOG, policy);
            List<RuleTarget> allowed = router.allowedTargets(proposal, List.of(perl), CATALOG, policy);
            if (target instanceof RuleTarget.None) {
                assertThat(allowed).isEmpty();
            } else {
                assertThat(allowed).contains(target);
            }
        }
    }

    private static AiSkill storedBuiltin(String builtinId) {
        AiSkillMarkdownCodec.BundledAiSkill shipped = CATALOG.byId(builtinId).orElseThrow();
        AiSkill skill = new AiSkill(shipped.skill());
        skill.setBuiltinBaseline(BuiltinAiSkillSupport.baselineOf(shipped));
        return skill;
    }

    private static AiSkill userSkill(String name, List<String> tags, String content) {
        AiSkill skill = new AiSkill();
        skill.setName(name);
        skill.setTags(tags);
        skill.setEnabled(true);
        skill.setTarget(AiSkillTarget.BOTH);
        skill.setContent(content);
        return skill;
    }


    @Test
    void aHintThatIsNotAKnownTopicDoesNotOverrideTheTextOfTheRule() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        for (String hint : List.of("Perl 5", "Perl-Skripte", "perl5", "Perl (Perl 5)")) {
            RuleProposal proposal = new RuleProposal("Perl: statische Variablen nur mit state deklarieren",
                RuleSource.CHAT, null, null, null, hint);
            assertThat(router.suggest(proposal, List.of(perl), CATALOG, ALL))
                .isEqualTo(RuleTarget.skill(perl.getId()));
        }
    }

    @Test
    void aToolTheListsDoNotKnowGetsItsOwnNewSkill() {
        RuleProposal proposal = new RuleProposal("Use runes for reactive state", RuleSource.CHAT, null, null, null,
            "svelte");
        assertThat(router.suggest(proposal, List.of(), CATALOG, ALL))
            .isEqualTo(RuleTarget.newSkill("svelte", "Svelte (korTTY)"));
    }

    @Test
    void aSkillWithoutContentNeverReceivesARuleAndTheBuiltInStaysInCharge() {
        AiSkill perl = storedBuiltin("builtin.lang.perl");
        AiSkill draft = userSkill("Perl draft", List.of("perl"), "   ");
        List<AiSkill> skills = List.of(perl, draft);
        RuleProposal proposal = RuleProposal.of("Perl: use Moose", RuleSource.CHAT);

        assertThat(router.suggest(proposal, skills, CATALOG, ALL)).isEqualTo(RuleTarget.skill(perl.getId()));
        assertThat(router.allowedTargets(proposal, skills, CATALOG, ALL))
            .doesNotContain(RuleTarget.skill(draft.getId()));
    }

    @Test
    void existingSkillsCanStillGetARuleWhileNewSkillsAreRefused() {
        AiSkill team = userSkill("Team Perl", List.of("perl"), "# Team\n\nUse Moose.");
        RuleTargetPolicy learnOnly = new RuleTargetPolicy(true, false, 1500, true);

        assertThat(router.suggest(RuleProposal.of("Perl: use Moose", RuleSource.CHAT), List.of(team), CATALOG,
            learnOnly)).isEqualTo(RuleTarget.skill(team.getId()));
        assertThat(router.suggest(RuleProposal.of("Svelte: use runes", RuleSource.CHAT), List.of(), CATALOG,
            learnOnly)).isEqualTo(RuleTarget.global());
    }
}
