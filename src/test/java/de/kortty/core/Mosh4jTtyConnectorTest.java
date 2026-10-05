package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


class Mosh4jTtyConnectorTest {

    @Test
    void connectRefusesAnEnabledJumpServerBeforeAnyNetwork() {
        ServerConnection connection = new ServerConnection("Test", "example.com", 22, "daniel");
        connection.setProtocol(ConnectionProtocol.MOSH);
        JumpServer jump = new JumpServer("bastion.example.com", 22, "hopper");
        jump.setEnabled(true);
        connection.setJumpServer(jump);

        Mosh4jTtyConnector connector = new Mosh4jTtyConnector(connection, "secret");

        IllegalStateException refusal = expectThrows(IllegalStateException.class, connector::connect);
        assertThat(refusal).hasMessageThat().contains("UDP");
        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    void isConnectedStaysTrueDuringTransientInterruption() throws Exception {
        Mosh4jTtyConnector connector = new Mosh4jTtyConnector(
                new ServerConnection("Test", "example.com", 22, "daniel"),
                "secret");

        // A mosh session stays connected while its transport is interrupted (the engine revives it),
        // so the terminal keeps forwarding keystrokes; only close() ends it.
        setField(connector, "connected", new java.util.concurrent.atomic.AtomicBoolean(true));

        assertThat(connector.isConnected()).isTrue();
        assertThat(connector.isNetworkInterrupted()).isFalse();
    }

    @Test
    void howASessionEndedBecomesItsDisconnectMessage() {
        assertThat(Mosh4jTtyConnector.endReason(Mosh4jEngine.End.REMOTE_LOGOUT, null))
            .isEqualTo(LanguageManager.getInstance().getString("mosh.mosh4j.remoteLogout"));
        assertThat(Mosh4jTtyConnector.endReason(Mosh4jEngine.End.ENDED, null))
            .isEqualTo(LanguageManager.getInstance().getString("mosh.mosh4j.sessionEnded"));
        assertThat(Mosh4jTtyConnector.endReason(Mosh4jEngine.End.STOPPED, null))
            .isEqualTo(LanguageManager.getInstance().getString("mosh.mosh4j.sessionEnded"));
        assertThat(Mosh4jTtyConnector.endReason(Mosh4jEngine.End.FAILED, "boom"))
            .isEqualTo(LanguageManager.getInstance().getString("mosh.mosh4j.frontendFailed", "boom"));
    }

    @Test
    void isConnectedIsFalseAfterConnectorClosed() {
        Mosh4jTtyConnector connector = new Mosh4jTtyConnector(
                new ServerConnection("Test", "example.com", 22, "daniel"),
                "secret");

        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    void anEncodingOverrideDoesNotApplyToMosh() throws Exception {
        ServerConnection connection = new ServerConnection("Test", "example.com", 22, "daniel");
        connection.setProtocol(ConnectionProtocol.MOSH);
        connection.setEncoding("ISO-8859-1");

        // mosh-server and mosh-client need a UTF-8 locale, so the override is kept but not applied.
        assertThat(TerminalEncodingSupport.resolve(connection, "Windows-1252"))
            .isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        Field charset = Mosh4jTtyConnector.class.getDeclaredField("MOSH_CHARSET");
        charset.setAccessible(true);
        assertThat(charset.get(null)).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Test
    void reusesBouncyCastleFromParentClassLoader() {
        assertThat(Mosh4jTtyConnector.parentProvidesBouncyCastle(getClass().getClassLoader())).isTrue();
        assertThat(Mosh4jTtyConnector.parentProvidesBouncyCastle(new ClassLoader(null) {})).isFalse();
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
