package de.kortty.memory.external;

/**
 * Where a reference memory file stands. Only {@link #OK} files are sent; the others say why not.
 */
public enum ExternalMemoryStatus {
    /** Approved, present, unchanged and free of secrets. */
    OK("ok"),
    /** The text differs from the approved text, or the file grew past the limit: not sent until approved again. */
    CHANGED("changed"),
    /** The file does not exist (any more). */
    MISSING("missing"),
    /** The secret guard found a secret; the entry was switched off. */
    REFUSED_SECRET("refused-secret"),
    /** The file lies outside its allowed folders, is a link out of them, is on a network drive or the policy forbids it. */
    REFUSED_PATH("refused-path");

    private final String wireName;

    ExternalMemoryStatus(String wireName) {
        this.wireName = wireName;
    }

    /** The name stored in {@code external.json}. */
    public String wireName() {
        return wireName;
    }

    /** The status with the stored name, or {@link #MISSING} for an unknown name (the next check decides again). */
    public static ExternalMemoryStatus fromWireName(String name) {
        for (ExternalMemoryStatus status : values()) {
            if (status.wireName.equals(name)) {
                return status;
            }
        }
        return MISSING;
    }
}
