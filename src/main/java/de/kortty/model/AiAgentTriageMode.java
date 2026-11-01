package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * When the AI agent judges a request first and offers solution options before it runs.
 */
@XmlEnum
public enum AiAgentTriageMode {

    /** Each request is judged; only requests that look complex get options. */
    @XmlEnumValue("AUTO")
    AUTO,

    /** Every agent run offers options first. */
    @XmlEnumValue("ALWAYS")
    ALWAYS,

    /** The agent runs requests as they are, without options. */
    @XmlEnumValue("NEVER")
    NEVER
}
