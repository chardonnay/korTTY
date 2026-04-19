package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.auth.keyboard.UserAuthKeyboardInteractiveFactory;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.auth.pubkey.UserAuthPublicKeyFactory;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SFTPSessionTest {

    @Test
    void passwordConnectionsIncludePasswordAuthFactory() {
        ServerConnection connection = new ServerConnection();
        connection.setAuthMethod(AuthMethod.PASSWORD);

        List<String> factoryNames = SFTPSession.buildUserAuthFactories(connection).stream()
            .map(factory -> factory.getClass().getSimpleName())
            .toList();

        assertEquals(UserAuthPasswordFactory.class.getSimpleName(), factoryNames.getFirst());
        assertTrue(factoryNames.contains(UserAuthKeyboardInteractiveFactory.class.getSimpleName()));
        assertTrue(factoryNames.contains(UserAuthPublicKeyFactory.class.getSimpleName()));
    }

    @Test
    void publicKeyConnectionsKeepInteractiveAndPasswordFallbacks() {
        ServerConnection connection = new ServerConnection();
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);

        List<String> factoryNames = SFTPSession.buildUserAuthFactories(connection).stream()
            .map(factory -> factory.getClass().getSimpleName())
            .toList();

        assertEquals(UserAuthPublicKeyFactory.class.getSimpleName(), factoryNames.getFirst());
        assertTrue(factoryNames.contains(UserAuthKeyboardInteractiveFactory.class.getSimpleName()));
        assertTrue(factoryNames.contains(UserAuthPasswordFactory.class.getSimpleName()));
    }
}
