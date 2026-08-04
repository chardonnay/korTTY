package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlAttribute;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The curated session journal document (journal.xml): small, mutable, rewritten atomically on
 * every change — unlike the append-only capture log next to it.
 */
@XmlRootElement(name = "session-journal")
@XmlAccessorType(XmlAccessType.FIELD)
public class SessionJournalDocument {

    public static final int CURRENT_FORMAT_VERSION = 1;

    @XmlAttribute(name = "formatVersion")
    private int formatVersion = CURRENT_FORMAT_VERSION;

    @XmlAttribute(name = "id")
    private String id;

    @XmlElement(name = "meta")
    private SessionJournalMeta meta = new SessionJournalMeta();

    @XmlElementWrapper(name = "entries")
    @XmlElement(name = "entry")
    private List<SessionJournalEntry> entries = new ArrayList<>();

    /**
     * Snapshot of the user-defined markers this journal actually uses. Built-ins are never stored
     * here, so a document written before custom markers existed stays byte identical. The
     * snapshot is what lets an exported or shared journal render exactly as it did when it was
     * shared, independent of the settings that produced it.
     */
    @XmlElementWrapper(name = "markerDefinitions")
    @XmlElement(name = "marker")
    private List<SessionJournalMarkerDefinition> markerDefinitions = new ArrayList<>();

    public SessionJournalDocument() {
        this.id = UUID.randomUUID().toString();
    }

    public SessionJournalDocument(SessionJournalDocument other) {
        this.formatVersion = other.formatVersion;
        this.id = other.id;
        this.meta = other.meta != null ? new SessionJournalMeta(other.meta) : new SessionJournalMeta();
        this.entries = new ArrayList<>();
        if (other.entries != null) {
            for (SessionJournalEntry entry : other.entries) {
                this.entries.add(new SessionJournalEntry(entry));
            }
        }
        this.markerDefinitions = new ArrayList<>();
        if (other.markerDefinitions != null) {
            for (SessionJournalMarkerDefinition definition : other.markerDefinitions) {
                this.markerDefinitions.add(new SessionJournalMarkerDefinition(definition));
            }
        }
    }

    public int getFormatVersion() {
        return formatVersion;
    }

    public void setFormatVersion(int formatVersion) {
        this.formatVersion = formatVersion;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public SessionJournalMeta getMeta() {
        if (meta == null) {
            meta = new SessionJournalMeta();
        }
        return meta;
    }

    public void setMeta(SessionJournalMeta meta) {
        this.meta = meta;
    }

    public List<SessionJournalEntry> getEntries() {
        if (entries == null) {
            entries = new ArrayList<>();
        }
        return entries;
    }

    public void setEntries(List<SessionJournalEntry> entries) {
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
    }

    /** Live list; {@code SessionJournalMarkers} adds to it when a custom marker is applied. */
    public List<SessionJournalMarkerDefinition> getMarkerDefinitions() {
        if (markerDefinitions == null) {
            markerDefinitions = new ArrayList<>();
        }
        return markerDefinitions;
    }

    public void setMarkerDefinitions(List<SessionJournalMarkerDefinition> markerDefinitions) {
        this.markerDefinitions = markerDefinitions != null
            ? new ArrayList<>(markerDefinitions) : new ArrayList<>();
    }

    /**
     * Drops the empty snapshot list before marshalling. JAXB writes an {@code @XmlElementWrapper}
     * even for an empty collection (see the {@code <inputExcerpt/>} elements on every entry), and
     * a journal that uses no custom markers must not grow a stray element just because it was
     * opened once. The getter recreates the list on demand.
     */
    @SuppressWarnings("unused")
    private void beforeMarshal(jakarta.xml.bind.Marshaller marshaller) {
        if (markerDefinitions != null && markerDefinitions.isEmpty()) {
            markerDefinitions = null;
        }
    }
}
