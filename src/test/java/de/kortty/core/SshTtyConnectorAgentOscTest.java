package de.kortty.core;

import de.kortty.model.ConnectionSettings;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.CommonModuleProperties;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.core.CoreModuleProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SshTtyConnectorAgentOscTest {

    @Test
    void disablesInitialPtyEchoWhenShellStartupCommandIsConfigured() {
        assertEquals(0, SshTtyConnector.initialPtyEchoMode("alias agent=true\n"));
    }

    @Test
    void keepsInitialPtyEchoEnabledWithoutShellStartupCommand() {
        assertEquals(1, SshTtyConnector.initialPtyEchoMode(null));
        assertEquals(1, SshTtyConnector.initialPtyEchoMode("   "));
    }

    @Test
    void configuresSessionHeartbeatFromConnectionSettings() {
        SshClient client = SshClient.setUpDefaultClient();
        ConnectionSettings settings = new ConnectionSettings();
        settings.setSshKeepAliveEnabled(true);
        settings.setSshKeepAliveInterval(30);

        SshTtyConnector.configureKeepAlive(client, settings);

        assertEquals(
            SessionHeartbeatController.HeartbeatType.IGNORE,
            CommonModuleProperties.SESSION_HEARTBEAT_TYPE.getRequired(client));
        assertEquals(Duration.ofSeconds(30), CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client));
        assertTrue(CoreModuleProperties.SOCKET_KEEPALIVE.getRequired(client));
    }

    @Test
    void disablesSessionHeartbeatWhenConnectionSettingIsDisabled() {
        SshClient client = SshClient.setUpDefaultClient();
        ConnectionSettings settings = new ConnectionSettings();
        settings.setSshKeepAliveEnabled(false);

        SshTtyConnector.configureKeepAlive(client, settings);

        assertEquals(
            SessionHeartbeatController.HeartbeatType.NONE,
            CommonModuleProperties.SESSION_HEARTBEAT_TYPE.getRequired(client));
        assertEquals(Duration.ZERO, CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client));
        assertFalse(CoreModuleProperties.SOCKET_KEEPALIVE.getRequired(client));
    }

    @Test
    void clampsSessionHeartbeatIntervalToSupportedUiRange() {
        SshClient client = SshClient.setUpDefaultClient();
        ConnectionSettings settings = new ConnectionSettings();
        settings.setSshKeepAliveEnabled(true);
        settings.setSshKeepAliveInterval(1);

        SshTtyConnector.configureKeepAlive(client, settings);
        assertEquals(Duration.ofSeconds(5), CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client));

        settings.setSshKeepAliveInterval(1000);
        SshTtyConnector.configureKeepAlive(client, settings);
        assertEquals(Duration.ofSeconds(600), CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client));
    }

    @Test
    void extractsWorkingDirectoryFromAgentOscPayload() {
        String cwd = Base64.getEncoder().encodeToString("/home/daniel/Dokumente".getBytes(StandardCharsets.UTF_8));
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertEquals(
            "/home/daniel/Dokumente",
            SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + cwd + ";" + prompt));
    }

    @Test
    void ignoresOldAgentOscPayloadWithoutWorkingDirectory() {
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertNull(SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + prompt));
    }

    @Test
    void ignoresRelativeWorkingDirectoryPayload() {
        String cwd = Base64.getEncoder().encodeToString("Dokumente".getBytes(StandardCharsets.UTF_8));
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertNull(SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + cwd + ";" + prompt));
    }
}
