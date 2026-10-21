package de.kortty.agentdesign;

/**
 * The parts a KI-Agent design combination is assembled from. A combination picks exactly one module
 * per role, so any two combinations fit together on screen.
 */
public enum AgentRole {
    /** Where the steps of a run are listed (the activity feed). */
    HISTORY,
    /** A compact indicator of where the run stands, shown next to the history. */
    STATUS,
    /** Where the user answers approval and password requests. */
    APPROVAL,
    /** How the modules are placed around the terminal. */
    ARRANGEMENT;

    /** The i18n key of the role's label in the KI-Agent Design dialog. */
    public String labelKey() {
        return "aiAgentDesign.role." + name().toLowerCase(java.util.Locale.ROOT);
    }
}
