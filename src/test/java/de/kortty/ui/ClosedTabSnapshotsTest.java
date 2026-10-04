package de.kortty.ui;

import de.kortty.core.SessionSnapshotStore;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.Project;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionSnapshot;
import de.kortty.ui.ClosedTabHistory.ClosedTab;
import de.kortty.ui.ClosedTabHistory.Entry;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The Recently Closed list across a restart: the session snapshot keeps ids only — never a host, a
 * user name, a password or a temporary SSH key — and after the restart each closed tab comes back
 * with its saved connection as it is now; a tab whose connection is gone, or was never saved, is
 * left out. Plain data, no JavaFX toolkit.
 */
class ClosedTabSnapshotsTest {

    private static final String TEMPORARY_KEY =
        "-----BEGIN OPENSSH PRIVATE KEY-----\nAAAA\n-----END OPENSSH PRIVATE KEY-----";

    @Test
    void theSnapshotKeepsIdsGroupsNamesAndEffectsButNoConnectionDetails() throws Exception {
        ServerConnection web = connection("web-01.example.com");
        web.setEncryptedPassword("ENCRYPTED-SECRET");
        web.setTemporaryKeyContent(TEMPORARY_KEY);
        Entry single = new Entry(List.of(ClosedTab.capture(web, true, "prod", "Billing", "mother", 1.5)), false);

        List<SessionSnapshot.ClosedEntry> stored = ClosedTabSnapshots.toSnapshot(List.of(single));

        assertThat(stored).hasSize(1);
        SessionSnapshot.ClosedTab tab = stored.get(0).getTabs().get(0);
        assertThat(tab.getConnectionId()).isEqualTo(web.getId());
        assertThat(tab.isUsedTemporaryKey()).isTrue();
        assertThat(tab.getTabGroup()).isEqualTo("prod");
        assertThat(tab.getCustomTitle()).isEqualTo("Billing");
        assertThat(tab.getTerminalEffectPluginId()).isEqualTo("mother");
        assertThat(tab.getTerminalEffectSpeed()).isEqualTo(1.5);

        // What reaches the disk: written through the store, read back as text.
        Path configDir = Files.createTempDirectory("kortty-closed-tabs-");
        SessionSnapshotStore store = SessionSnapshotStore.open(configDir);
        try {
            store.startUp();
            SessionSnapshot snapshot = new SessionSnapshot();
            snapshot.setProject(new Project("Session"));
            snapshot.setRecentlyClosed(new ArrayList<>(stored));
            store.write(snapshot);
            String xml = Files.readString(configDir.resolve(SessionSnapshotStore.DIRECTORY_NAME)
                .resolve(SessionSnapshotStore.SNAPSHOT_FILE));
            assertThat(xml).contains(web.getId());
            assertThat(xml).doesNotContain("web-01.example.com");
            assertThat(xml).doesNotContain("ENCRYPTED-SECRET");
            assertThat(xml).doesNotContain("OPENSSH");
            assertThat(xml).doesNotContain("<username>");
        } finally {
            store.close();
            try (var paths = Files.walk(configDir)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    @Test
    void afterARestartEachTabComesBackWithItsSavedConnectionAsItIsNow() {
        ServerConnection db = connection("db-07");
        ServerConnection web = connection("web-01");
        Entry window = new Entry(List.of(ClosedTab.capture(db, false, "prod", null, null, null),
            ClosedTab.capture(web, false, null, "Frontend", null, null)), true);
        Entry single = new Entry(List.of(ClosedTab.capture(web, false, null, null, null, null)), false);
        List<SessionSnapshot.ClosedEntry> stored = ClosedTabSnapshots.toSnapshot(List.of(single, window));
        // Edited after the close: the host moved.
        ServerConnection movedWeb = connection("web-01-new");
        movedWeb.setId(web.getId());
        Map<String, ServerConnection> saved = Map.of(db.getId(), db, web.getId(), movedWeb);

        List<Entry> restored = ClosedTabSnapshots.fromSnapshot(stored, saved::get);

        assertThat(restored).hasSize(2);
        assertThat(restored.get(0).window()).isFalse();
        assertThat(restored.get(1).window()).isTrue();
        assertThat(restored.get(1).tabs()).hasSize(2);
        assertThat(restored.get(1).tabs().get(0).tabGroup()).isEqualTo("prod");
        assertThat(restored.get(1).tabs().get(1).customTitle()).isEqualTo("Frontend");
        ClosedTabHistory.Target target = ClosedTabHistory.resolveConnection(restored.get(0).tabs().get(0), saved::get);
        assertThat(target.connection()).isSameInstanceAs(movedWeb);
        assertThat(target.needsNewTemporaryKey()).isFalse();
    }

    @Test
    void aTabWhoseConnectionIsGoneIsLeftOutAndAnEntryLeftWithoutTabsGoesWithIt() {
        ServerConnection kept = connection("kept");
        ServerConnection deleted = connection("deleted");
        ServerConnection quickConnect = connection("unsaved");
        Entry mixed = new Entry(List.of(ClosedTab.capture(deleted, false, null, null, null, null),
            ClosedTab.capture(kept, false, null, null, null, null)), true);
        Entry gone = new Entry(List.of(ClosedTab.capture(quickConnect, false, null, null, null, null)), false);

        List<Entry> restored = ClosedTabSnapshots.fromSnapshot(
            ClosedTabSnapshots.toSnapshot(List.of(gone, mixed)), Map.of(kept.getId(), kept)::get);

        assertThat(restored).hasSize(1);
        assertThat(restored.get(0).tabs()).hasSize(1);
        assertThat(restored.get(0).tabs().get(0).connectionId()).isEqualTo(kept.getId());
        assertThat(ClosedTabSnapshots.fromSnapshot(null, id -> kept)).isEmpty();
    }

    @Test
    void atMostTheHistorysCapComesBackAndSeedingOnlyFillsAnEmptyHistory() {
        Map<String, ServerConnection> saved = new HashMap<>();
        List<Entry> many = new ArrayList<>();
        for (int i = 0; i < ClosedTabHistory.MAX_ENTRIES + 5; i++) {
            ServerConnection connection = connection("host-" + i);
            saved.put(connection.getId(), connection);
            many.add(new Entry(List.of(ClosedTab.capture(connection, false, null, null, null, null)), false));
        }

        List<Entry> restored = ClosedTabSnapshots.fromSnapshot(ClosedTabSnapshots.toSnapshot(many), saved::get);
        assertThat(restored).hasSize(ClosedTabHistory.MAX_ENTRIES);
        assertThat(restored.get(0).tabs().get(0).connectionId()).isEqualTo(many.get(0).tabs().get(0).connectionId());

        ClosedTabHistory history = new ClosedTabHistory();
        assertThat(history.seed(restored)).isTrue();
        assertThat(history.entries()).containsExactlyElementsIn(restored).inOrder();

        ClosedTabHistory used = new ClosedTabHistory();
        Entry closedThisRun = many.get(1);
        used.push(closedThisRun);
        assertWithMessage("a close of this run is never replaced by the list of the last run")
            .that(used.seed(restored)).isFalse();
        assertThat(used.entries()).containsExactly(closedThisRun);
    }

    private static ServerConnection connection(String host) {
        ServerConnection connection = new ServerConnection(host, host, 22, "me");
        connection.setProtocol(ConnectionProtocol.SSH_TCP);
        connection.setAuthMethod(AuthMethod.PASSWORD);
        return connection;
    }
}
