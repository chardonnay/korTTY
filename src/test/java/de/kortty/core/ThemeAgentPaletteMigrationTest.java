package de.kortty.core;

import de.kortty.model.Theme;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/**
 * Themes whose cursor cannot carry the agent accent get the blue fallback, and a {@code themes.xml}
 * from before the blue palette loses the green colors korTTY derived for it while colors the user picked
 * stay; the migration runs once.
 */
class ThemeAgentPaletteMigrationTest {

    // What korTTY stored for the Default theme (black, white cursor) before the blue palette.
    private static final String LEGACY_DEFAULT_THEME = """
        <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
        <themes>
            <theme id="default">
                <name>Default</name>
                <foregroundColor>#FFFFFF</foregroundColor>
                <backgroundColor>#000000</backgroundColor>
                <cursorColor>#FFFFFF</cursorColor>
                <agentPanelBackgroundColor>#042314</agentPanelBackgroundColor>
                <agentPanelBorderColor>#0A512E</agentPanelBorderColor>
                <agentPanelTextColor>#FFFFFF</agentPanelTextColor>
                <agentPanelMutedTextColor>#A6A6A6</agentPanelMutedTextColor>
                <agentPanelAccentColor>#18C26E</agentPanelAccentColor>
                <agentPanelErrorColor>#E36A4D</agentPanelErrorColor>
            </theme>
            <theme id="mine">
                <name>Mine</name>
                <foregroundColor>#FFFFFF</foregroundColor>
                <backgroundColor>#000000</backgroundColor>
                <cursorColor>#FFFFFF</cursorColor>
                <agentPanelBackgroundColor>#202020</agentPanelBackgroundColor>
                <agentPanelBorderColor>#0A512E</agentPanelBorderColor>
                <agentPanelAccentColor>#FF00AA</agentPanelAccentColor>
            </theme>
        </themes>
        """;

    @Test
    void aGreyCursorGivesTheBlueAccentOnDarkAndLightThemes() {
        Theme dark = theme("#000000", "#FFFFFF", "#FFFFFF");
        assertThat(dark.getAgentPanelAccentColor()).isEqualTo("#4F9CF0");
        assertThat(dark.getAgentPanelBackgroundColor()).isEqualTo("#0D1218");
        assertThat(dark.getAgentPanelBorderColor()).isEqualTo("#1D344E");

        Theme light = theme("#FFFFFF", "#1E1E1E", "#1E1E1E");
        assertThat(light.getAgentPanelAccentColor()).isEqualTo("#0B63CE");
    }

    @Test
    void aColoredCursorStillCarriesTheAccent() {
        Theme nord = theme("#2E3440", "#D8DEE9", "#88C0D0");
        assertThat(nord.getAgentPanelAccentColor()).isEqualTo("#88C0D0");
    }

    @Test
    void storedGreenColorsAreReplacedAndOwnColorsKept() throws Exception {
        Path dir = Files.createTempDirectory("kortty-agent-palette");
        try {
            Files.writeString(dir.resolve(ThemeManager.THEMES_FILE), LEGACY_DEFAULT_THEME, StandardCharsets.UTF_8);
            ThemeManager manager = new ThemeManager(dir);
            manager.load();

            Theme migrated = manager.getTheme("default").orElseThrow();
            assertThat(migrated.getAgentPanelAccentColor()).isEqualTo("#4F9CF0");
            assertThat(migrated.getAgentPanelBackgroundColor()).isEqualTo("#0D1218");
            assertThat(migrated.getAgentPanelBorderColor()).isEqualTo("#1D344E");
            assertThat(migrated.getAgentPanelTextColor()).isEqualTo("#FFFFFF");

            Theme own = manager.getTheme("mine").orElseThrow();
            assertThat(own.getAgentPanelBackgroundColor()).isEqualTo("#202020");
            assertThat(own.getAgentPanelAccentColor()).isEqualTo("#FF00AA");
            assertThat(own.getAgentPanelBorderColor()).isEqualTo("#1D344E");

            String written = Files.readString(dir.resolve(ThemeManager.THEMES_FILE), StandardCharsets.UTF_8);
            assertThat(written).contains("agentPaletteVersion=\"2\"");
            assertThat(written).doesNotContain("#18C26E");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void theMigrationRunsOnlyOnce() throws Exception {
        Path dir = Files.createTempDirectory("kortty-agent-palette");
        try {
            ThemeManager manager = new ThemeManager(dir);
            manager.load();
            Theme theme = manager.getTheme("default").orElseThrow();
            // A user who deliberately picks the old green after the migration keeps it.
            theme.setAgentPanelAccentColor("#18C26E");
            manager.updateTheme(theme);

            ThemeManager reloaded = new ThemeManager(dir);
            reloaded.load();
            assertThat(reloaded.getTheme("default").orElseThrow().getAgentPanelAccentColor()).isEqualTo("#18C26E");
        } finally {
            deleteRecursively(dir);
        }
    }

    private static Theme theme(String background, String foreground, String cursor) {
        Theme theme = new Theme("t", "T", false);
        theme.setBackgroundColor(background);
        theme.setForegroundColor(foreground);
        theme.setCursorColor(cursor);
        return theme;
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var files = Files.walk(dir)) {
            files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
