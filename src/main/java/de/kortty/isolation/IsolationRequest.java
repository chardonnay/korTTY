package de.kortty.isolation;

/**
 * What a connector is asked to give its session, worked out by {@link IsolationSettings} before it
 * connects.
 *
 * @param level   the level asked for
 * @param minimum the least the organization's policy accepts, or null when it sets none
 */
public record IsolationRequest(IsolationLevel level, IsolationLevel minimum) {

    /** No isolation, nothing enforced: how every connector behaved before. */
    public static final IsolationRequest NONE = new IsolationRequest(IsolationLevel.NONE, null);

    public IsolationRequest {
        level = level != null ? level : IsolationLevel.NONE;
    }

    /**
     * Whether the session must not open with less than {@link #level()}: the policy's minimum is that
     * level, so falling back (say, from a sandbox to a plain process) would go below it.
     */
    public boolean enforced() {
        return minimum != null && level != IsolationLevel.NONE && minimum.atLeast(level);
    }

    /** The request for a resolved level under the policy's {@code minimum}. */
    public static IsolationRequest of(IsolationSettings.Resolution resolution, IsolationLevel minimum) {
        return resolution == null ? NONE : new IsolationRequest(resolution.level(), minimum);
    }
}
