package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlType;

import java.util.UUID;

/**
 * A named webhook receiver for job-run notifications. The URL is a secret (Slack and Teams carry
 * the credential in its path), so only its master-password encryption is stored, under the same
 * rules as the archive password of a job; see {@link WebhookTargetSecrets}. Nothing here ever
 * holds or prints the plaintext URL.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "WebhookTarget")
public class WebhookTarget {

    @XmlElement
    private String id = UUID.randomUUID().toString();

    @XmlElement
    private String name;

    @XmlElement
    private WebhookFormat format = WebhookFormat.GENERIC_JSON;

    @XmlElement
    private String encryptedUrl;

    @XmlElement
    private boolean includeSummary;

    @XmlElement
    private boolean enabled = true;

    public String getId() {
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
        }
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id.trim() : UUID.randomUUID().toString();
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = trimToNull(name);
    }

    public WebhookFormat getFormat() {
        return format != null ? format : WebhookFormat.GENERIC_JSON;
    }

    public void setFormat(WebhookFormat format) {
        this.format = format != null ? format : WebhookFormat.GENERIC_JSON;
    }

    /** The master-password encryption of the URL; never the URL itself. */
    public String getEncryptedUrl() {
        return encryptedUrl;
    }

    public void setEncryptedUrl(String encryptedUrl) {
        this.encryptedUrl = trimToNull(encryptedUrl);
    }

    public boolean hasUrl() {
        return encryptedUrl != null && !encryptedUrl.isBlank();
    }

    /** Whether the job's free-text summary is sent (masked); off by default. */
    public boolean isIncludeSummary() {
        return includeSummary;
    }

    public void setIncludeSummary(boolean includeSummary) {
        this.includeSummary = includeSummary;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public WebhookTarget copy() {
        WebhookTarget copy = new WebhookTarget();
        copy.id = getId();
        copy.name = name;
        copy.format = getFormat();
        copy.encryptedUrl = encryptedUrl;
        copy.includeSummary = includeSummary;
        copy.enabled = enabled;
        return copy;
    }

    @Override
    public String toString() {
        return "WebhookTarget[id=" + getId() + ", format=" + getFormat() + ", enabled=" + enabled + "]";
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
