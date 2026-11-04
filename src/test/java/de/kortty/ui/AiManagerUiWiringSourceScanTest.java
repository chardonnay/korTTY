package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The AI manager's memory and skills UI: the memory tab scrolls, disabled actions name their reason, the statistics
 * button explains a policy, the history diff shows the selected version, and confirmations open over their window.
 */
class AiManagerUiWiringSourceScanTest {

    private static String source(String name) throws IOException {
        return Files.readString(Path.of("src/main/java/de/kortty/ui/" + name + ".java")).replace("\r\n", "\n");
    }

    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertThat(start).isAtLeast(0);
        int end = source.indexOf("\n    }\n", start);
        assertThat(end).isGreaterThan(start);
        return source.substring(start, end);
    }

    @Test
    void theMemoryAndSkillsTabsFillTheDialogAndScrollOnlyWhenItIsSmall() throws IOException {
        String dialog = source("AiManagerDialog");
        assertThat(methodBody(dialog, "private Tab buildAiMemoryTab()")).contains("AiManagerSections.fillingScroller(aiMemoryPane)");
        assertThat(methodBody(dialog, "private Tab buildAiSkillsTab()")).contains("AiManagerSections.fillingScroller(aiSkillsPane)");
        String scroller = methodBody(source("AiManagerSections"), "static ScrollPane fillingScroller(Region content)");
        assertThat(scroller).contains("scroller.setFitToHeight(true)");
        assertThat(scroller).contains("content.setMinHeight(Region.USE_PREF_SIZE)");
        String memory = source("AiMemoryPane");
        assertThat(memory).contains("VBox.setVgrow(filesBox, Priority.ALWAYS)");
        assertThat(memory).contains("VBox.setVgrow(entriesBox, Priority.ALWAYS)");
        assertThat(memory).contains("VBox.setVgrow(markdownBox, Priority.ALWAYS)");
    }

    @Test
    void theStatisticsButtonNamesThePolicyThatTurnsStatisticsOff() throws IOException {
        assertThat(source("AiSkillsPane")).contains("settings.aiSkills.stats.policyDisabled");
    }

    @Test
    void theAutomaticFirstSelectionKeepsTheNewBadgeOfAKorttySkill() throws IOException {
        String pane = source("AiSkillsPane");
        assertWithMessage("the pane selects the first row only through the helper that skips acknowledging")
            .that(pane.split("getSelectionModel\\(\\)\\.selectFirst\\(\\)", -1).length - 1).isEqualTo(1);
        assertThat(methodBody(pane, "private void selectFirstAiSkillWithoutAcknowledging()"))
            .contains("selectingFirstAiSkill = true");
        assertThat(pane).contains("if (!selectingFirstAiSkill) {\n                acknowledgeKorttyChanges(newValue);");
    }

    @Test
    void theHistoryDiffShowsOnlyTheVersionThatIsStillSelected() throws IOException {
        String history = source("AiMemoryHistoryDialog");
        assertThat(methodBody(history, "private void showVersion(HistoryEntry entry)"))
            .contains("entry == versions.getSelectionModel().getSelectedItem()");
    }

    @Test
    void disabledMemoryActionsHaveAReasonTooltip() throws IOException {
        String pane = source("AiMemoryPane");
        assertThat(methodBody(pane, "private void updateButtons()")).contains("setDisabledReason(markdownSaveButton");
        assertThat(methodBody(pane, "private void updateButtons()")).contains("setDisabledReason(historyButton");
        assertThat(source("ExternalMemorySection")).contains("remove.setTooltip(allowed ? null");
    }

    @Test
    void everyConfirmationOfTheMemoryAndSkillUiOpensOverItsWindow() throws IOException {
        List<String> files = List.of("AiMemoryPane", "ExternalMemorySection", "AiSkillsPane", "AiSkillStatisticsDialog",
            "AiMemoryHistoryDialog");
        for (String name : files) {
            String[] lines = source(name).split("\n");
            for (int index = 0; index < lines.length; index++) {
                String line = lines[index];
                if (!line.contains("new Alert(") && !line.contains("new TextInputDialog(")) {
                    continue;
                }
                assertWithMessage(name + ":" + (index + 1)).that(lines[index + 1]).contains("DialogThemeHelper.ownedBy(");
            }
        }
    }

    @Test
    void theSearchLineOverlaysTheTabsAndEveryPageCarriesKeywords() throws IOException {
        String dialog = source("AiManagerDialog");
        assertThat(dialog).contains("aiManagerSearch = new SettingsSearchBar(tabPane, body, \"ai.manager.search.prompt\", "
            + "\"ai.manager.search.tooltip\");");
        assertThat(dialog).contains("BorderPane content = new BorderPane(aiManagerSearch.host());");
        assertThat(dialog).contains("getDialogPane().setContent(content);");
        for (String page : List.of("skills", "memory", "localModels", "knowledge", "profiles", "general", "localAi")) {
            assertThat(dialog).contains("ai.manager.search.keywords." + page);
        }
        assertThat(source("SettingsSearchBar")).contains("isInWebView(focusOwner)");
    }

    @Test
    void theGeneralAndLocalAiPagesAreBuiltUpFrontWhileTheHeavyPagesStayLazy() throws IOException {
        String dialog = source("AiManagerDialog");
        assertThat(methodBody(dialog, "private Tab buildGeneralTab()")).contains("LazyTabContent.defer(tab, generalPane);");
        assertThat(methodBody(dialog, "private Tab buildLocalPreferencesTab()")).contains("LazyTabContent.defer(tab, localPreferencesPane);");
        assertThat(methodBody(dialog, "private Tab buildAiSkillsTab()")).contains("LazyTabContent.defer(tab, () -> {");
        assertThat(methodBody(dialog, "private Tab buildAiMemoryTab()")).contains("LazyTabContent.defer(tab, () -> {");
        assertThat(methodBody(dialog, "private Tab buildLocalModelsTab()")).contains("LazyTabContent.defer(tab, () -> {");
        assertThat(methodBody(dialog, "private void refreshProfiles()")).contains("generalPane.refreshProfiles(profiles);");
    }

    @Test
    void theQuietCloseSaveOfTheGeneralPageWritesOnlyChanges() throws IOException {
        String general = source("AiGeneralSettingsPane");
        assertThat(methodBody(general, "boolean save(boolean quiet)")).contains("if (quiet && !hasUnsavedChanges()) {");
        assertThat(source("AiManagerDialog")).contains("generalPane.save(true);");
    }
}
