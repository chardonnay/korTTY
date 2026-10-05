package de.kortty.model;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlType;

/**
 * One command an AI summary mentions, with what the timeline shows when the reader hovers its
 * bold name: a one-line explanation, and where the command comes from — a regular tool of the
 * distribution, something that is not part of it, or a script the snippet manager knows.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "SessionJournalCommandInfo")
public class SessionJournalCommandInfo {

    @XmlEnum
    public enum Origin {
        /** A regular tool shipped with or packaged for the distribution. */
        DISTRIBUTION,
        /** Not part of the distribution: a custom script, a local binary, or a typo. */
        UNKNOWN,
        /** A script that is stored in korTTY's snippet manager; see {@link #snippetName}. */
        SNIPPET
    }

    @XmlElement
    private String name;

    @XmlElement
    private String description;

    @XmlElement
    private Origin origin = Origin.DISTRIBUTION;

    @XmlElement
    private String snippetName;

    public SessionJournalCommandInfo() {
    }

    public SessionJournalCommandInfo(String name, String description, Origin origin, String snippetName) {
        this.name = name;
        this.description = description;
        this.origin = origin;
        this.snippetName = snippetName;
    }

    public SessionJournalCommandInfo(SessionJournalCommandInfo other) {
        this(other.name, other.description, other.origin, other.snippetName);
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

    public Origin getOrigin() {
        return origin != null ? origin : Origin.DISTRIBUTION;
    }

    public void setOrigin(Origin origin) {
        this.origin = origin;
    }

    public String getSnippetName() {
        return snippetName;
    }

    public void setSnippetName(String snippetName) {
        this.snippetName = snippetName;
    }
}
