package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.HighlightMatcher;
import de.kortty.core.highlight.HighlightPreview;
import de.kortty.core.highlight.HighlightRuleValidator;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;

/**
 * The rule-set editor's model, without JavaFX: working copies until OK, read-only built-ins with
 * Duplicate, the limits, the shared validator deciding what may be saved, and the preview's view of a
 * set agreeing with the matcher a terminal pane runs.
 */
class HighlightRulesEditorModelTest {

    private static HighlightRule rule(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, false);
        rule.setBold(true);
        return rule;
    }

    private static HighlightRuleSet userSet(String id, String name, HighlightRule... rules) {
        return new HighlightRuleSet(id, name, new ArrayList<>(List.of(rules)));
    }

    private static GlobalSettings settingsWith(HighlightRuleSet... sets) {
        GlobalSettings settings = new GlobalSettings();
        settings.setHighlightRuleSets(new ArrayList<>(List.of(sets)));
        return settings;
    }

    @Test
    void theEditorWorksOnCopiesUntilItIsApplied() {
        GlobalSettings settings = settingsWith(userSet("web", "Web servers", rule("nginx")));
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(settings.getHighlightRuleSets());

        HighlightRuleSet working = model.userSets().getFirst();
        working.setName("Renamed");
        working.getRules().getFirst().setPattern("apache");
        model.addRule(working, -1).setPattern("php");

        // Cancel never calls applyTo: the stored sets are exactly as before.
        HighlightRuleSet stored = settings.getHighlightRuleSets().getFirst();
        assertThat(working).isNotSameInstanceAs(stored);
        assertThat(stored.getName()).isEqualTo("Web servers");
        assertThat(stored.getRules()).hasSize(1);
        assertThat(stored.getRules().getFirst().getPattern()).isEqualTo("nginx");
        assertThat(model.isModified()).isTrue();

        model.applyTo(settings);
        assertThat(settings.getHighlightRuleSets().getFirst().getName()).isEqualTo("Renamed");
        assertThat(settings.getHighlightRuleSets().getFirst().getRules()).hasSize(2);
        // The stored objects are copies, so editing on after OK cannot leak into the settings.
        working.setName("Later");
        assertThat(settings.getHighlightRuleSets().getFirst().getName()).isEqualTo("Renamed");
    }

    @Test
    void anUntouchedEditorIsNotModified() {
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(
            List.of(userSet("web", "Web servers", rule("nginx"))));

        assertThat(model.isModified()).isFalse();
        assertThat(model.deletedIds()).isEmpty();
        assertThat(new HighlightRulesEditorModel(null).isModified()).isFalse();
    }

    @Test
    void builtInSetsComeFirstAndAreReadOnly() {
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(List.of(userSet("web", "Web", rule("x"))));

        List<String> ids = model.allSets().stream().map(HighlightRuleSet::getId).toList();
        assertThat(ids).containsExactly(HighlightBuiltinSets.ERRORS, HighlightBuiltinSets.NETWORK,
            HighlightBuiltinSets.NETWORK_DEVICES, "web").inOrder();

        HighlightRuleSet errors = model.find(HighlightBuiltinSets.ERRORS);
        int rulesBefore = errors.getRules().size();
        assertThat(model.isReadOnly(errors)).isTrue();
        assertThat(model.delete(errors)).isFalse();
        assertThat(model.canAddRule(errors)).isFalse();
        assertThat(model.addRule(errors, -1)).isNull();
        assertThat(model.removeRule(errors, errors.getRules().getFirst())).isFalse();
        assertThat(model.moveRule(errors, 0, 1)).isEqualTo(-1);
        assertThat(errors.getRules()).hasSize(rulesBefore);
        assertThat(model.isReadOnly(model.find("web"))).isFalse();
    }

    @Test
    void duplicatingABuiltInGivesAnEditableValidCopyWithFreshIds() {
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(null);
        HighlightRuleSet errors = model.find(HighlightBuiltinSets.ERRORS);

        HighlightRuleSet copy = model.duplicate(errors);

        assertThat(model.isReadOnly(copy)).isFalse();
        assertThat(HighlightBuiltinSets.isReservedId(copy.getId())).isFalse();
        assertThat(copy.getName()).isEqualTo(I18n.get(HighlightRulesEditorModel.COPY_NAME_KEY,
            I18n.get(HighlightBuiltinSets.nameKey(HighlightBuiltinSets.ERRORS))));
        Set<String> sourceRuleIds = new HashSet<>();
        errors.getRules().forEach(rule -> sourceRuleIds.add(rule.getId()));
        copy.getRules().forEach(rule -> assertThat(sourceRuleIds).doesNotContain(rule.getId()));
        assertThat(copy.getRules()).hasSize(errors.getRules().size());
        assertThat(model.problems()).isEmpty();

        HighlightRuleSet second = model.duplicate(errors);
        assertThat(second.getName()).isEqualTo(copy.getName() + " 2");
    }

    @Test
    void aNewSetStartsWithOneEmptyRuleThatBlocksSavingUntilItHasAPattern() {
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(null);

        HighlightRuleSet created = model.addSet();

        assertThat(created.getName()).isEqualTo(I18n.get(HighlightRulesEditorModel.NEW_SET_NAME_KEY));
        assertThat(model.addSet().getName()).isEqualTo(created.getName() + " 2");
        assertThat(created.getRules()).hasSize(1);
        HighlightRule first = created.getRules().getFirst();
        assertThat(first.isWholeWord()).isTrue();
        assertThat(first.hasVisualEffect()).isTrue();

        HighlightRulesEditorModel.Problem problem = model.firstProblem().orElseThrow();
        assertThat(problem.set()).isSameInstanceAs(created);
        assertThat(problem.ruleIndex()).isEqualTo(0);
        assertThat(problem.key()).isEqualTo(HighlightRuleValidator.KEY_PATTERN_REQUIRED);
        assertThat(problem.message()).contains(created.getName());
        assertThat(problem.message()).contains(I18n.get(HighlightRuleValidator.KEY_PATTERN_REQUIRED));
        assertThat(model.canSave()).isFalse();

        model.userSets().forEach(set -> set.getRules().getFirst().setPattern("deploy"));
        assertThat(model.canSave()).isTrue();
    }

    @Test
    void aCharacterTheSettingsFileCannotHoldIsNamedBelowItsFieldAndBlocksSaving() {
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(List.of(userSet("ops", "Ops", rule("disk full"))));
        HighlightRuleSet set = model.userSets().getFirst();
        HighlightRule rule = set.getRules().getFirst();

        // Pasted from colored log output: saved, the escape character would keep global-settings.xml from loading.
        rule.setPattern("\u001b[31mERROR");
        String patternMessage = I18n.get(HighlightRuleValidator.KEY_PATTERN_UNSTORABLE, "U+001B");
        assertThat(HighlightRulesEditorModel.ruleMessages(rule, null)).containsExactly(patternMessage);
        assertThat(model.firstProblem().orElseThrow().message()).contains(patternMessage);
        assertThat(model.canSave()).isFalse();

        rule.setPattern("ERROR");
        rule.setName("Disk\u0007alert");
        assertThat(HighlightRulesEditorModel.ruleMessages(rule, null))
            .containsExactly(I18n.get(HighlightRuleValidator.KEY_RULE_NAME_UNSTORABLE, "U+0007"));
        assertThat(model.canSave()).isFalse();

        rule.setName("Disk alert");
        set.setName("Ops\u000Cteam");
        String nameMessage = I18n.get(HighlightRuleValidator.KEY_NAME_UNSTORABLE, "U+000C");
        assertThat(model.setMessages(set)).containsExactly(nameMessage);
        assertThat(model.firstProblem().orElseThrow().message()).contains(nameMessage);
        assertThat(model.canSave()).isFalse();

        set.setName("Ops team");
        assertThat(model.setMessages(set)).isEmpty();
        assertThat(model.canSave()).isTrue();
        // A built-in set is only looked at, so there is nothing to say below its name.
        assertThat(model.setMessages(model.find(HighlightBuiltinSets.IDS.getFirst()))).isEmpty();
    }

    @Test
    void theSharedValidatorDecidesWhatMayBeSaved() {
        HighlightRule broken = new HighlightRule("(", true);
        broken.setBold(true);
        HighlightRule noEffect = new HighlightRule("ok", false);
        HighlightRule switchedOffButBroken = new HighlightRule("a*", true);
        switchedOffButBroken.setUnderline(true);
        switchedOffButBroken.setEnabled(false);
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(List.of(
            userSet("one", "One", rule("fine"), broken, noEffect, switchedOffButBroken),
            userSet("two", " ", rule("fine"))));

        List<String> keys = model.problems().stream().map(HighlightRulesEditorModel.Problem::key).toList();
        assertThat(keys).containsExactly(HighlightRuleValidator.KEY_PATTERN_INVALID,
            HighlightRuleValidator.KEY_NO_EFFECT, HighlightRuleValidator.KEY_PATTERN_MATCHES_EMPTY,
            HighlightRuleValidator.KEY_NAME_REQUIRED).inOrder();
        assertThat(model.problems().stream().map(HighlightRulesEditorModel.Problem::ruleIndex).toList())
            .containsExactly(1, 2, 3, -1).inOrder();
        for (HighlightRulesEditorModel.Problem problem : model.problems()) {
            if (problem.ruleIndex() >= 0) {
                assertThat(HighlightRuleValidator.validateRule(problem.set().getRules().get(problem.ruleIndex())))
                    .contains(problem.key());
            }
        }
        // The rule number in the footer counts from one, as the table shows the rules.
        assertThat(model.problems().getFirst().message()).contains("2");
        assertThat(model.problems().get(3).message()).contains(I18n.get(HighlightRulesEditorModel.UNNAMED_KEY));
    }

    @Test
    void aHandEditedSetWithAReservedIdCanBeDeleted() {
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(
            List.of(userSet("builtin.mine", "Mine", rule("x"))));
        HighlightRuleSet mine = model.userSets().getFirst();

        assertThat(model.firstProblem().orElseThrow().key()).isEqualTo(HighlightRuleValidator.KEY_RESERVED_ID);
        assertThat(model.isReadOnly(mine)).isFalse();
        assertThat(model.delete(mine)).isTrue();
        assertThat(model.canSave()).isTrue();
    }

    @Test
    void theLimitsOfSetsAndRulesHold() {
        List<HighlightRuleSet> many = new ArrayList<>();
        for (int i = 0; i < HighlightRuleValidator.MAX_USER_SETS; i++) {
            many.add(userSet("set-" + i, "Set " + i, rule("x")));
        }
        HighlightRulesEditorModel full = new HighlightRulesEditorModel(many);
        assertThat(full.canAddSet()).isFalse();
        assertThat(full.addSet()).isNull();
        assertThat(full.duplicate(full.userSets().getFirst())).isNull();

        HighlightRulesEditorModel model = new HighlightRulesEditorModel(null);
        HighlightRuleSet set = model.addSet();
        while (set.getRules().size() < HighlightRuleValidator.MAX_RULES_PER_SET) {
            assertThat(model.addRule(set, -1)).isNotNull();
        }
        assertThat(model.canAddRule(set)).isFalse();
        assertThat(model.addRule(set, 0)).isNull();
    }

    @Test
    void rulesAreAddedBelowTheSelectionAndMoveWithinBounds() {
        HighlightRule a = rule("a");
        HighlightRule b = rule("b");
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(List.of(userSet("s", "S", a, b)));
        HighlightRuleSet set = model.userSets().getFirst();
        HighlightRule first = set.getRules().get(0);
        HighlightRule second = set.getRules().get(1);

        HighlightRule added = model.addRule(set, 0);
        assertThat(set.getRules()).containsExactly(first, added, second).inOrder();

        assertThat(model.moveRule(set, 2, -1)).isEqualTo(1);
        assertThat(set.getRules()).containsExactly(first, second, added).inOrder();
        assertThat(model.moveRule(set, 0, -1)).isEqualTo(-1);
        assertThat(model.moveRule(set, 2, 1)).isEqualTo(-1);
        assertThat(model.removeRule(set, added)).isTrue();
        assertThat(set.getRules()).containsExactly(first, second).inOrder();
    }

    @Test
    void deletingTheDefaultSetSwitchesTheDefaultBackToNone() {
        GlobalSettings settings = settingsWith(userSet("web", "Web", rule("x")), userSet("db", "DB", rule("y")));
        settings.setDefaultHighlightRuleSetId("web");
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(settings.getHighlightRuleSets());

        model.delete(model.find("web"));
        assertThat(model.deletedIds()).containsExactly("web");
        model.applyTo(settings);

        assertThat(settings.getDefaultHighlightRuleSetId()).isNull();
        assertThat(settings.getHighlightRuleSets().stream().map(HighlightRuleSet::getId).toList())
            .containsExactly("db");
    }

    @Test
    void aDefaultThatSurvivesTheEditIsKept() {
        GlobalSettings settings = settingsWith(userSet("web", "Web", rule("x")), userSet("db", "DB", rule("y")));
        settings.setDefaultHighlightRuleSetId("web");
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(settings.getHighlightRuleSets());

        model.delete(model.find("db"));
        model.find("web").setName("  Web servers  ");
        model.applyTo(settings);

        assertThat(settings.getDefaultHighlightRuleSetId()).isEqualTo("web");
        assertThat(settings.getHighlightRuleSets().getFirst().getName()).isEqualTo("Web servers");

        GlobalSettings builtinDefault = settingsWith(userSet("web", "Web", rule("x")));
        builtinDefault.setDefaultHighlightRuleSetId(HighlightBuiltinSets.NETWORK);
        HighlightRulesEditorModel other = new HighlightRulesEditorModel(builtinDefault.getHighlightRuleSets());
        other.delete(other.find("web"));
        other.applyTo(builtinDefault);
        assertThat(builtinDefault.getDefaultHighlightRuleSetId()).isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void theCheckColumnPutsProblemsFirstThenOffThenSpeed() {
        HighlightRule good = rule("error");
        HighlightPreview.RuleStats tooSlow = new HighlightPreview.RuleStats(true, 0, 3_000_000L,
            HighlightPreview.Speed.TOO_SLOW);
        HighlightPreview.RuleStats slow = new HighlightPreview.RuleStats(true, 4, 1_500_000L, HighlightPreview.Speed.SLOW);
        HighlightPreview.RuleStats fast = new HighlightPreview.RuleStats(true, 4, 10_000L, HighlightPreview.Speed.OK);

        assertThat(HighlightRulesEditorModel.statusKey(good, fast)).isNull();
        assertThat(HighlightRulesEditorModel.statusKey(good, slow)).isEqualTo(HighlightRulesEditorModel.STATUS_SLOW_KEY);
        assertThat(HighlightRulesEditorModel.statusKey(good, tooSlow))
            .isEqualTo(HighlightRulesEditorModel.STATUS_TOO_SLOW_KEY);
        good.setEnabled(false);
        assertThat(HighlightRulesEditorModel.statusKey(good, tooSlow)).isEqualTo(HighlightRulesEditorModel.STATUS_OFF_KEY);
        good.setPattern("");
        assertThat(HighlightRulesEditorModel.statusKey(good, tooSlow))
            .isEqualTo(HighlightRulesEditorModel.STATUS_INVALID_KEY);

        HighlightRule slowRule = rule("x");
        assertThat(HighlightRulesEditorModel.ruleMessages(slowRule, slow))
            .containsExactly(I18n.get(HighlightRulesEditorModel.SLOW_MESSAGE_KEY));
        assertThat(HighlightRulesEditorModel.ruleMessages(slowRule, tooSlow))
            .containsExactly(I18n.get(HighlightRulesEditorModel.TOO_SLOW_MESSAGE_KEY));
        assertThat(HighlightRulesEditorModel.ruleMessages(new HighlightRule("", false), slow)).containsExactly(
            I18n.get(HighlightRuleValidator.KEY_PATTERN_REQUIRED), I18n.get(HighlightRuleValidator.KEY_NO_EFFECT))
            .inOrder();
        assertThat(HighlightRulesEditorModel.hitsText(fast)).isEqualTo("4");
        assertThat(HighlightRulesEditorModel.hitsText(null)).isEqualTo("–");
    }

    @Test
    void thePreviewOfAnEditedSetMatchesWhatAPaneWouldShow() {
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(null);
        HighlightRuleSet set = model.addSet();
        set.getRules().getFirst().setPattern("error");
        HighlightRule address = model.addRule(set, 0);
        address.setRegex(true);
        address.setWholeWord(false);
        address.setPattern("\\d+\\.\\d+\\.\\d+\\.\\d+");
        HighlightRule line = model.addRule(set, 1);
        line.setPattern("timed out");
        line.setScope(HighlightRule.Scope.LINE);
        assertThat(model.canSave()).isTrue();

        String sample = HighlightRulesDialog.DEFAULT_SAMPLE;
        HighlightPreview.Result preview = HighlightPreview.evaluate(set, sample);
        CompiledHighlightSet compiled = CompiledHighlightSet.compile(set);
        List<String> lines = List.of(sample.split("\n"));

        assertThat(preview.lines()).hasSize(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            int[] owners = HighlightMatcher.match(compiled, lines.get(i),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(30)).owners();
            for (HighlightPreview.Span span : preview.lines().get(i).spans()) {
                for (int c = span.start(); c < span.end(); c++) {
                    assertWithMessage("line %s char %s", i, c).that(span.ruleIndex()).isEqualTo(owners[c]);
                }
            }
        }
        // The default test text exercises all three rules; the whole-line rule counts its one line once,
        // although the two rules above it split that line into several runs.
        assertThat(preview.rule(0).hits()).isEqualTo(1);
        assertThat(preview.rule(1).hits()).isEqualTo(1);
        assertThat(preview.rule(2).hits()).isEqualTo(1);
    }

    @Test
    void namesAreTranslatedForBuiltInsAndNeverAnIdForUserSets() {
        HighlightRulesEditorModel model = new HighlightRulesEditorModel(List.of(userSet("3f2a", null, rule("x"))));

        assertThat(HighlightRulesEditorModel.displayName(model.find(HighlightBuiltinSets.NETWORK)))
            .isEqualTo(I18n.get(HighlightBuiltinSets.nameKey(HighlightBuiltinSets.NETWORK)));
        assertThat(HighlightRulesEditorModel.displayName(model.find("3f2a")))
            .isEqualTo(I18n.get(HighlightRulesEditorModel.UNNAMED_KEY));
    }
}
