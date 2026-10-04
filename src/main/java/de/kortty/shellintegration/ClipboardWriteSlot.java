package de.kortty.shellintegration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.jetbrains.annotations.Nullable;

/**
 * The clipboard write ({@code OSC 52}) of a terminal pane on its way from the pane's emulator thread
 * to the UI thread.
 *
 * <p>vim or tmux may copy many times in a row, and only the last copy matters: it is what the
 * clipboard holds in the end. {@link #offer} therefore replaces a write still on its way and tells
 * the caller to schedule a {@link #take} on the UI thread only when none was, so a burst costs one
 * UI task and the clipboard ends up with the burst's last text. Unlike
 * {@link RemoteNotificationSlot}, where the first of a burst is shown, here the last one wins.
 *
 * <p>FX-free and lock-free; any thread.
 */
public final class ClipboardWriteSlot {

    private final AtomicReference<ShellIntegrationEvent.ClipboardWrite> pending = new AtomicReference<>();

    /**
     * Keeps {@code write} in place of any write still on its way.
     *
     * @return {@code true} when no write was on its way: the caller schedules a {@link #take} on the
     *         UI thread; {@code false} when one was, and the take already scheduled gets this one
     */
    public boolean offer(ShellIntegrationEvent.ClipboardWrite write) {
        return pending.getAndSet(Objects.requireNonNull(write, "write")) == null;
    }

    /** Takes the write on its way and frees the slot; {@code null} when there is none. */
    public @Nullable ShellIntegrationEvent.ClipboardWrite take() {
        return pending.getAndSet(null);
    }
}
