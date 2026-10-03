package de.kortty.policy;

import de.kortty.core.JumpHostSupport;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

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
        return firstBlockedTarget(connection, PolicyManager.effective());
    }

    /** {@link #firstBlockedTarget(ServerConnection)} against an explicit {@code policy}. */
    public static Optional<String> firstBlockedTarget(ServerConnection connection, EffectivePolicy policy) {
        if (connection == null || connection.getProtocol() == de.kortty.model.ConnectionProtocol.LOCAL_SHELL) {
            return Optional.empty();
        }
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

    /**
     * Splits {@code connections} into the ones the active policy allows and the targets that block
     * the others, for opening several connections at once: the allowed ones still open, and one
     * policy message names every blocked target.
     */
    public static Partition partition(List<ServerConnection> connections) {
        return partition(connections, PolicyManager.effective());
    }

    /** {@link #partition(List)} against an explicit {@code policy}. */
    public static Partition partition(List<ServerConnection> connections, EffectivePolicy policy) {
        List<ServerConnection> allowed = new ArrayList<>();
        Set<String> blockedTargets = new LinkedHashSet<>();
        for (ServerConnection connection : connections) {
            if (connection == null) {
                continue;
            }
            Optional<String> blocked = firstBlockedTarget(connection, policy);
            if (blocked.isPresent()) {
                blockedTargets.add(blocked.get());
            } else {
                allowed.add(connection);
            }
        }
        return new Partition(List.copyOf(allowed), List.copyOf(blockedTargets));
    }

    /**
     * The connections a policy allows, in their original order, and the distinct blocked targets
     * ({@code host:port}, jump hosts included) of the others in order of first appearance.
     */
    public record Partition(List<ServerConnection> allowed, List<String> blockedTargets) {

        /** The blocked targets as one comma-separated list for the blocked-server message. */
        public String blockedTargetList() {
            return String.join(", ", blockedTargets);
        }
    }
}
