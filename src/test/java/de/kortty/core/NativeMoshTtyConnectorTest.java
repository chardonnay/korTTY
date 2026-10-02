package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class NativeMoshTtyConnectorTest {

    @Test
    void connectRefusesAnEnabledJumpServerBeforeAnyNetwork() {
        ServerConnection connection = new ServerConnection("Test", "example.com", 22, "daniel");
        connection.setProtocol(ConnectionProtocol.MOSH_CLIENT);
        JumpServer jump = new JumpServer("bastion.example.com", 22, "hopper");
        jump.setEnabled(true);
        connection.setJumpServer(jump);

        NativeMoshTtyConnector connector = new NativeMoshTtyConnector(connection, "secret");

        IllegalStateException refusal = expectThrows(IllegalStateException.class, connector::connect);
        assertThat(refusal).hasMessageThat().contains("UDP");
        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    void anEncodingOverrideDoesNotApplyToMosh() throws Exception {
        ServerConnection connection = new ServerConnection("Test", "example.com", 22, "daniel");
        connection.setProtocol(ConnectionProtocol.MOSH_CLIENT);
        connection.setEncoding("Windows-1252");

        // mosh-server and mosh-client need a UTF-8 locale, so the override is kept but not applied.
        assertThat(TerminalEncodingSupport.resolve(connection, "ISO-8859-1"))
            .isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        java.lang.reflect.Field charset = NativeMoshTtyConnector.class.getDeclaredField("MOSH_CHARSET");
        charset.setAccessible(true);
        assertThat(charset.get(null)).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
    }
}
