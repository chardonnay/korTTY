package de.kortty.core;

import de.kortty.model.ServerConnection;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * The saved connections used last, the one used last first: the buttons at the top of Quick Connect
 * and the connections of <i>File → Open Recent</i>. A connection counts as used once its
 * {@link ServerConnection#getLastUsed() last use} is set, which happens when a tab opens for it.
 */
public final class RecentConnections {

    private RecentConnections() {
    }

    /**
     * The at most {@code count} connections that were ever used, the one used last first; connections
     * used at the same moment keep the order they have in {@code connections}.
     */
    public static List<ServerConnection> top(Collection<ServerConnection> connections, int count) {
        return top(connections, count, 0L);
    }

    /**
     * Like {@link #top(Collection, int)}, counting only the uses after {@code usedAfter}, the moment
     * (epoch milliseconds) <i>File → Open Recent → Clear List</i> was chosen; {@code 0} counts every use.
     */
    public static List<ServerConnection> top(Collection<ServerConnection> connections, int count, long usedAfter) {
        if (connections == null || count <= 0) {
            return List.of();
        }
        long threshold = Math.max(0L, usedAfter);
        return connections.stream()
                .filter(Objects::nonNull)
                .filter(connection -> connection.getLastUsed() > threshold)
                .sorted(Comparator.comparingLong(ServerConnection::getLastUsed).reversed())
                .limit(count)
                .toList();
    }
}
