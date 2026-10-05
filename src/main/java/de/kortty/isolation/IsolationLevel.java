package de.kortty.isolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * How far a terminal session is kept apart from korTTY and from every other session, as chosen for a
 * connection, a folder or in Settings. Ordered from least to most isolated, so {@link #ordinal()}
 * compares strictness. What a running session actually got is its {@link IsolationState}.
 */
public enum IsolationLevel {

    /** The session runs as before: SSH inside korTTY's own process, a local shell with the user's full rights. */
    NONE("none"),

    /** The session runs in a process of its own that sees only what this one connection needs. */
    PROCESS("process"),

    /** {@link #PROCESS}, and that process is confined by the operating system's sandbox. */
    SANDBOX("sandbox");

    /** What a fresh installation uses: no isolation, so nothing changes until someone opts in. */
    public static final IsolationLevel DEFAULT = NONE;

    private final String id;

    IsolationLevel(String id) {
        this.id = id;
    }

    /** The value stored in the settings and connection files and used by the policy file. */
    public String id() {
        return id;
    }

    /** The level stored as {@code id}, ignoring case and blanks around it; null for null or an unknown id. */
    public static IsolationLevel parseId(String id) {
        if (id == null) {
            return null;
        }
        String value = id.trim().toLowerCase(Locale.ROOT);
        for (IsolationLevel level : values()) {
            if (level.id.equals(value)) {
                return level;
            }
        }
        return null;
    }

    /** {@link #parseId}, or {@link #DEFAULT} for null or an unknown id. */
    public static IsolationLevel fromId(String id) {
        IsolationLevel level = parseId(id);
        return level != null ? level : DEFAULT;
    }

    /** The stricter of the two; null only when both are null. */
    public static IsolationLevel mostRestrictive(IsolationLevel a, IsolationLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }

    /** Whether this level is at least as strict as {@code other}; anything is when {@code other} is null. */
    public boolean atLeast(IsolationLevel other) {
        return other == null || ordinal() >= other.ordinal();
    }

    /** Every level a user may still choose under {@code floor}, least strict first; all of them for null. */
    public static List<IsolationLevel> atLeastLevels(IsolationLevel floor) {
        List<IsolationLevel> levels = new ArrayList<>();
        for (IsolationLevel level : values()) {
            if (level.atLeast(floor)) {
                levels.add(level);
            }
        }
        return List.copyOf(levels);
    }
}
