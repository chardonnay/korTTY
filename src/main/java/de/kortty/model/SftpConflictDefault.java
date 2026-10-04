package de.kortty.model;

import java.util.Locale;

/**
 * What the SFTP manager does when a transfer target already exists and the user has not answered
 * for this batch yet. Settings › SFTP Manager › "When the target already exists"; an organization
 * can set and lock it with {@code [rule.sftp] conflict-default}.
 *
 * <p>The {@link #id()} is what {@code global-settings.xml} and the policy file store; it must stay
 * stable across releases.
 */
public enum SftpConflictDefault {

    /** Ask once per batch, with "apply to all". */
    ASK("ask"),

    /** Leave the existing target alone and skip the item. */
    SKIP("skip"),

    /** Replace the existing target (folders are merged). */
    OVERWRITE("overwrite");

    /** The value a missing, empty or unknown stored value means. */
    public static final SftpConflictDefault DEFAULT = ASK;

    private final String id;

    SftpConflictDefault(String id) {
        this.id = id;
    }

    /** The stable stored form: {@code ask}, {@code skip} or {@code overwrite}. */
    public String id() {
        return id;
    }

    /**
     * The value with this id, or {@link #DEFAULT} when it is missing or unknown, so a damaged file
     * never makes transfers replace files without asking.
     */
    public static SftpConflictDefault fromId(String id) {
        SftpConflictDefault value = parseId(id);
        return value != null ? value : DEFAULT;
    }

    /**
     * The value with this id, compared without regard to case or surrounding blanks.
     *
     * @return the value, or null for null, blank or unknown input
     */
    public static SftpConflictDefault parseId(String id) {
        if (id == null) {
            return null;
        }
        String value = id.trim().toLowerCase(Locale.ROOT);
        for (SftpConflictDefault candidate : values()) {
            if (candidate.id.equals(value)) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * The value that loses less data: {@link #ASK} before {@link #SKIP} before {@link #OVERWRITE};
     * null stands for "no opinion". How an organization's policy merges two rules.
     */
    public static SftpConflictDefault mostRestrictive(SftpConflictDefault a, SftpConflictDefault b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
