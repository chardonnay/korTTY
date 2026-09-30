package de.kortty.core;

import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyManager;

/**
 * The enterprise-policy view the automation journals need, as a plain value so the recorder,
 * the retention sweep and their tests do not depend on the policy singleton.
 *
 * @param allowed            automation journals may be recorded at all
 * @param aiAllowed          their AI summaries may run
 * @param userDeleteAllowed  korTTY may delete journals because of a <em>user</em> setting
 *                           (keep mode, duplicates, user retention and limits); administrator caps
 *                           below are enforced regardless
 * @param maxRetentionDays   administrator cap on retention, null = none
 * @param maxStorageMb       administrator cap on disk space per source, null = none
 * @param maxJournals        administrator cap on kept runs per source, null = none
 */
public record AutomationJournalPolicy(
    boolean allowed,
    boolean aiAllowed,
    boolean userDeleteAllowed,
    Integer maxRetentionDays,
    Integer maxStorageMb,
    Integer maxJournals) {

    /** No policy: everything allowed, nothing capped. */
    public static final AutomationJournalPolicy UNRESTRICTED =
        new AutomationJournalPolicy(true, true, true, null, null, null);

    /** The currently effective policy. */
    public static AutomationJournalPolicy current() {
        EffectivePolicy policy = PolicyManager.effective();
        return new AutomationJournalPolicy(
            policy.automationJournalAllowed(),
            policy.sessionJournalAiSummariesAllowed(),
            policy.sessionJournalDeleteAllowed(),
            policy.automationJournalMaxRetentionDays(),
            policy.automationJournalMaxStorageMb(),
            policy.automationJournalMaxJournals());
    }

    /** True when any administrator cap is set. */
    public boolean hasCaps() {
        return positive(maxRetentionDays) || positive(maxStorageMb) || positive(maxJournals);
    }

    private static boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
