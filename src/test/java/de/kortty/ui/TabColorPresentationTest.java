package de.kortty.ui;

import de.kortty.core.ConnectionColorSupport;
import javafx.scene.AccessibleRole;
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
 * family name for every color, and the wiring that keeps it off the tab's status style and fresh in
 * every window. The wiring is pinned against the sources, since neither {@link MainWindow} nor
 * {@link TerminalTab} can be built without a JavaFX stage.
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
    void theTooltipNamesTheConnectionFirstAndLeavesOutABlankOne() {
        assertThat(TabColorPresentation.describe("Tab color: red", "Connection: root@db", "\n"))
                .isEqualTo("Connection: root@db\nTab color: red");
        assertThat(TabColorPresentation.describe("Tab color: red", " ", ", ")).isEqualTo("Tab color: red");
        assertThat(TabColorPresentation.describe("Tab color: red", null, ", ")).isEqualTo("Tab color: red");
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
        String refresh = methodBody(window, "private static void refreshConnectionColorsInAllWindows() {");
        assertThat(refresh).contains("for (MainWindow window : new ArrayList<>(openWindows)) {");
        assertThat(refresh).contains("window.applyConnectionColor(terminalTab);");
        assertThat(methodBody(window, "private void applyConnectionColor(TerminalTab tab) {"))
                .contains("ConnectionColorSupport.tabColorOf(");
    }

    @Test
    void theConnectionColorNeverTouchesTheTabStatusStyle() throws IOException {
        String tab = source("TerminalTab.java");

        String show = methodBody(tab, "private void showConnectionColor(String color) {");
        assertWithMessage("the tab style shows the connection status; the retry looks for #8B0000 in it")
                .that(show).doesNotContain("setStyle");
        assertThat(show).contains("setGraphic(");
        assertThat(show).contains("setTooltip(");
        assertThat(methodBody(tab, "public void applyConnectionColor(String hex) {"))
                .contains("ConnectionColorSupport.normalizeHex(hex)");
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
