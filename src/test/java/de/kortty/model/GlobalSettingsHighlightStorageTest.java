package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.UnmarshalException;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Highlight rule sets never put text into {@code global-settings.xml} that XML 1.0 cannot read back: one
 * such character would make the whole file fail to load, and every setting would be back at its default.
 */
class GlobalSettingsHighlightStorageTest {

    private static String marshal(GlobalSettings settings) throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(GlobalSettings.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter writer = new StringWriter();
        marshaller.marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class).createUnmarshaller()
            .unmarshal(new StringReader(xml));
    }

    private static HighlightRule rule(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, true);
        rule.setBold(true);
        return rule;
    }

    /** A setting away from its default, to tell a loaded file from the fallback to defaults. */
    private static GlobalSettings settingsWithAMarker() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalHighlightingEnabled(false);
        settings.setDefaultHighlightRuleSetId("ops");
        return settings;
    }

    @Test
    void theFileCannotHoldAControlCharacterWhichIsWhyNoneMayReachIt() {
        // What the guard prevents: JAXB writes the character raw, and reading the file back fails.
        String xml = "<globalSettings><highlightRuleSets><ruleSet><id>ops</id><name>Ops\u0007</name>"
            + "</ruleSet></highlightRuleSets></globalSettings>";

        expectThrows(UnmarshalException.class, () -> unmarshal(xml));
    }

    @Test
    void theSetterLeavesOutWhatTheFileCannotHoldSoTheSettingsStillLoad() throws Exception {
        HighlightRule kept = rule("disk (full|error)");
        kept.setForeground("#ff0000");
        HighlightRule escapePattern = rule("\u001b\\[31mERROR");
        HighlightRule bellName = rule("panic");
        bellName.setName("Kernel\u0007panic");
        HighlightRuleSet ops = new HighlightRuleSet("ops", "Ops", List.of(kept, escapePattern, bellName));
        HighlightRuleSet badName = new HighlightRuleSet("web", "Web￿", List.of(rule("nginx")));
        GlobalSettings settings = settingsWithAMarker();

        settings.setHighlightRuleSets(List.of(ops, badName));

        // The rule whose pattern cannot be stored is left out: without the character it would find something else.
        HighlightRuleSet storedOps = settings.getHighlightRuleSets().get(0);
        assertThat(storedOps.getId()).isEqualTo("ops");
        assertThat(storedOps.getRules().stream().map(HighlightRule::getPattern).toList())
            .containsExactly("disk (full|error)", "panic").inOrder();
        // A name only labels: the rule and the set stay, without it.
        assertThat(storedOps.getRules().get(1).getId()).isEqualTo(bellName.getId());
        assertThat(storedOps.getRules().get(1).getName()).isNull();
        assertThat(settings.getHighlightRuleSets().get(1).getName()).isNull();
        // The caller's objects are not changed.
        assertThat(ops.getRules()).hasSize(3);
        assertThat(bellName.getName()).isEqualTo("Kernel\u0007panic");

        GlobalSettings restored = unmarshal(marshal(settings));
        assertThat(restored.isTerminalHighlightingEnabled()).isFalse();
        assertThat(restored.getDefaultHighlightRuleSetId()).isEqualTo("ops");
        assertThat(restored.getHighlightRuleSets()).hasSize(2);
        HighlightRule restoredKept = restored.getHighlightRuleSets().get(0).getRules().get(0);
        assertThat(restoredKept.getId()).isEqualTo(kept.getId());
        assertThat(restoredKept.getForeground()).isEqualTo("#ff0000");
        assertThat(restored.getHighlightRuleSets().get(1).getRules().get(0).getPattern()).isEqualTo("nginx");
    }

    @Test
    void setsTheFileCanHoldAreStoredAsGiven() {
        HighlightRule tab = rule("a\tb");
        tab.setName("Deploy 🚀");
        HighlightRuleSet set = new HighlightRuleSet("ops", "Ops <&>", List.of(tab, rule("\\u0007")));
        GlobalSettings settings = new GlobalSettings();

        settings.setHighlightRuleSets(List.of(set));

        assertThat(settings.getHighlightRuleSets().get(0)).isSameInstanceAs(set);
        assertThat(set.getRules()).hasSize(2);
    }

    @Test
    void aSetAddedToTheLiveListIsCleanedBeforeTheFileIsWritten() throws Exception {
        HighlightRule textColor = rule("ERROR");
        // Inside the text: at either end a control character would be trimmed away with the spaces.
        textColor.setForeground("ansi:\u00001");
        textColor.setAction(HighlightRule.Action.RUN_SNIPPET);
        textColor.setSnippetId("snip\u0008pet");
        GlobalSettings settings = settingsWithAMarker();
        // Past the setter: the getter hands out the live list.
        settings.getHighlightRuleSets().add(
            new HighlightRuleSet("ops", "Ops", new ArrayList<>(List.of(rule("\uD800half"), textColor))));
        List<HighlightRuleSet> before = settings.getHighlightRuleSets();

        String xml = marshal(settings);

        GlobalSettings restored = unmarshal(xml);
        assertThat(restored.isTerminalHighlightingEnabled()).isFalse();
        List<HighlightRule> rules = restored.getHighlightRuleSets().get(0).getRules();
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).getPattern()).isEqualTo("ERROR");
        assertThat(rules.get(0).getForeground()).isNull();
        assertThat(rules.get(0).getSnippetId()).isNull();
        // The settings now hold what the file holds; the list someone may still be reading is left alone.
        assertThat(settings.getHighlightRuleSets().get(0).getRules()).hasSize(1);
        assertThat(before.get(0).getRules()).hasSize(2);
    }

    @Test
    void aListTheFileCanHoldIsNotReplacedWhenTheFileIsWritten() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.getHighlightRuleSets().add(new HighlightRuleSet("ops", "Ops", List.of(rule("ERROR"))));
        List<HighlightRuleSet> before = settings.getHighlightRuleSets();

        marshal(settings);

        assertThat(settings.getHighlightRuleSets()).isSameInstanceAs(before);
    }
}
