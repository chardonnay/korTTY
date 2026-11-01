package de.kortty.memory;

import de.kortty.core.AiSkillMarkdownCodec;
import de.kortty.core.AiSkillRelevanceSelector;
import de.kortty.core.BuiltinAiSkillCatalog;
import de.kortty.model.AiSkill;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Suggests where a confirmed rule belongs. The rules run locally, and the first one that fits wins:
 * <ol>
 *   <li>the rule names the active connection or its host: the memory of that connection;</li>
 *   <li>the topic is a built-in topic: a user skill tagged with that topic (it overrides the built-in in the prompt),
 *       otherwise the built-in skill itself; for any other topic, or for the rule text when there is no topic, a user
 *       skill whose local relevance score reaches {@link AiSkillRelevanceSelector#LOCAL_MATCH_THRESHOLD};</li>
 *   <li>the topic is a known language or tool and no stored skill carries it: a new korTTY skill;</li>
 *   <li>otherwise the global memory.</li>
 * </ol>
 * Skills are chosen only when the policy allows skill creation, and memory targets only when the memory is writable.
 * Disabled, hidden and external skills are never chosen: a rule saved to a disabled or hidden skill would not apply,
 * and a provider update could overwrite an external one. With nothing allowed, the suggestion is
 * {@link RuleTarget.None}.
 */
public final class RuleRouter {

    /**
     * The target for {@code proposal}. {@code skills} are the stored skills and {@code catalog} the built-in catalog;
     * neither is changed.
     */
    public RuleTarget suggest(RuleProposal proposal, List<AiSkill> skills, BuiltinAiSkillCatalog catalog,
                              RuleTargetPolicy policy) {
        Context context = new Context(proposal, skills, catalog);
        if (policy.memoryWritable() && namesConnection(proposal)) {
            return RuleTarget.connection(proposal.connectionId());
        }
        if (policy.skillCreationAllowed()) {
            Optional<RuleTarget> skill = matchingSkill(context);
            if (skill.isPresent()) {
                return skill.get();
            }
            Optional<RuleTarget> newSkill = newSkillFor(context);
            if (newSkill.isPresent()) {
                return newSkill.get();
            }
        }
        return policy.memoryWritable() ? RuleTarget.global() : RuleTarget.none();
    }

    /**
     * Every target the user may pick for {@code proposal}, for the dropdown of the rule card: the memory targets when
     * the memory is writable, then, when skill creation is allowed, the eligible user skills, the built-in skill of
     * the topic and a new skill for a known topic. Empty when nothing is allowed.
     */
    public List<RuleTarget> allowedTargets(RuleProposal proposal, List<AiSkill> skills,
                                           BuiltinAiSkillCatalog catalog, RuleTargetPolicy policy) {
        Context context = new Context(proposal, skills, catalog);
        List<RuleTarget> targets = new ArrayList<>();
        if (policy.memoryWritable()) {
            if (proposal.connectionId() != null) {
                targets.add(RuleTarget.connection(proposal.connectionId()));
            }
            targets.add(RuleTarget.global());
        }
        if (policy.skillCreationAllowed()) {
            for (AiSkill skill : context.userSkills()) {
                targets.add(RuleTarget.skill(skill.getId()));
            }
            Optional<AiSkill> builtin = context.topic().flatMap(context::eligibleBuiltinFor);
            if (builtin.isPresent() && !targets.contains(RuleTarget.skill(builtin.get().getId()))) {
                targets.add(RuleTarget.skill(builtin.get().getId()));
            }
            newSkillFor(context).ifPresent(targets::add);
        }
        return List.copyOf(targets);
    }

    /** Whether the rule names the active connection by its display name or host. Names shorter than three characters do not count. */
    static boolean namesConnection(RuleProposal proposal) {
        if (proposal.connectionId() == null) {
            return false;
        }
        String text = proposal.text().toLowerCase(Locale.ROOT);
        return mentions(text, proposal.connectionName()) || mentions(text, proposal.connectionHost());
    }

    private static boolean mentions(String lowerCaseText, String name) {
        return name != null && name.length() >= 3 && lowerCaseText.contains(name.toLowerCase(Locale.ROOT));
    }

    private static Optional<RuleTarget> matchingSkill(Context context) {
        Optional<String> topic = context.topic();
        if (topic.isPresent() && context.isBuiltinTopic(topic.get())) {
            Optional<AiSkill> override = context.userSkills().stream()
                .filter(skill -> hasTag(skill, topic.get()))
                .findFirst();
            if (override.isPresent()) {
                return Optional.of(RuleTarget.skill(override.get().getId()));
            }
            return context.eligibleBuiltinFor(topic.get()).map(skill -> RuleTarget.skill(skill.getId()));
        }
        String relevance = topic.orElse(context.proposal().text());
        AiSkill best = null;
        int bestScore = 0;
        for (AiSkill skill : context.userSkills()) {
            int score = AiSkillRelevanceSelector.localMatchScore(skill, relevance);
            if (score >= AiSkillRelevanceSelector.LOCAL_MATCH_THRESHOLD && score > bestScore) {
                best = skill;
                bestScore = score;
            }
        }
        return best == null ? Optional.empty() : Optional.of(RuleTarget.skill(best.getId()));
    }

    /** A new skill for a known language or tool that no stored skill carries, a disabled one included. */
    private static Optional<RuleTarget> newSkillFor(Context context) {
        Optional<String> topic = context.topic();
        if (topic.isEmpty() || context.isBuiltinTopic(topic.get())) {
            return Optional.empty();
        }
        String normalized = topic.get();
        if (!RuleTopic.isLanguageOrTool(normalized, context.builtinTopics())) {
            return Optional.empty();
        }
        if (context.skills().stream().anyMatch(skill -> hasTag(skill, normalized))) {
            return Optional.empty();
        }
        return Optional.of(RuleTarget.newSkill(normalized, RuleTopic.skillName(normalized)));
    }

    /** Whether the skill carries {@code topic} as a tag, ignoring case. */
    static boolean hasTag(AiSkill skill, String topic) {
        return skill.getTags() != null && skill.getTags().stream().anyMatch(topic::equalsIgnoreCase);
    }

    private static boolean eligible(AiSkill skill) {
        return skill.isEnabled() && !skill.isHidden();
    }

    /** The stored skills and the built-in topics of one suggestion, with the lookups the rules share. */
    private static final class Context {

        private final RuleProposal proposal;
        private final List<AiSkill> skills;
        private final Map<String, String> builtinIdByTopic = new HashMap<>();
        private final List<String> builtinTopics = new ArrayList<>();

        Context(RuleProposal proposal, List<AiSkill> skills, BuiltinAiSkillCatalog catalog) {
            this.proposal = Objects.requireNonNull(proposal, "proposal");
            this.skills = skills == null ? List.of()
                : skills.stream().filter(Objects::nonNull).toList();
            if (catalog != null) {
                for (AiSkillMarkdownCodec.BundledAiSkill entry : catalog.entries()) {
                    String builtinId = entry.skill().getBuiltinId();
                    for (String topic : entry.skill().getBuiltinTopics()) {
                        String normalized = RuleTopic.normalize(topic);
                        if (normalized != null) {
                            builtinIdByTopic.putIfAbsent(normalized, builtinId);
                            builtinTopics.add(normalized);
                        }
                    }
                }
            }
        }

        RuleProposal proposal() {
            return proposal;
        }

        List<AiSkill> skills() {
            return skills;
        }

        List<String> builtinTopics() {
            return builtinTopics;
        }

        Optional<String> topic() {
            return RuleTopic.of(proposal, builtinTopics);
        }

        boolean isBuiltinTopic(String topic) {
            return builtinIdByTopic.containsKey(topic);
        }

        /** The stored built-in skill that carries {@code topic}, when it is enabled and visible. */
        Optional<AiSkill> eligibleBuiltinFor(String topic) {
            String builtinId = builtinIdByTopic.get(topic);
            if (builtinId == null) {
                return Optional.empty();
            }
            return skills.stream()
                .filter(skill -> skill.isBuiltin() && builtinId.equals(skill.getBuiltinId()))
                .filter(RuleRouter::eligible)
                .findFirst();
        }

        /** The user skills a rule may be added to, in stored order: not built-in, not external, enabled and visible. */
        List<AiSkill> userSkills() {
            return skills.stream()
                .filter(skill -> !skill.isBuiltin())
                .filter(skill -> !skill.isExternal())
                .filter(RuleRouter::eligible)
                .toList();
        }
    }
}
