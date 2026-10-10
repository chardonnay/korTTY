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

    /**
     * Whether a click on a notification of this backend can reach korTTY, so that
     * {@link #notify(String, String, Runnable)} runs its {@code onActivate}. The default is false:
     * the notification is shown, a click on it does whatever the operating system does.
     */
    default boolean supportsActivation() {
        return false;
    }

    /**
     * Shows one notification whose click runs {@code onActivate}, on whatever thread the operating
     * system reports the click on; {@link DesktopNotifier} hands it to the UI thread. A backend
     * without {@link #supportsActivation()} shows the plain notification and never runs it.
     *
     * @param onActivate what a click on the notification does, or {@code null} for nothing
     * @throws Exception when the notification could not be delivered
     */
    default void notify(String title, String body, Runnable onActivate) throws Exception {
        notify(title, body);
    }

    /** Releases native resources best-effort; never blocks. */
    @Override
    default void close() {
    }
}
