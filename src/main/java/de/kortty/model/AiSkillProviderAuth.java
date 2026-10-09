package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * How korTTY authenticates against an external AI-skill provider.
 */
@XmlEnum
public enum AiSkillProviderAuth {
    @XmlEnumValue("NONE")
    NONE,

    /** {@code Authorization: Bearer <token>} — GitHub personal access tokens, SkillsMP API keys. */
    @XmlEnumValue("TOKEN")
    TOKEN,

    /** HTTP Basic with user name and password; only ever sent over HTTPS. */
    @XmlEnumValue("BASIC")
    BASIC
}
