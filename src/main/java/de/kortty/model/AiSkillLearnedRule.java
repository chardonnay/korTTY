package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/**
 * One rule the user confirmed for an AI skill. The rule is appended to the skill's prompt text; korTTY
 * never changes the skill's own content, only adds rules here.
 */
@XmlRootElement(name = "aiSkillLearnedRule")
@XmlAccessorType(XmlAccessType.FIELD)
public class AiSkillLearnedRule {

    public static final String SOURCE_CHAT = "chat";
    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_TRIAGE = "triage";
    public static final String SOURCE_MANUAL = "manual";

    @XmlElement
    private String id;

    @XmlElement
    private String text;

    /** ISO-8601 instant the user confirmed the rule. */
    @XmlElement
    private String createdAt;

    /** Where the rule came from: one of the {@code SOURCE_*} constants. */
    @XmlElement
    private String source;

    /** Lower-case hex SHA-256 of the rule text, kept in step with {@link #setText(String)}. */
    @XmlElement
    private String sha256;

    /** Whether the user has seen the rule in the skill editor; new rules show as a badge until then. */
    @XmlElement
    private boolean acknowledged;

    public AiSkillLearnedRule() {
        this.id = UUID.randomUUID().toString();
    }

    public AiSkillLearnedRule(AiSkillLearnedRule source) {
        if (source == null) {
            this.id = UUID.randomUUID().toString();
            return;
        }
        this.id = source.id;
        this.text = source.text;
        this.createdAt = source.createdAt;
        this.source = source.source;
        this.sha256 = source.sha256;
        this.acknowledged = source.acknowledged;
    }

    /** A new, not yet acknowledged rule confirmed now. */
    public static AiSkillLearnedRule create(String text, String source) {
        AiSkillLearnedRule rule = new AiSkillLearnedRule();
        rule.setText(text);
        rule.setSource(source);
        rule.setCreatedAt(Instant.now().toString());
        return rule;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id.trim() : UUID.randomUUID().toString();
    }

    public String getText() {
        return text;
    }

    public void setText(String text) {
        this.text = text != null ? text.strip() : null;
        this.sha256 = this.text != null ? sha256Hex(this.text) : null;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String getSha256() {
        return sha256;
    }

    public boolean isAcknowledged() {
        return acknowledged;
    }

    public void setAcknowledged(boolean acknowledged) {
        this.acknowledged = acknowledged;
    }

    /** Removes the characters {@code global-settings.xml} cannot hold, as {@link AiSkill#stripUnstorableText()}. */
    boolean stripUnstorableText() {
        String[] before = {id, text, createdAt, source, sha256};
        setId(AiSkill.withoutUnstorable(id));
        createdAt = AiSkill.withoutUnstorable(createdAt);
        source = AiSkill.withoutUnstorable(source);
        sha256 = AiSkill.withoutUnstorable(sha256);
        if (!Objects.equals(text, AiSkill.withoutUnstorable(text))) {
            setText(AiSkill.withoutUnstorable(text));
        }
        String[] after = {id, text, createdAt, source, sha256};
        return !java.util.Arrays.equals(before, after);
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
