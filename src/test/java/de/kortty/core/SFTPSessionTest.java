package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.testng.annotations.Test;

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
}
