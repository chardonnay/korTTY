package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * Wire protocol of an external AI-skill provider: where its SKILL.md files come from.
 */
@XmlEnum
public enum AiSkillProviderType {
    /**
     * A GitHub (or GitHub Enterprise) REST API. Covers every directory that publishes skills as
     * {@code owner/repo@skill} — agenticskills.io, skills.sh, the Anthropic skills repository.
     */
    @XmlEnumValue("GITHUB")
    GITHUB,

    /** The SkillsMP keyword search API; the skills it finds are fetched from GitHub. */
    @XmlEnumValue("SKILLSMP")
    SKILLSMP,

    /** A plain HTTP(S) server that serves SKILL.md files, e.g. a self-hosted Gitea or an intranet share. */
    @XmlEnumValue("HTTP")
    HTTP;

    /** The base URL a new provider of this type starts with; empty for {@link #HTTP}. */
    public String defaultBaseUrl() {
        return switch (this) {
            case GITHUB -> "https://api.github.com";
            case SKILLSMP -> "https://skillsmp.com";
            case HTTP -> "";
        };
    }
}
