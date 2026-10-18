package de.kortty.core.sftp;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The user and group names behind the numeric owners an SFTP version 3 server reports: OpenSSH
 * sends only the UID and GID, so the SFTP manager showed "1000 1000". The names are looked up on
 * the server with one command per batch of IDs not seen before ({@link #lookupCommand}): getent,
 * which also knows LDAP and other directory users, or /etc/passwd and /etc/group where getent is
 * missing. They are kept for the session. A server that cannot run the command (an SFTP-only
 * account, a shell that is not POSIX) is {@linkplain #markUnavailable() asked once}, and the
 * numbers stay. Thread-safe: listings and lookups run off the FX thread, cells read on it.
 */
public final class RemoteAccountNames {

    /** Separates the passwd part of the lookup output from the group part. */
    static final String GROUP_MARKER = "#kortty-groups";
    /** A plausible user or group name: printable, no blank or colon, not absurdly long. */
    private static final Pattern NAME = Pattern.compile("[\\p{Graph}&&[^:]]{1,64}");

    private final Map<Integer, String> users = new ConcurrentHashMap<>();
    private final Map<Integer, String> groups = new ConcurrentHashMap<>();
    private final Set<Integer> askedUsers = ConcurrentHashMap.newKeySet();
    private final Set<Integer> askedGroups = ConcurrentHashMap.newKeySet();
    private volatile boolean unavailable;

    /** The IDs of one lookup; sorted, so the command is the same for the same IDs. */
    public record Pending(SortedSet<Integer> uids, SortedSet<Integer> gids) {
        public boolean isEmpty() {
            return uids.isEmpty() && gids.isEmpty();
        }
    }

    /** The user name of {@code uid}, once a lookup found it. */
    public Optional<String> user(int uid) {
        return Optional.ofNullable(users.get(uid));
    }

    /** The group name of {@code gid}, once a lookup found it. */
    public Optional<String> group(int gid) {
        return Optional.ofNullable(groups.get(gid));
    }

    /**
     * The IDs among these that were never asked for, now marked as asked, so two listings never
     * look up the same ID twice. Empty once the server proved unable to answer, and for negative IDs.
     */
    public synchronized Pending claim(Collection<Integer> uids, Collection<Integer> gids) {
        SortedSet<Integer> newUids = new TreeSet<>();
        SortedSet<Integer> newGids = new TreeSet<>();
        if (!unavailable) {
            for (Integer uid : uids) {
                if (uid != null && uid >= 0 && askedUsers.add(uid)) {
                    newUids.add(uid);
                }
            }
            for (Integer gid : gids) {
                if (gid != null && gid >= 0 && askedGroups.add(gid)) {
                    newGids.add(gid);
                }
            }
        }
        return new Pending(newUids, newGids);
    }

    /** Stores the names in the output of {@link #lookupCommand}; IDs it does not name stay numbers. */
    public void accept(String output) {
        boolean inGroups = false;
        for (String line : (output == null ? "" : output).split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.equals(GROUP_MARKER)) {
                inGroups = true;
                continue;
            }
            // passwd: name:x:uid:gid:...   group: name:x:gid:members
            String[] fields = trimmed.split(":", -1);
            if (fields.length < 3 || !NAME.matcher(fields[0]).matches()) {
                continue;
            }
            int id;
            try {
                id = Integer.parseInt(fields[2]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (id < 0) {
                continue;
            }
            // getent answers first; a /etc fallback after it may repeat an ID and must not win.
            (inGroups ? groups : users).putIfAbsent(id, fields[0]);
        }
    }

    /** The server cannot run the lookup: no further attempts in this session. */
    public void markUnavailable() {
        unavailable = true;
    }

    public boolean isUnavailable() {
        return unavailable;
    }

    /**
     * One shell command that prints the passwd lines of {@code pending}'s UIDs, the marker line and
     * the group lines of its GIDs. Only decimal numbers reach the command line. It always exits 0;
     * a missing getent, or one that does not know an ID, falls back to the whole /etc file.
     */
    public static String lookupCommand(Pending pending) {
        StringBuilder command = new StringBuilder();
        if (!pending.uids().isEmpty()) {
            command.append("{ getent passwd ").append(numbers(pending.uids()))
                .append(" || cat /etc/passwd; } 2>/dev/null; ");
        }
        command.append("echo '").append(GROUP_MARKER).append("'; ");
        if (!pending.gids().isEmpty()) {
            command.append("{ getent group ").append(numbers(pending.gids()))
                .append(" || cat /etc/group; } 2>/dev/null; ");
        }
        return command.append("true").toString();
    }

    private static String numbers(Collection<Integer> ids) {
        return ids.stream().map(id -> Integer.toString(id)).collect(Collectors.joining(" "));
    }
}
