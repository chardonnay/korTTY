package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

import java.util.Locale;

/**
 * A named, coloured marker a journal entry can carry. The four built-in definitions mirror
 * {@link SessionJournalMarker} so existing journals keep rendering unchanged; users may add
 * their own ("Software installation") and recolour any of them.
 *
 * <p>Definitions live in the global settings, and every non-built-in definition that is actually
 * applied is also snapshotted into the journal document — a shared or exported journal must
 * render correctly without the settings that produced it.</p>
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SessionJournalMarkerDefinition")
public class SessionJournalMarkerDefinition {

    public static final String ID_NONE = "none";
    public static final String ID_INFO = "info";
    public static final String ID_IMPORTANT = "important";
    public static final String ID_ERROR = "error";

    /** Longest id we store; ids are normalized to {@code [a-z0-9-]} in {@link #setId(String)}. */
    public static final int MAX_ID_LENGTH = 32;

    @XmlElement
    private String id;

    /** User-facing label; {@code null} on built-ins, which are translated via their id. */
    @XmlElement
    private String name;

    /**
     * {@code #rrggbb}, or {@code null} to use the renderer's own palette. Built-ins default to
     * {@code null} because the HTML page carries a light and a dark value for each of them that a
     * single hex cannot express; setting a colour on a built-in overrides both.
     */
    @XmlElement
    private String color;

    @XmlElement
    private boolean builtIn;

    /**
     * The legacy enum value this definition degrades to. Written alongside {@code markerId} on
     * every entry so an older korTTY — and {@code refreshErrorCount} — keep working.
     */
    @XmlElement
    private SessionJournalMarker legacyMarker = SessionJournalMarker.INFO;

    public SessionJournalMarkerDefinition() {
    }

    public SessionJournalMarkerDefinition(String id, String name, String color, boolean builtIn,
                                          SessionJournalMarker legacyMarker) {
        setId(id);
        setName(name);
        setColor(color);
        this.builtIn = builtIn;
        setLegacyMarker(legacyMarker);
    }

    public SessionJournalMarkerDefinition(SessionJournalMarkerDefinition other) {
        if (other != null) {
            this.id = other.id;
            this.name = other.name;
            this.color = other.color;
            this.builtIn = other.builtIn;
            this.legacyMarker = other.legacyMarker;
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = normalizeId(id);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        String trimmed = name != null ? name.trim() : "";
        this.name = trimmed.isEmpty() ? null : trimmed;
    }

    public String getColor() {
        return color;
    }

    public void setColor(String color) {
        String trimmed = color != null ? color.trim() : "";
        this.color = trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isBuiltIn() {
        return builtIn;
    }

    public void setBuiltIn(boolean builtIn) {
        this.builtIn = builtIn;
    }

    public SessionJournalMarker getLegacyMarker() {
        return legacyMarker != null ? legacyMarker : SessionJournalMarker.INFO;
    }

    public void setLegacyMarker(SessionJournalMarker legacyMarker) {
        this.legacyMarker = legacyMarker != null ? legacyMarker : SessionJournalMarker.INFO;
    }

    /** True for the "no marker" definition, which is a real entry so combo boxes can offer it. */
    public boolean isNone() {
        return ID_NONE.equals(id);
    }

    /**
     * Lowercases, replaces anything outside {@code [a-z0-9-]} with {@code -}, collapses and trims
     * the dashes and caps the length. Returns {@code null} when nothing usable remains — ids end
     * up in CSS attribute selectors, so they must be predictable.
     */
    public static String normalizeId(String raw) {
        if (raw == null) {
            return null;
        }
        String lower = raw.trim().toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lower.length());
        boolean lastDash = false;
        for (int i = 0; i < lower.length() && sb.length() < MAX_ID_LENGTH; i++) {
            char c = lower.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                sb.append(c);
                lastDash = false;
            } else if (!lastDash && sb.length() > 0) {
                sb.append('-');
                lastDash = true;
            }
        }
        while (sb.length() > 0 && sb.charAt(sb.length() - 1) == '-') {
            sb.setLength(sb.length() - 1);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    @Override
    public String toString() {
        return "SessionJournalMarkerDefinition[" + id + (builtIn ? ", builtIn" : "") + "]";
    }
}
