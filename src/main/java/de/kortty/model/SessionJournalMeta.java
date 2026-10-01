package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;
import jakarta.xml.bind.annotation.adapters.XmlJavaTypeAdapter;

import java.nio.file.Path;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Metadata block of a session journal document. Connection details are duplicated here (and in
 * the capture log's meta line) on purpose: the management UI must never need to open the large
 * capture log, and an exported/merged journal directory stays self-describing.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SessionJournalMeta")
public class SessionJournalMeta {

    @XmlElement
    private String title;

    /** Free-text description the user can edit in the journal manager. */
    @XmlElement
    private String description;

    @XmlElement
    private String connectionId;

    @XmlElement
    private String connectionName;

    @XmlElement
    private String host;

    @XmlElement
    private int port;

    @XmlElement
    private String username;

    @XmlElement
    private String appVersion;

    @XmlElement
    @XmlJavaTypeAdapter(IsoOffsetDateTimeAdapter.class)
    private OffsetDateTime startedAt;

    /** Absent while the session is live; a missing value on a non-live journal marks a crash. */
    @XmlElement
    @XmlJavaTypeAdapter(IsoOffsetDateTimeAdapter.class)
    private OffsetDateTime endedAt;

    /** True when the journal was enabled retroactively and seeded from scrollback. */
    @XmlElement
    private boolean seeded;

    @XmlElement
    private SessionJournalLogFormat logFormat = SessionJournalLogFormat.DEFAULT;

    @XmlElement
    private long logEntryCount;

    @XmlElement
    private int logParts = 1;

    /** Highest capture-log sequence already summarized; restarts never re-summarize. */
    @XmlElement
    private long lastSummarizedSeq;

    /** Language code frozen at journal creation so summaries stay in one language. */
    @XmlElement
    private String appLanguageCode;

    @XmlElement
    private long commandCount;

    @XmlElement
    private long errorCount;

    @XmlElement
    private long screenshotCount;

    /**
     * Search keywords the closing AI summary extracted (hostnames, script/file names, error
     * classes, tools). Optional and additive: journals written before this field simply have
     * none and rank on their summaries alone.
     */
    @jakarta.xml.bind.annotation.XmlElementWrapper(name = "aiKeywords")
    @XmlElement(name = "keyword")
    private java.util.List<String> aiKeywords;

    // --- AI usage of this journal (summaries, title, screenshot analysis, Q&A); absent on
    // journals written before usage was tracked, which then read as zero ---

    @XmlElement
    private long aiPromptTokens;

    @XmlElement
    private long aiCompletionTokens;

    @XmlElement
    private long aiTotalTokens;

    @XmlElement
    private int aiCallCount;

    /** Money spent, summed per call from the calling profile's price at that time. */
    @XmlElement
    private double aiCost;

    /** Currency of {@link #aiCost}; null when no priced call was made. */
    @XmlElement
    private String aiCostCurrency;

    /** Id of the profile that made the most recent AI call for this journal. */
    @XmlElement
    private String aiProfileId;

    /** Display name of that profile, kept for journals whose profile was deleted since. */
    @XmlElement
    private String aiProfileName;

    /** True when that profile runs locally (no per-token charges). */
    @XmlElement
    private boolean aiProfileLocal;

    // --- transient (the keyword alone keeps JAXB away; combining it with @XmlTransient is an
    // IllegalAnnotationsException), populated by SessionJournalService for the management UI ---

    private transient Path directory;

    private transient boolean live;

    private transient String journalId;

    public SessionJournalMeta() {
    }

    public SessionJournalMeta(SessionJournalMeta other) {
        this.title = other.title;
        this.description = other.description;
        this.connectionId = other.connectionId;
        this.connectionName = other.connectionName;
        this.host = other.host;
        this.port = other.port;
        this.username = other.username;
        this.appVersion = other.appVersion;
        this.startedAt = other.startedAt;
        this.endedAt = other.endedAt;
        this.seeded = other.seeded;
        this.logFormat = other.logFormat;
        this.logEntryCount = other.logEntryCount;
        this.logParts = other.logParts;
        this.lastSummarizedSeq = other.lastSummarizedSeq;
        this.appLanguageCode = other.appLanguageCode;
        this.commandCount = other.commandCount;
        this.errorCount = other.errorCount;
        this.screenshotCount = other.screenshotCount;
        this.aiKeywords = other.aiKeywords != null
            ? new java.util.ArrayList<>(other.aiKeywords) : null;
        this.aiPromptTokens = other.aiPromptTokens;
        this.aiCompletionTokens = other.aiCompletionTokens;
        this.aiTotalTokens = other.aiTotalTokens;
        this.aiCallCount = other.aiCallCount;
        this.aiCost = other.aiCost;
        this.aiCostCurrency = other.aiCostCurrency;
        this.aiProfileId = other.aiProfileId;
        this.aiProfileName = other.aiProfileName;
        this.aiProfileLocal = other.aiProfileLocal;
        this.directory = other.directory;
        this.live = other.live;
        this.journalId = other.journalId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getConnectionId() {
        return connectionId;
    }

    public void setConnectionId(String connectionId) {
        this.connectionId = connectionId;
    }

    public String getConnectionName() {
        return connectionName;
    }

    public void setConnectionName(String connectionName) {
        this.connectionName = connectionName;
    }

    public String getHost() {
        return host;
    }

    public void setHost(String host) {
        this.host = host;
    }

    public int getPort() {
        return port;
    }

    public void setPort(int port) {
        this.port = port;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getAppVersion() {
        return appVersion;
    }

    public void setAppVersion(String appVersion) {
        this.appVersion = appVersion;
    }

    public OffsetDateTime getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(OffsetDateTime startedAt) {
        this.startedAt = startedAt;
    }

    public OffsetDateTime getEndedAt() {
        return endedAt;
    }

    public void setEndedAt(OffsetDateTime endedAt) {
        this.endedAt = endedAt;
    }

    public boolean isSeeded() {
        return seeded;
    }

    public void setSeeded(boolean seeded) {
        this.seeded = seeded;
    }

    public SessionJournalLogFormat getLogFormat() {
        return logFormat != null ? logFormat : SessionJournalLogFormat.DEFAULT;
    }

    public void setLogFormat(SessionJournalLogFormat logFormat) {
        this.logFormat = logFormat;
    }

    public long getLogEntryCount() {
        return logEntryCount;
    }

    public void setLogEntryCount(long logEntryCount) {
        this.logEntryCount = logEntryCount;
    }

    public int getLogParts() {
        return logParts > 0 ? logParts : 1;
    }

    public void setLogParts(int logParts) {
        this.logParts = logParts;
    }

    public long getLastSummarizedSeq() {
        return lastSummarizedSeq;
    }

    public void setLastSummarizedSeq(long lastSummarizedSeq) {
        this.lastSummarizedSeq = lastSummarizedSeq;
    }

    public String getAppLanguageCode() {
        return appLanguageCode;
    }

    public void setAppLanguageCode(String appLanguageCode) {
        this.appLanguageCode = appLanguageCode;
    }

    public long getCommandCount() {
        return commandCount;
    }

    public void setCommandCount(long commandCount) {
        this.commandCount = commandCount;
    }

    public long getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(long errorCount) {
        this.errorCount = errorCount;
    }

    public long getScreenshotCount() {
        return screenshotCount;
    }

    public void setScreenshotCount(long screenshotCount) {
        this.screenshotCount = screenshotCount;
    }

    /** Never null; empty for journals recorded before keyword extraction existed. */
    public java.util.List<String> getAiKeywords() {
        return aiKeywords != null ? aiKeywords : java.util.List.of();
    }

    public void setAiKeywords(java.util.List<String> aiKeywords) {
        this.aiKeywords = aiKeywords != null && !aiKeywords.isEmpty()
            ? new java.util.ArrayList<>(aiKeywords) : null;
    }

    public Path getDirectory() {
        return directory;
    }

    public long getAiPromptTokens() {
        return aiPromptTokens;
    }

    public void setAiPromptTokens(long aiPromptTokens) {
        this.aiPromptTokens = aiPromptTokens;
    }

    public long getAiCompletionTokens() {
        return aiCompletionTokens;
    }

    public void setAiCompletionTokens(long aiCompletionTokens) {
        this.aiCompletionTokens = aiCompletionTokens;
    }

    public long getAiTotalTokens() {
        return aiTotalTokens;
    }

    public void setAiTotalTokens(long aiTotalTokens) {
        this.aiTotalTokens = aiTotalTokens;
    }

    public int getAiCallCount() {
        return aiCallCount;
    }

    public void setAiCallCount(int aiCallCount) {
        this.aiCallCount = aiCallCount;
    }

    public double getAiCost() {
        return aiCost;
    }

    public void setAiCost(double aiCost) {
        this.aiCost = aiCost;
    }

    public String getAiCostCurrency() {
        return aiCostCurrency;
    }

    public void setAiCostCurrency(String aiCostCurrency) {
        this.aiCostCurrency = aiCostCurrency;
    }

    public String getAiProfileId() {
        return aiProfileId;
    }

    public void setAiProfileId(String aiProfileId) {
        this.aiProfileId = aiProfileId;
    }

    public String getAiProfileName() {
        return aiProfileName;
    }

    public void setAiProfileName(String aiProfileName) {
        this.aiProfileName = aiProfileName;
    }

    public boolean isAiProfileLocal() {
        return aiProfileLocal;
    }

    public void setAiProfileLocal(boolean aiProfileLocal) {
        this.aiProfileLocal = aiProfileLocal;
    }

    public void setDirectory(Path directory) {
        this.directory = directory;
    }

    public boolean isLive() {
        return live;
    }

    public void setLive(boolean live) {
        this.live = live;
    }

    public String getJournalId() {
        return journalId;
    }

    public void setJournalId(String journalId) {
        this.journalId = journalId;
    }

    /** A journal that is not live and never recorded a clean end crashed mid-session. */
    public boolean isCrashed() {
        return !live && endedAt == null && startedAt != null;
    }

    /** Duration from start to end, or to now while the session is live; null without a start. */
    public Duration getDuration() {
        if (startedAt == null) {
            return null;
        }
        OffsetDateTime end = endedAt != null ? endedAt : OffsetDateTime.now();
        return Duration.between(startedAt, end);
    }
}
