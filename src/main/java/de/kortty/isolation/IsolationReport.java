package de.kortty.isolation;

/**
 * What a running terminal session got: the state its tab shows, what was asked for, and a few words on
 * how (or why not).
 *
 * @param state     the isolation the session actually has
 * @param requested the level that was asked for
 * @param backendId the sandbox backend in use ({@code sandbox-exec}, {@code bubblewrap}), or null
 * @param detail    why a sandbox is not active ({@link IsolationState#DEGRADED}), or null
 */
public record IsolationReport(IsolationState state, IsolationLevel requested, String backendId, String detail) {

    /** A session that is not isolated and was not asked to be. */
    public static final IsolationReport NONE =
        new IsolationReport(IsolationState.NONE, IsolationLevel.NONE, null, null);

    public IsolationReport {
        state = state != null ? state : IsolationState.NONE;
        requested = requested != null ? requested : IsolationLevel.NONE;
    }
}
