package de.kortty.codingagent.desktop;

/**
 * Operating-system backend for the application-icon badge.
 *
 * <p>Implementations are package-private, constructed only on their own platform by
 * {@link AppBadgeBackends}, and keep every asynchronous body inside {@code try … catch
 * (Throwable)}; synchronous failures may propagate and let {@link AppBadgeService} degrade to the
 * window-title fallback after repeated errors.
 */
public interface AppBadgeBackend extends AutoCloseable {

    /** Whether this backend can show a badge on this platform (may be evaluated lazily). */
    boolean isSupported();

    /**
     * Shows {@code blockedCount} on the application icon; {@code 0} clears the badge.
     *
     * @throws Exception when the badge could not be applied
     */
    void showCount(int blockedCount) throws Exception;

    /**
     * Asks the desktop for the user's attention (bouncing Dock icon, urgent launcher entry). No-op
     * by default.
     *
     * @throws Exception when the request could not be made
     */
    default void requestAttention() throws Exception {
    }

    /** Clears the badge best-effort; never blocks. */
    @Override
    default void close() {
    }
}
