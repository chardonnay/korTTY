package de.kortty.control;

/**
 * The result of {@code agent.wait}, and of {@code agent.prompt} when {@code wait_until} is given.
 *
 * <p>Pure, any thread.
 *
 * @param state the wire state the agent reached
 * @param previousState the wire state it held when the wait started, or null when it was unknown
 * @param waitedMillis how long the wait actually took
 * @param agent the agent as it looked when the wait completed
 */
public record AgentWaitResult(String state, String previousState, long waitedMillis, AgentInfo agent) {
}
