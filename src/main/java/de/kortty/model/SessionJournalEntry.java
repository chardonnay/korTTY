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

    /**
     * Who set the current marker. Precedence is USER &gt; RULE &gt; AI: an auto-marker rule may
     * overwrite an AI category (a rule is an explicit, deterministic instruction), but never a
     * marker the user chose by hand. An older korTTY reads an unknown {@code RULE} as {@code null}
     * and {@link #getMarkerSource()} defaults it to {@code AI}, i.e. to "regenerable" — which is
     * the correct degradation.
     */
    @XmlEnum
    public enum MarkerSource { AI, USER, RULE }

    /** Production state of an AI entry; RAW entries exist when AI summaries are unavailable. */
    @XmlEnum
    public enum State { SUMMARIZED, FAILED, RAW }

    @XmlElement
    private String id;

    @XmlElement
    private SessionJournalEntryKind kind = SessionJournalEntryKind.AI_SUMMARY;

    @XmlElement
    private SessionJournalMarker marker = SessionJournalMarker.NONE;

    /**
     * Id of the applied {@link SessionJournalMarkerDefinition}, or {@code null} for a built-in
     * marker and on every document written before custom markers existed — then {@link #marker}
     * decides. Whenever this is set, {@code marker} is set to the definition's legacy value too,
     * so an older korTTY still shows a sensible badge.
     */
    @XmlElement
    private String markerId;

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

    /**
     * Marks drawn on a SCREENSHOT entry's image. Kept as data rather than only burnt into the PNG
     * so they stay editable; the untouched capture lives next to it as {@code *.orig.png}.
     */
    @XmlElementWrapper(name = "annotations")
    @XmlElement(name = "annotation")
    private List<SessionJournalAnnotation> annotations;

    /** Display text of the LLM behind an AGENT entry's run (profile/model); null otherwise. */
    @XmlElement
    private String agentModel;

    /** Wall-clock duration of an AGENT entry's run in milliseconds; null otherwise. */
    @XmlElement
    private Long agentDurationMillis;

    /** Total tokens the AGENT entry's run reported; null when unknown. */
    @XmlElement
    private Long agentTokens;

    public SessionJournalEntry() {
        this.id = UUID.randomUUID().toString();
        this.createdAt = OffsetDateTime.now();
    }

    public SessionJournalEntry(SessionJournalEntry other) {
        this.id = other.id;
        this.kind = other.kind;
        this.marker = other.marker;
        this.markerId = other.markerId;
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
        if (other.annotations != null) {
            this.annotations = new ArrayList<>();
            for (SessionJournalAnnotation annotation : other.annotations) {
                this.annotations.add(new SessionJournalAnnotation(annotation));
            }
        }
        this.agentModel = other.agentModel;
        this.agentDurationMillis = other.agentDurationMillis;
        this.agentTokens = other.agentTokens;
    }

    public String getAgentModel() {
        return agentModel;
    }

    public void setAgentModel(String agentModel) {
        this.agentModel = agentModel;
    }

    public Long getAgentDurationMillis() {
        return agentDurationMillis;
    }

    public void setAgentDurationMillis(Long agentDurationMillis) {
        this.agentDurationMillis = agentDurationMillis;
    }

    public Long getAgentTokens() {
        return agentTokens;
    }

    public void setAgentTokens(Long agentTokens) {
        this.agentTokens = agentTokens;
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

    /** Raw id; use {@code SessionJournalMarkers.resolve} to get the definition to render with. */
    public String getMarkerId() {
        return markerId;
    }

    public void setMarkerId(String markerId) {
        String normalized = SessionJournalMarkerDefinition.normalizeId(markerId);
        this.markerId = normalized;
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

    /** Live list; only SCREENSHOT entries ever carry marks. */
    public List<SessionJournalAnnotation> getAnnotations() {
        if (annotations == null) {
            annotations = new ArrayList<>();
        }
        return annotations;
    }

    public void setAnnotations(List<SessionJournalAnnotation> annotations) {
        this.annotations = annotations != null ? new ArrayList<>(annotations) : new ArrayList<>();
    }

    public boolean hasAnnotations() {
        return annotations != null && !annotations.isEmpty();
    }

    /**
     * Drops the empty annotation list before marshalling. JAXB writes an
     * {@code @XmlElementWrapper} even when the collection is empty, and every non-screenshot entry
     * would otherwise grow a stray element. The getter recreates the list on demand.
     */
    @SuppressWarnings("unused")
    private void beforeMarshal(jakarta.xml.bind.Marshaller marshaller) {
        if (annotations != null && annotations.isEmpty()) {
            annotations = null;
        }
    }
}
