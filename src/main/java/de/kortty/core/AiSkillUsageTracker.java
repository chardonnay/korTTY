package de.kortty.core;

import java.util.List;

/**
 * Exposes AI skills that were actually appended to recent prompts.
 */
public interface AiSkillUsageTracker {

    List<AiSkillPromptSupport.SkillUsage> drainSkillUsages();
}
