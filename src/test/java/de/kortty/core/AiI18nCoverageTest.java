package de.kortty.core;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiI18nCoverageTest {

    private static final List<String> BUNDLES = List.of(
        "messages.properties",
        "messages_de.properties",
        "messages_en.properties",
        "messages_it.properties",
        "messages_es.properties",
        "messages_pt.properties",
        "messages_fr.properties",
        "messages_hr.properties",
        "messages_nl.properties");

    private static final List<String> REQUIRED_KEYS = List.of(
        "menu.tools.aiManager",
        "ai.action.generateTitle",
        "ai.saved.defaultTitle",
        "ai.manager.title",
        "ai.manager.header",
        "ai.manager.empty",
        "ai.manager.column.title",
        "ai.manager.column.profile",
        "ai.manager.column.updated",
        "ai.manager.column.connection",
        "ai.manager.open",
        "ai.manager.rename",
        "ai.manager.delete",
        "ai.manager.refresh",
        "ai.manager.opened",
        "ai.manager.renamed",
        "ai.manager.deleted",
        "ai.manager.delete.title",
        "ai.manager.delete.header",
        "ai.manager.delete.content",
        "ai.profile.missing.title",
        "ai.profile.missing.header",
        "ai.profile.missing.content",
        "ai.result.profile",
        "ai.result.save",
        "ai.result.share",
        "ai.result.readOnly",
        "ai.result.save.title",
        "ai.result.save.header",
        "ai.result.rename.title",
        "ai.result.rename.header",
        "ai.result.save.label",
        "ai.result.save.generating",
        "ai.result.save.generated",
        "ai.result.save.generateFailed",
        "ai.result.save.success",
        "ai.result.save.failed",
        "ai.result.save.autosaveFailed",
        "ai.result.saveSnippet",
        "ai.result.saveSnippet.tooltip",
        "ai.result.saveSnippet.success",
        "ai.result.saveSnippet.failed",
        "ai.result.share.success",
        "ai.result.share.directoryFallback",
        "ai.result.share.failed",
        "ai.result.export.pdf.reportTitle",
        "ai.result.export.pdf.options.title",
        "ai.result.export.pdf.options.header",
        "ai.result.export.pdf.layout",
        "ai.result.export.pdf.layout.report",
        "ai.result.export.pdf.layout.compact",
        "ai.result.export.pdf.metadata",
        "ai.result.export.pdf.metadata.title",
        "ai.result.export.pdf.metadata.producer",
        "ai.result.export.pdf.metadata.subject",
        "ai.result.export.pdf.bookmarks",
        "ai.result.export.pdf.meta.exportedAt",
        "ai.result.export.pdf.meta.profile",
        "ai.result.export.pdf.meta.messages",
        "ai.result.export.pdf.pageNumber",
        "ai.table.copyTable",
        "ai.table.copyTable.success",
        "ai.table.copyColumn",
        "ai.table.copyColumn.success",
        "ai.manager.tab.profiles",
        "ai.manager.tab.chats",
        "ai.manager.profile.saved",
        "ai.manager.profile.test.success",
        "menu.tools.aiAgent",
        "menu.tools.aiPlanning",
        "terminal.contextMenu.ai.agent",
        "terminal.contextMenu.ai.agentAsk",
        "terminal.contextMenu.ai.plan",
        "settings.ai.featuresEnabled",
        "settings.ai.featuresEnabled.hint",
        "settings.ai.promptHook",
        "settings.ai.showDebugMessages",
        "settings.ai.showRuntimeMessages",
        "settings.ai.agentCommandName",
        "settings.ai.agentCommandInfo",
        "settings.ai.executionTarget",
        "settings.ai.executionTarget.terminalWindow",
        "settings.ai.executionTarget.chatWindow",
        "settings.ai.codeTextLanguage",
        "settings.ai.snippetInstructionsEnabled",
        "settings.ai.alternativeSolutionCount",
        "ai.agent.title",
        "ai.agent.header",
        "ai.agent.start",
        "ai.agent.connection",
        "ai.agent.prompt",
        "ai.agent.prompt.label",
        "ai.agent.showDebug",
        "ai.agent.showRuntime",
        "ai.agent.ask.title",
        "ai.agent.ask.header",
        "ai.agent.ask.start",
        "ai.agent.ask.connection",
        "ai.agent.ask.prompt",
        "ai.agent.ask.prompt.label",
        "ai.agent.ask.tabTitle",
        "ai.agent.profile",
        "ai.agent.connection.unknown",
        "ai.agent.executionTarget.value",
        "ai.agent.executionTarget.chatWindow",
        "ai.agent.executionTarget.terminalWindow",
        "ai.agent.error.noTerminal",
        "ai.agent.error.profileMissing",
        "ai.agent.approval.title",
        "ai.agent.approval.once",
        "ai.agent.approval.always",
        "ai.agent.password.title",
        "ai.agent.run.tabTitle",
        "ai.agent.run.phase.starting",
        "ai.agent.run.phase.failed",
        "ai.agent.run.status.preparing",
        "ai.agent.run.copy",
        "ai.agent.run.copy.done",
        "ai.agent.run.save",
        "ai.agent.run.saved",
        "ai.agent.run.saveFailed",
        "ai.agent.run.stop",
        "ai.agent.run.startedAt",
        "ai.agent.run.failed",
        "ai.plan.title",
        "ai.plan.header",
        "ai.plan.start",
        "ai.plan.connection",
        "ai.plan.prompt",
        "ai.plan.prompt.label",
        "ai.plan.tab.title",
        "ai.plan.probe.loading",
        "ai.plan.probe.summary",
        "ai.plan.questions",
        "ai.plan.answers",
        "ai.plan.answers.prompt",
        "ai.plan.answers.submit",
        "ai.plan.approach",
        "ai.plan.approach.prompt",
        "ai.plan.approach.submit",
        "ai.plan.options",
        "ai.plan.startAccepted",
        "ai.plan.status.starting",
        "ai.plan.status.probing",
        "ai.plan.status.options",
        "ai.plan.failed",
        "ai.plan.none",
        "ai.plan.option.feasibility",
        "ai.plan.option.risks",
        "ai.plan.option.prerequisites",
        "ai.plan.option.steps",
        "ai.plan.option.alternatives",
        "snippets.descriptionPrompt",
        "snippets.description.correct",
        "snippets.description.correct.tooltip",
        "snippets.ai.metadata.generate",
        "snippets.ai.metadata.generate.tooltip",
        "snippets.ai.metadata.generating",
        "snippets.ai.metadata.generated",
        "snippets.ai.metadata.generateFailed",
        "snippets.ai.description.correcting",
        "snippets.ai.description.corrected",
        "snippets.ai.description.correctFailed",
        "snippets.ai.description.empty",
        "snippets.ai.instructions.label",
        "snippets.ai.instructions.prompt",
        "snippets.ai.menu",
        "snippets.ai.menu.correct",
        "snippets.ai.menu.translate",
        "snippets.ai.menu.describe",
        "snippets.ai.noTextSegments",
        "snippets.ai.correctingSelection",
        "snippets.ai.selectionCorrected",
        "snippets.ai.selectionCorrectionFailed",
        "snippets.ai.translatingSelection",
        "snippets.ai.selectionTranslated",
        "snippets.ai.selectionTranslationFailed",
        "snippets.ai.translate.dialog.title",
        "snippets.ai.translate.dialog.prompt",
        "snippets.ai.description.generating",
        "snippets.ai.description.generated",
        "snippets.ai.description.generateFailed",
        "snippets.ai.description.inserted",
        "snippets.ai.describe.dialog.title",
        "snippets.ai.describe.commentSyntax",
        "snippets.ai.describe.copy",
        "snippets.ai.describe.insert",
        "snippets.ai.describe.info",
        "snippets.ai.toggle.tooltip",
        "snippets.ai.toggle.tooltip.showOriginal",
        "snippets.ai.toggle.tooltip.showModified",
        "snippets.ai.toggle.showingOriginal",
        "snippets.ai.toggle.showingModified",
        "snippets.ai.toggle.action.correct",
        "snippets.ai.toggle.action.translate",
        "snippets.ai.toggle.action.description",
        "snippets.ai.toggle.action.alternative",
        "snippets.ai.alternatives.context",
        "snippets.ai.alternatives.title",
        "snippets.ai.alternatives.instructions.prompt",
        "snippets.ai.alternatives.reload",
        "snippets.ai.alternatives.loading",
        "snippets.ai.alternatives.empty",
        "snippets.ai.alternatives.loaded",
        "snippets.ai.alternatives.failed",
        "snippets.ai.alternatives.apply",
        "snippets.ai.alternatives.applied",
        "snippets.ai.alternatives.zoom",
        "snippets.ai.alternatives.zoom.restore");

    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{\\d+}");

    @Test
    void allNewAiKeysExistInEveryBundledLocaleAndKeepPlaceholderCounts() throws Exception {
        Properties base = loadBundle("messages.properties");

        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (String key : REQUIRED_KEYS) {
                String baseValue = base.getProperty(key);
                String localizedValue = localized.getProperty(key);
                assertNotNull(baseValue, "Base bundle is missing key " + key);
                assertNotNull(localizedValue, bundle + " is missing key " + key);
                assertEquals(countPlaceholders(baseValue), countPlaceholders(localizedValue),
                    bundle + " has different placeholder count for key " + key);
                assertTrue(!localizedValue.isBlank(), bundle + " has blank value for key " + key);
            }
        }
    }

    private Properties loadBundle(String fileName) throws Exception {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertNotNull(inputStream, "Missing i18n bundle " + fileName);
            Properties properties = new Properties();
            properties.load(new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8));
            return properties;
        }
    }

    private int countPlaceholders(String value) {
        int count = 0;
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(value);
        while (matcher.find()) {
            count++;
        }
        return count;
    }
}
