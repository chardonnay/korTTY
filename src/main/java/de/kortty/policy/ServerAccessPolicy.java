package de.kortty.policy;

import de.kortty.core.JumpHostSupport;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;

import java.util.Optional;

/**
 * Convenience gate for the server allow/deny policy: evaluates a whole {@link ServerConnection}
 * (target host and jump host) against {@link PolicyManager#effective()}. Local-shell connections
 * carry no network target and are never blocked here.
 */
public final class ServerAccessPolicy {

    private ServerAccessPolicy() {
    }

    /** The first policy-blocked target of {@code connection} as {@code host:port}, or empty. */
    public static Optional<String> firstBlockedTarget(ServerConnection connection) {
        if (connection == null || connection.getProtocol() == de.kortty.model.ConnectionProtocol.LOCAL_SHELL) {
            return Optional.empty();
        }
        EffectivePolicy policy = PolicyManager.effective();
        // Only an actually-used jump host is checked: a connection routes through its jump server
        // only when it is enabled and has a host (JumpHostSupport.isActive). A disabled/blank jump
        // server is never contacted, so it must not block the connection.
        if (JumpHostSupport.isActive(connection)) {
            JumpServer jump = connection.getJumpServer();
            if (!policy.isServerAllowed(jump.getHost(), jump.getPort())) {
                return Optional.of(jump.getHost() + ":" + jump.getPort());
            }
        }
        if (!policy.isServerAllowed(connection.getHost(), connection.getPort())) {
            return Optional.of(connection.getHost() + ":" + connection.getPort());
        }
        return Optional.empty();
    }

    /** Whether {@code connection} may be opened under the active policy. */
    public static boolean isAllowed(ServerConnection connection) {
        return firstBlockedTarget(connection).isEmpty();
    }
}
