package de.kortty.core;

import de.kortty.model.ConnectionSettings;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.common.CommonModuleProperties;
import org.apache.sshd.common.session.SessionHeartbeatController;
import org.apache.sshd.core.CoreModuleProperties;
import org.testng.annotations.Test;

import java.time.Duration;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import static com.google.common.truth.Truth.assertThat;


class SshTtyConnectorAgentOscTest {

    @Test
    void disablesInitialPtyEchoWhenShellStartupCommandIsConfigured() {
        assertThat(SshTtyConnector.initialPtyEchoMode("alias agent=true\n")).isEqualTo(0);
    }

    @Test
    void keepsInitialPtyEchoEnabledWithoutShellStartupCommand() {
        assertThat(SshTtyConnector.initialPtyEchoMode(null)).isEqualTo(1);
        assertThat(SshTtyConnector.initialPtyEchoMode("   ")).isEqualTo(1);
    }

    @Test
    void configuresSessionHeartbeatFromConnectionSettings() {
        SshClient client = SshClient.setUpDefaultClient();
        ConnectionSettings settings = new ConnectionSettings();
        settings.setSshKeepAliveEnabled(true);
        settings.setSshKeepAliveInterval(30);

        SshTtyConnector.configureKeepAlive(client, settings);

        assertThat(CommonModuleProperties.SESSION_HEARTBEAT_TYPE.getRequired(client)).isEqualTo(SessionHeartbeatController.HeartbeatType.IGNORE);
        assertThat(CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client)).isEqualTo(Duration.ofSeconds(30));
        assertThat(CoreModuleProperties.SOCKET_KEEPALIVE.getRequired(client)).isTrue();
    }

    @Test
    void disablesSessionHeartbeatWhenConnectionSettingIsDisabled() {
        SshClient client = SshClient.setUpDefaultClient();
        ConnectionSettings settings = new ConnectionSettings();
        settings.setSshKeepAliveEnabled(false);

        SshTtyConnector.configureKeepAlive(client, settings);

        assertThat(CommonModuleProperties.SESSION_HEARTBEAT_TYPE.getRequired(client)).isEqualTo(SessionHeartbeatController.HeartbeatType.NONE);
        assertThat(CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client)).isEqualTo(Duration.ZERO);
        assertThat(CoreModuleProperties.SOCKET_KEEPALIVE.getRequired(client)).isFalse();
    }

    @Test
    void clampsSessionHeartbeatIntervalToSupportedUiRange() {
        SshClient client = SshClient.setUpDefaultClient();
        ConnectionSettings settings = new ConnectionSettings();
        settings.setSshKeepAliveEnabled(true);
        settings.setSshKeepAliveInterval(1);

        SshTtyConnector.configureKeepAlive(client, settings);
        assertThat(CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client)).isEqualTo(Duration.ofSeconds(5));

        settings.setSshKeepAliveInterval(1000);
        SshTtyConnector.configureKeepAlive(client, settings);
        assertThat(CommonModuleProperties.SESSION_HEARTBEAT_INTERVAL.getRequired(client)).isEqualTo(Duration.ofSeconds(600));
    }

    @Test
    void extractsWorkingDirectoryFromAgentOscPayload() {
        String cwd = Base64.getEncoder().encodeToString("/home/daniel/Dokumente".getBytes(StandardCharsets.UTF_8));
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertThat(SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + cwd + ";" + prompt)).isEqualTo("/home/daniel/Dokumente");
    }

    @Test
    void ignoresOldAgentOscPayloadWithoutWorkingDirectory() {
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertThat(SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + prompt)).isNull();
    }

    @Test
    void ignoresRelativeWorkingDirectoryPayload() {
        String cwd = Base64.getEncoder().encodeToString("Dokumente".getBytes(StandardCharsets.UTF_8));
        String prompt = Base64.getEncoder().encodeToString("create file".getBytes(StandardCharsets.UTF_8));

        assertThat(SshTtyConnector.extractWorkingDirectoryFromAgentOscPayload("execute;" + cwd + ";" + prompt)).isNull();
    }
}
