package de.kortty.policy;

/**
 * Thrown by low-level guards (e.g. {@code SessionManager.createSession}) when an action is blocked
 * by the enterprise policy. UI layers should pre-check via {@link PolicyManager#effective()} and
 * show a proper dialog; this exception is the defense-in-depth backstop for code paths that skip
 * the UI gate.
 */
public class PolicyRestrictionException extends RuntimeException {

    public PolicyRestrictionException(String message) {
        super(message);
    }
}
