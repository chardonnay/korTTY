package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * Whether korTTY looks for rules in the user's messages that belong in the korTTY memory or in an AI skill.
 */
@XmlEnum
public enum AiMemoryLearningMode {

    /** Detected rules are proposed; nothing is saved before the user confirms it. */
    @XmlEnumValue("PROPOSE")
    PROPOSE,

    /** Nothing is detected and nothing is proposed. */
    @XmlEnumValue("OFF")
    OFF
}
