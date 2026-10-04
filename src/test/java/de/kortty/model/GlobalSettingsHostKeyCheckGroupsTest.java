package de.kortty.model;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.HostKeyCheckGroupExemptions;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

/**
 * The folders without host-key verification keep their stored form in {@code global-settings.xml}: a
 * file written by an earlier version loads with the same exemptions, and the list that a rename or
 * delete leaves behind is saved and read back under the same element.
 */
class GlobalSettingsHostKeyCheckGroupsTest {

    private static final String OLD_FILE = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n"
            + "<globalSettings>\n"
            + "    <language>de</language>\n"
            + "    <hostKeyCheckDisabledForAllConnections>false</hostKeyCheckDisabledForAllConnections>\n"
            + "    <hostKeyCheckDisabledGroups>\n"
            + "        <group>Lab</group>\n"
            + "        <group>Lab/DB</group>\n"
            + "        <group>Work/Staging</group>\n"
            + "    </hostKeyCheckDisabledGroups>\n"
            + "</globalSettings>\n";

    @Test
    void aSettingsFileFromAnEarlierVersionLoadsWithItsExemptions() throws Exception {
        Path dir = Files.createTempDirectory("kortty-host-key-groups");
        try {
            Files.writeString(dir.resolve(GlobalSettingsManager.SETTINGS_FILE), OLD_FILE, StandardCharsets.UTF_8);
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.load();

            GlobalSettings settings = manager.getSettings();
            assertThat(settings.getLanguage()).isEqualTo("de");
            assertThat(settings.getHostKeyCheckDisabledGroups())
                .containsExactly("Lab", "Lab/DB", "Work/Staging").inOrder();
            assertThat(settings.isHostKeyCheckDisabledForGroup("Lab")).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void theExemptionsLeftByADeleteAreSavedAndReadBack() throws Exception {
        Path dir = Files.createTempDirectory("kortty-host-key-groups");
        try {
            Files.writeString(dir.resolve(GlobalSettingsManager.SETTINGS_FILE), OLD_FILE, StandardCharsets.UTF_8);
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.load();
            GlobalSettings settings = manager.getSettings();
            settings.setHostKeyCheckDisabledGroups(
                HostKeyCheckGroupExemptions.deleted(settings.getHostKeyCheckDisabledGroups(), "Lab"));
            manager.save();

            String saved = Files.readString(dir.resolve(GlobalSettingsManager.SETTINGS_FILE), StandardCharsets.UTF_8);
            assertThat(saved).contains("<group>Work/Staging</group>");
            assertThat(saved).doesNotContain("<group>Lab</group>");

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().getHostKeyCheckDisabledGroups()).containsExactly("Work/Staging");
            assertThat(reloaded.getSettings().isHostKeyCheckDisabledForGroup("Lab")).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void aSettingsFileWithoutExemptionsLoadsWithNone() throws Exception {
        Path dir = Files.createTempDirectory("kortty-host-key-groups");
        try {
            Files.writeString(dir.resolve(GlobalSettingsManager.SETTINGS_FILE),
                "<globalSettings><language>de</language></globalSettings>", StandardCharsets.UTF_8);
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.load();

            assertThat(manager.getSettings().getLanguage()).isEqualTo("de");
            assertThat(manager.getSettings().getHostKeyCheckDisabledGroups()).isEmpty();
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var files = Files.walk(dir)) {
            files.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
