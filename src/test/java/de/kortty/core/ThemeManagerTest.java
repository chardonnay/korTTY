package de.kortty.core;

import de.kortty.model.Theme;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import static com.google.common.truth.Truth.assertThat;


class ThemeManagerTest {

    @Test
    void updateThemePersistsChangesForBuiltInThemeAndKeepsBuiltInFlag() throws Exception {
        Path dir = Files.createTempDirectory("kortty-themes");
        try {
            ThemeManager manager = new ThemeManager(dir);
            manager.load();

            Theme editedDefault = copyTheme(manager.getTheme("default").orElseThrow());
            editedDefault.setName("Default Custom");
            editedDefault.setForegroundColor("#112233");
            editedDefault.setBackgroundColor("#445566");
            editedDefault.setBuiltIn(false);

            manager.updateTheme(editedDefault);

            ThemeManager reloaded = new ThemeManager(dir);
            reloaded.load();
            Theme reloadedDefault = reloaded.getTheme("default").orElseThrow();
            assertThat(reloadedDefault.getName()).isEqualTo("Default Custom");
            assertThat(reloadedDefault.getForegroundColor()).isEqualTo("#112233");
            assertThat(reloadedDefault.getBackgroundColor()).isEqualTo("#445566");
            assertThat(reloadedDefault.isBuiltIn()).isTrue();
        } finally {
            Files.deleteIfExists(dir.resolve("themes.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void addThemePersistsCustomThemeAcrossReload() throws Exception {
        Path dir = Files.createTempDirectory("kortty-themes");
        try {
            ThemeManager manager = new ThemeManager(dir);
            manager.load();
            Theme custom = new Theme();
            custom.setName("Ops Custom");
            custom.setForegroundColor("#ABCDEF");
            custom.setBackgroundColor("#101820");

            Theme saved = manager.addTheme(custom);

            ThemeManager reloaded = new ThemeManager(dir);
            reloaded.load();
            Theme reloadedCustom = reloaded.getTheme(saved.getId()).orElseThrow();
            assertThat(reloadedCustom.getName()).isEqualTo("Ops Custom");
            assertThat(reloadedCustom.getForegroundColor()).isEqualTo("#ABCDEF");
            assertThat(reloadedCustom.getBackgroundColor()).isEqualTo("#101820");
            assertThat(reloadedCustom.isBuiltIn()).isFalse();
        } finally {
            Files.deleteIfExists(dir.resolve("themes.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void removeThemeKeepsBuiltInThemes() throws Exception {
        Path dir = Files.createTempDirectory("kortty-themes");
        try {
            ThemeManager manager = new ThemeManager(dir);
            manager.load();

            manager.removeTheme("default");

            assertThat(manager.getTheme("default").isPresent()).isTrue();
            assertThat(manager.getTheme("default").orElseThrow().isBuiltIn()).isTrue();
        } finally {
            Files.deleteIfExists(dir.resolve("themes.xml"));
            Files.deleteIfExists(dir);
        }
    }

    private static Theme copyTheme(Theme source) {
        Theme copy = new Theme();
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setFontFamily(source.getFontFamily());
        copy.setFontSize(source.getFontSize());
        copy.setForegroundColor(source.getForegroundColor());
        copy.setBackgroundColor(source.getBackgroundColor());
        copy.setCursorColor(source.getCursorColor());
        copy.setCursorStyle(source.getCursorStyle());
        copy.setAgentPanelBackgroundColor(source.getAgentPanelBackgroundColor());
        copy.setAgentPanelBorderColor(source.getAgentPanelBorderColor());
        copy.setAgentPanelTextColor(source.getAgentPanelTextColor());
        copy.setAgentPanelMutedTextColor(source.getAgentPanelMutedTextColor());
        copy.setAgentPanelAccentColor(source.getAgentPanelAccentColor());
        copy.setAgentPanelErrorColor(source.getAgentPanelErrorColor());
        copy.setBuiltIn(source.isBuiltIn());
        return copy;
    }
}
