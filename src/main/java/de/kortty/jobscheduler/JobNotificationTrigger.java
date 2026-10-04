package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlEnum;
import jakarta.xml.bind.annotation.XmlEnumValue;

/**
 * The run outcomes a job can notify about. {@link #RECOVERED} is not a {@link JobRunStatus}: it is
 * a successful run that follows a failed or blocked one, derived when the run event is published.
 * Cancelled runs never notify, since a cancellation is always the user's own action.
 */
@XmlEnum
public enum JobNotificationTrigger {
    @XmlEnumValue("FAILED")
    FAILED,

    @XmlEnumValue("BLOCKED")
    BLOCKED,

    @XmlEnumValue("RECOVERED")
    RECOVERED,

    @XmlEnumValue("SUCCESS")
    SUCCESS
}
