package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * How a job's predecessors ({@link ScheduledJob#getDependsOnJobIds()}) govern its runs.
 */
@XmlEnum
public enum JobDependencyMode {
    /** The job keeps its own schedule; a run is blocked unless every predecessor last succeeded. */
    @XmlEnumValue("GATE")
    GATE,

    /** The job starts automatically once every predecessor has succeeded since its own last run. */
    @XmlEnumValue("CHAIN")
    CHAIN
}
