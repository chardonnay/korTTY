package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The connection map's automatic layout: a zone per folder with its connections inside, this
 * computer on the left, jump routes through the saved jump server or a node of its own, tunnels
 * from this computer, and zones that wrap into the next row when the width runs out.
 */
class ConnectionMapLayoutTest {

    private static ConnectionMapLayout.Entry entry(String id, String group, String host, int port) {
        return new ConnectionMapLayout.Entry(id, group, host, port, null, 0, null);
    }

    private static ConnectionMapLayout.Entry viaJump(String id, String group, String host, String jumpHost, int jumpPort) {
        return new ConnectionMapLayout.Entry(id, group, host, 22, jumpHost, jumpPort, null);
    }

    @Test
    void everyFolderIsAZoneThatHoldsItsConnections() {
        var result = ConnectionMapLayout.layout(List.of(
            entry("web01", "Production", "web-01.prod.example", 22),
            entry("web02", "Production", "web-02.prod.example", 22),
            entry("api", "Staging", "api.stage.example", 22),
            entry("shell", null, "localhost", 22)), 1400);

        assertThat(result.zones().stream().map(ConnectionMapLayout.Zone::key).toList())
            .containsExactly("", "Production", "Staging").inOrder();
        for (ConnectionMapLayout.Node node : result.nodes()) {
            if (ConnectionMapLayout.ME.equals(node.id())) {
                continue;
            }
            ConnectionMapLayout.Zone zone = result.zoneAt(node.centerX(), node.centerY());
            assertWithMessage(node.id() + " lies inside its folder's zone").that(zone).isNotNull();
            assertThat(zone.key()).isEqualTo(node.zone());
        }
        assertWithMessage("this computer stays left of every zone")
            .that(result.node(ConnectionMapLayout.ME).x() + ConnectionMapLayout.NODE_WIDTH)
            .isLessThan(ConnectionMapLayout.ORIGIN_X);
    }

    @Test
    void aSavedJumpServerIsThatConnectionsNode() {
        var result = ConnectionMapLayout.layout(List.of(
            entry("bastion", "Production", "bastion.example", 2222),
            entry("bastion-ssh", "Production", "bastion.example", 22),
            viaJump("web01", "Production", "web-01.prod.example", "BASTION.example", 2222),
            viaJump("web02", "Production", "web-02.prod.example", "bastion.example", 2222)), 1400);

        assertWithMessage("matched by host, ignoring case, and by port")
            .that(result.edges()).containsAtLeast(
                new ConnectionMapLayout.Edge(ConnectionMapLayout.ME, "bastion", ConnectionMapLayout.EdgeKind.JUMP, null),
                new ConnectionMapLayout.Edge("bastion", "web01", ConnectionMapLayout.EdgeKind.JUMP, null),
                new ConnectionMapLayout.Edge("bastion", "web02", ConnectionMapLayout.EdgeKind.JUMP, null));
        assertWithMessage("one route from this computer to the jump server, however many use it")
            .that(result.edges().stream().filter(edge -> edge.from().equals(ConnectionMapLayout.ME)).count())
            .isEqualTo(1);
        assertThat(result.zones().stream().map(ConnectionMapLayout.Zone::key).toList())
            .doesNotContain(ConnectionMapLayout.EXTERNAL_ZONE);
    }

    @Test
    void aJumpServerWithoutASavedConnectionGetsANodeOfItsOwn() {
        var result = ConnectionMapLayout.layout(List.of(
            viaJump("db", "Production", "db-01.prod.example", "gate.example", 2200)), 1400);

        ConnectionMapLayout.Node gate = result.node("jump:gate.example:2200");
        assertThat(gate).isNotNull();
        assertThat(gate.external()).isTrue();
        assertThat(gate.label()).isEqualTo("gate.example:2200");
        assertThat(gate.zone()).isEqualTo(ConnectionMapLayout.EXTERNAL_ZONE);
        assertThat(result.edges()).contains(
            new ConnectionMapLayout.Edge(gate.id(), "db", ConnectionMapLayout.EdgeKind.JUMP, null));
    }

    @Test
    void aTunnelRunsFromThisComputerWithItsDescription() {
        var result = ConnectionMapLayout.layout(List.of(new ConnectionMapLayout.Entry(
            "db", "Production", "db-01.prod.example", 22, null, 0, "L 15432 → localhost:5432")), 1400);

        assertThat(result.edges()).containsExactly(new ConnectionMapLayout.Edge(
            ConnectionMapLayout.ME, "db", ConnectionMapLayout.EdgeKind.TUNNEL, "L 15432 → localhost:5432"));
    }

    @Test
    void zonesWithJumpRoutesAndTunnelsComeFirst() {
        var result = ConnectionMapLayout.layout(List.of(
            entry("api", "Alpha", "api.example", 22),
            entry("bastion", "Production", "bastion.example", 22),
            viaJump("web", "Production", "web.example", "bastion.example", 22)), 1400);

        assertThat(result.zones().get(0).key()).isEqualTo("Production");
        assertWithMessage("the linked zone sits nearest to this computer")
            .that(result.zones().get(0).x()).isEqualTo(ConnectionMapLayout.ORIGIN_X);
    }

    @Test
    void zonesWrapIntoTheNextRowWhenTheWidthRunsOut() {
        var wide = ConnectionMapLayout.layout(List.of(
            entry("a", "A", "a.example", 22), entry("b", "B", "b.example", 22), entry("c", "C", "c.example", 22)), 3000);
        var narrow = ConnectionMapLayout.layout(List.of(
            entry("a", "A", "a.example", 22), entry("b", "B", "b.example", 22), entry("c", "C", "c.example", 22)), 700);

        assertWithMessage("side by side when there is room")
            .that(wide.zones().stream().map(ConnectionMapLayout.Zone::y).distinct().count()).isEqualTo(1);
        assertWithMessage("below each other when there is not")
            .that(narrow.zones().stream().map(ConnectionMapLayout.Zone::y).distinct().count()).isGreaterThan(1L);
        for (ConnectionMapLayout.Zone zone : narrow.zones()) {
            assertThat(zone.x()).isAtLeast(ConnectionMapLayout.ORIGIN_X);
        }
        assertThat(narrow.height()).isGreaterThan(wide.height());
    }

    @Test
    void aZoneLaysItsConnectionsOutInAtMostThreeColumns() {
        assertThat(ConnectionMapLayout.columns(1)).isEqualTo(1);
        assertThat(ConnectionMapLayout.columns(2)).isEqualTo(2);
        assertThat(ConnectionMapLayout.columns(4)).isEqualTo(2);
        assertThat(ConnectionMapLayout.columns(9)).isEqualTo(3);
        assertThat(ConnectionMapLayout.columns(40)).isEqualTo(3);
    }
}
