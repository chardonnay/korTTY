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
}
