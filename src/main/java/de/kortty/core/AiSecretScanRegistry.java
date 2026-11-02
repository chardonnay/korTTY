package de.kortty.core;

import de.kortty.core.AiSecretGuard.Finding;
import de.kortty.model.AiSkill;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The possible secrets the startup scan found, kept in memory for the session: the ids of AI skills, and for each memory
 * scope the findings (line and category; the value is never kept). Never persisted, and nothing in it changes a skill or a
 * memory file. The AI-skills list shows a badge for each flagged skill, and the Memory tab a warning for each flagged
 * scope. A flag follows the text when it is saved.
 */
public final class AiSecretScanRegistry {

    private static final AiSecretScanRegistry SHARED = new AiSecretScanRegistry();

    private Set<String> skillIds = Set.of();
    /**
     * The flagged memory scopes by key. A scope with an empty list is flagged, but its findings are not known; a scope
     * that is not flagged has no entry.
     */
    private Map<String, List<Finding>> memory = Map.of();

    /** The registry of the running application. */
    public static AiSecretScanRegistry shared() {
        return SHARED;
    }

    public synchronized void replaceSkills(Collection<String> skillIds) {
        this.skillIds = Set.copyOf(skillIds);
    }

    /** Replaces the flagged memory scopes with the given keys; their findings are not known. */
    public synchronized void replaceMemoryScopes(Collection<String> scopeKeys) {
        Map<String, List<Finding>> next = new LinkedHashMap<>();
        for (String key : scopeKeys) {
            if (key != null) {
                next.put(key, List.of());
            }
        }
        this.memory = Map.copyOf(next);
    }

    /** Replaces the memory findings by scope. A scope without findings is not flagged. */
    public synchronized void replaceMemoryFindings(Map<String, List<Finding>> findingsByScope) {
        Map<String, List<Finding>> next = new LinkedHashMap<>();
        findingsByScope.forEach((key, findings) -> {
            if (key != null && findings != null && !findings.isEmpty()) {
                next.put(key, List.copyOf(findings));
            }
        });
        this.memory = Map.copyOf(next);
    }

    /**
     * Sets the findings of one scope after its file was checked again: flagged when there are findings, unflagged when there
     * are none.
     */
    public synchronized void refreshMemory(String scopeKey, List<Finding> findings) {
        if (scopeKey == null) {
            return;
        }
        Map<String, List<Finding>> next = new LinkedHashMap<>(memory);
        if (findings == null || findings.isEmpty()) {
            next.remove(scopeKey);
        } else {
            next.put(scopeKey, List.copyOf(findings));
        }
        this.memory = Map.copyOf(next);
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
        return scopeKey != null && memory.containsKey(scopeKey);
    }

    /** The findings of a scope: line and category. Empty when the scope is not flagged or its findings are not known. */
    public synchronized List<Finding> memoryFindings(String scopeKey) {
        return scopeKey == null ? List.of() : memory.getOrDefault(scopeKey, List.of());
    }

    public synchronized Set<String> skillIds() {
        return skillIds;
    }

    public synchronized Set<String> memoryScopeKeys() {
        return Set.copyOf(memory.keySet());
    }
}
