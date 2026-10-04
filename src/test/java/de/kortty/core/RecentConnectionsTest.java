package de.kortty.core;

import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The connections used last, shared by Quick Connect's buttons and <i>File → Open Recent</i>: only
 * connections that were ever used, the one used last first, at most as many as asked for, and for
 * Open Recent only the uses after <i>Clear List</i>. Plain data, no JavaFX toolkit.
 */
class RecentConnectionsTest {

    @Test
    void theConnectionUsedLastComesFirstAndNeverUsedOnesAreLeftOut() {
        ServerConnection old = used("old", 1_000L);
        ServerConnection never = used("never", 0L);
        ServerConnection latest = used("latest", 3_000L);
        ServerConnection middle = used("middle", 2_000L);

        assertThat(names(RecentConnections.top(List.of(old, never, latest, middle), 10)))
            .containsExactly("latest", "middle", "old").inOrder();
    }

    @Test
    void atMostTheRequestedNumberIsReturned() {
        List<ServerConnection> connections = new ArrayList<>();
        for (int i = 1; i <= 15; i++) {
            connections.add(used("c" + i, i * 100L));
        }

        List<ServerConnection> top = RecentConnections.top(connections, 10);

        assertThat(top).hasSize(10);
        assertThat(top.get(0).getName()).isEqualTo("c15");
        assertThat(top.get(9).getName()).isEqualTo("c6");
        assertThat(RecentConnections.top(connections, 0)).isEmpty();
        assertThat(RecentConnections.top(connections, -1)).isEmpty();
    }

    @Test
    void connectionsUsedAtTheSameMomentKeepTheirOrder() {
        ServerConnection first = used("first", 500L);
        ServerConnection second = used("second", 500L);
        ServerConnection third = used("third", 500L);

        assertThat(names(RecentConnections.top(List.of(first, second, third), 10)))
            .containsExactly("first", "second", "third").inOrder();
    }

    @Test
    void openRecentCountsOnlyTheUsesAfterClearList() {
        ServerConnection before = used("before", 1_000L);
        ServerConnection atTheMoment = used("at", 2_000L);
        ServerConnection after = used("after", 3_000L);
        List<ServerConnection> connections = List.of(before, atTheMoment, after);

        assertThat(names(RecentConnections.top(connections, 10, 2_000L))).containsExactly("after");
        assertWithMessage("never cleared counts every use, like Quick Connect")
            .that(names(RecentConnections.top(connections, 10, 0L)))
            .containsExactly("after", "at", "before").inOrder();
        assertThat(names(RecentConnections.top(List.of(used("never", 0L)), 10, -5L))).isEmpty();
    }

    @Test
    void missingInputGivesAnEmptyList() {
        assertThat(RecentConnections.top(null, 10)).isEmpty();
        assertThat(RecentConnections.top(Arrays.asList(null, used("x", 10L)), 10)).hasSize(1);
    }

    @Test
    void quickConnectUsesTheSharedOrder() throws Exception {
        // Windows CI checks the sources out with CRLF line endings.
        String dialog = Files.readString(Path.of("src/main/java/de/kortty/ui/QuickConnectDialog.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(dialog).contains("RecentConnections.top(savedConnections, maxCount)");
        assertWithMessage("the order lives in RecentConnections only")
            .that(dialog).doesNotContain("Long.compare(b.getLastUsed(), a.getLastUsed())");
    }

    private static ServerConnection used(String name, long lastUsed) {
        ServerConnection connection = new ServerConnection(name, name + ".example", 22, "me");
        connection.setLastUsed(lastUsed);
        return connection;
    }

    private static List<String> names(List<ServerConnection> connections) {
        return connections.stream().map(ServerConnection::getName).toList();
    }
}
