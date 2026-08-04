package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMarkerDefinition;

import java.awt.Color;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Resolves a journal entry to the marker definition it should be shown with, and keeps the
 * document-level snapshot of user-defined markers in sync.
 *
 * <p>Resolution deliberately never consults the global settings: a journal that was exported or
 * handed to someone else must render exactly as it did when it was shared, so the definitions it
 * uses travel inside {@code journal.xml}. Only the four built-ins are resolved from code, and only
 * because they are guaranteed to exist in every korTTY.</p>
 */
public final class SessionJournalMarkers {

    /** Colours are {@code null} on purpose: the renderer carries a light and a dark value each. */
    private static final List<SessionJournalMarkerDefinition> BUILT_INS = List.of(
        new SessionJournalMarkerDefinition(
            SessionJournalMarkerDefinition.ID_NONE, null, null, true, SessionJournalMarker.NONE),
        new SessionJournalMarkerDefinition(
            SessionJournalMarkerDefinition.ID_INFO, null, null, true, SessionJournalMarker.INFO),
        new SessionJournalMarkerDefinition(
            SessionJournalMarkerDefinition.ID_IMPORTANT, null, null, true, SessionJournalMarker.IMPORTANT),
        new SessionJournalMarkerDefinition(
            SessionJournalMarkerDefinition.ID_ERROR, null, null, true, SessionJournalMarker.ERROR));

    private SessionJournalMarkers() {
    }

    /** Fresh copies of the four built-in definitions, in menu order. */
    public static List<SessionJournalMarkerDefinition> builtIns() {
        List<SessionJournalMarkerDefinition> copies = new ArrayList<>(BUILT_INS.size());
        for (SessionJournalMarkerDefinition definition : BUILT_INS) {
            copies.add(new SessionJournalMarkerDefinition(definition));
        }
        return copies;
    }

    /** The built-in definition for a legacy enum value; never {@code null}. */
    public static SessionJournalMarkerDefinition builtIn(SessionJournalMarker legacy) {
        String id = idOf(legacy != null ? legacy : SessionJournalMarker.NONE);
        for (SessionJournalMarkerDefinition definition : BUILT_INS) {
            if (definition.getId().equals(id)) {
                return new SessionJournalMarkerDefinition(definition);
            }
        }
        return new SessionJournalMarkerDefinition(BUILT_INS.get(0));
    }

    /** The built-in id matching a legacy enum value ("none", "info", "important", "error"). */
    public static String idOf(SessionJournalMarker legacy) {
        return (legacy != null ? legacy : SessionJournalMarker.NONE).name().toLowerCase(Locale.ROOT);
    }

    /** True for one of the four reserved built-in ids. */
    public static boolean isBuiltInId(String id) {
        for (SessionJournalMarkerDefinition definition : BUILT_INS) {
            if (definition.getId().equals(id)) {
                return true;
            }
        }
        return false;
    }

    public static SessionJournalMarkerDefinition byId(String id, List<SessionJournalMarkerDefinition> pool) {
        String normalized = SessionJournalMarkerDefinition.normalizeId(id);
        if (normalized == null || pool == null) {
            return null;
        }
        for (SessionJournalMarkerDefinition definition : pool) {
            if (definition != null && normalized.equals(definition.getId())) {
                return definition;
            }
        }
        return null;
    }

    /**
     * The definition an entry should be rendered with. Falls back through the document snapshot
     * and the built-ins to a synthetic definition derived from the legacy enum, so this never
     * returns {@code null} even for a journal whose marker definition went missing.
     */
    public static SessionJournalMarkerDefinition resolve(SessionJournalEntry entry,
                                                         SessionJournalDocument document) {
        if (entry == null) {
            return builtIn(SessionJournalMarker.NONE);
        }
        String markerId = SessionJournalMarkerDefinition.normalizeId(entry.getMarkerId());
        if (markerId != null) {
            SessionJournalMarkerDefinition snapshot = document != null
                ? byId(markerId, document.getMarkerDefinitions()) : null;
            if (snapshot != null) {
                return snapshot;
            }
            if (isBuiltInId(markerId)) {
                return byId(markerId, BUILT_INS);
            }
            // The id survived but its definition did not: keep the name visible rather than
            // silently degrading the entry to an unmarked one.
            SessionJournalMarkerDefinition orphan = builtIn(entry.getMarker());
            orphan.setId(markerId);
            orphan.setName(markerId);
            orphan.setBuiltIn(false);
            orphan.setLegacyMarker(entry.getMarker());
            return orphan;
        }
        return builtIn(entry.getMarker());
    }

    /**
     * Applies a definition to an entry, writing both the id and the legacy enum value. Writing
     * both is what keeps an older korTTY — and {@code SessionJournalService.refreshErrorCount} —
     * working on a journal that uses custom markers.
     */
    public static void apply(SessionJournalEntry entry, SessionJournalMarkerDefinition definition) {
        if (entry == null) {
            return;
        }
        SessionJournalMarkerDefinition effective = definition != null
            ? definition : builtIn(SessionJournalMarker.NONE);
        entry.setMarkerId(effective.isBuiltIn() ? null : effective.getId());
        entry.setMarker(effective.isBuiltIn() ? legacyOf(effective) : effective.getLegacyMarker());
    }

    private static SessionJournalMarker legacyOf(SessionJournalMarkerDefinition builtIn) {
        for (SessionJournalMarker marker : SessionJournalMarker.values()) {
            if (idOf(marker).equals(builtIn.getId())) {
                return marker;
            }
        }
        return builtIn.getLegacyMarker();
    }

    /**
     * Copies a user-defined definition into the document so the journal renders standalone.
     * Built-ins are never snapshotted — that is what keeps a legacy {@code journal.xml} byte
     * identical after a save. Returns true when the document changed.
     */
    public static boolean snapshot(SessionJournalDocument document, SessionJournalMarkerDefinition definition) {
        if (document == null || definition == null || definition.isBuiltIn() || definition.getId() == null) {
            return false;
        }
        List<SessionJournalMarkerDefinition> snapshots = document.getMarkerDefinitions();
        for (int i = 0; i < snapshots.size(); i++) {
            SessionJournalMarkerDefinition existing = snapshots.get(i);
            if (existing != null && definition.getId().equals(existing.getId())) {
                if (equalContent(existing, definition)) {
                    return false;
                }
                snapshots.set(i, new SessionJournalMarkerDefinition(definition));
                return true;
            }
        }
        snapshots.add(new SessionJournalMarkerDefinition(definition));
        return true;
    }

    private static boolean equalContent(SessionJournalMarkerDefinition a, SessionJournalMarkerDefinition b) {
        return java.util.Objects.equals(a.getName(), b.getName())
            && java.util.Objects.equals(a.getColor(), b.getColor())
            && a.getLegacyMarker() == b.getLegacyMarker();
    }

    /** Drops snapshots no entry references any more; returns how many were removed. */
    public static int pruneUnused(SessionJournalDocument document) {
        if (document == null || document.getMarkerDefinitions().isEmpty()) {
            return 0;
        }
        Set<String> used = new LinkedHashSet<>();
        for (SessionJournalEntry entry : document.getEntries()) {
            String id = entry != null
                ? SessionJournalMarkerDefinition.normalizeId(entry.getMarkerId()) : null;
            if (id != null) {
                used.add(id);
            }
        }
        List<SessionJournalMarkerDefinition> snapshots = document.getMarkerDefinitions();
        int before = snapshots.size();
        snapshots.removeIf(definition -> definition == null || !used.contains(definition.getId()));
        return before - snapshots.size();
    }

    /**
     * Built-ins followed by the user's own definitions. A custom definition that squats a
     * built-in id is dropped: the ids are reserved so legacy journals keep resolving.
     */
    public static List<SessionJournalMarkerDefinition> registry(GlobalSettings settings) {
        List<SessionJournalMarkerDefinition> result = builtIns();
        if (settings == null) {
            return result;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (SessionJournalMarkerDefinition definition : result) {
            seen.add(definition.getId());
        }
        for (SessionJournalMarkerDefinition custom : settings.getSessionJournalMarkers()) {
            if (custom == null || custom.getId() == null || !seen.add(custom.getId())) {
                continue;
            }
            SessionJournalMarkerDefinition copy = new SessionJournalMarkerDefinition(custom);
            copy.setBuiltIn(false);
            result.add(copy);
        }
        return result;
    }

    /**
     * The label to show. Built-ins are translated through {@code journal.marker.<id>}; user
     * definitions carry their own name and are never translated.
     */
    public static String displayName(SessionJournalMarkerDefinition definition) {
        if (definition == null) {
            return "";
        }
        if (definition.getName() != null && !definition.getName().isBlank()) {
            return definition.getName();
        }
        String key = "journal.marker." + definition.getId();
        String translated = de.kortty.ui.I18n.get(key);
        return translated != null && !translated.equals(key) ? translated : definition.getId();
    }

    /**
     * The definition's colour for AWT-based output (the PDF export), or {@code fallback} when it
     * has none — a built-in without an explicit colour keeps the exporter's own palette.
     */
    public static Color awtColor(SessionJournalMarkerDefinition definition, Color fallback) {
        String hex = definition != null ? definition.getColor() : null;
        if (hex == null || hex.isBlank()) {
            return fallback;
        }
        try {
            return Color.decode(hex.startsWith("#") ? hex : "#" + hex);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** Convenience for {@link SessionJournalMarkerDefinition#normalizeId(String)}. */
    public static String normalizeId(String raw) {
        return SessionJournalMarkerDefinition.normalizeId(raw);
    }
}
