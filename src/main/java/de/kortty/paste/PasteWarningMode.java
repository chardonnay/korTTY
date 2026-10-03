package de.kortty.paste;

import java.util.Locale;

/**
 * When a paste that holds a line break asks for confirmation. Control characters ask in every mode
 * except {@link #OFF}, whatever the program in the pane says about bracketed paste.
 *
 * <p>Ordered from the least to the most restrictive, so a higher ordinal never asks less. The
 * {@link #id()} is what the settings file (and, later, the per-connection override and the
 * enterprise policy) store; it must stay stable across releases.
 */
public enum PasteWarningMode {

    /** Never ask about line breaks or control characters. */
    OFF("off"),

    /**
     * Ask about line breaks only when the program in the pane has not enabled bracketed paste: a
     * program that brackets pastes receives the text as one block instead of as pressed Enter keys.
     */
    UNLESS_BRACKETED("unless-bracketed"),

    /** Ask about every paste that holds a line break, bracketed or not. */
    ALWAYS("always");

    /** The mode a missing, empty or unknown stored value means. */
    public static final PasteWarningMode DEFAULT = UNLESS_BRACKETED;

    private final String id;

    PasteWarningMode(String id) {
        this.id = id;
    }

    /** The stable stored form: {@code off}, {@code unless-bracketed} or {@code always}. */
    public String id() {
        return id;
    }

    /**
     * The mode with this id, compared without regard to case or surrounding blanks.
     *
     * @return the mode, or null for null, blank or unknown input
     */
    public static PasteWarningMode parseId(String id) {
        if (id == null) {
            return null;
        }
        String value = id.trim().toLowerCase(Locale.ROOT);
        for (PasteWarningMode mode : values()) {
            if (mode.id.equals(value)) {
                return mode;
            }
        }
        return null;
    }

    /**
     * The mode with this id, or {@link #DEFAULT} when it is missing or unknown (a value written by a
     * newer korTTY, or a hand-edited file), so a bad value never switches protection off.
     */
    public static PasteWarningMode fromId(String id) {
        PasteWarningMode mode = parseId(id);
        return mode != null ? mode : DEFAULT;
    }

    /** The mode that asks more often of the two; null stands for "no opinion". */
    public static PasteWarningMode mostRestrictive(PasteWarningMode a, PasteWarningMode b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
