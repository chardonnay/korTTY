package de.kortty.stats;

import de.kortty.model.AiTokenizerType;

/**
 * One skill that was sent with a successful request: its id and name, whether auto-detection chose it (rather than
 * a connection assignment or an explicit pin), where it was sent, and the size of its text when it was sent.
 *
 * @param fingerprint          the {@link SkillSize#fingerprint()} of the skill at the time it was sent
 * @param learnedRulesCounted  whether {@code tokens} counts the learned rules too; a size without them is a sent
 *                             count, not a snapshot of the skill, and is not recorded as one
 */
public record SkillUsageEvent(
    String skillId,
    String skillName,
    boolean automatic,
    SkillSurface surface,
    int tokens,
    AiTokenizerType tokenizer,
    String fingerprint,
    boolean learnedRulesCounted) {

    /** An event whose size counts the learned rules, the complete skill. */
    public SkillUsageEvent(
        String skillId,
        String skillName,
        boolean automatic,
        SkillSurface surface,
        int tokens,
        AiTokenizerType tokenizer,
        String fingerprint) {
        this(skillId, skillName, automatic, surface, tokens, tokenizer, fingerprint, true);
    }
}
