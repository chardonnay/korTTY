package de.kortty.control;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;

/**
 * The enabled-state gate of the control API: the single source of truth, evaluated at start-up, from
 * the Settings post-save hook, on every {@code accept()} and again on every dispatch.
 *
 * <p>Pure, any thread.
 *
 * <p>The gate answers a {@link Verdict}, not a boolean, because "the API is not serving" is three
 * different facts to the client on the other end of the socket: an administrator's decision it must
 * not retry and must report, the user's own switch the user can simply turn on, and a korTTY that is
 * still starting up, where the very same request will succeed a moment later. A boolean collapses all
 * three into one refusal and makes two of the three wire codes unreachable.
 *
 * <p>The API runs only when <strong>both</strong> legs say yes — enterprise policy allows the
 * {@code control-api} feature and the user has ticked the (default-off) setting. Every unknown leg is
 * treated as a refusal, so a half-wired build never opens a listener.
 */
public final class ControlApiGate {

    private ControlApiGate() {
    }

    /**
     * What the gate says right now, and which refusal that is on the wire.
     *
     * <p>Each arm carries its own {@link ControlErrorCode} so no caller has to re-derive the mapping,
     * and its own English message: these are wire-protocol strings, not user-facing UI text, and they
     * name the cause without ever naming the setting, the policy file or the rule that produced it.
     */
    public enum Verdict {

        /** Both legs say yes; the listener may run and requests are served. */
        OPEN(null, "The korTTY control API is available"),

        /** The user has not ticked the (default-off) control-API setting. */
        DISABLED_BY_SETTING(ControlErrorCode.CONTROL_API_DISABLED,
            "The korTTY control API is switched off"),

        /** Enterprise policy denies the {@code control-api} feature. */
        BLOCKED_BY_POLICY(ControlErrorCode.BLOCKED_BY_POLICY,
            "The korTTY control API is denied by enterprise policy"),

        /** One of the two legs is not resolved yet, i.e. korTTY is still starting up. */
        NOT_READY(ControlErrorCode.NOT_READY,
            "The korTTY control API is not ready yet");

        private final ControlErrorCode errorCode;

        private final String message;

        Verdict(ControlErrorCode errorCode, String message) {
            this.errorCode = errorCode;
            this.message = message;
        }

        /** Whether the API may serve; the only arm that answers true is {@link #OPEN}. */
        public boolean isOpen() {
            return this == OPEN;
        }

        /**
         * The refusal this verdict raises, or null for {@link #OPEN}, which raises nothing.
         *
         * <p>Callers reach this only after {@link #isOpen()} answered false, which is exactly when it
         * is non-null.
         */
        public ControlErrorCode errorCode() {
            return errorCode;
        }

        /** The English wire message that names the cause without leaking configuration detail. */
        public String message() {
            return message;
        }
    }

    /**
     * The verdict for the live settings and the resolved policy.
     *
     * <p>A null argument is an unresolved leg, not a refusal to serve for ever: the settings and the
     * policy are both resolved during start-up, and a call that lands before either is ready answers
     * {@link Verdict#NOT_READY} — fail-closed, so no socket is opened, and retryable, so the client
     * learns to come back rather than to give up.
     *
     * @param settings the live global settings, or null before they are loaded
     * @param policy the resolved enterprise policy, or null before it is resolved
     */
    public static Verdict verdict(GlobalSettings settings, EffectivePolicy policy) {
        return evaluate(policy == null ? null : policy.controlApiAllowed(),
            settings == null ? null : settings.isControlApiEnabled());
    }

    /**
     * The truth table itself, kept separate from the two accessors so it can be exercised
     * exhaustively — including both "leg unknown" cases, which no live object can produce.
     *
     * <p>The order is deliberate and is the whole point of the verdict:
     *
     * <ol>
     *   <li>a policy that is known and denies wins outright. It outranks the setting because
     *       {@code PolicyClamp} forces the setting false whenever policy denies, so "the setting is
     *       off" is a <em>consequence</em> of the policy and not an independent user choice;
     *       reporting the policy is the honest answer, and the only one the client can act on
     *       (an administrator's decision must not be retried and must be shown to the user);</li>
     *   <li>a setting that is known and off is the user's own switch, which the user can turn on;</li>
     *   <li>either leg still unknown means korTTY is starting up — fail-closed and retryable;</li>
     *   <li>otherwise both legs say yes.</li>
     * </ol>
     *
     * @param policyAllowed the policy leg; null when it could not be determined
     * @param settingsEnabled the setting leg; null when it could not be determined
     */
    static Verdict evaluate(Boolean policyAllowed, Boolean settingsEnabled) {
        if (Boolean.FALSE.equals(policyAllowed)) {
            return Verdict.BLOCKED_BY_POLICY;
        }
        if (Boolean.FALSE.equals(settingsEnabled)) {
            return Verdict.DISABLED_BY_SETTING;
        }
        if (policyAllowed == null || settingsEnabled == null) {
            return Verdict.NOT_READY;
        }
        return Verdict.OPEN;
    }
}
