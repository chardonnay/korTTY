package de.kortty.memory;

import de.kortty.model.AiSkillLearnedRule;

/** Where a rule the user confirmed came from. The value is the source string the memory fence and a skill rule record. */
public enum RuleSource {

    CHAT(AiSkillLearnedRule.SOURCE_CHAT),
    AGENT(AiSkillLearnedRule.SOURCE_AGENT),
    TRIAGE(AiSkillLearnedRule.SOURCE_TRIAGE),
    MANUAL(AiSkillLearnedRule.SOURCE_MANUAL);

    private final String value;

    RuleSource(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
