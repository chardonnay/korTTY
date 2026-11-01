package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlRootElement;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * User-defined instructions that can be attached to AI prompts.
 *
 * <p>The setters keep text {@code global-settings.xml} cannot hold ({@link XmlStorableText}), so the
 * AI-skills editor can say what is wrong with it ({@link #firstUnstorableText()});
 * {@link #stripUnstorableText()} is what keeps it out of the file.
 */
@XmlRootElement(name = "aiSkill")
@XmlAccessorType(XmlAccessType.FIELD)
public class AiSkill {

    public static final AiSkillTarget DEFAULT_TARGET = AiSkillTarget.BOTH;

    @XmlElement
    private String id;

    @XmlElement
    private String name;

    @XmlElement
    private String description;

    @XmlElementWrapper(name = "tags")
    @XmlElement(name = "tag")
    private List<String> tags = new ArrayList<>();

    @XmlElement
    private boolean enabled = true;

    @XmlElement
    private AiSkillTarget target = AiSkillTarget.BOTH;

    @XmlElement
    private String content;

    /** Stable delivery key (e.g. {@code builtin.lang.python}); null for user-created skills. */
    @XmlElement
    private String builtinId;

    /** Built-ins cannot be deleted, only hidden; meaningless for user skills. */
    @XmlElement
    private boolean hidden;

    /** Delivery-owned topic keys a user skill's tags are matched against to override this built-in. */
    @XmlElementWrapper(name = "builtinTopics")
    @XmlElement(name = "topic")
    private List<String> builtinTopics = new ArrayList<>();

    @XmlElement(name = "builtinBaseline")
    private AiSkillBuiltinBaseline builtinBaseline;

    /** Set for skills imported from an external provider; null for local and built-in skills. */
    @XmlElement(name = "externalSource")
    private AiSkillExternalSource externalSource;

    /** Set for skills korTTY created itself; null for skills the user wrote, built-ins and external skills. */
    @XmlElement
    private AiSkillOrigin origin;

    /** ISO date (yyyy-MM-dd) on which korTTY created the skill; null otherwise. */
    @XmlElement
    private String createdAt;

    /** Whether the user has opened a korTTY-created skill; null until the skill is first opened. */
    @XmlElement
    private Boolean originAcknowledged;

    /** Rules the user confirmed for this skill; each is appended to the skill's text in the prompt. */
    @XmlElementWrapper(name = "learnedRules")
    @XmlElement(name = "rule")
    private List<AiSkillLearnedRule> learnedRules = new ArrayList<>();

    public AiSkill() {
        this.id = UUID.randomUUID().toString();
    }

    public AiSkill(AiSkill source) {
        if (source == null) {
            this.id = UUID.randomUUID().toString();
            return;
        }
        setId(source.getId());
        this.name = source.getName();
        this.description = source.getDescription();
        setTags(source.getTags());
        this.enabled = source.isEnabled();
        setTarget(source.getTarget());
        this.content = source.getContent();
        setBuiltinId(source.getBuiltinId());
        this.hidden = source.isHidden();
        setBuiltinTopics(source.getBuiltinTopics());
        this.builtinBaseline = source.getBuiltinBaseline() != null
            ? new AiSkillBuiltinBaseline(source.getBuiltinBaseline())
            : null;
        this.externalSource = source.getExternalSource() != null
            ? new AiSkillExternalSource(source.getExternalSource())
            : null;
        this.origin = source.getOrigin();
        this.createdAt = source.getCreatedAt();
        this.originAcknowledged = source.originAcknowledged;
        this.learnedRules = new ArrayList<>();
        for (AiSkillLearnedRule rule : source.getLearnedRules()) {
            this.learnedRules.add(new AiSkillLearnedRule(rule));
        }
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id != null && !id.isBlank() ? id.trim() : UUID.randomUUID().toString();
    }

    public void ensureId() {
        setId(id);
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getTags() {
        if (tags == null) {
            tags = new ArrayList<>();
        }
        normalizeTags();
        return tags;
    }

    public void setTags(List<String> tags) {
        this.tags = tags != null ? new ArrayList<>(tags) : new ArrayList<>();
        normalizeTags();
    }

    public String getTagsAsString() {
        return String.join(", ", getTags());
    }

    public void setTagsFromString(String tagsString) {
        List<String> parsed = new ArrayList<>();
        if (tagsString != null && !tagsString.isBlank()) {
            for (String tag : tagsString.split(",")) {
                String trimmed = tag.trim();
                if (!trimmed.isBlank()) {
                    parsed.add(trimmed);
                }
            }
        }
        setTags(parsed);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public AiSkillTarget getTarget() {
        return target != null ? target : DEFAULT_TARGET;
    }

    public void setTarget(AiSkillTarget target) {
        this.target = target != null ? target : DEFAULT_TARGET;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getBuiltinId() {
        return builtinId;
    }

    public void setBuiltinId(String builtinId) {
        this.builtinId = builtinId != null && !builtinId.isBlank() ? builtinId.trim() : null;
    }

    public boolean isBuiltin() {
        return builtinId != null;
    }

    public boolean isHidden() {
        return hidden;
    }

    public void setHidden(boolean hidden) {
        this.hidden = hidden;
    }

    public List<String> getBuiltinTopics() {
        if (builtinTopics == null) {
            builtinTopics = new ArrayList<>();
        }
        return builtinTopics;
    }

    public void setBuiltinTopics(List<String> builtinTopics) {
        List<String> normalized = new ArrayList<>();
        if (builtinTopics != null) {
            for (String topic : builtinTopics) {
                String trimmed = topic != null ? topic.trim() : "";
                if (!trimmed.isBlank()
                    && normalized.stream().noneMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
                    normalized.add(trimmed);
                }
            }
        }
        this.builtinTopics = normalized;
    }

    public AiSkillBuiltinBaseline getBuiltinBaseline() {
        return builtinBaseline;
    }

    public void setBuiltinBaseline(AiSkillBuiltinBaseline builtinBaseline) {
        this.builtinBaseline = builtinBaseline;
    }

    public AiSkillExternalSource getExternalSource() {
        return externalSource;
    }

    public void setExternalSource(AiSkillExternalSource externalSource) {
        this.externalSource = externalSource;
    }

    /** Whether the skill was imported from an external provider (AI Skills → External). */
    public boolean isExternal() {
        return externalSource != null;
    }

    public AiSkillOrigin getOrigin() {
        return origin;
    }

    public void setOrigin(AiSkillOrigin origin) {
        this.origin = origin;
    }

    /** Whether korTTY created this skill, as opposed to the user, a built-in or an external provider. */
    public boolean isCreatedByKortty() {
        return origin == AiSkillOrigin.KORTTY;
    }

    public String getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(String createdAt) {
        this.createdAt = createdAt;
    }

    /** Whether the user has opened a korTTY-created skill since korTTY created it. */
    public boolean isOriginAcknowledged() {
        return Boolean.TRUE.equals(originAcknowledged);
    }

    public void setOriginAcknowledged(boolean originAcknowledged) {
        this.originAcknowledged = originAcknowledged;
    }

    /** The rules the user confirmed for this skill; the list is live, so callers may edit it. */
    public List<AiSkillLearnedRule> getLearnedRules() {
        if (learnedRules == null) {
            learnedRules = new ArrayList<>();
        }
        // A hand-edited file may lack ids; every rule needs one to be told apart when merging.
        learnedRules.removeIf(java.util.Objects::isNull);
        for (AiSkillLearnedRule rule : learnedRules) {
            if (rule.getId() == null || rule.getId().isBlank()) {
                rule.setId(null);
            }
        }
        return learnedRules;
    }

    public void setLearnedRules(List<AiSkillLearnedRule> learnedRules) {
        this.learnedRules = new ArrayList<>();
        if (learnedRules != null) {
            for (AiSkillLearnedRule rule : learnedRules) {
                if (rule != null) {
                    this.learnedRules.add(new AiSkillLearnedRule(rule));
                }
            }
        }
    }

    /** How many of the learned rules the user has not seen in the skill editor yet. */
    public int unacknowledgedLearnedRuleCount() {
        int count = 0;
        for (AiSkillLearnedRule rule : getLearnedRules()) {
            if (rule != null && !rule.isAcknowledged()) {
                count++;
            }
        }
        return count;
    }

    /** A text field of a skill the user edits, as {@link #firstUnstorableText()} names it. */
    public enum TextField {
        NAME, DESCRIPTION, TAGS, CONTENT, LEARNED_RULE
    }

    /**
     * The first character of a skill's {@code field} that {@code global-settings.xml} cannot hold.
     *
     * @param codePoint the character; a lone surrogate is reported as its own value
     */
    public record UnstorableText(TextField field, int codePoint) {

        /** The character as {@code U+0007}: it is invisible in the editor, so a message names it. */
        public String codePointLabel() {
            return String.format(Locale.ROOT, "U+%04X", codePoint);
        }
    }

    /**
     * The first text field, in editor order (name, description, tags, content, learned rules), holding a character
     * {@code global-settings.xml} cannot store ({@link XmlStorableText}), or {@code null} when the file
     * can hold every one of them.
     */
    public UnstorableText firstUnstorableText() {
        int codePoint = XmlStorableText.firstUnstorableCodePoint(name);
        if (codePoint >= 0) {
            return new UnstorableText(TextField.NAME, codePoint);
        }
        codePoint = XmlStorableText.firstUnstorableCodePoint(description);
        if (codePoint >= 0) {
            return new UnstorableText(TextField.DESCRIPTION, codePoint);
        }
        for (String tag : getTags()) {
            codePoint = XmlStorableText.firstUnstorableCodePoint(tag);
            if (codePoint >= 0) {
                return new UnstorableText(TextField.TAGS, codePoint);
            }
        }
        codePoint = XmlStorableText.firstUnstorableCodePoint(content);
        if (codePoint >= 0) {
            return new UnstorableText(TextField.CONTENT, codePoint);
        }
        for (AiSkillLearnedRule rule : getLearnedRules()) {
            codePoint = rule != null ? XmlStorableText.firstUnstorableCodePoint(rule.getText()) : -1;
            if (codePoint >= 0) {
                return new UnstorableText(TextField.LEARNED_RULE, codePoint);
            }
        }
        return null;
    }

    /**
     * Removes every character {@code global-settings.xml} cannot hold from all text of the skill — the
     * fields the user edits as well as its ids, built-in topics and built-in baseline — and reports
     * whether anything was removed. Written anyway, one such character would keep every setting from
     * loading at the next start. An id that ends up blank is replaced by a fresh one.
     */
    public boolean stripUnstorableText() {
        String previousId = id;
        setId(withoutUnstorable(id));
        boolean changed = !java.util.Objects.equals(previousId, id);
        String strippedName = withoutUnstorable(name);
        changed |= !java.util.Objects.equals(strippedName, name);
        name = strippedName;
        String strippedDescription = withoutUnstorable(description);
        changed |= !java.util.Objects.equals(strippedDescription, description);
        description = strippedDescription;
        String strippedContent = withoutUnstorable(content);
        changed |= !java.util.Objects.equals(strippedContent, content);
        content = strippedContent;
        List<String> strippedTags = withoutUnstorable(getTags());
        if (!strippedTags.equals(getTags())) {
            setTags(strippedTags);
            changed = true;
        }
        String strippedBuiltinId = withoutUnstorable(builtinId);
        if (!java.util.Objects.equals(strippedBuiltinId, builtinId)) {
            setBuiltinId(strippedBuiltinId);
            changed = true;
        }
        List<String> strippedTopics = withoutUnstorable(getBuiltinTopics());
        if (!strippedTopics.equals(getBuiltinTopics())) {
            setBuiltinTopics(strippedTopics);
            changed = true;
        }
        if (builtinBaseline != null) {
            changed |= builtinBaseline.stripUnstorableText();
        }
        if (externalSource != null) {
            changed |= externalSource.stripUnstorableText();
        }
        String strippedCreatedAt = withoutUnstorable(createdAt);
        changed |= !java.util.Objects.equals(strippedCreatedAt, createdAt);
        createdAt = strippedCreatedAt;
        for (AiSkillLearnedRule rule : getLearnedRules()) {
            changed |= rule.stripUnstorableText();
        }
        return changed;
    }

    /** {@code text} without the characters {@code global-settings.xml} cannot hold; {@code null} stays {@code null}. */
    static String withoutUnstorable(String text) {
        if (text == null || XmlStorableText.isStorable(text)) {
            return text;
        }
        StringBuilder kept = new StringBuilder(text.length());
        text.codePoints()
            .filter(codePoint -> XmlStorableText.isStorable(new String(Character.toChars(codePoint))))
            .forEach(kept::appendCodePoint);
        return kept.toString();
    }

    static List<String> withoutUnstorable(List<String> texts) {
        List<String> kept = new ArrayList<>(texts.size());
        for (String text : texts) {
            kept.add(withoutUnstorable(text));
        }
        return kept;
    }

    private void normalizeTags() {
        List<String> normalized = new ArrayList<>();
        if (tags != null) {
            for (String tag : tags) {
                String trimmed = tag != null ? tag.trim() : "";
                if (!trimmed.isBlank() && normalized.stream().noneMatch(existing -> existing.equalsIgnoreCase(trimmed))) {
                    normalized.add(trimmed);
                }
            }
        }
        tags = normalized;
    }
}
