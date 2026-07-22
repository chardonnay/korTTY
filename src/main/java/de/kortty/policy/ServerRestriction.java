package de.kortty.policy;

import java.util.List;

/**
 * One rule's {@code [rule.servers]} table: an allow-list or deny-list of server patterns.
 *
 * @param mode     ALLOW = only listed servers may be connected to; DENY = listed servers are blocked
 * @param patterns the parsed patterns; never null
 */
public record ServerRestriction(Mode mode, List<ServerMatcher> patterns) {

    public enum Mode { ALLOW, DENY }

    public ServerRestriction {
        patterns = List.copyOf(patterns);
    }

    /** Whether a connection to {@code host:port} passes this restriction. */
    public boolean permits(String host, int port) {
        boolean listed = patterns.stream().anyMatch(p -> p.matches(host, port));
        return mode == Mode.ALLOW ? listed : !listed;
    }
}
