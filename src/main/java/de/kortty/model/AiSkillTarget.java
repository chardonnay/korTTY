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
    BOTH,

    /** Only used for connections the skill has been assigned to; applies to chat and agent there. */
    @XmlEnumValue("CONNECTION")
    CONNECTION;

    public boolean appliesToChat() {
        return this == CHAT || this == BOTH || this == CONNECTION;
    }

    public boolean appliesToAgent() {
        return this == AGENT || this == BOTH || this == CONNECTION;
    }

    public boolean requiresConnectionAssignment() {
        return this == CONNECTION;
    }
}
