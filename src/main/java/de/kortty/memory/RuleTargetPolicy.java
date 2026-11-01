package de.kortty.memory;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;

/**
 * What the policy and the settings allow for saving rules, as the router and the service read it:
 *
 * @param memoryWritable        whether the global and connection memory may be written: the memory is on, the
 *                              kill switch is not set and the policy makes the user's memory editable
 * @param skillCreationAllowed  whether korTTY may create a new skill for a learned rule: the setting is on, the
 *                              memory is switched on (the kill switch is not set), the AI memory feature is allowed
 *                              and the policy permits skill creation
 * @param memoryBudgetTokens    the memory budget in tokens that a korTTY entry is checked against
 * @param existingSkillsAllowed whether a learned rule may go into a skill the user already has. Only the setting, the
 *                              memory switch and the AI memory feature decide it: the policy key for skill creation
 *                              does not forbid it, because it only forbids creating skills
 */
public record RuleTargetPolicy(boolean memoryWritable, boolean skillCreationAllowed, int memoryBudgetTokens,
                               boolean existingSkillsAllowed) {

    /** A policy whose existing-skill permission follows skill creation, as before the two were separated. */
    public RuleTargetPolicy(boolean memoryWritable, boolean skillCreationAllowed, int memoryBudgetTokens) {
        this(memoryWritable, skillCreationAllowed, memoryBudgetTokens, skillCreationAllowed);
    }

    /** Allows every target, for tests and for code that runs without a policy. */
    public static RuleTargetPolicy unrestricted() {
        return new RuleTargetPolicy(true, true, MemoryStore.DEFAULT_BUDGET_TOKENS, true);
    }

    /** The view of the application's settings, policy and memory service, read at the moment of the call. */
    public static RuleTargetPolicy from(GlobalSettings settings, EffectivePolicy policy, MemoryService memory) {
        boolean memoryWritable = memory.isEnabled() && memory.isUserMemoryWritable();
        boolean existingSkills = memory.isEnabled() && settings.isAiSkillAutoCreate() && policy.aiMemoryAllowed();
        boolean skillCreation = existingSkills && policy.aiSkillCreationAllowed();
        int budget = settings.getAiMemoryMaxTokens();
        if (policy.aiMemoryMaxTokens().isPresent()) {
            budget = Math.min(budget, policy.aiMemoryMaxTokens().getAsInt());
        }
        return new RuleTargetPolicy(memoryWritable, skillCreation, budget, existingSkills);
    }
}
