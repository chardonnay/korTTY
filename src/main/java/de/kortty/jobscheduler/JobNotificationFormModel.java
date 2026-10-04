package de.kortty.jobscheduler;

import de.kortty.core.DisplayTextSanitizer;
import de.kortty.policy.EffectivePolicy;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * The state behind the "Notifications" section of a job in the JobScheduler window: which run
 * results notify, whether on the desktop, and which webhook targets receive them. FX-free, so the
 * section's rules are unit-tested without a toolkit; the dialog only mirrors this model into its
 * check boxes and back.
 *
 * <p>Also holds the rules of the webhook-target editor ({@link #validateTarget}) so the dialog and
 * the tests apply the same checks.
 */
public final class JobNotificationFormModel {

    /** i18n prefix of the section's labels and warnings. */
    public static final String KEY_PREFIX = "jobscheduler.dialog.notifications.";

    /** Shown when no trigger is ticked: the job never notifies. */
    public static final String WARNING_NO_TRIGGER = KEY_PREFIX + "warning.noTrigger";
    /** Shown when neither the desktop nor a webhook target is selected. */
    public static final String WARNING_NO_CHANNEL = KEY_PREFIX + "warning.noChannel";
    /** Shown when webhook targets are selected but the policy denies {@code job-webhooks}. */
    public static final String WARNING_POLICY_DENIED = KEY_PREFIX + "warning.policyDenied";

    /** The target editor's error for a target without a name. */
    public static final String ERROR_TARGET_NAME = "jobscheduler.dialog.webhook.error.name";

    private static final int MAX_LABEL_CHARS = 80;

    /** One webhook target as the section lists it; never carries the URL. */
    public record TargetOption(String id, String label, WebhookFormat format, boolean enabled) {
    }

    private final EnumSet<JobNotificationTrigger> triggers = EnumSet.noneOf(JobNotificationTrigger.class);
    private boolean desktop;
    private final LinkedHashSet<String> selectedTargetIds = new LinkedHashSet<>();
    private final List<TargetOption> targets = new ArrayList<>();

    private JobNotificationFormModel() {
    }

    /**
     * The section for a job: {@code config} (the defaults when {@code null}) against the stored
     * webhook targets. A selected id whose target no longer exists is dropped.
     */
    public static JobNotificationFormModel load(JobNotificationConfig config, Collection<WebhookTarget> available) {
        JobNotificationFormModel model = new JobNotificationFormModel();
        JobNotificationConfig effective = config != null ? config : JobNotificationConfig.defaults();
        model.triggers.addAll(effective.getTriggers());
        model.desktop = effective.isDesktop();
        model.setAvailableTargets(available);
        for (String id : effective.getWebhookTargetIds()) {
            if (model.hasTarget(id)) {
                model.selectedTargetIds.add(id);
            }
        }
        return model;
    }

    /**
     * Replaces the list of stored targets, for example after the target manager added or deleted
     * one; selections of targets that are gone are dropped, the others kept.
     */
    public void setAvailableTargets(Collection<WebhookTarget> available) {
        targets.clear();
        if (available != null) {
            for (WebhookTarget target : available) {
                if (target != null) {
                    targets.add(new TargetOption(target.getId(), label(target), target.getFormat(), target.isEnabled()));
                }
            }
        }
        targets.sort(Comparator.comparing((TargetOption option) -> option.label().toLowerCase(Locale.ROOT))
            .thenComparing(TargetOption::id));
        selectedTargetIds.removeIf(id -> !hasTarget(id));
    }

    /** The stored targets, sorted by name. */
    public List<TargetOption> targets() {
        return List.copyOf(targets);
    }

    public boolean isTrigger(JobNotificationTrigger trigger) {
        return trigger != null && triggers.contains(trigger);
    }

    public void setTrigger(JobNotificationTrigger trigger, boolean selected) {
        if (trigger == null) {
            return;
        }
        if (selected) {
            triggers.add(trigger);
        } else {
            triggers.remove(trigger);
        }
    }

    public Set<JobNotificationTrigger> triggers() {
        return EnumSet.copyOf(triggers);
    }

    public boolean isDesktop() {
        return desktop;
    }

    public void setDesktop(boolean desktop) {
        this.desktop = desktop;
    }

    public boolean isTargetSelected(String targetId) {
        return targetId != null && selectedTargetIds.contains(targetId);
    }

    /** Selects or clears a stored target; an unknown id is ignored. */
    public void setTargetSelected(String targetId, boolean selected) {
        if (targetId == null || !hasTarget(targetId)) {
            return;
        }
        if (selected) {
            selectedTargetIds.add(targetId);
        } else {
            selectedTargetIds.remove(targetId);
        }
    }

    /** The selected target ids in the order they were selected. */
    public List<String> selectedTargetIds() {
        return List.copyOf(selectedTargetIds);
    }

    /** The configuration to store on the job. */
    public JobNotificationConfig toConfig() {
        JobNotificationConfig config = new JobNotificationConfig();
        config.setTriggers(triggers);
        config.setDesktop(desktop);
        config.setWebhookTargetIds(selectedTargetIds);
        return config;
    }

    /**
     * The i18n keys of the hints the section shows under its controls, most important first; empty
     * when the job notifies as configured.
     */
    public List<String> warningKeys(EffectivePolicy policy) {
        List<String> warnings = new ArrayList<>();
        if (triggers.isEmpty()) {
            warnings.add(WARNING_NO_TRIGGER);
        } else if (!desktop && selectedTargetIds.isEmpty()) {
            warnings.add(WARNING_NO_CHANNEL);
        }
        EffectivePolicy effective = policy != null ? policy : EffectivePolicy.unrestricted();
        if (!selectedTargetIds.isEmpty() && !effective.jobWebhooksAllowed()) {
            warnings.add(WARNING_POLICY_DENIED);
        }
        return warnings;
    }

    /**
     * Checks the target editor's input. An empty URL field keeps a stored URL, so the secret never
     * has to be shown again to change the name or the format.
     *
     * @return the i18n key of the first problem, or empty when the target can be saved
     */
    public static Optional<String> validateTarget(String name, String typedUrl, boolean hasStoredUrl) {
        if (DisplayTextSanitizer.sanitize(name, MAX_LABEL_CHARS).isEmpty()) {
            return Optional.of(ERROR_TARGET_NAME);
        }
        if ((typedUrl == null || typedUrl.isBlank()) && hasStoredUrl) {
            return Optional.empty();
        }
        WebhookUrlValidator.Result result = WebhookUrlValidator.validate(typedUrl);
        return result.valid() ? Optional.empty() : Optional.of(result.problem().i18nKey());
    }

    /** Whether the URL field holds a new URL to encrypt, rather than keeping the stored one. */
    public static boolean replacesUrl(String typedUrl) {
        return typedUrl != null && !typedUrl.isBlank();
    }

    /** The target's name without control or bidi characters; its id when it has no visible name. */
    public static String label(WebhookTarget target) {
        String name = DisplayTextSanitizer.sanitize(target.getName(), MAX_LABEL_CHARS);
        return name.isEmpty() ? target.getId() : name;
    }

    private boolean hasTarget(String id) {
        return targets.stream().anyMatch(option -> option.id().equals(id));
    }
}
