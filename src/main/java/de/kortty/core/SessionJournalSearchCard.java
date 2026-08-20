package de.kortty.core;

import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMeta;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * The searchable digest of one journal: its meta line plus one section per curated entry —
 * built exclusively from {@code journal.xml}, never from the capture log. Cards feed the
 * cross-journal search's BM25 prefilter and, for the top-ranked journals, the AI prompt.
 */
public record SessionJournalSearchCard(
    String journalId,
    long documentMtimeMillis,
    String metaText,
    List<Section> sections) {

    static final int MAX_SECTION_CHARS = 600;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    /** One curated entry, carrying its deep-link anchors (entry id and covered log range). */
    public record Section(
        String entryId,
        SessionJournalEntryKind kind,
        String title,
        String text,
        List<String> tags,
        Integer logPart,
        Long logStartSeq,
        Long logEndSeq) {

        /** The section as one searchable line. */
        public String searchText() {
            StringBuilder sb = new StringBuilder(128);
            if (title != null) {
                sb.append(title).append(' ');
            }
            if (text != null) {
                sb.append(text).append(' ');
            }
            if (tags != null && !tags.isEmpty()) {
                sb.append(String.join(" ", tags));
            }
            return sb.toString().strip();
        }
    }

    public SessionJournalSearchCard {
        sections = sections == null ? List.of() : List.copyOf(sections);
    }

    /** Everything on the card as one searchable text — the BM25 document body. */
    public String searchText() {
        StringBuilder sb = new StringBuilder(1024);
        sb.append(metaText != null ? metaText : "");
        for (Section section : sections) {
            sb.append('\n').append(section.searchText());
        }
        return sb.toString();
    }

    /** Builds the card from a loaded document; pure, so it is unit-testable without I/O. */
    public static SessionJournalSearchCard build(SessionJournalMeta meta,
                                                 List<SessionJournalEntry> entries,
                                                 String journalId,
                                                 long documentMtimeMillis) {
        StringBuilder metaText = new StringBuilder(160);
        appendWord(metaText, meta.getTitle());
        appendWord(metaText, meta.getConnectionName());
        appendWord(metaText, meta.getHost());
        appendWord(metaText, meta.getUsername());
        appendWord(metaText, meta.getDescription());
        if (meta.getStartedAt() != null) {
            appendWord(metaText, meta.getStartedAt().format(DATE));
        }
        if (!meta.getAiKeywords().isEmpty()) {
            appendWord(metaText, String.join(" ", meta.getAiKeywords()));
        }
        List<Section> sections = new ArrayList<>();
        if (entries != null) {
            for (SessionJournalEntry entry : entries) {
                StringBuilder text = new StringBuilder(MAX_SECTION_CHARS + 32);
                appendPart(text, entry.getText());
                appendPart(text, entry.getAiDescription());
                appendPart(text, entry.getUserNote());
                String flat = flatten(text.toString(), MAX_SECTION_CHARS);
                sections.add(new Section(
                    entry.getId(),
                    entry.getKind(),
                    flatten(entry.getTitle(), 120),
                    flat,
                    entry.getAiTags() != null ? List.copyOf(entry.getAiTags()) : List.of(),
                    entry.getLogPart(),
                    entry.getLogStartSeq(),
                    entry.getLogEndSeq()));
            }
        }
        return new SessionJournalSearchCard(journalId, documentMtimeMillis,
            metaText.toString().strip(), sections);
    }

    private static void appendWord(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(value.strip());
        }
    }

    private static void appendPart(StringBuilder sb, String value) {
        if (value != null && !value.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" | ");
            }
            sb.append(value.strip());
        }
    }

    private static String flatten(String value, int maxChars) {
        if (value == null) {
            return null;
        }
        String flat = value.replace('\n', ' ').replace('\r', ' ').strip();
        return flat.length() > maxChars ? flat.substring(0, maxChars) + "…" : flat;
    }
}
