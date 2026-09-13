package de.kortty.codingagent;

/**
 * One ignored or rejected rule file, reported by {@link AgentRuleRepository#problems()}.
 * {@code location} is the classpath resource name of a bundled file or the absolute path of a user
 * override; {@code message} is a one-line human-readable reason.
 */
public record RuleLoadProblem(String location, String message) {
}
