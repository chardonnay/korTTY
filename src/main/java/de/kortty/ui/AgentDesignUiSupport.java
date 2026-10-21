package de.kortty.ui;

import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.agentdesign.AgentDesignPresets;
import de.kortty.agentdesign.AgentDesignResolver;
import de.kortty.model.GlobalSettings;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Names and choice lists of KI-Agent design combinations for the design dialog and the connection editor. */
final class AgentDesignUiSupport {

    /**
     * One entry of the connection editor's design list.
     *
     * @param storedId    what the connection stores; {@code null} for "use the default"
     * @param label       what the list shows
     * @param unavailable the stored id names no combination any more
     */
    record Choice(String storedId, String label, boolean unavailable) {
        @Override
        public String toString() {
            return label;
        }
    }

    private AgentDesignUiSupport() {
    }

    /** The name a combination is shown with. */
    static String displayName(AgentDesignCombination combination) {
        if (combination == null) {
            return "";
        }
        if (combination.builtIn()) {
            return I18n.get(AgentDesignPresets.nameKey(combination.id()));
        }
        return combination.name().isBlank() ? I18n.get("aiAgentDesign.newName") : combination.name();
    }

    /** The built-in combinations followed by the user's own. */
    static List<AgentDesignCombination> allCombinations(GlobalSettings settings) {
        List<AgentDesignCombination> all = new ArrayList<>(AgentDesignPresets.all());
        if (settings != null) {
            for (AgentDesignCombination own : settings.getAiAgentDesignCombinations()) {
                if (AgentDesignPresets.find(own.id()).isEmpty()) {
                    all.add(own);
                }
            }
        }
        return all;
    }

    /** The combination used when a connection picks none. */
    static AgentDesignCombination defaultCombination(GlobalSettings settings) {
        return AgentDesignResolver.resolve(null,
            settings != null ? settings.getAiAgentDesignDefaultId() : null,
            settings != null ? settings.getAiAgentDesignCombinations() : List.of()).combination();
    }

    static String newCustomId() {
        return "custom-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /**
     * The connection editor's list: "use the default (name)" first, then every combination, plus an
     * entry for a stored id that names no combination any more, so opening and saving keeps it.
     */
    static List<Choice> connectionChoices(GlobalSettings settings, String storedId) {
        List<Choice> choices = new ArrayList<>();
        choices.add(new Choice(null, I18n.get("connEdit.aiAgentDesign.default",
            displayName(defaultCombination(settings))), false));
        for (AgentDesignCombination combination : allCombinations(settings)) {
            choices.add(new Choice(combination.id(), displayName(combination), false));
        }
        if (storedId != null && !storedId.isBlank()
            && AgentDesignResolver.find(storedId, settings != null ? settings.getAiAgentDesignCombinations() : List.of()).isEmpty()) {
            choices.add(new Choice(storedId.trim(), I18n.get("connEdit.aiAgentDesign.unavailable"), true));
        }
        return choices;
    }

    /** The entry for what the connection stores. */
    static Choice selectedChoice(List<Choice> choices, String storedId) {
        String trimmed = storedId != null && !storedId.isBlank() ? storedId.trim() : null;
        for (Choice choice : choices) {
            if (trimmed == null ? choice.storedId() == null : trimmed.equals(choice.storedId())) {
                return choice;
            }
        }
        return choices.get(0);
    }
}
