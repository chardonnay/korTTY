package de.kortty.core;

import de.kortty.model.Theme;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
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

    @Test
    void corruptThemesFileIsQuarantinedBeforeDefaultsAreWritten() throws Exception {
        Path dir = Files.createTempDirectory("kortty-themes");
        byte[] truncated = "<themeList><theme><id>custom</id><name>Ops".getBytes(StandardCharsets.UTF_8);
        Path themesFile = Files.write(dir.resolve(ThemeManager.THEMES_FILE), truncated);
        Path backup = null;
        try {
            ThemeManager manager = new ThemeManager(dir);
            manager.load();

            backup = manager.getLoadFailureBackup().orElseThrow();
            assertThat(backup.getFileName().toString()).matches("themes\\.xml\\.corrupt-\\d{8}-\\d{6}");
            // The user's bytes survive; the defaults went to a fresh file instead of over them.
            assertThat(Files.readAllBytes(backup)).isEqualTo(truncated);
            assertThat(manager.getTheme("default")).isPresent();
            ThemeManager fresh = new ThemeManager(dir);
            fresh.load();
            assertThat(fresh.getTheme("default")).isPresent();
            assertThat(fresh.getTheme("dark")).isPresent();
            assertThat(fresh.getLoadFailureBackup()).isEmpty();

            // korTTY loads the themes twice at startup; the second load must not hide the backup.
            manager.load();
            assertThat(manager.getLoadFailureBackup()).hasValue(backup);
            assertThat(Files.readAllBytes(backup)).isEqualTo(truncated);
            assertThat(Files.exists(themesFile)).isTrue();
        } finally {
            if (backup != null) {
                Files.deleteIfExists(backup);
            }
            Files.deleteIfExists(dir.resolve("themes.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void themesFileThatCannotBeMovedAsideIsNotReplacedByTheDefaults() throws Exception {
        Path dir = Files.createTempDirectory("kortty-themes");
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) == null
            || "root".equals(System.getProperty("user.name"))) {
            Files.deleteIfExists(dir);
            throw new SkipException("needs POSIX directory permissions to make the rename fail");
        }
        byte[] truncated = "<themeList><theme>".getBytes(StandardCharsets.UTF_8);
        Path themesFile = Files.write(dir.resolve(ThemeManager.THEMES_FILE), truncated);
        try {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("r-x------"));
            ThemeManager manager = new ThemeManager(dir);
            manager.load();

            // The built-ins are usable in memory, but nothing was written over the file.
            assertThat(manager.getTheme("default")).isPresent();
            assertThat(manager.isSaveBlocked()).isTrue();
            assertThat(manager.getLoadFailureBackup()).isEmpty();
            Theme custom = new Theme();
            custom.setName("Ops Custom");
            manager.addTheme(custom);
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            assertThat(Files.readAllBytes(themesFile)).isEqualTo(truncated);
            try (var siblings = Files.list(dir)) {
                assertThat(siblings.map(p -> p.getFileName().toString()).toList()).containsExactly("themes.xml");
            }
        } finally {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
            Files.deleteIfExists(themesFile);
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
