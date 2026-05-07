package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.testng.annotations.Test;

import java.io.EOFException;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class SFTPSessionTest {

    @Test
    void passwordConnectionsIncludePasswordAuthFactory() {
        ServerConnection connection = new ServerConnection();
        connection.setAuthMethod(AuthMethod.PASSWORD);

        List<String> factoryNames = SFTPSession.buildUserAuthFactories(connection).stream()
            .map(factory -> factory.getClass().getSimpleName())
            .toList();

        assertThat(factoryNames.getFirst()).isEqualTo(UserAuthPasswordFactory.class.getSimpleName());
        assertThat(factoryNames.contains(UserAuthKeyboardInteractiveFactory.class.getSimpleName())).isTrue();
        assertThat(factoryNames.contains(UserAuthPublicKeyFactory.class.getSimpleName())).isTrue();
    }

    @Test
    void publicKeyConnectionsKeepInteractiveAndPasswordFallbacks() {
        ServerConnection connection = new ServerConnection();
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);

        List<String> factoryNames = SFTPSession.buildUserAuthFactories(connection).stream()
            .map(factory -> factory.getClass().getSimpleName())
            .toList();

        assertThat(factoryNames.getFirst()).isEqualTo(UserAuthPublicKeyFactory.class.getSimpleName());
        assertThat(factoryNames.contains(UserAuthKeyboardInteractiveFactory.class.getSimpleName())).isTrue();
        assertThat(factoryNames.contains(UserAuthPasswordFactory.class.getSimpleName())).isTrue();
    }

    @Test
    void detectsSftpSubsystemNegotiationFailure() {
        RuntimeException failure = new RuntimeException(
            "IoWriteFutureImpl[SftpChannelSubsystem][SSH_MSG_CHANNEL_DATA]: Failed (EOFException) to execute: Channel closing",
            new EOFException("Channel closing"));

        assertThat(SFTPSession.isSftpSubsystemNegotiationFailure(failure)).isTrue();
        assertThat(SFTPSession.sftpSubsystemFailureMessage(failure))
            .contains("SFTP-Subsystem wurde nach erfolgreicher SSH-Authentifizierung vom Server abgelehnt oder geschlossen");
    }

    @Test
    void detectsSftpSubsystemRequestFailure() {
        RuntimeException failure = new RuntimeException("subsystem request failed on channel 0");

        assertThat(SFTPSession.isSftpSubsystemNegotiationFailure(failure)).isTrue();
        assertThat(SFTPSession.sftpSubsystemFailureMessage(failure))
            .contains("Prüfe, ob SFTP für dieses Ziel bzw. den SSH-Proxy freigegeben ist");
    }

    @Test
    void genericSftpStartFailureKeepsCauseMessage() {
        RuntimeException failure = new RuntimeException("permission denied");

        assertThat(SFTPSession.isSftpSubsystemNegotiationFailure(failure)).isFalse();
        assertThat(SFTPSession.sftpSubsystemFailureMessage(failure))
            .isEqualTo("SFTP-Subsystem konnte nach erfolgreicher SSH-Authentifizierung nicht gestartet werden: permission denied");
    }
}
