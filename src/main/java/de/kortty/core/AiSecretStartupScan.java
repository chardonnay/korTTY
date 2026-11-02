package de.kortty.core;

import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The scan after the settings load, and again after a backup restore or a vault unlock: every AI skill (its text and learned
 * rules) and every memory file that korTTY would read, the global memory and the memory of each given connection. The result
 * goes into an {@link AiSecretScanRegistry}. The scan only reads; a finding never changes a file, it only adds a flag. Once a
 * memory store is scanned, every write to it checks the written file again, so a file saved clean loses its flag. The log
 * holds counts, never content.
 */
public final class AiSecretStartupScan {

    private static final Logger logger = LoggerFactory.getLogger(AiSecretStartupScan.class);
    /** The stores that have a write listener, each with the registry its writes update. Guarded by its own monitor. */
    private static final Map<MemoryStore, AiSecretScanRegistry> WATCHED = new IdentityHashMap<>();

    private AiSecretStartupScan() {
    }

    /**
     * Scans {@code skills} and the memory files of the global scope and of {@code connectionIds}, then replaces the
     * registry's findings. A file that cannot be read is skipped with a warning.
     */
    public static void run(List<AiSkill> skills, Collection<String> connectionIds, MemoryStore memory,
                           AiSecretScanRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        Set<String> flaggedSkills = new LinkedHashSet<>();
        for (AiSkill skill : skills != null ? skills : List.<AiSkill>of()) {
            if (skill != null && skill.getId() != null && !AiSkillSecretCheck.findings(skill).isEmpty()) {
                flaggedSkills.add(skill.getId());
            }
        }
        registry.replaceSkills(flaggedSkills);

        Map<String, List<AiSecretGuard.Finding>> flaggedScopes = new LinkedHashMap<>();
        if (memory != null) {
            watch(memory, registry);
            for (MemoryScope scope : scopes(connectionIds)) {
                try {
                    List<AiSecretGuard.Finding> findings = memory.secretFindings(scope);
                    if (!findings.isEmpty()) {
                        flaggedScopes.put(scope.key(), findings);
                    }
                } catch (IOException | RuntimeException e) {
                    logger.warn("Memory file {} could not be scanned for secrets", scope.key(), e);
                }
            }
        }
        registry.replaceMemoryFindings(flaggedScopes);
        logger.info("Secret scan: {} AI skill(s) and {} memory file(s) flagged", flaggedSkills.size(), flaggedScopes.size());
    }

    /** Listens to the writes of {@code memory} once; a later scan with another registry hands the updates to that one. */
    private static void watch(MemoryStore memory, AiSecretScanRegistry registry) {
        synchronized (WATCHED) {
            if (WATCHED.put(memory, registry) == null) {
                memory.addChangeListener(scope -> recheck(memory, scope));
            }
        }
    }

    /**
     * Checks a memory file again after a write. A write is refused while the new text holds a secret, so a file that was
     * written has normally lost its flag here; the check keeps the registry in step with the file either way.
     */
    private static void recheck(MemoryStore memory, MemoryScope scope) {
        AiSecretScanRegistry registry;
        synchronized (WATCHED) {
            registry = WATCHED.get(memory);
        }
        if (registry == null) {
            return;
        }
        try {
            registry.refreshMemory(scope.key(), memory.secretFindings(scope));
        } catch (IOException | RuntimeException e) {
            logger.warn("Memory file {} could not be checked for secrets after a change", scope.key(), e);
        }
    }

    private static Set<MemoryScope> scopes(Collection<String> connectionIds) {
        Set<MemoryScope> scopes = new LinkedHashSet<>();
        scopes.add(MemoryScope.global());
        for (String id : connectionIds != null ? connectionIds : List.<String>of()) {
            if (id != null && !id.isBlank()) {
                scopes.add(MemoryScope.connection(id));
            }
        }
        return scopes;
    }
}
