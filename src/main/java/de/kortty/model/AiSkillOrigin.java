package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * Who created an AI skill. A skill without an origin was written by the user, delivered as a built-in or
 * imported from an external provider.
 */
@XmlEnum
public enum AiSkillOrigin {

    /** Created by korTTY after the user confirmed a rule for a topic that had no skill yet. */
    @XmlEnumValue("KORTTY")
    KORTTY
}
