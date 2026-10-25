package de.kortty.model;

import de.kortty.core.GlobalSettingsManager;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The Connection Manager's favorites are connection ids in {@code global-settings.xml}: a file
 * without them loads with none, and marked favorites are saved and read back. They live in the
 * settings rather than in the connection, so teamwork connections can be favorites too and an
 * exported connection never carries the mark.
 */
class GlobalSettingsFavoriteConnectionsTest {

    @Test
    void aSettingsFileWithoutFavoritesLoadsWithNone() throws Exception {
        Path dir = Files.createTempDirectory("kortty-favorites");
        try {
            Files.writeString(dir.resolve(GlobalSettingsManager.SETTINGS_FILE),
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>\n<globalSettings>\n"
                    + "    <language>en</language>\n</globalSettings>\n");
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.load();

            assertThat(manager.getSettings().getFavoriteConnectionIds()).isEmpty();
            assertThat(manager.getSettings().isFavoriteConnection("web-01")).isFalse();
            assertThat(manager.getSettings().isFavoriteConnection(null)).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void favoritesAreSavedAndReadBack() throws Exception {
        Path dir = Files.createTempDirectory("kortty-favorites");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            manager.load();
            manager.getSettings().setFavoriteConnectionIds(List.of("id-bastion", "id-web-01"));
            manager.save();

            String xml = Files.readString(dir.resolve(GlobalSettingsManager.SETTINGS_FILE));
            assertThat(xml).contains("<favoriteConnections>");
            assertThat(xml).contains("<connection>id-web-01</connection>");

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().getFavoriteConnectionIds())
                .containsExactly("id-bastion", "id-web-01").inOrder();
            assertThat(reloaded.getSettings().isFavoriteConnection("id-bastion")).isTrue();
            assertThat(reloaded.getSettings().isFavoriteConnection("id-db")).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    private static void deleteRecursively(Path dir) throws Exception {
        try (var paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
