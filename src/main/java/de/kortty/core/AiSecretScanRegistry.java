package de.kortty.core;

import de.kortty.model.AiSkill;

import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

/**
 * The possible secrets the startup scan found, kept in memory for the session: the ids of AI skills and the keys of
 * memory scopes. Never persisted, and nothing in it changes a skill or a memory file; the AI-skills list shows a
 * badge for each flagged skill. A skill's flag follows its text when it is saved.
 */
public final class AiSecretScanRegistry {

    private static final AiSecretScanRegistry SHARED = new AiSecretScanRegistry();

    private Set<String> skillIds = Set.of();
    private Set<String> memoryScopeKeys = Set.of();

    /** The registry of the running application. */
    public static AiSecretScanRegistry shared() {
        return SHARED;
    }

    public synchronized void replaceSkills(Collection<String> skillIds) {
        this.skillIds = Set.copyOf(skillIds);
    }

    public synchronized void replaceMemoryScopes(Collection<String> scopeKeys) {
        this.memoryScopeKeys = Set.copyOf(scopeKeys);
    }

    /** Re-checks {@code skills} after they were stored: flagged when they hold a possible secret, unflagged otherwise. */
    public synchronized void refreshSkills(Collection<AiSkill> skills) {
        Set<String> next = new HashSet<>(skillIds);
        for (AiSkill skill : skills) {
            if (skill == null || skill.getId() == null) {
                continue;
            }
            if (AiSkillSecretCheck.findings(skill).isEmpty()) {
                next.remove(skill.getId());
            } else {
                next.add(skill.getId());
            }
        }
        this.skillIds = Set.copyOf(next);
    }

    public synchronized boolean hasSkillFinding(String skillId) {
        return skillId != null && skillIds.contains(skillId);
    }

    public synchronized boolean hasMemoryFinding(String scopeKey) {
        return scopeKey != null && memoryScopeKeys.contains(scopeKey);
    }

    public synchronized Set<String> skillIds() {
        return skillIds;
    }

    public synchronized Set<String> memoryScopeKeys() {
        return memoryScopeKeys;
    }
}
