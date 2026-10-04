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

    /** A text field of a skill the user edits, as {@link #firstUnstorableText()} names it. */
    public enum TextField {
        NAME, DESCRIPTION, TAGS, CONTENT
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
     * The first text field, in editor order (name, description, tags, content), holding a character
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
        return codePoint >= 0 ? new UnstorableText(TextField.CONTENT, codePoint) : null;
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
