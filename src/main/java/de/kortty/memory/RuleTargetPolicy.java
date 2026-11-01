package de.kortty.memory;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;

/**
 * What the policy and the settings allow for saving rules, as the router and the service read it:
 *
 * @param memoryWritable       whether the global and connection memory may be written: the memory is on, the
 *                             kill switch is not set and the policy makes the user's memory editable
 * @param skillCreationAllowed whether korTTY may create skills and add rules to them: the setting is on, the
 *                             memory is switched on (the kill switch is not set), the AI memory feature is allowed
 *                             and the policy permits skill creation
 * @param memoryBudgetTokens   the memory budget in tokens that a korTTY entry is checked against
 */
public record RuleTargetPolicy(boolean memoryWritable, boolean skillCreationAllowed, int memoryBudgetTokens) {

    /** Allows every target, for tests and for code that runs without a policy. */
    public static RuleTargetPolicy unrestricted() {
        return new RuleTargetPolicy(true, true, MemoryStore.DEFAULT_BUDGET_TOKENS);
    }

    /** The view of the application's settings, policy and memory service, read at the moment of the call. */
    public static RuleTargetPolicy from(GlobalSettings settings, EffectivePolicy policy, MemoryService memory) {
        boolean memoryWritable = memory.isEnabled() && memory.isUserMemoryWritable();
        boolean skillCreation = memory.isEnabled() && settings.isAiSkillAutoCreate() && policy.aiMemoryAllowed()
            && policy.aiSkillCreationAllowed();
        int budget = settings.getAiMemoryMaxTokens();
        if (policy.aiMemoryMaxTokens().isPresent()) {
            budget = Math.min(budget, policy.aiMemoryMaxTokens().getAsInt());
        }
        return new RuleTargetPolicy(memoryWritable, skillCreation, budget);
    }
}
