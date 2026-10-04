package de.kortty.core;

import de.kortty.model.ServerConnection;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The exemptions of folders that no longer exist (left behind by earlier versions, or by a
 * connections.xml restored without its settings) are dropped against the stored connections only:
 * a load that failed leaves an empty or stale list in memory, and pruning against it would drop
 * every exemption the user set.
 */
class HostKeyCheckExemptionsAfterLoadTest {

    private static final byte[] TRUNCATED = "<?xml version=\"1.0\"?>\n<connections><connection><name>cut"
        .getBytes(StandardCharsets.UTF_8);

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kortty-host-key-exemptions");
    }

    @AfterMethod(alwaysRun = true)
    void deleteDir() throws IOException {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) != null) {
            Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwx------"));
        }
        try (var stream = Files.walk(dir)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void leftoverExemptionsOfEarlierVersionsAreDroppedOnceTheConnectionsAreLoaded() throws Exception {
        saveConnections(placeholder("Lab"), connection("Prod/Web"));
        ConfigurationManager store = new ConfigurationManager(dir);
        store.load(null);

        assertThat(store.isLoaded()).isTrue();
        assertWithMessage("Lab keeps its placeholder, Prod is above Prod/Web; the others are gone")
            .that(HostKeyCheckGroupExemptions.prunedAgainstLoaded(
                List.of("Lab", "Prod", "Deleted", "Renamed/Away"), store))
            .containsExactly("Lab", "Prod").inOrder();
    }

    @Test
    void withoutAConnectionsFileEveryExemptionGoes() {
        ConfigurationManager store = new ConfigurationManager(dir);
        store.load(null);

        assertThat(store.isLoaded()).isTrue();
        assertThat(HostKeyCheckGroupExemptions.prunedAgainstLoaded(List.of("Lab"), store)).isEmpty();
    }

    @Test
    void nothingIsDroppedBeforeTheConnectionsWereLoaded() {
        ConfigurationManager store = new ConfigurationManager(dir);

        assertThat(store.isLoaded()).isFalse();
        assertThat(HostKeyCheckGroupExemptions.prunedAgainstLoaded(List.of("Lab"), store)).containsExactly("Lab");
        assertThat(HostKeyCheckGroupExemptions.prunedAgainstLoaded(List.of("Lab"), null)).containsExactly("Lab");
    }

    @Test
    void aCorruptConnectionsFileDropsNothing() throws Exception {
        Files.write(dir.resolve("connections.xml"), TRUNCATED);
        ConfigurationManager store = new ConfigurationManager(dir);
        store.load(null);

        assertThat(store.getLoadFailureBackup()).isPresent();
        assertThat(store.getConnections()).isEmpty();
        assertThat(store.isLoaded()).isFalse();
        assertThat(HostKeyCheckGroupExemptions.prunedAgainstLoaded(List.of("Lab", "Prod"), store))
            .containsExactly("Lab", "Prod").inOrder();
    }

    @Test
    void aFailedReloadDropsNothingUntilALoadSucceedsAgain() throws Exception {
        saveConnections(connection("Lab"));
        ConfigurationManager store = new ConfigurationManager(dir);
        store.load(null);
        assertThat(store.isLoaded()).isTrue();

        // E.g. a backup import that restored a damaged connections.xml, then reloaded every store.
        Files.write(dir.resolve("connections.xml"), TRUNCATED);
        store.load(null);

        assertWithMessage("the previous list stays in memory, but it is not the stored one any more")
            .that(store.getConnections()).hasSize(1);
        assertThat(store.isLoaded()).isFalse();
        assertThat(HostKeyCheckGroupExemptions.prunedAgainstLoaded(List.of("Lab", "Gone"), store))
            .containsExactly("Lab", "Gone").inOrder();

        saveConnections(connection("Prod"));
        store.load(null);
        assertThat(store.isLoaded()).isTrue();
        assertThat(HostKeyCheckGroupExemptions.prunedAgainstLoaded(List.of("Lab", "Prod"), store))
            .containsExactly("Prod");
    }

    @Test
    void anUnreadableConnectionsFileDropsNothing() throws Exception {
        if (Files.getFileAttributeView(dir, PosixFileAttributeView.class) == null
            || "root".equals(System.getProperty("user.name"))) {
            throw new SkipException("needs POSIX permissions that apply to the current user");
        }
        saveConnections(connection("Lab"));
        Path file = dir.resolve("connections.xml");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("---------"));
        ConfigurationManager store = new ConfigurationManager(dir);
        try {
            store.load(null);
        } finally {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        }

        assertThat(store.isSaveBlocked()).isTrue();
        assertThat(store.isLoaded()).isFalse();
        assertThat(HostKeyCheckGroupExemptions.prunedAgainstLoaded(List.of("Lab", "Gone"), store))
            .containsExactly("Lab", "Gone").inOrder();
    }

    private void saveConnections(ServerConnection... connections) throws Exception {
        Files.deleteIfExists(dir.resolve("connections.xml"));
        ConfigurationManager writer = new ConfigurationManager(dir);
        writer.load(null);
        for (ServerConnection connection : connections) {
            writer.addConnection(connection);
        }
        writer.saveOrThrow(null);
    }

    private static ServerConnection connection(String group) {
        ServerConnection connection = new ServerConnection("demo", "demo.example.test", 22, "demo");
        connection.setGroup(group);
        return connection;
    }

    /** The entry "New folder" adds so that an empty folder shows in the tree. */
    private static ServerConnection placeholder(String group) {
        ServerConnection placeholder = new ServerConnection("(Ordner: " + group + ")", "placeholder", 22, "");
        placeholder.setGroup(group);
        return placeholder;
    }
}
