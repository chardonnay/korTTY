package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * "Session journal per run" settings of one automation source — a JobScheduler job or the
 * interactive AI Swarm. Every run then records one session journal per target server, which is
 * kept or discarded, summarized or not, and deleted automatically once its retention ends.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "AutomationJournalConfig")
public class AutomationJournalConfig {

    public static final int DEFAULT_RETENTION_DAYS = 14;
    public static final int MAX_RETENTION_DAYS = 3650;

    @XmlElement
    private boolean enabled = false;

    /** Record the commands sent to the server in addition to its output. */
    @XmlElement
    private boolean captureInput = true;

    @XmlElement
    private AutomationJournalAiMode aiMode = AutomationJournalAiMode.ALWAYS;

    /** Profile for the summaries; null = the automation journal profile from the settings. */
    @XmlElement
    private String aiProfileId;

    @XmlElement
    private AutomationJournalKeepMode keepMode = AutomationJournalKeepMode.ALWAYS;

    @XmlElement
    private AutomationJournalRetentionMode retentionMode = AutomationJournalRetentionMode.DAYS;

    @XmlElement
    private int retentionDays = DEFAULT_RETENTION_DAYS;

    /** ISO date (yyyy-MM-dd) for {@link AutomationJournalRetentionMode#FIXED_DATE}. */
    @XmlElement
    private String expiryDate;

    /** Maximum number of kept runs of this source; 0 = unlimited. */
    @XmlElement
    private int maxJournals = 0;

    /** Maximum disk space of this source's journals in MB; 0 = unlimited. */
    @XmlElement
    private int maxStorageMb = 0;

    /** Discard a run's journal when its output equals the previous kept run's, keeping a reference. */
    @XmlElement
    private boolean dedupEnabled = true;

    public AutomationJournalConfig() {
    }

    public AutomationJournalConfig(AutomationJournalConfig other) {
        if (other == null) {
            return;
        }
        this.enabled = other.enabled;
        this.captureInput = other.captureInput;
        this.aiMode = other.aiMode;
        this.aiProfileId = other.aiProfileId;
        this.keepMode = other.keepMode;
        this.retentionMode = other.retentionMode;
        this.retentionDays = other.retentionDays;
        this.expiryDate = other.expiryDate;
        this.maxJournals = other.maxJournals;
        this.maxStorageMb = other.maxStorageMb;
        this.dedupEnabled = other.dedupEnabled;
    }

    /**
     * When a journal of a run that ended at {@code endedAt} expires, or null when it never does.
     * {@code policyMaxDays} (an administrator cap, null/0 = none) always wins over a longer or
     * missing user retention.
     */
    public OffsetDateTime computeExpiry(OffsetDateTime endedAt, Integer policyMaxDays) {
        OffsetDateTime end = endedAt != null ? endedAt : OffsetDateTime.now();
        OffsetDateTime userExpiry = switch (getRetentionMode()) {
            case DAYS -> end.plusDays(getEffectiveRetentionDays());
            case FIXED_DATE -> {
                LocalDate date = parseExpiryDate();
                yield date != null
                    ? date.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toOffsetDateTime()
                    : end.plusDays(getEffectiveRetentionDays());
            }
            case NONE -> null;
        };
        if (policyMaxDays == null || policyMaxDays <= 0) {
            return userExpiry;
        }
        OffsetDateTime cap = end.plusDays(policyMaxDays);
        return userExpiry == null || userExpiry.isAfter(cap) ? cap : userExpiry;
    }

    /** {@link #getRetentionDays()} clamped to 1..{@value #MAX_RETENTION_DAYS}. */
    public int getEffectiveRetentionDays() {
        return Math.max(1, Math.min(MAX_RETENTION_DAYS, retentionDays));
    }

    /** The fixed expiry date, or null when unset or not a valid ISO date. */
    public LocalDate parseExpiryDate() {
        if (expiryDate == null || expiryDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(expiryDate.trim());
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** The smaller of a user limit and an admin cap, where 0/null means "no limit". */
    public static int effectiveLimit(int userLimit, Integer policyLimit) {
        int user = Math.max(0, userLimit);
        int admin = policyLimit != null ? Math.max(0, policyLimit) : 0;
        if (user == 0) {
            return admin;
        }
        return admin == 0 ? user : Math.min(user, admin);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isCaptureInput() {
        return captureInput;
    }

    public void setCaptureInput(boolean captureInput) {
        this.captureInput = captureInput;
    }

    public AutomationJournalAiMode getAiMode() {
        return aiMode != null ? aiMode : AutomationJournalAiMode.ALWAYS;
    }

    public void setAiMode(AutomationJournalAiMode aiMode) {
        this.aiMode = aiMode;
    }

    public String getAiProfileId() {
        return aiProfileId;
    }

    public void setAiProfileId(String aiProfileId) {
        this.aiProfileId = aiProfileId != null && !aiProfileId.isBlank() ? aiProfileId : null;
    }

    public AutomationJournalKeepMode getKeepMode() {
        return keepMode != null ? keepMode : AutomationJournalKeepMode.ALWAYS;
    }

    public void setKeepMode(AutomationJournalKeepMode keepMode) {
        this.keepMode = keepMode;
    }

    public AutomationJournalRetentionMode getRetentionMode() {
        return retentionMode != null ? retentionMode : AutomationJournalRetentionMode.DAYS;
    }

    public void setRetentionMode(AutomationJournalRetentionMode retentionMode) {
        this.retentionMode = retentionMode;
    }

    public int getRetentionDays() {
        return retentionDays;
    }

    public void setRetentionDays(int retentionDays) {
        this.retentionDays = retentionDays;
    }

    public String getExpiryDate() {
        return expiryDate;
    }

    public void setExpiryDate(String expiryDate) {
        this.expiryDate = expiryDate;
    }

    public int getMaxJournals() {
        return Math.max(0, maxJournals);
    }

    public void setMaxJournals(int maxJournals) {
        this.maxJournals = Math.max(0, maxJournals);
    }

    public int getMaxStorageMb() {
        return Math.max(0, maxStorageMb);
    }

    public void setMaxStorageMb(int maxStorageMb) {
        this.maxStorageMb = Math.max(0, maxStorageMb);
    }

    public boolean isDedupEnabled() {
        return dedupEnabled;
    }

    public void setDedupEnabled(boolean dedupEnabled) {
        this.dedupEnabled = dedupEnabled;
    }
}
