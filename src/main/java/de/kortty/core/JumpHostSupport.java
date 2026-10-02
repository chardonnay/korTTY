package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Establishes the bastion hop for connections with an enabled jump server: connect to the jump
 * host, authenticate there, and open a local loopback forward whose far end is the target host.
 * The caller then connects the real session to the returned loopback address.
 *
 * <p>Two properties are deliberate and load-bearing:
 *
 * <ul>
 *   <li><b>Credential separation.</b> The hop uses its own {@link SshClient} and adds the jump
 *       credentials at session level, so the target's password is never offered to the bastion and
 *       the bastion's password never to the target. (SSHD's native ProxyJump shares one client,
 *       whose client-level identities are offered to every hop.)</li>
 *   <li><b>Per-host key pinning.</b> The jump client verifies against a
 *       {@link SshHostKeyTrustManager} verifier built for the jump endpoint, so the bastion's key
 *       is pinned under {@code jumpHost:jumpPort}. The caller keeps its own verifier for the
 *       target, and because that verifier pins under the ADDRESSED host — not the transport
 *       address — the target's key is verified under {@code targetHost:targetPort} even though
 *       the TCP connection goes to the loopback forward.</li>
 * </ul>
 *
 * <p>Failures that retrying cannot fix — an incomplete jump setup, a jump password that cannot be
 * used, a bastion host key the user rejected — are thrown as {@link PermanentJumpFailure}; network
 * and handshake failures stay plain {@link IOException}s, so callers can keep retrying those.
 */
public final class JumpHostSupport {

    private static final Logger logger = LoggerFactory.getLogger(JumpHostSupport.class);

    private JumpHostSupport() {
    }

    /**
     * A jump-server failure that a retry cannot fix, so the caller must stop and tell the user
     * instead of trying again. Its message is user-presentable as is.
     */
    public static final class PermanentJumpFailure extends IOException {
        private static final long serialVersionUID = 1L;

        /** Why the hop can never succeed as configured. */
        public enum Kind {
            /** The jump server is incomplete: no username, no key file, or a key that cannot be loaded. */
            CONFIGURATION,
            /** The stored jump password is missing, locked in the vault, or cannot be decrypted. */
            CREDENTIALS,
            /** The bastion's host key was rejected, by the user or against its pinned key. */
            HOST_KEY_REJECTED
        }

        private final Kind kind;

        PermanentJumpFailure(Kind kind, String message) {
            this(kind, message, null);
        }

        PermanentJumpFailure(Kind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = java.util.Objects.requireNonNull(kind, "kind");
        }

        public Kind kind() {
            return kind;
        }
    }

    /** Whether {@code connection} declares a usable, enabled jump server. */
    public static boolean isActive(ServerConnection connection) {
        JumpServer jump = connection != null ? connection.getJumpServer() : null;
        return jump != null && jump.isEnabled()
            && jump.getHost() != null && !jump.getHost().isBlank();
    }

    /**
     * An established hop: the jump SSH session plus the loopback address that forwards to the
     * target. Closing releases the forward, the session and the client, in that order.
     */
    public static final class JumpTunnel implements AutoCloseable {
        private final SshClient jumpClient;
        private final ClientSession jumpSession;
        private final SshdSocketAddress localEndpoint;

        private JumpTunnel(SshClient jumpClient, ClientSession jumpSession, SshdSocketAddress localEndpoint) {
            this.jumpClient = jumpClient;
            this.jumpSession = jumpSession;
            this.localEndpoint = localEndpoint;
        }

        /** Loopback host the caller must connect to instead of the target host. */
        public String localHost() {
            return localEndpoint.getHostName();
        }

        /** Loopback port the caller must connect to instead of the target port. */
        public int localPort() {
            return localEndpoint.getPort();
        }

        @Override
        public void close() {
            try {
                if (jumpSession != null && !jumpSession.isClosed()) {
                    jumpSession.close(true);
                }
            } catch (Exception e) {
                logger.debug("Closing jump session failed", e);
            }
            try {
                if (jumpClient != null) {
                    jumpClient.stop();
                }
            } catch (Exception e) {
                logger.debug("Stopping jump client failed", e);
            }
        }
    }

    /**
     * Connects and authenticates to {@code connection}'s jump server and opens a loopback forward
     * to the target. Throws with a user-presentable message when the hop cannot be established;
     * never returns a half-open tunnel.
     *
     * <p>The jump credentials are checked — and a stored password decrypted — before any socket is
     * opened, so a locked vault or an incomplete setup never contacts the bastion or shows a
     * host-key prompt for it.
     *
     * @param masterPassword needed to decrypt the stored jump password; may be {@code null} for
     *        key-based jumps
     * @throws PermanentJumpFailure when the jump configuration or its credentials cannot work, or
     *         the bastion's host key was rejected; retrying will not help
     * @throws IOException when the bastion cannot be reached or the hop fails on the network
     */
    public static JumpTunnel open(
            ServerConnection connection,
            SshHostKeyTrustManager trustManager,
            char[] masterPassword,
            Duration timeout) throws IOException {

        JumpServer jump = connection.getJumpServer();
        if (!isActive(connection)) {
            throw new PermanentJumpFailure(PermanentJumpFailure.Kind.CONFIGURATION,
                "Jump server is not configured for this connection.");
        }
        String jumpUser = jump.getUsername();
        if (jumpUser == null || jumpUser.isBlank()) {
            throw new PermanentJumpFailure(PermanentJumpFailure.Kind.CONFIGURATION,
                "Jump server username is missing.");
        }
        // Validate the credential configuration before opening any socket: an incomplete jump setup
        // should fail fast, not after a connect attempt to the bastion.
        boolean keyAuth = jump.getAuthMethod() == AuthMethod.PUBLIC_KEY;
        if (keyAuth && (jump.getPrivateKeyPath() == null || jump.getPrivateKeyPath().isBlank())) {
            throw new PermanentJumpFailure(PermanentJumpFailure.Kind.CONFIGURATION,
                "Jump server is set to key authentication but no key file is configured.");
        }
        // The same goes for the password: it needs the vault, and a locked vault must not first
        // contact the bastion (and show its host-key prompt) only to fail afterwards.
        String jumpPassword = null;
        if (!keyAuth) {
            jumpPassword = decryptJumpPassword(jump, masterPassword);
            if (jumpPassword == null || jumpPassword.isEmpty()) {
                throw new PermanentJumpFailure(PermanentJumpFailure.Kind.CREDENTIALS,
                    "Jump server password is not available. Store it in the connection settings, "
                        + "or unlock the master password vault.");
            }
        }

        // The bastion's key is pinned under its own endpoint, with the same TOFU prompt as any
        // other host. A synthetic ServerConnection carries just host+port to the shared verifier.
        ServerConnection jumpEndpoint = new ServerConnection();
        jumpEndpoint.setHost(jump.getHost());
        jumpEndpoint.setPort(jump.getPort());

        SshClient jumpClient = SshClient.setUpDefaultClient();
        jumpClient.setKeyIdentityProvider(null);
        SshHostKeyTrustManager.ConnectionVerifier verifier = trustManager.verifierFor(jumpEndpoint);
        jumpClient.setServerKeyVerifier(verifier);
        jumpClient.start();

        ClientSession jumpSession = null;
        try {
            jumpSession = jumpClient
                .connect(jumpUser, jump.getHost(), jump.getPort())
                .verify(timeout)
                .getSession();
            jumpSession.setKeyIdentityProvider(null);

            if (keyAuth) {
                // Presence already validated before connect; re-read the path here.
                String keyPath = jump.getPrivateKeyPath();
                // Passphrase-protected keys are not supported for the hop: the jump model stores
                // no passphrase, and prompting mid-connect would block the connection thread.
                FileKeyPairProvider keyProvider = new FileKeyPairProvider(Path.of(keyPath));
                try {
                    keyProvider.loadKeys(jumpSession).forEach(jumpSession::addPublicKeyIdentity);
                } catch (Exception e) {
                    throw new PermanentJumpFailure(PermanentJumpFailure.Kind.CONFIGURATION,
                        "Jump server key could not be loaded (passphrase-protected keys are not supported for the hop): "
                            + e.getMessage(), e);
                }
            } else {
                // Resolved and checked before connecting.
                jumpSession.addPasswordIdentity(jumpPassword);
            }

            jumpSession.auth().verify(timeout);

            // Loopback-only listener on an ephemeral port; the far end is the real target. The
            // caller's own host-key verifier still pins the target under its real name.
            SshdSocketAddress local = jumpSession.startLocalPortForwarding(
                new SshdSocketAddress("127.0.0.1", 0),
                new SshdSocketAddress(connection.getHost(), connection.getPort()));
            logger.info("Jump tunnel established via {}:{} -> {}:{} (local {})",
                jump.getHost(), jump.getPort(), connection.getHost(), connection.getPort(), local);
            return new JumpTunnel(jumpClient, jumpSession, local);
        } catch (IOException e) {
            closeQuietly(jumpClient, jumpSession);
            if (verifier.wasRejected()) {
                throw new PermanentJumpFailure(PermanentJumpFailure.Kind.HOST_KEY_REJECTED,
                    "Jump server host key was not accepted.", e);
            }
            throw e;
        } catch (Exception e) {
            closeQuietly(jumpClient, jumpSession);
            throw new IOException("Jump server connection failed: " + e.getMessage(), e);
        }
    }

    private static String decryptJumpPassword(JumpServer jump, char[] masterPassword) throws PermanentJumpFailure {
        String encrypted = jump.getEncryptedPassword();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        if (masterPassword == null) {
            throw new PermanentJumpFailure(PermanentJumpFailure.Kind.CREDENTIALS,
                "Master password vault is locked; the jump server password cannot be decrypted.");
        }
        try {
            return new EncryptionService().decryptPassword(encrypted, masterPassword);
        } catch (Exception e) {
            throw new PermanentJumpFailure(PermanentJumpFailure.Kind.CREDENTIALS,
                "Stored jump server password could not be decrypted.", e);
        }
    }

    private static void closeQuietly(SshClient client, ClientSession session) {
        try {
            if (session != null && !session.isClosed()) {
                session.close(true);
            }
        } catch (Exception ignored) {
            // best-effort cleanup on the failure path
        }
        try {
            if (client != null) {
                client.stop();
            }
        } catch (Exception ignored) {
            // best-effort cleanup on the failure path
        }
    }
}
