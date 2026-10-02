package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import de.kortty.ui.I18n;
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

    // The messages come from the active UI language; compare against the bundle, not a literal.

    @Test
    void detectsSftpSubsystemNegotiationFailure() {
        String cause = "IoWriteFutureImpl[SftpChannelSubsystem][SSH_MSG_CHANNEL_DATA]: Failed (EOFException) to execute: Channel closing";
        RuntimeException failure = new RuntimeException(cause, new EOFException("Channel closing"));

        assertThat(SFTPSession.isSftpSubsystemNegotiationFailure(failure)).isTrue();
        assertThat(SFTPSession.sftpSubsystemFailureMessage(failure))
            .isEqualTo(I18n.get("sftp.error.subsystemRejected", cause));
    }

    @Test
    void detectsSftpSubsystemRequestFailure() {
        RuntimeException failure = new RuntimeException("subsystem request failed on channel 0");

        assertThat(SFTPSession.isSftpSubsystemNegotiationFailure(failure)).isTrue();
        assertThat(SFTPSession.sftpSubsystemFailureMessage(failure))
            .isEqualTo(I18n.get("sftp.error.subsystemRejected", "subsystem request failed on channel 0"));
    }

    @Test
    void genericSftpStartFailureKeepsCauseMessage() {
        RuntimeException failure = new RuntimeException("permission denied");

        assertThat(SFTPSession.isSftpSubsystemNegotiationFailure(failure)).isFalse();
        assertThat(SFTPSession.sftpSubsystemFailureMessage(failure))
            .isEqualTo(I18n.get("sftp.error.subsystemStartFailed", "permission denied"));
        assertThat(SFTPSession.sftpSubsystemFailureMessage(failure)).endsWith("permission denied");
    }

    @Test
    void subsystemMessagesAreTranslatedNotGerman() {
        // Before: always German, whatever the UI language.
        assertThat(I18n.get("sftp.error.subsystemRejected", "x")).isNotEqualTo("sftp.error.subsystemRejected");
        assertThat(I18n.get("sftp.error.subsystemStartFailed", "x")).isNotEqualTo("sftp.error.subsystemStartFailed");
        assertThat(I18n.get("sftp.error.unknownCause")).isNotEqualTo("sftp.error.unknownCause");
    }
}
