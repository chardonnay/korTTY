package de.kortty.ui;

import java.util.List;

/**
 * What the user decides about a provider's newer version of an imported skill. Closing the update dialog without
 * a choice decides nothing.
 */
enum ExternalAiSkillUpdateDecision {

    /** Replace the skill's text and description with the provider version; only for an unchanged skill. */
    APPLY,

    /** Keep the skill and add the provider version as a new, deactivated local skill. */
    COPY,

    /** Keep the skill as it is and do not offer this revision again. */
    SKIP;

    /**
     * The decisions the update dialog offers. An unchanged skill can take the update; a skill the user or the skill
     * optimizer changed never can, so it is only offered a copy or a skip.
     */
    static List<ExternalAiSkillUpdateDecision> offeredFor(boolean locallyModified) {
        return locallyModified ? List.of(COPY, SKIP) : List.of(APPLY);
    }
}
