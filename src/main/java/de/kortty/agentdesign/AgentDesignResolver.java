package de.kortty.agentdesign;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides which design combination a terminal pane shows an agent run in: the connection's own choice,
 * else the global default, else {@link AgentDesignPresets#STANDARD}. An id that names no combination
 * (deleted, or from another machine) is skipped like an unset one, and a module this version cannot show
 * (a hand-edited or newer settings file) is replaced by the standard combination's module for that role.
 */
public final class AgentDesignResolver {

    /** Where the resolved combination came from. */
    public enum Source {
        CONNECTION,
        GLOBAL_DEFAULT,
        BUILT_IN
    }

    /**
     * @param combination the combination to show, with every module usable
     * @param source      where it came from
     * @param substituted the roles whose stored module had to be replaced
     */
    public record Resolution(AgentDesignCombination combination, Source source, List<AgentRole> substituted) {
    }

    private AgentDesignResolver() {
    }

    public static Resolution resolve(String connectionDesignId, String globalDefaultId, List<AgentDesignCombination> custom) {
        Optional<AgentDesignCombination> connection = find(connectionDesignId, custom);
        if (connection.isPresent()) {
            return usable(connection.get(), Source.CONNECTION);
        }
        Optional<AgentDesignCombination> global = find(globalDefaultId, custom);
        if (global.isPresent()) {
            return usable(global.get(), Source.GLOBAL_DEFAULT);
        }
        return usable(AgentDesignPresets.STANDARD, Source.BUILT_IN);
    }

    /** The built-in or own combination with {@code id}, if there is one. */
    public static Optional<AgentDesignCombination> find(String id, List<AgentDesignCombination> custom) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String trimmed = id.trim();
        Optional<AgentDesignCombination> preset = AgentDesignPresets.find(trimmed);
        if (preset.isPresent()) {
            return preset;
        }
        if (custom == null) {
            return Optional.empty();
        }
        return custom.stream().filter(entry -> entry != null && entry.id().equals(trimmed)).findFirst();
    }

    /** {@code combination} with every module this version cannot show replaced by the standard one. */
    public static Resolution usable(AgentDesignCombination combination, Source source) {
        AgentDesignCombination result = combination;
        List<AgentRole> substituted = new ArrayList<>();
        for (AgentRole role : AgentRole.values()) {
            if (!AgentModuleCatalog.isUsable(result.moduleFor(role), role)) {
                result = result.with(role, AgentDesignPresets.STANDARD.moduleFor(role));
                substituted.add(role);
            }
        }
        return new Resolution(result, source, List.copyOf(substituted));
    }
}
