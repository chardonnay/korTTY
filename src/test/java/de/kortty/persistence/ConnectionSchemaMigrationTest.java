package de.kortty.persistence;

import de.kortty.core.ConnectionSettingsSupport;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ConnectionSource;
import de.kortty.model.ServerConnection;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import de.kortty.teamwork.CachedTeamworkSource;
import de.kortty.teamwork.SharedFileTeamworkAdapter;
import de.kortty.teamwork.TeamworkCacheRepository;
import de.kortty.teamwork.TeamworkLoadResult;
import de.kortty.teamwork.TeamworkRecycleBinService;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins the migration of stored per-connection terminal settings: files written before own settings
 * took effect (no {@code schemaVersion}) load with every connection on the global settings, so stale
 * values never suddenly apply; files of the current version keep an explicit own-settings choice.
 * Covers connections.xml, korTTY exports and imports, teamwork files, the teamwork cache and the
 * teamwork recycle bin.
 */
public class ConnectionSchemaMigrationTest {

    private static final String SCHEMA_ATTRIBUTE = " schemaVersion=\"" + ConnectionSchemaMigration.CURRENT_VERSION + "\"";

    @Test
    void anOldConnectionsFileLoadsWithEveryConnectionOnTheGlobalSettings() throws Exception {
        Path dir = Files.createTempDirectory("kortty-schema-old");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            repository.saveConnections(List.of(connectionWithOwnSettings("own"), connectionFollowingGlobal("global")));
            writeWithoutSchemaVersion(repository.connectionsFile());

            List<ServerConnection> loaded = repository.loadConnections();

            assertThat(loaded).hasSize(2);
            for (ServerConnection connection : loaded) {
                assertThat(connection.getSettings().isUseGlobalSettings()).isTrue();
                assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(connection.getSettings())).isFalse();
            }
            // The stale values stay stored, they just do not apply.
            assertThat(loaded.get(0).getSettings().getFontFamily()).isEqualTo("Stale Mono");
            ConnectionSettings effective = ConnectionSettingsSupport.effectiveTerminalSettings(
                loaded.get(0).getSettings(), globalDefaults());
            assertThat(effective.getFontFamily()).isEqualTo("Global Mono");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void anOlderSchemaVersionIsMigratedToo() throws Exception {
        Path dir = Files.createTempDirectory("kortty-schema-v1");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            repository.saveConnections(List.of(connectionWithOwnSettings("own")));
            Path file = repository.connectionsFile();
            Files.writeString(file, Files.readString(file).replace(SCHEMA_ATTRIBUTE, " schemaVersion=\"1\""));

            assertThat(repository.loadConnections().get(0).getSettings().isUseGlobalSettings()).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void saveStampsTheSchemaVersionSoTheMigrationRunsOnce() throws Exception {
        Path dir = Files.createTempDirectory("kortty-schema-once");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            repository.saveConnections(List.of(connectionWithOwnSettings("own")));
            writeWithoutSchemaVersion(repository.connectionsFile());

            // Load the old file (migrated), opt in to own settings explicitly, save.
            List<ServerConnection> migrated = repository.loadConnections();
            ServerConnection connection = migrated.get(0);
            connection.setSettings(ConnectionSettingsSupport.settingsToSave(
                true, connection.getSettings(), globalDefaults(), s -> s.setFontSize(19)));
            repository.saveConnections(migrated);

            assertThat(Files.readString(repository.connectionsFile())).contains(SCHEMA_ATTRIBUTE);
            ServerConnection reloaded = repository.loadConnections().get(0);
            assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(reloaded.getSettings())).isTrue();
            assertThat(reloaded.getSettings().getFontSize()).isEqualTo(19);
            // The opt-in started from the global values shown, not from the stale stored ones.
            assertThat(reloaded.getSettings().getFontFamily()).isEqualTo("Global Mono");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void editorRoundTripKeepsTheChoiceThroughSaveAndLoad() throws Exception {
        Path dir = Files.createTempDirectory("kortty-schema-editor");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            ServerConnection connection = connectionFollowingGlobal("edited");

            // "Own settings for this connection" with an edited font.
            connection.setSettings(ConnectionSettingsSupport.settingsToSave(
                true, connection.getSettings(), globalDefaults(), s -> s.setFontFamily("Edited Mono")));
            repository.saveConnections(List.of(connection));
            ServerConnection own = repository.loadConnections().get(0);
            assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(own.getSettings())).isTrue();
            assertThat(own.getSettings().getFontFamily()).isEqualTo("Edited Mono");
            assertThat(ConnectionSettingsSupport.effectiveTerminalSettings(own.getSettings(), globalDefaults())
                .getFontFamily()).isEqualTo("Edited Mono");

            // Back to "Use the global terminal settings".
            own.setSettings(ConnectionSettingsSupport.settingsToSave(false, own.getSettings(), globalDefaults(), null));
            repository.saveConnections(List.of(own));
            ServerConnection global = repository.loadConnections().get(0);
            assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(global.getSettings())).isFalse();
            assertThat(ConnectionSettingsSupport.effectiveTerminalSettings(global.getSettings(), globalDefaults())
                .getFontFamily()).isEqualTo("Global Mono");
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void exportAndImportCarryTheOwnSettingsFlag() throws Exception {
        ServerConnection own = connectionWithOwnSettings("own");
        ServerConnection global = connectionFollowingGlobal("global");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        XMLConnectionRepository.writeConnections(List.of(
            ServerConnection.copyForExport(own, false, false, false, false),
            ServerConnection.copyForExport(global, false, false, false, false)), out, null);
        String exported = out.toString(StandardCharsets.UTF_8);
        assertThat(exported).contains(SCHEMA_ATTRIBUTE);

        List<ServerConnection> read = XMLConnectionRepository.readConnections(
            new ByteArrayInputStream(exported.getBytes(StandardCharsets.UTF_8)), null);
        ServerConnection importedOwn = ServerConnection.copyForImport(read.get(0), false, false, false, false);
        ServerConnection importedGlobal = ServerConnection.copyForImport(read.get(1), false, false, false, false);
        assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(importedOwn.getSettings())).isTrue();
        assertThat(importedOwn.getSettings().getFontFamily()).isEqualTo("Stale Mono");
        assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(importedGlobal.getSettings())).isFalse();
    }

    @Test
    void anOldExportImportsWithTheGlobalSettings() throws Exception {
        Path dir = Files.createTempDirectory("kortty-schema-import");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            Path export = dir.resolve("old-export.xml");
            repository.exportConnections(List.of(connectionWithOwnSettings("own")), export);
            writeWithoutSchemaVersion(export);

            List<ServerConnection> imported = repository.importConnections(export);

            assertThat(imported.get(0).getSettings().isUseGlobalSettings()).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void teamworkFilesFollowTheSameRule() throws Exception {
        Path dir = Files.createTempDirectory("kortty-schema-teamwork");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            Path current = dir.resolve("team-current.xml");
            Path old = dir.resolve("team-old.xml");
            repository.exportConnections(List.of(connectionWithOwnSettings("shared")), current);
            repository.exportConnections(List.of(connectionWithOwnSettings("shared")), old);
            writeWithoutSchemaVersion(old);

            SharedFileTeamworkAdapter adapter = new SharedFileTeamworkAdapter(dir);
            TeamworkLoadResult fromCurrent = adapter.loadConnections(
                new TeamworkSourceConfig(TeamworkSourceType.SHARED_FILE, current.toString()));
            TeamworkLoadResult fromOld = adapter.loadConnections(
                new TeamworkSourceConfig(TeamworkSourceType.SHARED_FILE, old.toString()));

            assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(
                fromCurrent.getConnections().get(0).getSettings())).isTrue();
            assertThat(ConnectionSettingsSupport.usesOwnTerminalSettings(
                fromOld.getConnections().get(0).getSettings())).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void anOldTeamworkCacheLoadsWithTheGlobalSettings() throws Exception {
        Path dir = Files.createTempDirectory("kortty-schema-cache");
        try {
            TeamworkCacheRepository cache = new TeamworkCacheRepository(dir);
            List<ServerConnection> connections = new ArrayList<>(List.of(connectionWithOwnSettings("cached")));
            assertThat(cache.saveCache(List.of(new CachedTeamworkSource("src-1", 1L, "v1", connections)))).isTrue();
            Path file = dir.resolve("teamwork-cache.xml");
            assertThat(Files.readString(file)).contains(SCHEMA_ATTRIBUTE);

            // Current cache: the explicit choice survives.
            assertThat(cache.loadCache().get(0).getConnections().get(0).getSettings().isUseGlobalSettings()).isFalse();

            writeWithoutSchemaVersion(file);
            assertThat(cache.loadCache().get(0).getConnections().get(0).getSettings().isUseGlobalSettings()).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void anOldTeamworkRecycleBinLoadsWithTheGlobalSettings() throws Exception {
        Path dir = Files.createTempDirectory("kortty-schema-recycle");
        try {
            TeamworkRecycleBinService bin = new TeamworkRecycleBinService(dir);
            ServerConnection deleted = connectionWithOwnSettings("deleted");
            deleted.setConnectionSource(ConnectionSource.TEAMWORK);
            deleted.setTeamworkSourceId("src-1");
            bin.addDeleted(deleted);
            assertThat(bin.getDeleted()).hasSize(1);
            bin.save();
            Path file = dir.resolve("teamwork-recycle-bin.xml");
            assertThat(Files.readString(file)).contains(SCHEMA_ATTRIBUTE);

            TeamworkRecycleBinService current = new TeamworkRecycleBinService(dir);
            current.load();
            assertThat(current.getDeleted().get(0).getSettings().isUseGlobalSettings()).isFalse();

            writeWithoutSchemaVersion(file);
            TeamworkRecycleBinService old = new TeamworkRecycleBinService(dir);
            old.load();
            assertThat(old.getDeleted().get(0).getSettings().isUseGlobalSettings()).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void migrateLeavesCurrentAndNewerFilesAlone() {
        List<ServerConnection> connections = List.of(connectionWithOwnSettings("own"));

        assertThat(ConnectionSchemaMigration.migrate(ConnectionSchemaMigration.CURRENT_VERSION, connections)).isEqualTo(0);
        assertThat(ConnectionSchemaMigration.migrate(ConnectionSchemaMigration.CURRENT_VERSION + 1, connections)).isEqualTo(0);
        assertThat(connections.get(0).getSettings().isUseGlobalSettings()).isFalse();

        assertThat(ConnectionSchemaMigration.migrate(null, connections)).isEqualTo(1);
        assertThat(connections.get(0).getSettings().isUseGlobalSettings()).isTrue();
        // Idempotent, and a connection without settings is fine.
        ServerConnection noSettings = new ServerConnection();
        noSettings.setSettings(null);
        assertThat(ConnectionSchemaMigration.migrate(null, List.of(connections.get(0), noSettings))).isEqualTo(0);
    }

    private static ServerConnection connectionWithOwnSettings(String name) {
        ServerConnection connection = new ServerConnection(name, name + ".example.test", 22, "demo");
        ConnectionSettings settings = new ConnectionSettings();
        settings.setFontFamily("Stale Mono");
        settings.setFontSize(23);
        settings.setUseGlobalSettings(false);
        connection.setSettings(settings);
        return connection;
    }

    private static ServerConnection connectionFollowingGlobal(String name) {
        ServerConnection connection = new ServerConnection(name, name + ".example.test", 22, "demo");
        connection.getSettings().setUseGlobalSettings(true);
        return connection;
    }

    private static ConnectionSettings globalDefaults() {
        ConnectionSettings global = new ConnectionSettings();
        global.setFontFamily("Global Mono");
        global.setFontSize(13);
        return global;
    }

    /** Turns a file written now into one written before the schema version existed. */
    private static void writeWithoutSchemaVersion(Path file) throws IOException {
        String xml = Files.readString(file);
        assertThat(xml).contains(SCHEMA_ATTRIBUTE);
        Files.writeString(file, xml.replace(SCHEMA_ATTRIBUTE, ""));
    }

    private static void deleteRecursively(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
