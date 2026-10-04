package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.core.SshTtyConnectorTestIo.connector;
import static de.kortty.core.SshTtyConnectorTestIo.osc7;
import static de.kortty.core.SshTtyConnectorTestIo.receive;
import static de.kortty.core.SshTtyConnectorTestIo.type;

/** Pins the remote-directory listener of {@link SshTtyConnector} on its real read and input paths. */
class SshTtyConnectorDirectoryListenerTest {

    @Test
    void anOsc7SplitAcrossReadsFiresOnce() throws Exception {
        SshTtyConnector connector = connector();
        List<RemoteDirectoryChange> changes = new CopyOnWriteArrayList<>();
        connector.addRemoteDirectoryListener(changes::add);

        receive(connector, "daniel@web01:~$ " + osc7("web01", "/srv/app"), 3);

        assertThat(changes).containsExactly(
            new RemoteDirectoryChange("/srv/app", RemoteDirectoryChange.Source.OSC7, "web01"));
        assertThat(changes.get(0).lowConfidence()).isFalse();
    }

    @Test
    void anUnchangedDirectoryDoesNotFireAgain() throws Exception {
        SshTtyConnector connector = connector();
        List<RemoteDirectoryChange> changes = new CopyOnWriteArrayList<>();
        connector.addRemoteDirectoryListener(changes::add);

        receive(connector, osc7("web01", "/srv") + "prompt$ " + osc7("web01", "/srv"), 4096);
        receive(connector, osc7("web01.example.com", "/srv/"), 4096);

        assertThat(changes).hasSize(1);
    }

    @Test
    void aTypedCdFiresAsLowConfidence() throws Exception {
        SshTtyConnector connector = connector();
        List<RemoteDirectoryChange> changes = new CopyOnWriteArrayList<>();
        connector.addRemoteDirectoryListener(changes::add);

        type(connector, "cd /var\r");

        assertThat(changes).containsExactly(
            new RemoteDirectoryChange("/var", RemoteDirectoryChange.Source.TYPED_CD, null));
        assertThat(changes.get(0).lowConfidence()).isTrue();
    }

    @Test
    void aThrowingListenerBreaksNeitherTheOthersNorTheParser() throws Exception {
        SshTtyConnector connector = connector();
        List<RemoteDirectoryChange> changes = new CopyOnWriteArrayList<>();
        connector.addRemoteDirectoryListener(change -> {
            throw new IllegalStateException("boom");
        });
        connector.addRemoteDirectoryListener(changes::add);

        receive(connector, osc7("web01", "/srv") + osc7("web01", "/opt"), 7);

        assertThat(changes).hasSize(2);
        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/opt");
    }

    @Test
    void aListenerMayReadTheDirectoryBack() throws Exception {
        SshTtyConnector connector = connector();
        List<String> seen = new CopyOnWriteArrayList<>();
        connector.addRemoteDirectoryListener(change -> seen.add(connector.getCurrentRemoteDirectory()));

        receive(connector, osc7("web01", "/srv"), 4096);

        assertThat(seen).containsExactly("/srv");
    }

    @Test
    void unsubscribeStopsDeliveryAndClosingTwiceIsHarmless() throws Exception {
        SshTtyConnector connector = connector();
        List<RemoteDirectoryChange> changes = new CopyOnWriteArrayList<>();
        RemoteDirectoryChange.Subscription subscription = connector.addRemoteDirectoryListener(changes::add);

        receive(connector, osc7("web01", "/srv"), 4096);
        subscription.close();
        subscription.close();
        receive(connector, osc7("web01", "/opt"), 4096);

        assertThat(changes).hasSize(1);
        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/opt");
    }

    @Test
    void aForeignOsc7DoesNotFire() throws Exception {
        SshTtyConnector connector = connector();
        receive(connector, osc7("web01", "/srv"), 4096);
        List<RemoteDirectoryChange> changes = new CopyOnWriteArrayList<>();
        connector.addRemoteDirectoryListener(changes::add);

        receive(connector, osc7("db02", "/etc"), 4096);

        assertThat(changes).isEmpty();
    }
}
