package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.testng.annotations.Test;

/**
 * Where a connection's keyword highlighting rule set is hooked in, which no headless test can build:
 * the connection editor stores the dropdown outside the blocks that custom terminal settings and terminal
 * effects switch off, Quick Connect and the Connection Manager import carry the value like every other
 * per-connection override, every pane resolves its connection between its own choice and the default,
 * and saving connections moves the open panes.
 */
class HighlightConnectionWiringTest {

    private static final Path UI = Path.of("src/main/java/de/kortty/ui");

    @Test
    void theConnectionEditorStoresTheChosenSetOnSave() throws IOException {
        String converter = region(source("ConnectionEditDialog.java"), "setResultConverter(dialogButton -> {",
            "connection.setHighlightRuleSetId(HighlightConnectionSupport.storedValue(highlightRuleSetCombo.getValue()));");

        assertWithMessage("stored with the other fields when Save is pressed")
            .that(converter).contains("if (dialogButton == saveButtonType) {");
        assertThat(converter).contains("saveTerminalEffectSettings();");
    }

    @Test
    void theSectionSitsOutsideTheCustomSettingsAndEffectBlocks() throws IOException {
        String tab = region(source("ConnectionEditDialog.java"), "private Tab createSettingsTab() {", "\n    }\n");

        int section = tab.indexOf("createTerminalBehaviorGrid()");
        int effects = tab.indexOf("if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {");
        assertThat(section).isAtLeast(0);
        assertWithMessage("the section must stay visible while terminal effects are switched off")
            .that(section).isLessThan(effects);
        assertWithMessage("custom terminal settings disable settingsGrid; the rule set must not depend on them")
            .that(tab).doesNotContain("settingsGrid.add(highlightRuleSetCombo");
        assertThat(tab).contains("new Label(I18n.get(HighlightConnectionSupport.SECTION_KEY))");
    }

    @Test
    void quickConnectAndTheImportCarryTheSet() throws IOException {
        String baseCopy = region(source("QuickConnectDialog.java"), "private ServerConnection baseCopyOf(", "\n    }\n");
        assertThat(baseCopy).contains("modified.setHighlightRuleSetId(selected.getHighlightRuleSetId());");

        // copyForImport carries highlightRuleSetId; ServerConnectionCopyPolicyTest pins that.
        assertThat(source("ConnectionManagerDialog.java"))
            .contains("ServerConnection imported = ServerConnection.copyForImport(conn,");
    }

    @Test
    void everyPaneResolvesItsConnectionBetweenItsOwnChoiceAndTheDefault() throws IOException {
        String view = source("TerminalView.java");

        assertThat(region(view, "private void attachTerminalHighlighter(SithTermFxWidget widget) {", "\n    }\n"))
            .contains("highlightSelection(pane),");
        assertThat(region(view, "public @Nullable String getEffectiveHighlightSetId(", "\n    }\n"))
            .contains("service.resolveSetId(highlightSelection(pane));");
        assertThat(region(view, "private @Nullable String getInheritedHighlightSetId(", "\n    }\n"))
            .contains("service.resolveSetId(inheritedHighlightSelection(pane));");
        String selection = region(view, "private TerminalHighlightService.PaneSelection highlightSelection(", "\n    }\n");
        assertThat(selection).contains("return paneHighlightOverride.get(pane);");
        assertThat(selection).contains("return connectionHighlightSetId(pane);");
        String inherited = region(view, "private TerminalHighlightService.PaneSelection inheritedHighlightSelection(",
            "\n    }\n");
        assertThat(inherited).contains("return null;");
        assertThat(inherited).contains("return connectionHighlightSetId(pane);");
    }

    @Test
    void theConnectionLevelPrefersTheSavedConnectionAndNeverBreaksResolution() throws IOException {
        String view = source("TerminalView.java");
        String connectionSet = region(view, "private @Nullable String connectionHighlightSetId(", "\n    }\n");

        assertThat(connectionSet).contains("TerminalHighlightService.connectionSetId(paneConnection,");
        assertThat(connectionSet).contains("configManager::getConnectionById");
        assertThat(connectionSet).contains("catch (RuntimeException e)");
        String connectionOf = region(view, "private ServerConnection highlightConnectionOf(", "\n    }\n");
        assertWithMessage("a split to another server follows its own connection")
            .that(connectionOf).contains("unwrapTerminalEffectConnector(pane.getTtyConnector())");
        assertThat(connectionOf).contains("return paneConnection != null ? paneConnection : connection;");
    }

    @Test
    void aSplitWithoutAChoiceFollowsItsOwnConnectionOnceItIsConnected() throws IOException {
        String view = source("TerminalView.java");
        String inherit = region(view, "private void inheritHighlightOnSplit(", "\n    }\n");

        assertThat(inherit).contains("setPaneHighlightOverride(newWidget, choice);");
        assertThat(inherit).contains("refreshInheritedHighlightSet(newWidget);");
        assertWithMessage("the split's set is reported once, after it follows its own connection")
            .that(inherit.indexOf("reportPendingHighlightActivation(newWidget);"))
            .isGreaterThan(inherit.indexOf("refreshInheritedHighlightSet(newWidget);"));
        String refresh = region(view, "private void refreshInheritedHighlightSet(", "\n    }\n");
        assertThat(refresh).contains("service.refresh(highlighter);");
        assertThat(refresh).doesNotContain("reportInheritedHighlightSet(");
        assertThat(refresh).contains("catch (RuntimeException e)");
    }

    @Test
    void savingConnectionsMovesTheOpenPanes() throws IOException {
        String main = source("MainWindow.java");

        assertThat(region(main, "private void refreshAllTerminalTabsConnectionSettings() {", "\n    }\n"))
            .contains("refreshHighlightingAfterConnectionsSaved();");
        String refresh = region(main, "private void refreshHighlightingAfterConnectionsSaved() {", "\n    }\n");
        assertThat(refresh).contains("service.refreshAll();");
        assertWithMessage("a failing refresh must never break saving connections")
            .that(refresh).contains("catch (RuntimeException e)");
        assertThat(refresh).contains("syncHighlightingToggleItems();");
    }

    private static String source(String file) throws IOException {
        // A Windows checkout has CRLF line endings; the markers above are written with \n.
        return Files.readString(UI.resolve(file), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text from {@code startMarker} up to and including the next {@code endMarker}. */
    private static String region(String source, String startMarker, String endMarker) {
        int from = source.indexOf(startMarker);
        assertWithMessage("marker not found: " + startMarker).that(from).isAtLeast(0);
        int to = source.indexOf(endMarker, from + startMarker.length());
        assertWithMessage("end marker not found after " + startMarker).that(to).isAtLeast(0);
        return source.substring(from, to + endMarker.length());
    }
}
