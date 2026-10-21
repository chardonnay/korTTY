package de.kortty.core;

import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.agentdesign.AgentModuleCatalog;
import de.kortty.agentdesign.AgentModuleInfo;
import de.kortty.agentdesign.AgentRole;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Every text of Plugins → KI-Agent Design — each design's name and description, the roles, the built-in
 * combinations, the texts of the designs themselves and the connection editor — exists in every bundled
 * language.
 */
class AgentDesignI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> FIXED_KEYS = List.of(
        "menu.plugins.aiAgentDesign",
        "menu.view.aiAgentPanel.statusBar",
        "menu.view.aiAgentPanel.design",
        "aiAgentDesign.title",
        "aiAgentDesign.comingLater",
        "aiAgentDesign.preview.title",
        "aiAgentDesign.preview.prompt",
        "aiAgentDesign.preview.answer",
        "aiAgentDesign.inline.title",
        "aiAgentDesign.inline.runningHint",
        "aiAgentDesign.inline.steps",
        "aiAgentDesign.strip.approval",
        "aiAgentDesign.metro.answer",
        "aiAgentDesign.steps.show",
        "aiAgentDesign.steps.hide",
        "aiAgentDesign.lines",
        "aiAgentDesign.exit",
        "aiAgentDesign.cursor.run",
        "aiAgentDesign.cursor.edit",
        "aiAgentDesign.cursor.dismiss",
        "aiAgentDesign.cursor.waiting",
        "aiAgentDesign.spotlight.prompt",
        "aiAgentDesign.spotlight.start",
        "aiAgentDesign.spotlight.history",
        "aiAgentDesign.spotlight.defaultProfile",
        "aiAgentDesign.spotlight.unavailable",
        "aiAgentDesign.hud.awaiting",
        "aiAgentDesign.hud.progress",
        "aiAgentDesign.toast.approval",
        "aiAgentDesign.toast.steps",
        "aiAgentDesign.pip.detach",
        "aiAgentDesign.pip.attach",
        "aiAgentDesign.pip.fold",
        "aiAgentDesign.plan.caption",
        "aiAgentDesign.plan.next",
        "aiAgentDesign.plan.running",
        "aiAgentDesign.cards.history",
        "aiAgentDesign.tmux.panes",
        "aiAgentDesign.chat.followUp",
        "aiAgentDesign.chat.send",
        "aiAgentDesign.table.time",
        "aiAgentDesign.table.kind",
        "aiAgentDesign.table.step",
        "aiAgentDesign.table.result",
        "aiAgentDesign.table.duration",
        "aiAgentDesign.table.tokens",
        "aiAgentDesign.table.waiting",
        "menu.view.aiAgentPanel.spotlight",
        "aiAgentDesign.menu.more",
        "aiAgentDesign.menu.workflow",
        "aiAgentDesign.menu.exportRun",
        "aiAgentDesign.menu.exportAll",
        "aiAgentDesign.menu.rerun",
        "aiAgentDesign.menu.copyAnswer",
        "aiAgentDesign.menu.earlierRuns",
        "aiAgentDesign.menu.lastRun",
        "aiAgentDesign.step.copyCommand",
        "aiAgentDesign.step.copyOutput",
        "connEdit.aiAgentDesign",
        "connEdit.aiAgentDesign.default",
        "connEdit.aiAgentDesign.unavailable");

    @Test
    void everyDesignTextExistsInEveryBundledLanguage() throws Exception {
        List<String> keys = new ArrayList<>(FIXED_KEYS);
        for (AgentModuleInfo module : AgentModuleCatalog.all()) {
            keys.add(module.nameKey());
            keys.add(module.summaryKey());
        }
        for (AgentRole role : AgentRole.values()) {
            keys.add(role.labelKey());
        }
        for (AgentDesignCombination preset : AgentDesignPresets.all()) {
            keys.add(AgentDesignPresets.nameKey(preset.id()));
        }
        for (String bundle : BUNDLES) {
            Properties localized = load(bundle);
            for (String key : keys) {
                String value = localized.getProperty(key);
                assertWithMessage("%s is missing %s", bundle, key).that(value).isNotNull();
                assertWithMessage("%s has a blank %s", bundle, key).that(value.isBlank()).isFalse();
            }
        }
    }

    private Properties load(String fileName) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle %s", fileName).that(in).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(in, StandardCharsets.UTF_8));
            return properties;
        }
    }
}
