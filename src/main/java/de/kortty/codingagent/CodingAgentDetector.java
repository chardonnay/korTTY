package de.kortty.codingagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;

/**
 * Rule-driven {@link ScreenClassifier}: evaluates the effective rule set of the agent kind in priority
 * order (highest first, ties in file order) and returns the first full match. When no rule matches the
 * rule set's fallback state is reported with a null rule id; a kind without a rule set yields
 * {@link CodingAgentState#UNKNOWN}; {@link CodingAgentKind#UNKNOWN} yields {@link DetectionResult#NONE}.
 * Stateless and thread-safe; safe to call from the detector scheduler thread and from tests alike.
 */
public final class CodingAgentDetector implements ScreenClassifier {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentDetector.class);

    private final AgentRuleRepository repository;

    public CodingAgentDetector(AgentRuleRepository repository) {
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    @Override
    public DetectionResult classify(CodingAgentKind kind, ScreenSnapshot snapshot) {
        if (kind == null || kind == CodingAgentKind.UNKNOWN) {
            return DetectionResult.NONE;
        }
        Optional<AgentRuleSet> ruleSet = repository.ruleSetFor(kind);
        if (ruleSet.isEmpty()) {
            return DetectionResult.of(kind, CodingAgentState.UNKNOWN, null, null);
        }
        ScreenSnapshot screen = snapshot == null ? ScreenSnapshot.EMPTY : snapshot;
        for (AgentRule rule : ruleSet.get().rules()) {
            Optional<String> evidence;
            try {
                evidence = rule.match(screen);
            } catch (RuntimeException e) {
                // A pathological user-supplied regex must not take the whole classification down.
                logger.debug("Rule {} of {} failed to evaluate: {}", rule.id(), kind, e.toString());
                continue;
            }
            if (evidence.isPresent()) {
                return DetectionResult.of(kind, rule.state(), rule.id(), evidence.get());
            }
        }
        return DetectionResult.of(kind, ruleSet.get().fallbackState(), null, null);
    }
}
