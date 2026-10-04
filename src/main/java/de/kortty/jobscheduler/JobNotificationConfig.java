package de.kortty.jobscheduler;

import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlElementWrapper;
import jakarta.xml.bind.annotation.XmlType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Per-job notification settings. A job without one uses {@link #defaults()}: failed and blocked
 * runs notify on the desktop, recoveries and successes are opt-in, and webhooks are always opt-in
 * per job because they send data off the machine.
 */
@XmlAccessorType(XmlAccessType.FIELD)
@XmlType(name = "JobNotificationConfig")
public class JobNotificationConfig {

    @XmlElementWrapper(name = "triggers")
    @XmlElement(name = "trigger")
    private List<JobNotificationTrigger> triggers = new ArrayList<>(defaultTriggers());

    @XmlElement
    private boolean desktop = true;

    @XmlElementWrapper(name = "webhookTargetIds")
    @XmlElement(name = "targetId")
    private List<String> webhookTargetIds = new ArrayList<>();

    public static JobNotificationConfig defaults() {
        return new JobNotificationConfig();
    }

    public static Set<JobNotificationTrigger> defaultTriggers() {
        return EnumSet.of(JobNotificationTrigger.FAILED, JobNotificationTrigger.BLOCKED);
    }

    public EnumSet<JobNotificationTrigger> getTriggers() {
        return normalize(triggers);
    }

    public void setTriggers(Collection<JobNotificationTrigger> triggers) {
        this.triggers = new ArrayList<>(normalize(triggers));
    }

    public boolean isDesktop() {
        return desktop;
    }

    public void setDesktop(boolean desktop) {
        this.desktop = desktop;
    }

    public List<String> getWebhookTargetIds() {
        return List.copyOf(normalizeIds(webhookTargetIds));
    }

    public void setWebhookTargetIds(Collection<String> webhookTargetIds) {
        this.webhookTargetIds = new ArrayList<>(normalizeIds(webhookTargetIds));
    }

    /** True when the run matches at least one configured trigger. */
    public boolean matches(JobRunEvent event) {
        if (event == null) {
            return false;
        }
        EnumSet<JobNotificationTrigger> configured = getTriggers();
        for (JobNotificationTrigger trigger : event.matchingTriggers()) {
            if (configured.contains(trigger)) {
                return true;
            }
        }
        return false;
    }

    public JobNotificationConfig copy() {
        JobNotificationConfig copy = new JobNotificationConfig();
        copy.setTriggers(getTriggers());
        copy.setDesktop(desktop);
        copy.setWebhookTargetIds(getWebhookTargetIds());
        return copy;
    }

    private static EnumSet<JobNotificationTrigger> normalize(Collection<JobNotificationTrigger> values) {
        EnumSet<JobNotificationTrigger> result = EnumSet.noneOf(JobNotificationTrigger.class);
        if (values != null) {
            for (JobNotificationTrigger trigger : values) {
                if (trigger != null) {
                    result.add(trigger);
                }
            }
        }
        return result;
    }

    private static LinkedHashSet<String> normalizeIds(Collection<String> values) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        if (values != null) {
            for (String id : values) {
                if (id != null && !id.isBlank()) {
                    result.add(id.trim());
                }
            }
        }
        return result;
    }
}
