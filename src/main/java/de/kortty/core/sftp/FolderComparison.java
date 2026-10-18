package de.kortty.core.sftp;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Compares a local folder with a server folder by name, type, size and modification time, the way
 * "Compare folders" in the SFTP manager shows it, and lists what "Synchronize" copies.
 *
 * <p>Both sides are given as {@link Entry entries} with paths relative to the compared folders
 * ({@code docs/readme.md}, always with {@code /}). Two files whose times lie within
 * {@link #TIME_TOLERANCE_MILLIS} count as equally old: SFTP 3 keeps whole seconds and FAT two.
 * Synchronizing copies what is missing, newer or differs in size at the same age; a newer file on
 * the target side stays. A file on one side and a folder of the same name on the other is a
 * conflict that is never copied, and neither is anything below it.
 */
public final class FolderComparison {

    /** Times closer than this count as the same. */
    public static final long TIME_TOLERANCE_MILLIS = 2_000;

    /** How one path compares. */
    public enum State {
        /** Only in the local folder. */
        ONLY_LOCAL,
        /** Only in the server folder. */
        ONLY_REMOTE,
        /** A file on both sides, the local one newer. */
        NEWER_LOCAL,
        /** A file on both sides, the server's newer. */
        NEWER_REMOTE,
        /** The same age but another size, or a file on one side and a folder on the other. */
        DIFFERENT,
        /** The same: equally old files of the same size, or folders whose contents are the same. */
        SAME,
        /** A folder on both sides with something inside that differs. */
        CONTENT_DIFFERS
    }

    /** A file or folder below a compared folder. */
    public record Entry(String relativePath, boolean directory, long size, long modifiedMillis) {
        public Entry {
            relativePath = normalize(relativePath);
            size = directory ? -1 : Math.max(0, size);
        }

        /** The last segment of the path. */
        public String name() {
            int slash = relativePath.lastIndexOf('/');
            return slash < 0 ? relativePath : relativePath.substring(slash + 1);
        }

        /** The relative path of the folder this entry is in; empty for the compared folder itself. */
        public String parent() {
            return parentOf(relativePath);
        }
    }

    /** How many entries differ in each way; a folder only on one side counts once, with what is in it. */
    public record Counts(int onlyLocal, int onlyRemote, int newerLocal, int newerRemote, int different, int same) {
        public int differing() {
            return onlyLocal + onlyRemote + newerLocal + newerRemote + different;
        }
    }

    private final Map<String, Entry> local;
    private final Map<String, Entry> remote;
    private final Map<String, State> states;

    private FolderComparison(Map<String, Entry> local, Map<String, Entry> remote, Map<String, State> states) {
        this.local = local;
        this.remote = remote;
        this.states = states;
    }

    /** Compares the two sides. */
    public static FolderComparison compare(Collection<Entry> localEntries, Collection<Entry> remoteEntries) {
        Map<String, Entry> local = byPath(localEntries);
        Map<String, Entry> remote = byPath(remoteEntries);
        Set<String> paths = new TreeSet<>(local.keySet());
        paths.addAll(remote.keySet());
        Map<String, State> states = new TreeMap<>();
        for (String path : paths) {
            states.put(path, stateOf(local.get(path), remote.get(path)));
        }
        // A folder on both sides differs when anything below it does; deepest first, so the
        // answer climbs up through every level.
        List<String> deepestFirst = new ArrayList<>(paths);
        deepestFirst.sort((a, b) -> Integer.compare(depth(b), depth(a)));
        for (String path : deepestFirst) {
            State state = states.get(path);
            if (state != State.SAME && state != null) {
                String parent = parentOf(path);
                if (!parent.isEmpty() && states.get(parent) == State.SAME) {
                    states.put(parent, State.CONTENT_DIFFERS);
                }
            }
        }
        return new FolderComparison(local, remote, states);
    }

    private static State stateOf(Entry here, Entry there) {
        if (there == null) {
            return State.ONLY_LOCAL;
        }
        if (here == null) {
            return State.ONLY_REMOTE;
        }
        if (here.directory() != there.directory()) {
            return State.DIFFERENT;
        }
        if (here.directory()) {
            return State.SAME;
        }
        long difference = here.modifiedMillis() - there.modifiedMillis();
        if (Math.abs(difference) < TIME_TOLERANCE_MILLIS) {
            return here.size() == there.size() ? State.SAME : State.DIFFERENT;
        }
        return difference > 0 ? State.NEWER_LOCAL : State.NEWER_REMOTE;
    }

    /** How {@code relativePath} compares; empty for a path neither side has. */
    public Optional<State> state(String relativePath) {
        return Optional.ofNullable(states.get(normalize(relativePath)));
    }

    /** The left (local) side's entry at {@code relativePath}, if it has one. */
    public Optional<Entry> local(String relativePath) {
        return Optional.ofNullable(local.get(normalize(relativePath)));
    }

    /** The right (server) side's entry at {@code relativePath}, if it has one. */
    public Optional<Entry> remote(String relativePath) {
        return Optional.ofNullable(remote.get(normalize(relativePath)));
    }

    /** Every compared path with its state, sorted by path. */
    public Map<String, State> states() {
        return Collections.unmodifiableMap(states);
    }

    public Counts counts() {
        int onlyLocal = 0;
        int onlyRemote = 0;
        int newerLocal = 0;
        int newerRemote = 0;
        int different = 0;
        int same = 0;
        for (Map.Entry<String, State> entry : states.entrySet()) {
            State state = entry.getValue();
            switch (state) {
                case ONLY_LOCAL -> onlyLocal += topMost(entry.getKey(), state) ? 1 : 0;
                case ONLY_REMOTE -> onlyRemote += topMost(entry.getKey(), state) ? 1 : 0;
                case NEWER_LOCAL -> newerLocal++;
                case NEWER_REMOTE -> newerRemote++;
                case DIFFERENT -> different++;
                case SAME -> same += isFile(entry.getKey()) ? 1 : 0;
                case CONTENT_DIFFERS -> { }
            }
        }
        return new Counts(onlyLocal, onlyRemote, newerLocal, newerRemote, different, same);
    }

    /**
     * What "Synchronize to the server" uploads: local entries the server lacks (a missing folder as
     * a whole), local files that are newer, and files of the same age but another size. Newer
     * server files and file/folder conflicts are left alone.
     */
    public List<Entry> toUpload() {
        return toCopy(State.ONLY_LOCAL, State.NEWER_LOCAL, local);
    }

    /** What "Synchronize to this computer" downloads; the counterpart of {@link #toUpload()}. */
    public List<Entry> toDownload() {
        return toCopy(State.ONLY_REMOTE, State.NEWER_REMOTE, remote);
    }

    /** Server entries the local folder lacks, a missing folder as a whole: deleted on request by an upload sync. */
    public List<Entry> extraOnRemote() {
        return topMostOnly(State.ONLY_REMOTE, remote);
    }

    /** Local entries the server folder lacks: deleted on request by a download sync. */
    public List<Entry> extraOnLocal() {
        return topMostOnly(State.ONLY_LOCAL, local);
    }

    /** Files an upload sync leaves alone because the server's copy is newer. */
    public int newerOnRemote() {
        return count(State.NEWER_REMOTE);
    }

    /** Files a download sync leaves alone because the local copy is newer. */
    public int newerOnLocal() {
        return count(State.NEWER_LOCAL);
    }

    /** Paths that are a file on one side and a folder on the other; never copied. */
    public int typeConflicts() {
        int conflicts = 0;
        for (Map.Entry<String, State> entry : states.entrySet()) {
            if (entry.getValue() == State.DIFFERENT && isTypeConflict(entry.getKey())) {
                conflicts++;
            }
        }
        return conflicts;
    }

    /**
     * The bytes copying {@code entries} moves: their sizes, a folder with everything below it on
     * the side it comes from ({@code fromLocal}).
     */
    public long bytes(List<Entry> entries, boolean fromLocal) {
        Map<String, Entry> side = fromLocal ? local : remote;
        long total = 0;
        for (Entry entry : entries) {
            if (!entry.directory()) {
                total += entry.size();
                continue;
            }
            String prefix = entry.relativePath() + "/";
            for (Entry below : side.values()) {
                if (!below.directory() && below.relativePath().startsWith(prefix)) {
                    total += below.size();
                }
            }
        }
        return total;
    }

    private List<Entry> toCopy(State only, State newer, Map<String, Entry> source) {
        List<Entry> copy = new ArrayList<>();
        for (Map.Entry<String, State> entry : states.entrySet()) {
            String path = entry.getKey();
            State state = entry.getValue();
            boolean wanted = (state == only && topMost(path, only))
                || state == newer
                || (state == State.DIFFERENT && !isTypeConflict(path));
            if (wanted && !underTypeConflict(path)) {
                copy.add(source.get(path));
            }
        }
        return copy;
    }

    private List<Entry> topMostOnly(State only, Map<String, Entry> side) {
        List<Entry> extra = new ArrayList<>();
        for (Map.Entry<String, State> entry : states.entrySet()) {
            if (entry.getValue() == only && topMost(entry.getKey(), only) && !underTypeConflict(entry.getKey())) {
                extra.add(side.get(entry.getKey()));
            }
        }
        return extra;
    }

    private int count(State wanted) {
        int count = 0;
        for (State state : states.values()) {
            if (state == wanted) {
                count++;
            }
        }
        return count;
    }

    /** Whether no folder above {@code path} has the same one-sided state (it is copied as part of that). */
    private boolean topMost(String path, State state) {
        String parent = parentOf(path);
        return parent.isEmpty() || states.get(parent) != state;
    }

    private boolean isTypeConflict(String path) {
        Entry here = local.get(path);
        Entry there = remote.get(path);
        return here != null && there != null && here.directory() != there.directory();
    }

    /** Whether a folder above {@code path} is a file on the other side: nothing below it can be copied. */
    private boolean underTypeConflict(String path) {
        for (String parent = parentOf(path); !parent.isEmpty(); parent = parentOf(parent)) {
            if (isTypeConflict(parent)) {
                return true;
            }
        }
        return false;
    }

    private boolean isFile(String path) {
        Entry entry = local.containsKey(path) ? local.get(path) : remote.get(path);
        return entry != null && !entry.directory();
    }

    private static Map<String, Entry> byPath(Collection<Entry> entries) {
        Map<String, Entry> map = new TreeMap<>();
        for (Entry entry : entries) {
            if (!entry.relativePath().isEmpty()) {
                map.put(entry.relativePath(), entry);
            }
        }
        return map;
    }

    private static int depth(String path) {
        int depth = 0;
        for (int i = 0; i < path.length(); i++) {
            if (path.charAt(i) == '/') {
                depth++;
            }
        }
        return depth;
    }

    static String parentOf(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    static String normalize(String path) {
        String value = path == null ? "" : path.replace('\\', '/');
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
