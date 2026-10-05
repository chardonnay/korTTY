package de.kortty.core.worker;

import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.auth.password.UserAuthPasswordFactory;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;

import java.io.IOException;
import java.security.PublicKey;
import java.time.Duration;
import java.util.List;

/**
 * korTTY's login to a session worker's loopback endpoint: the token as the password, and the
 * worker's host key pinned to the one it reported on its control channel.
 *
 * @param client  the client, to stop with the session
 * @param session the authenticated session
 */
public record WorkerLogin(SshClient client, ClientSession session) implements AutoCloseable {

    /** Logs in to the endpoint {@code ready} reported, with {@code token}. */
    public static WorkerLogin connect(SessionWorkerProcess.Ready ready, String token) throws IOException {
        SshClient client = SshClient.setUpDefaultClient();
        client.setKeyIdentityProvider(null);
        client.setUserAuthFactories(List.of(new UserAuthPasswordFactory()));
        PublicKey workerKey = ready.hostKey();
        client.setServerKeyVerifier((clientSession, address, key) -> KeyUtils.compareKeys(workerKey, key));
        client.start();
        try {
            ClientSession session = client.connect("kortty", "127.0.0.1", ready.port())
                .verify(Duration.ofSeconds(15)).getSession();
            session.setKeyIdentityProvider(null);
            session.addPasswordIdentity(token);
            session.auth().verify(Duration.ofSeconds(15));
            return new WorkerLogin(client, session);
        } catch (IOException | RuntimeException e) {
            client.stop();
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            session.close(true);
        } catch (RuntimeException ignored) {
            // Closing anyway.
        }
        client.stop();
    }
}
