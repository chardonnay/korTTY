package de.kortty.ui;

import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * How MainWindow wires Ctrl+Tab in most-recently-used order, pinned against the source because the
 * window cannot be built without a stage: Ctrl+Tab keeps its positional path while the Window setting
 * is off; with it on, each step selects without counting the tab as used; the cycle ends on the
 * Ctrl release, on any other key (before that key acts), on another selection and on focus loss;
 * the palette's Next Tab and Previous Tab end it at once; and the Settings dialog saves the option.
 */
class TabCycleWiringTest {

    private static final Path MAIN_WINDOW = Path.of("src/main/java/de/kortty/ui/MainWindow.java");
    private static final Path SETTINGS_DIALOG = Path.of("src/main/java/de/kortty/ui/SettingsDialog.java");

    @Test
    void ctrlTabStaysPositionalUnlessTheSettingIsOn() throws IOException {
        String switchTab = methodBody(source(MAIN_WINDOW), "private void switchTabFromKeyboard(boolean backwards) {");

        int positional = switchTab.indexOf("if (!isTabSwitchMostRecentFirst()) {");
        assertThat(positional).isAtLeast(0);
        assertWithMessage("the setting off takes the old positional methods before any cycle starts")
            .that(switchTab.indexOf("selectPreviousTab();")).isGreaterThan(positional);
        assertThat(switchTab.indexOf("selectNextTab();")).isLessThan(switchTab.indexOf("tabMru.advance("));
        assertThat(methodBody(source(MAIN_WINDOW), "private boolean isTabSwitchMostRecentFirst() {"))
            .contains("settings != null && settings.isTabSwitchMostRecentFirst()");
    }

    @Test
    void aCycleStepSelectsItsTabWithoutCountingItAsUsed() throws IOException {
        String source = source(MAIN_WINDOW);
        String switchTab = methodBody(source, "private void switchTabFromKeyboard(boolean backwards) {");

        assertThat(switchTab).contains(
            "tabMru.advance(tabPane.getTabs(), tabPane.getSelectionModel().getSelectedItem(), backwards)");
        assertThat(switchTab).contains("steppingTabCycle = true;\n"
            + "        try {\n"
            + "            tabPane.getSelectionModel().select(next);\n"
            + "        } finally {\n"
            + "            steppingTabCycle = false;\n"
            + "        }");
        assertThat(methodBody(source, "private void setupUI() {"))
            .contains("if (newTab != null && !reorganizingTabs && !steppingTabCycle) {");
    }

    @Test
    void theCtrlTabChordsGoThroughTheCycle() throws IOException {
        String router = methodBody(source(MAIN_WINDOW), "private SceneShortcutRouter createSceneShortcutRouter() {");

        assertThat(router).contains(".consume(SceneShortcutKeys::isNextTab, SceneShortcutRouter.ALWAYS,\n"
            + "                () -> switchTabFromKeyboard(false), SceneShortcutKeys.TAB_RESIDUE)");
        assertThat(router).contains(".consume(SceneShortcutKeys::isPreviousTab, SceneShortcutRouter.ALWAYS,\n"
            + "                () -> switchTabFromKeyboard(true), SceneShortcutKeys.TAB_RESIDUE)");
    }

    @Test
    void anyOtherKeyEndsTheCycleBeforeItActs() throws IOException {
        String router = methodBody(source(MAIN_WINDOW), "private SceneShortcutRouter createSceneShortcutRouter() {");
        String observer = ".observe(SceneShortcutKeys::endsTabCycle, tabMru::isCycling, this::commitTabCycle)";

        int at = router.indexOf(observer);
        assertThat(at).isAtLeast(0);
        assertWithMessage("the observer runs before every chord, so Cmd/Ctrl+W closes the tab the cycle stopped at")
            .that(at).isLessThan(router.indexOf(".consume("));
    }

    @Test
    void releasingCtrlOrLeavingTheWindowEndsTheCycle() throws IOException {
        String source = source(MAIN_WINDOW);

        // A release observer of the router: it never consumes, so the terminal sees the release as before.
        assertThat(methodBody(source, "private SceneShortcutRouter createSceneShortcutRouter() {")).contains(
            ".observeRelease(SceneShortcutKeys::endsTabCycleOnRelease, tabMru::isCycling, this::commitTabCycle)");
        assertThat(source).contains("stage.focusedProperty().addListener((observable, wasFocused, focused) -> {\n"
            + "            if (!focused) {\n"
            + "                commitTabCycle();\n"
            + "            }\n"
            + "        });");
        assertThat(methodBody(source, "private void commitTabCycle() {"))
            .contains("tabMru.commit(tabPane.getSelectionModel().getSelectedItem());");
    }

    @Test
    void thePalettesTabStepsEndTheCycleAtOnce() throws IOException {
        String source = source(MAIN_WINDOW);

        String registry = methodBody(source, "private ActionRegistry actionRegistry() {");
        assertThat(registry).contains("() -> switchTabOnce(false)");
        assertThat(registry).contains("() -> switchTabOnce(true)");
        String once = methodBody(source, "private void switchTabOnce(boolean backwards) {");
        assertThat(once.indexOf("commitTabCycle();")).isGreaterThan(once.indexOf("switchTabFromKeyboard(backwards);"));
    }

    @Test
    void theSettingsDialogLoadsAndSavesTheOption() throws IOException {
        String dialog = source(SETTINGS_DIALOG);

        assertThat(dialog).contains("new CheckBox(I18n.get(\"settings.window.tabSwitchMostRecentFirst\"))");
        assertThat(dialog).contains(
            "tabSwitchMostRecentFirstCheck.setSelected(globalSettings != null && globalSettings.isTabSwitchMostRecentFirst());");
        assertThat(dialog).contains(
            "globalSettings.setTabSwitchMostRecentFirst(tabSwitchMostRecentFirstCheck.isSelected());");
        assertWithMessage("the option sits in the Tabs section of the Window tab")
            .that(dialog.indexOf("settings.window.tabSwitchMostRecentFirst\""))
            .isGreaterThan(dialog.indexOf("settings.window.tabs.header"));
    }

    private static String source(Path path) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(path, StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    /** The text of the method whose declaration contains {@code signature}, up to its closing brace. */
    private static String methodBody(String source, String signature) {
        int start = source.indexOf(signature);
        assertWithMessage("method not found: " + signature).that(start).isAtLeast(0);
        int open = source.indexOf('{', start);
        int depth = 0;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return source.substring(start, i + 1);
                }
            }
        }
        throw new AssertionError("unbalanced method: " + signature);
    }
}
