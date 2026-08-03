package de.kortty.core;

import de.kortty.model.SessionJournalMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Builds the connection subtitle shown under a journal's title, without repeating what the title
 * already says. A journal named after its endpoint ("daniel@10.0.0.5") would otherwise print the
 * same connection three times: as the title, as the endpoint, and as the connection name.
 */
public final class SessionJournalHeaderSupport {

    public static final String SEPARATOR = " · ";

    private SessionJournalHeaderSupport() {
    }

    /**
     * The endpoint and connection name, each dropped when it is redundant. Returns an empty
     * string when the title already carries the whole connection.
     */
    public static String connectionSubtitle(SessionJournalMeta meta) {
        if (meta == null) {
            return "";
        }
        String title = normalize(meta.getTitle());
        String user = strip(meta.getUsername());
        String host = strip(meta.getHost());
        String name = strip(meta.getConnectionName());

        List<String> parts = new ArrayList<>(2);
        if (!host.isEmpty()) {
            String userHost = user.isEmpty() ? host : user + "@" + host;
            // The port is the only part the title never repeats, but showing "…:22" alone next to a
            // title that already names the endpoint is the duplication this method exists to avoid.
            if (!title.contains(normalize(userHost))) {
                parts.add(userHost + ":" + meta.getPort());
            }
        }
        if (!name.isEmpty() && !isEndpointAlias(name, user, host, meta.getPort())
            && !title.contains(normalize(name))) {
            parts.add(name);
        }
        return String.join(SEPARATOR, parts);
    }

    /** True when the connection name says nothing beyond the endpoint it was derived from. */
    private static boolean isEndpointAlias(String name, String user, String host, int port) {
        if (host.isEmpty()) {
            return false;
        }
        String normalized = normalize(name);
        String userHost = user.isEmpty() ? host : user + "@" + host;
        return normalized.equals(normalize(host))
            || normalized.equals(normalize(userHost))
            || normalized.equals(normalize(userHost + ":" + port));
    }

    private static String normalize(String value) {
        return strip(value).toLowerCase(Locale.ROOT);
    }

    private static String strip(String value) {
        return value != null ? value.strip() : "";
    }
}
