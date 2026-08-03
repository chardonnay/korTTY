package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlRootElement;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * One curated entry of a session journal document (journal.xml): an AI summary, the closing
 * session summary, a screenshot, a user note, or a system notice. Entries may reference the
 * capture-log range they cover via {@code logPart}/{@code logStartSeq}/{@code logEndSeq}, which
 * is the deep-link anchor the HTML timeline uses.
 */
@XmlRootElement(name = "entry")
@XmlAccessorType(XmlAccessType.FIELD)
public class SessionJournalEntry {

    /** Who set the current marker; USER edits are never overwritten by AI regeneration. */
    @XmlEnum
    public enum MarkerSource { AI, USER }

    /** Production state of an AI entry; RAW entries exist when AI summaries are unavailable. */
    @XmlEnum
    public enum State { SUMMARIZED, FAILED, RAW }

    @XmlElement
    private String id;

    @XmlElement
    private SessionJournalEntryKind kind = SessionJournalEntryKind.AI_SUMMARY;

    @XmlElement
    private SessionJournalMarker marker = SessionJournalMarker.NONE;

    @XmlElement
    private MarkerSource markerSource = MarkerSource.AI;

    @XmlElement
    private State state = State.SUMMARIZED;

    @XmlElement
    @XmlJavaTypeAdapter(IsoOffsetDateTimeAdapter.class)
    private OffsetDateTime createdAt;

    @XmlElement
    @XmlJavaTypeAdapter(IsoOffsetDateTimeAdapter.class)
    private OffsetDateTime editedAt;

    @XmlElement
    private String title;

    @XmlElement
    private String text;

    @XmlElement
    private String userNote;

    /** Journal-directory-relative path (screenshots/shot-000009.png); only for SCREENSHOT entries. */
    @XmlElement
    private String screenshotFile;

    @XmlElement
    private Integer logPart;

    @XmlElement
    private Long logStartSeq;

    @XmlElement
    private Long logEndSeq;

    /** Short redacted input preview (max ~5 lines) so exports never re-read the capture log. */
    @XmlElementWrapper(name = "inputExcerpt")
    @XmlElement(name = "line")
    private List<String> inputExcerpt = new ArrayList<>();

    @XmlElementWrapper(name = "outputExcerpt")
    @XmlElement(name = "line")
    private List<String> outputExcerpt = new ArrayList<>();

    public SessionJournalEntry() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = OffsetDateTime.now();
    }

    public SessionJournalEntry(SessionJournalEntry other) {
        this.id = other.id;
        this.kind = other.kind;
        this.marker = other.marker;
        this.markerSource = other.markerSource;
        this.state = other.state;
        this.createdAt = other.createdAt;
        this.editedAt = other.editedAt;
        this.title = other.title;
        this.text = other.text;
        this.userNote = other.userNote;
        this.screenshotFile = other.screenshotFile;
        this.logPart = other.logPart;
        this.logStartSeq = other.logStartSeq;
        this.logEndSeq = other.logEndSeq;
        this.inputExcerpt = new ArrayList<>(other.inputExcerpt != null ? other.inputExcerpt : List.of());
        this.outputExcerpt = new ArrayList<>(other.outputExcerpt != null ? other.outputExcerpt : List.of());
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public SessionJournalEntryKind getKind() {
        return kind != null ? kind : SessionJournalEntryKind.AI_SUMMARY;
    }

    public void setKind(SessionJournalEntryKind kind) {
        this.kind = kind;
    }

    public SessionJournalMarker getMarker() {
        return marker != null ? marker : SessionJournalMarker.NONE;
    }

    public void setMarker(SessionJournalMarker marker) {
        this.marker = marker;
    }

    public MarkerSource getMarkerSource() {
        return markerSource != null ? markerSource : MarkerSource.AI;
    }

    public void setMarkerSource(MarkerSource markerSource) {
        this.markerSource = markerSource;
    }

    public State getState() {
        return state != null ? state : State.SUMMARIZED;
    }

    public void setState(State state) {
        this.state = state;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(OffsetDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public OffsetDateTime getEditedAt() {
        return editedAt;
    }

    public void setEditedAt(OffsetDateTime editedAt) {
        this.editedAt = editedAt;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String getUserNote() {
        return userNote;
    }

    public void setUserNote(String userNote) {
        this.userNote = userNote;
    }

    public String getScreenshotFile() {
        return screenshotFile;
    }

    public void setScreenshotFile(String screenshotFile) {
        this.screenshotFile = screenshotFile;
    }

    public Integer getLogPart() {
        return logPart;
    }

    public void setLogPart(Integer logPart) {
        this.logPart = logPart;
    }

    public Long getLogStartSeq() {
        return logStartSeq;
    }

    public void setLogStartSeq(Long logStartSeq) {
        this.logStartSeq = logStartSeq;
    }

    public Long getLogEndSeq() {
        return logEndSeq;
    }

    public void setLogEndSeq(Long logEndSeq) {
        this.logEndSeq = logEndSeq;
    }

    public List<String> getInputExcerpt() {
        if (inputExcerpt == null) {
            inputExcerpt = new ArrayList<>();
        }
        return inputExcerpt;
    }

    public void setInputExcerpt(List<String> inputExcerpt) {
        this.inputExcerpt = inputExcerpt != null ? new ArrayList<>(inputExcerpt) : new ArrayList<>();
    }

    public List<String> getOutputExcerpt() {
        if (outputExcerpt == null) {
            outputExcerpt = new ArrayList<>();
        }
        return outputExcerpt;
    }

    public void setOutputExcerpt(List<String> outputExcerpt) {
        this.outputExcerpt = outputExcerpt != null ? new ArrayList<>(outputExcerpt) : new ArrayList<>();
    }
}
