package de.kortty.core;

import de.kortty.model.IconToolbarSettings;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;

public class GlobalSettingsAsyncSaveTest {
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
