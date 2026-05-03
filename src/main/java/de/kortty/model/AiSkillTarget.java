package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * Target surface for a user-defined AI skill.
 */
@XmlEnum
public enum AiSkillTarget {
    @XmlEnumValue("CHAT")
    CHAT,

    @XmlEnumValue("AGENT")
    AGENT,

    @XmlEnumValue("BOTH")
    BOTH;

    public boolean appliesToChat() {
        return this == CHAT || this == BOTH;
    }

    public boolean appliesToAgent() {
        return this == AGENT || this == BOTH;
    }
}
