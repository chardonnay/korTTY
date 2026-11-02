package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

/**
 * Where an imported external AI skill came from, so it can be checked for updates.
 *
 * <p>{@link #getRevision()} is what the provider reports for the imported SKILL.md (the Git blob SHA
 * on GitHub, a SHA-256 of the body for a plain HTTP server); {@link #getContentSha256()} is the SHA-256
 * of the skill text as imported, which tells a local edit apart from an upstream change.
 * {@link #getDescriptionSha256()} does the same for the description, and {@link #getSkippedRevision()} is a
 * provider revision the user chose not to take: the update check does not offer it again.
 */
@XmlRootElement(name = "aiSkillExternalSource")
@XmlAccessorType(XmlAccessType.FIELD)
public class AiSkillExternalSource {

    @XmlElement
    private String providerId;

    /** Provider-specific locator, e.g. {@code https://github.com/owner/repo/tree/main/skills/pdf}. */
    @XmlElement
    private String reference;

    /** Page a browser opens for "Open source". */
    @XmlElement
    private String sourceUrl;

    @XmlElement
    private String revision;

    @XmlElement
    private String contentSha256;

    /**
     * SHA-256 of the description as imported or as last adopted from an update; null for an import from before
     * descriptions were tracked, whose description is then never replaced by an update.
     */
    @XmlElement
    private String descriptionSha256;

    /** Revision the user declined with "skip this version"; the update check does not offer it again. */
    @XmlElement
    private String skippedRevision;

    /** Epoch milliseconds of the import or of the last adopted update. */
    @XmlElement
    private long importedAt;

    public AiSkillExternalSource() {
    }

    public AiSkillExternalSource(AiSkillExternalSource source) {
        if (source == null) {
            return;
        }
        this.providerId = source.providerId;
        this.reference = source.reference;
        this.sourceUrl = source.sourceUrl;
        this.revision = source.revision;
        this.contentSha256 = source.contentSha256;
        this.descriptionSha256 = source.descriptionSha256;
        this.skippedRevision = source.skippedRevision;
        this.importedAt = source.importedAt;
    }

    public String getProviderId() {
        return providerId;
    }

    public void setProviderId(String providerId) {
        this.providerId = providerId;
    }

    public String getReference() {
        return reference;
    }

    public void setReference(String reference) {
        this.reference = reference;
    }

    public String getSourceUrl() {
        return sourceUrl;
    }

    public void setSourceUrl(String sourceUrl) {
        this.sourceUrl = sourceUrl;
    }

    public String getRevision() {
        return revision;
    }

    public void setRevision(String revision) {
        this.revision = revision;
    }

    public String getContentSha256() {
        return contentSha256;
    }

    public void setContentSha256(String contentSha256) {
        this.contentSha256 = contentSha256;
    }

    public String getDescriptionSha256() {
        return descriptionSha256;
    }

    public void setDescriptionSha256(String descriptionSha256) {
        this.descriptionSha256 = descriptionSha256;
    }

    public String getSkippedRevision() {
        return skippedRevision;
    }

    public void setSkippedRevision(String skippedRevision) {
        this.skippedRevision = skippedRevision;
    }

    public long getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(long importedAt) {
        this.importedAt = importedAt;
    }

    /** Removes characters {@code global-settings.xml} cannot hold; reports whether anything changed. */
    boolean stripUnstorableText() {
        String[] before = {providerId, reference, sourceUrl, revision, contentSha256, descriptionSha256, skippedRevision};
        providerId = AiSkill.withoutUnstorable(providerId);
        reference = AiSkill.withoutUnstorable(reference);
        sourceUrl = AiSkill.withoutUnstorable(sourceUrl);
        revision = AiSkill.withoutUnstorable(revision);
        contentSha256 = AiSkill.withoutUnstorable(contentSha256);
        descriptionSha256 = AiSkill.withoutUnstorable(descriptionSha256);
        skippedRevision = AiSkill.withoutUnstorable(skippedRevision);
        String[] after = {providerId, reference, sourceUrl, revision, contentSha256, descriptionSha256, skippedRevision};
        return !java.util.Arrays.equals(before, after);
    }
}
