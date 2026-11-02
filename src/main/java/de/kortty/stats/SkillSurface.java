package de.kortty.stats;

/**
 * Where a skill was sent. {@link #CHAT} is an interactive chat request, {@link #AGENT} an autonomous prompt
 * (agent turns, planning, swarm and job runs), and {@link #WORKFLOW} the workflow script generator.
 */
public enum SkillSurface {
    CHAT,
    AGENT,
    WORKFLOW
}
