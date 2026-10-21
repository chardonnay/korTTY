package de.kortty.agentdesign;

import java.util.List;
import java.util.Optional;

/** The design combinations that ship with korTTY. */
public final class AgentDesignPresets {

    /** Id of the combination used when neither the connection nor the global settings pick one. */
    public static final String STANDARD_ID = "standard";
    public static final String CLASSIC_ID = "classic";

    /** In-terminal blocks with the metro-line bar below the terminal. */
    public static final AgentDesignCombination STANDARD = new AgentDesignCombination(
        STANDARD_ID,
        "",
        true,
        AgentModuleCatalog.INLINE_BLOCKS,
        AgentModuleCatalog.METRO_BAR,
        AgentModuleCatalog.APPROVAL_IN_HISTORY,
        AgentModuleCatalog.FIXED);

    /** The activity panel below the terminal, as korTTY showed it before designs existed. */
    public static final AgentDesignCombination CLASSIC = new AgentDesignCombination(
        CLASSIC_ID,
        "",
        true,
        AgentModuleCatalog.CLASSIC,
        AgentModuleCatalog.NO_STATUS,
        AgentModuleCatalog.APPROVAL_IN_HISTORY,
        AgentModuleCatalog.FIXED);

    private static final List<AgentDesignCombination> ALL = List.of(STANDARD, CLASSIC);

    private AgentDesignPresets() {
    }

    public static List<AgentDesignCombination> all() {
        return ALL;
    }

    public static Optional<AgentDesignCombination> find(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String trimmed = id.trim();
        return ALL.stream().filter(preset -> preset.id().equals(trimmed)).findFirst();
    }

    /** The i18n key of a built-in combination's name. */
    public static String nameKey(String id) {
        return "aiAgentDesign.preset." + id;
    }
}
