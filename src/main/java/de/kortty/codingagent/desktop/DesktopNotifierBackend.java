package de.kortty.codingagent.desktop;

/**
 * Operating-system backend for desktop notifications.
 *
 * <p>Implementations are package-private and constructed only on their own platform by
 * {@link DesktopNotifierBackends}. {@link #notify} is invoked on the notifier's daemon executor
 * ({@code kortty-desktop-notifier}), never on the JavaFX thread; AWT-based backends post their work
 * onto the AWT event queue from there.
 */
public interface DesktopNotifierBackend extends AutoCloseable {

    /** Whether this backend can deliver notifications (may turn false after a hard failure). */
    boolean isSupported();

    /**
     * Shows one notification.
     *
     * @param title the notification title (for example "claude needs a decision")
     * @param body the body text, already truncated by {@link DesktopNotifier}
     * @throws Exception when the notification could not be delivered
     */
    void notify(String title, String body) throws Exception;

    /** Releases native resources best-effort; never blocks. */
    @Override
    default void close() {
    }
}
