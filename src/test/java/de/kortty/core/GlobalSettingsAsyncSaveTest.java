package de.kortty.core;

import de.kortty.model.IconToolbarSettings;
import de.kortty.model.IconToolbarSettings.Entry;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;

public class GlobalSettingsAsyncSaveTest {
    @Test
    void legacyToolbarMigrationPersistsOnceAndLaterRemovalSurvivesReload() throws Exception {
        var directory = Files.createTempDirectory("kortty-toolbar-migration-test");
        Files.writeString(directory.resolve(GlobalSettingsManager.SETTINGS_FILE),
            "<globalSettings><iconToolbar visible=\"false\">"
                + "<entry id=\"video\" kind=\"ACTION\" section=\"TOOLS\" target=\"menu.tools.videoManager\"/>"
                + "<entry id=\"shell\" kind=\"ACTION\" section=\"TERMINAL\" target=\"toolbar.localShell\"/>"
                + "</iconToolbar></globalSettings>");
        var manager = new GlobalSettingsManager(directory);
        manager.load();
        var migrated = manager.getSettings().getIconToolbar();
        assertThat(migrated.entries().stream().map(Entry::target).toList())
            .containsExactly("menu.tools.videoManager", "menu.tools.logfileManager", "toolbar.localShell").inOrder();
        assertThat(migrated.isVisible()).isFalse();
        assertThat(migrated.isConnectionsVisible()).isFalse();
        manager.saveAsync().get(10, TimeUnit.SECONDS);
        manager.load();
        assertThat(manager.getSettings().getIconToolbar().entries().stream().map(Entry::id).toList())
            .containsExactlyElementsIn(migrated.entries().stream().map(Entry::id).toList()).inOrder();

        migrated.setEntries(migrated.entries().stream()
            .filter(entry -> !"menu.tools.logfileManager".equals(entry.target())).toList());
        manager.getSettings().setIconToolbar(migrated);
        manager.saveAsync().get(10, TimeUnit.SECONDS);
        manager.load();
        assertThat(manager.getSettings().getIconToolbar().entries().stream().map(Entry::id).toList())
            .containsExactly("video", "shell").inOrder();
    }

    @Test
    void completionMeansToolbarConfigurationIsDurable() throws Exception {
        var directory = Files.createTempDirectory("kortty-async-settings-test");
        var manager = new GlobalSettingsManager(directory);
        manager.load();
        var toolbar = IconToolbarSettings.defaults();
        toolbar.setVisible(false);
        toolbar.setEntries(List.of());
        manager.getSettings().setIconToolbar(toolbar);
        manager.saveAsync().get(10, TimeUnit.SECONDS);
        var restored = new GlobalSettingsManager(directory);
        restored.load();
        assertThat(restored.getSettings().getIconToolbar().isVisible()).isFalse();
        assertThat(restored.getSettings().getIconToolbar().entries()).isEmpty();
    }

    @Test
    void failedWriteIsReportedThroughCompletion() throws Exception {
        var directory = Files.createTempDirectory("kortty-async-settings-failure-test");
        var manager = new GlobalSettingsManager(directory);
        manager.load();
        Files.createDirectory(directory.resolve(GlobalSettingsManager.SETTINGS_FILE));
        try {
            manager.saveAsync().get(10, TimeUnit.SECONDS);
            throw new AssertionError("Saving over a directory must fail");
        } catch (ExecutionException expected) {
            assertThat(expected.getCause()).isInstanceOf(java.io.IOException.class);
        }
    }
}
