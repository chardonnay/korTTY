package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import de.kortty.model.GlobalSettings;
import javafx.geometry.Insets;
import javafx.scene.AccessibleRole;
import javafx.scene.layout.Border;
import javafx.scene.layout.BorderStroke;
import javafx.scene.layout.BorderStrokeStyle;
import javafx.scene.layout.BorderWidths;
import javafx.scene.layout.CornerRadii;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The connection color on a terminal tab: a small outlined dot that screen readers can read, a
 * family name for every color, the optional frame around the terminal, the credential environment
 * a color can come from, and the wiring that keeps them off the tab's status style and the terminal
 * view's style and fresh in every window. The wiring is pinned against the sources, since neither
 * {@link MainWindow} nor {@link TerminalTab} nor the dialogs can be built without a JavaFX stage.
 */
class TabColorPresentationTest {

    private static final Path UI_ROOT = Path.of("src/main/java/de/kortty/ui");

    private static final List<String> BUNDLES = List.of(
            "messages.properties",
            "messages_de.properties",
            "messages_it.properties",
            "messages_es.properties",
            "messages_pt.properties",
            "messages_fr.properties",
            "messages_hr.properties",
            "messages_nl.properties");

    // ---- presentation ------------------------------------------------------------------------

    @Test
    void theSwatchIsATenPixelDotOfTheColorThatScreenReadersCanRead() {
        Circle dot = TabColorPresentation.swatch("#D32F2F", "Tab color: red (#D32F2F), set on this connection");

        assertThat(dot.getRadius() * 2).isEqualTo(10.0);
        assertThat(dot.getFill()).isEqualTo(Color.web("#D32F2F"));
        assertThat(dot.getStroke()).isNotNull();
        assertThat(dot.getAccessibleText()).isEqualTo("Tab color: red (#D32F2F), set on this connection");
        assertThat(dot.getAccessibleRole()).isEqualTo(AccessibleRole.IMAGE_VIEW);
        assertThat(dot.getStyleClass()).contains(TabColorPresentation.SWATCH_STYLE_CLASS);
    }

    @Test
    void theSwatchLetsClicksAndDragsThroughToTheTabHeader() {
        assertWithMessage("double-click retry and tab dragging start on the tab header")
                .that(TabColorPresentation.swatch("#1976D2", "blue").isMouseTransparent()).isTrue();
    }

    @Test
    void darkColorsGetALightOutlineAndLightColorsADarkOne() {
        Color darkOutline = TabColorPresentation.strokeFor(Color.web("#FBC02D"));
        Color lightOutline = TabColorPresentation.strokeFor(Color.web("#000000"));

        assertThat(lightOutline.getBrightness()).isGreaterThan(0.9);
        assertThat(darkOutline.getBrightness()).isLessThan(0.1);
        assertThat(TabColorPresentation.strokeFor(Color.web("#D32F2F")).getBrightness()).isGreaterThan(0.9);
        assertThat(TabColorPresentation.strokeFor(Color.web("#FFFFFF")).getBrightness()).isLessThan(0.1);
    }

    @Test
    void hexOfRoundsTheChannelsAndRoundTripsEveryPreset() {
        for (String preset : ConnectionColorSupport.PRESETS) {
            assertThat(TabColorPresentation.hexOf(Color.web(preset))).isEqualTo(preset);
        }
        assertThat(TabColorPresentation.hexOf(Color.color(0.999, 0.0, 0.5))).isEqualTo("#FF0080");
    }

    @Test
    void theFrameIsOneSolidThreePixelStrokeOfTheColorOnEverySide() {
        Border frame = TabColorPresentation.frame("#D32F2F");

        assertThat(frame.getImages()).isEmpty();
        assertThat(frame.getStrokes()).hasSize(1);
        BorderStroke stroke = frame.getStrokes().get(0);
        assertThat(stroke.getTopStroke()).isEqualTo(Color.web("#D32F2F"));
        assertThat(stroke.getRightStroke()).isEqualTo(Color.web("#D32F2F"));
        assertThat(stroke.getBottomStroke()).isEqualTo(Color.web("#D32F2F"));
        assertThat(stroke.getLeftStroke()).isEqualTo(Color.web("#D32F2F"));
        assertThat(stroke.getTopStyle()).isEqualTo(BorderStrokeStyle.SOLID);
        assertThat(stroke.getLeftStyle()).isEqualTo(BorderStrokeStyle.SOLID);
        assertThat(stroke.getWidths()).isEqualTo(new BorderWidths(3));
        assertThat(stroke.getRadii()).isEqualTo(CornerRadii.EMPTY);
        assertThat(TabColorPresentation.FRAME_WIDTH).isEqualTo(3.0);
        assertWithMessage("the frame takes 3 px of layout on every side; the guide says switching it resizes the terminal")
                .that(frame.getInsets()).isEqualTo(new Insets(3));
    }

    @Test
    void theFrameShowsOnlyForAColoredConnectionWhileItIsSwitchedOn() {
        assertThat(TabColorPresentation.frameFor("#1976D2", true)).isEqualTo(TabColorPresentation.frame("#1976D2"));
        assertWithMessage("switched off in the Window settings: only the dot remains")
                .that(TabColorPresentation.frameFor("#1976D2", false)).isNull();
        assertWithMessage("a connection without a tab color never gets a frame")
                .that(TabColorPresentation.frameFor(null, true)).isNull();
        assertThat(TabColorPresentation.frameFor(null, false)).isNull();
    }

    @Test
    void theFrameFollowsTheWindowSettingAndIsOnWithoutSettings() {
        GlobalSettings settings = new GlobalSettings();
        assertThat(TabColorPresentation.frameEnabled(settings)).isTrue();

        settings.setConnectionColorBorderEnabled(false);
        assertThat(TabColorPresentation.frameEnabled(settings)).isFalse();

        assertThat(TabColorPresentation.frameEnabled(null)).isTrue();
    }

    @Test
    void theTooltipNamesTheConnectionFirstAndLeavesOutABlankOne() {
        assertThat(TabColorPresentation.describe("Tab color: red", "Connection: root@db", "\n"))
                .isEqualTo("Connection: root@db\nTab color: red");
        assertThat(TabColorPresentation.describe("Tab color: red", " ", ", ")).isEqualTo("Tab color: red");
        assertThat(TabColorPresentation.describe("Tab color: red", null, ", ")).isEqualTo("Tab color: red");
    }

    @Test
    void theEnvironmentNameInTheTooltipIsCleanedAndCappedAndNeverBlank() {
        assertThat(TabColorPresentation.environmentLabel("Production", "PRODUCTION")).isEqualTo("Production");
        assertThat(TabColorPresentation.environmentLabel("  Lab\u202E\007 east\n", "custom-1"))
                .isEqualTo("Lab east");
        assertWithMessage("a name with nothing visible falls back to the environment's id")
                .that(TabColorPresentation.environmentLabel("\u200F\000 ", "custom-1")).isEqualTo("custom-1");
        assertThat(TabColorPresentation.environmentLabel(null, "STAGING")).isEqualTo("STAGING");
        String longName = "x".repeat(200);
        assertThat(TabColorPresentation.environmentLabel(longName, "custom-1"))
                .hasLength(TabColorPresentation.MAX_ENVIRONMENT_NAME_LENGTH);
    }

    @Test
    void everyColorFamilyIsNamedInEveryLanguage() throws IOException {
        for (String bundle : BUNDLES) {
            Properties localized = loadBundle(bundle);
            for (ConnectionColorSupport.Family family : ConnectionColorSupport.Family.values()) {
                String key = TabColorPresentation.familyKey(family);
                String name = localized.getProperty(key);
                assertWithMessage("%s is missing %s", bundle, key).that(name).isNotNull();
                assertWithMessage("%s has a blank %s", bundle, key).that(name.isBlank()).isFalse();
            }
        }
    }

    // ---- wiring ------------------------------------------------------------------------------

    @Test
    void everyTerminalTabTheWindowCreatesShowsItsConnectionColor() throws IOException {
        String window = source("MainWindow.java");

        int created = occurrences(window, "new TerminalTab(");
        assertThat(created).isAtLeast(3);
        // The creation sites call it on their new tab; the refresh calls window.applyConnectionColor.
        Matcher creationSites = Pattern.compile("\\n\\s+applyConnectionColor\\((terminalTab|tab|newTab)\\);")
                .matcher(window);
        int applied = 0;
        while (creationSites.find()) {
            applied++;
        }
        assertWithMessage("a terminal tab created without its connection color would stay unmarked")
                .that(applied).isEqualTo(created);
    }

    @Test
    void savingConnectionsRefreshesTheColorsInEveryWindow() throws IOException {
        String window = source("MainWindow.java");

        assertThat(methodBody(window, "private void refreshAllTerminalTabsConnectionSettings() {"))
                .contains("refreshConnectionColorsInAllWindows();");
        String refresh = methodBody(window, "static void refreshConnectionColorsInAllWindows() {");
        assertThat(refresh).contains("for (MainWindow window : new ArrayList<>(openWindows)) {");
        assertThat(refresh).contains("window.applyConnectionColor(terminalTab);");
        String apply = methodBody(window, "private void applyConnectionColor(TerminalTab tab) {");
        assertThat(apply).contains("ConnectionColorSupport.effectiveTabColor(");
        assertThat(apply).contains("this::credentialEnvironmentId, this::environmentColor");
        assertThat(apply).contains("TabColorPresentation.environmentLabel(");
        assertThat(apply).contains("TabColorPresentation.frameEnabled(app.getGlobalSettingsManager().getSettings())");
        assertThat(methodBody(window, "private String credentialEnvironmentId(String credentialId) {"))
                .contains(".map(StoredCredential::getEnvironmentId)");
        assertThat(methodBody(window, "private String environmentColor(String environmentId) {"))
                .contains("app.getEnvironmentManager().getColor(environmentId)");
    }

    @Test
    void credentialAndEnvironmentEditsRecolorTheOpenTabs() throws IOException {
        String dialog = source("CredentialManagementDialog.java");

        for (String method : List.of("private void showEnvironments() {", "private void addCredential() {",
                "private void editCredential() {", "private void removeCredential() {")) {
            assertWithMessage("%s must refresh the tab colors in every window", method)
                    .that(methodBody(dialog, method)).contains("MainWindow.refreshConnectionColorsInAllWindows();");
        }
        int imported = source("MainWindow.java").indexOf("reloadStoresAfterBackupImport();\n");
        assertThat(imported).isAtLeast(0);
        assertWithMessage("a restored backup can bring other connections, credentials and environment colors")
                .that(source("MainWindow.java").substring(imported, imported + 200))
                .contains("refreshConnectionColorsInAllWindows();");
    }

    @Test
    void theEnvironmentsDialogAppliesItsColorsOnlyOnOkAndTogetherWithTheSave() throws IOException {
        String dialog = source("EnvironmentManagementDialog.java");

        assertThat(dialog).contains("this.colorEdits = environmentManager.getColors();");
        assertWithMessage("colors are edited in the dialog's copy, so Cancel discards them")
                .that(dialog).doesNotContain("environmentManager.setColor(");
        assertThat(occurrences(dialog, "environmentManager.replaceColors(")).isEqualTo(2);
        int ok = dialog.indexOf("if (buttonType == ButtonType.OK) {");
        int apply = dialog.indexOf("Map<String, String> previousColors = environmentManager.replaceColors(colorEdits);");
        int save = dialog.indexOf("environmentManager.save();");
        int restore = dialog.indexOf("environmentManager.replaceColors(previousColors);");
        assertThat(ok).isAtLeast(0);
        assertThat(apply).isGreaterThan(ok);
        assertThat(save).isGreaterThan(apply);
        assertWithMessage("a failed save puts the previous colors back")
                .that(restore).isGreaterThan(save);
        assertThat(methodBody(dialog, "private void storeColorEdit() {")).contains("colorEdits.put(");
    }

    @Test
    void savingTheGlobalSettingsShowsOrHidesTheFrameInEveryWindow() throws IOException {
        String window = source("MainWindow.java");

        assertWithMessage("switching the frame in the Window settings must reach the open tabs at once")
                .that(methodBody(window, "private void showSettings() {"))
                .contains("refreshConnectionColorsInAllWindows();");
    }

    @Test
    void theConnectionColorNeverTouchesTheTabStatusStyle() throws IOException {
        String tab = source("TerminalTab.java");

        String show = methodBody(tab,
                "private void showConnectionColor(String color, String environmentName, boolean showFrame) {");
        assertWithMessage("the tab style shows the connection status; the retry looks for #8B0000 in it")
                .that(show).doesNotContain("setStyle");
        assertThat(show).contains("setGraphic(");
        assertThat(show).contains("setTooltip(");
        assertThat(methodBody(tab,
                "public void applyConnectionColor(String hex, String environmentName, boolean showFrame) {"))
                .contains("ConnectionColorSupport.normalizeHex(hex)");
    }

    @Test
    void theTooltipSaysWhetherTheColorIsTheConnectionsOrTheEnvironments() throws IOException {
        String show = methodBody(source("TerminalTab.java"),
                "private void showConnectionColor(String color, String environmentName, boolean showFrame) {");

        assertThat(show).contains("environmentName == null");
        assertThat(show).contains("I18n.get(\"tab.tooltip.connectionColor\", family, color)");
        assertThat(show).contains("I18n.get(\"tab.tooltip.environmentColor\", family, color, environmentName)");
    }

    @Test
    void theFrameIsTheBorderOfTheTabContentAroundThePanesAndStatusBars() throws IOException {
        String tab = source("TerminalTab.java");

        String show = methodBody(tab,
                "private void showConnectionColor(String color, String environmentName, boolean showFrame) {");
        assertThat(show).contains("TabColorPresentation.frameFor(color, showFrame)");
        assertThat(show).contains("content.setBorder(frame);");
        assertWithMessage("the terminal view's style belongs to the see-through window mode")
                .that(show).doesNotContain("terminalView");
        // The content box holds the terminal view (and with it every split pane and its focus
        // marking) and the status bars, and it is what the tab shows.
        assertThat(tab).contains("content.getChildren().add(terminalView);");
        assertThat(tab).contains("setContent(content);");
        assertThat(occurrences(tab, ".setBorder(")).isEqualTo(1);
    }

    @Test
    void theWindowSettingsTabLoadsAndSavesTheFrameSwitch() throws IOException {
        String dialog = source("SettingsDialog.java");

        assertThat(dialog).contains("new CheckBox(I18n.get(\"settings.window.connectionColorBorder\"))");
        assertThat(dialog).contains(
                "connectionColorBorderCheck.setSelected(globalSettings == null || globalSettings.isConnectionColorBorderEnabled());");
        assertThat(dialog).contains(
                "globalSettings.setConnectionColorBorderEnabled(connectionColorBorderCheck.isSelected());");
        int header = dialog.indexOf("I18n.get(\"settings.window.tabs.header\")");
        int fixedGeometry = dialog.indexOf("I18n.get(\"settings.window.fixedGeometry.header\")");
        int windowTab = dialog.indexOf("I18n.get(\"settings.tab.window\")");
        assertWithMessage("the Tabs section belongs to the Window tab, before Fixed Window Geometry")
                .that(header).isGreaterThan(windowTab);
        assertThat(header).isLessThan(fixedGeometry);
    }

    @Test
    void quickConnectAndTheImportCarryTheTabColor() throws IOException {
        assertThat(methodBody(source("QuickConnectDialog.java"),
                "private ServerConnection baseCopyOf(ServerConnection selected) {"))
                .contains("modified.setTabColor(selected.getTabColor());");
        assertThat(source("ConnectionManagerDialog.java"))
                .contains("imported.setTabColor(de.kortty.core.ConnectionColorSupport.normalizeHex(conn.getTabColor()));");
    }

    @Test
    void theEditorKeepsTheTabColorOutsideTheEffectsSectionAndSavesIt() throws IOException {
        String dialog = source("ConnectionEditDialog.java");

        String settingsTab = methodBody(dialog, "private Tab createSettingsTab() {");
        int behavior = settingsTab.indexOf("createTerminalBehaviorGrid()");
        int effects = settingsTab.indexOf("if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {");
        assertWithMessage("the section must not hide when terminal effects are switched off")
                .that(behavior).isAtLeast(0);
        assertThat(behavior).isLessThan(effects);
        assertThat(dialog).contains("connection.setTabColor(tabColorCheck != null && tabColorCheck.isSelected()");
    }

    // ---- helpers -----------------------------------------------------------------------------

    private Properties loadBundle(String fileName) throws IOException {
        try (InputStream inputStream = getClass().getClassLoader().getResourceAsStream("i18n/" + fileName)) {
            assertWithMessage("Missing i18n bundle " + fileName).that(inputStream).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            return properties;
        }
    }

    private static String source(String fileName) throws IOException {
        // Windows CI checks the sources out with CRLF line endings.
        return Files.readString(UI_ROOT.resolve(fileName), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private static int occurrences(String source, String text) {
        Matcher matcher = Pattern.compile(Pattern.quote(text)).matcher(source);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
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
        throw new AssertionError("unbalanced braces after " + signature);
    }
}
