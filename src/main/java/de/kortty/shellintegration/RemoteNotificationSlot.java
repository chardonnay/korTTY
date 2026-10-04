package de.kortty.shellintegration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * The one notification of a terminal pane on its way from the pane's emulator thread to the UI
 * thread.
 *
 * <p>A program can print {@code OSC 9} as fast as it can print text, so a request must stay cheap on
 * the emulator thread and must not queue a UI task of its own. {@link #offer} keeps the request only
 * while no other one is on its way and tells the caller to schedule a single {@link #take} on the UI
 * thread; until that runs, every further request is dropped. A burst therefore costs one UI task,
 * and its first request is the one shown; the policy on the UI thread then allows one desktop
 * notification per pane every 5 seconds ({@link TerminalNotificationPolicy.Kind#REMOTE}).
 *
 * <p>FX-free and lock-free; any thread.
 */
public final class RemoteNotificationSlot {

    private final AtomicReference<RemoteNotificationText> pending = new AtomicReference<>();

    /**
     * Keeps {@code notification} when the slot is free.
     *
     * @return {@code true} when it was kept: the caller schedules a {@link #take} on the UI thread;
     *         {@code false} when another notification is still on its way, and this one is dropped
     */
    public boolean offer(RemoteNotificationText notification) {
        return pending.compareAndSet(null, Objects.requireNonNull(notification, "notification"));
    }

    /** Takes the notification on its way and frees the slot; {@code null} when there is none. */
    public @Nullable RemoteNotificationText take() {
        return pending.getAndSet(null);
    }
}
