package de.kortty.core;

import de.kortty.memory.MemoryScope;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiSkill;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The one-time scan after the settings load: every AI skill (its text and learned rules) and every memory file
 * that korTTY would read, the global memory and the memory of each given connection. The result goes into an
 * {@link AiSecretScanRegistry}. The scan only reads; a finding never changes a file, it only adds a badge. It
 * logs counts, never content.
 */
public final class AiSecretStartupScan {

    private static final Logger logger = LoggerFactory.getLogger(AiSecretStartupScan.class);

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

        Set<String> flaggedScopes = new LinkedHashSet<>();
        if (memory != null) {
            for (MemoryScope scope : scopes(connectionIds)) {
                try {
                    if (!memory.secretFindings(scope).isEmpty()) {
                        flaggedScopes.add(scope.key());
                    }
                } catch (IOException | RuntimeException e) {
                    logger.warn("Memory file {} could not be scanned for secrets", scope.key(), e);
                }
            }
        }
        registry.replaceMemoryScopes(flaggedScopes);
        logger.info("Secret scan: {} AI skill(s) and {} memory file(s) flagged", flaggedSkills.size(), flaggedScopes.size());
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
