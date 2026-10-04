package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.core.SshTtyConnectorTestIo.connector;
import static de.kortty.core.SshTtyConnectorTestIo.osc7;
import static de.kortty.core.SshTtyConnectorTestIo.receive;
import static de.kortty.core.SshTtyConnectorTestIo.type;

/**
 * Pins the OSC 7 host baseline. The connection's configured host (an IP, an alias, a proxy) is no
 * reference for the host a shell reports, so the connector learns the first OSC 7 host of the
 * native session and refuses reports from any other host, marking the session foreign until the
 * baseline host reports again.
 */
class SshTtyConnectorOsc7HostTest {

    @Test
    void parsesHostAndPath() {
        assertThat(SshTtyConnector.parseOsc7Uri("file://Web01.Example.com/srv/My%20App"))
            .isEqualTo(new SshTtyConnector.Osc7Location("web01.example.com", "/srv/My App"));
        assertThat(SshTtyConnector.parseOsc7Uri("file:///opt")).isEqualTo(new SshTtyConnector.Osc7Location("", "/opt"));
        assertThat(SshTtyConnector.parseOsc7Uri("file://web01/My Docs"))
            .isEqualTo(new SshTtyConnector.Osc7Location("web01", "/My Docs"));
        assertThat(SshTtyConnector.parseOsc7Uri("http://web01/srv")).isNull();
        assertThat(SshTtyConnector.parseOsc7Uri("")).isNull();
    }

    @Test
    void anIpConfiguredConnectionAdoptsAShortHostnameAsBaseline() throws Exception {
        SshTtyConnector connector = connector();

        receive(connector, osc7("web01", "/srv/app"), 4096);

        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/srv/app");
        assertThat(connector.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void theFqdnOfTheBaselineIsTheSameHost() throws Exception {
        SshTtyConnector connector = connector();
        receive(connector, osc7("web01", "/srv"), 4096);

        receive(connector, osc7("WEB01.example.com", "/var/log"), 5);

        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/var/log");
        assertThat(connector.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void localhostAndAnEmptyHostAreAdopted() throws Exception {
        SshTtyConnector connector = connector();
        receive(connector, osc7("web01", "/srv"), 4096);

        receive(connector, osc7("localhost", "/tmp"), 4096);
        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/tmp");
        receive(connector, osc7("", "/opt"), 4096);
        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/opt");
        assertThat(connector.isForeignSessionSuspected()).isFalse();
    }

    @Test
    void anotherHostIsNotAdoptedAndMarksTheSessionForeign() throws Exception {
        SshTtyConnector connector = connector();
        receive(connector, osc7("web01", "/srv"), 4096);

        receive(connector, osc7("db02", "/etc"), 3);

        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/srv");
        assertThat(connector.isForeignSessionSuspected()).isTrue();
    }

    @Test
    void theForeignFlagSurvivesANativePromptAndClearsOnABaselineReport() throws Exception {
        SshTtyConnector connector = connector();
        receive(connector, osc7("web01", "/srv"), 4096);
        receive(connector, osc7("db02", "/etc"), 4096);

        connector.confirmNativeSessionIdentity(); // the prompt heuristic must not clear it
        assertThat(connector.isForeignSessionSuspected()).isTrue();
        receive(connector, osc7("db02", "/root"), 4096);
        assertThat(connector.isForeignSessionSuspected()).isTrue();
        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/srv");

        receive(connector, osc7("web01.example.com", "/home/daniel"), 4096);

        assertThat(connector.isForeignSessionSuspected()).isFalse();
        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/home/daniel");
    }

    @Test
    void noBaselineIsLearnedWhileTheSessionIsSuspectedForeign() throws Exception {
        SshTtyConnector connector = connector();
        type(connector, "ssh db02\r");

        receive(connector, osc7("db02", "/etc"), 4096); // no baseline yet: the previous behaviour
        type(connector, "exit\r");
        receive(connector, osc7("web01", "/srv"), 4096); // native again: learned now

        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/srv");
        receive(connector, osc7("db02", "/tmp"), 4096);
        assertThat(connector.getCurrentRemoteDirectory()).isEqualTo("/srv");
        assertThat(connector.isForeignSessionSuspected()).isTrue();
    }
}
