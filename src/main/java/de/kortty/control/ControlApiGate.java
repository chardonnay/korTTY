package de.kortty.control;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;

/**
 * The pure enabled-state gate of the control API: the single source of truth, evaluated at start-up,
 * from the Settings post-save hook, on every {@code accept()} and again on every dispatch.
 *
 * <p>Pure, any thread.
 *
 * <p>The API runs only when <strong>both</strong> legs say yes — enterprise policy allows the
 * {@code control-api} feature and the user has ticked the (default-off) setting. Every unknown leg is
 * treated as a refusal, so a half-wired build never opens a listener.
 */
public final class ControlApiGate {

    private ControlApiGate() {
    }

    /**
     * Whether the control-API server should be listening right now.
     *
     * <p>A null argument is a refusal: the gate is fail-closed in both legs, because the settings and
     * the policy are both resolved during start-up and a call that lands before either is ready must
     * not open a socket.
     *
     * @param settings the live global settings, or null before they are loaded
     * @param policy the resolved enterprise policy, or null before it is resolved
     */
    public static boolean shouldRun(GlobalSettings settings, EffectivePolicy policy) {
        return evaluate(policy == null ? null : policy.controlApiAllowed(),
            settings == null ? null : settings.isControlApiEnabled());
    }

    /**
     * The truth table itself, kept separate from the two accessors so it can be exercised
     * exhaustively — including both "leg unknown" cases, which no live object can produce.
     *
     * @param policyAllowed the policy leg; null when it could not be determined
     * @param settingsEnabled the setting leg; null when it could not be determined
     */
    static boolean evaluate(Boolean policyAllowed, Boolean settingsEnabled) {
        return Boolean.TRUE.equals(policyAllowed) && Boolean.TRUE.equals(settingsEnabled);
    }
}
