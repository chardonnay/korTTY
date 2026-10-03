package de.kortty.ui;

import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.Objects;

/**
 * Marks input that broadcast mode mirrors from the pane the user types in into the other panes.
 *
 * <p>{@link MirroredInputWriter} performs every mirrored write inside {@link #run}, on the thread
 * that writes. The decorators of the target pane's connector run on that same thread, so they can
 * call {@link #active()} to tell mirrored bytes from input typed into the pane itself, for example
 * so an AI-agent shortcut fires only in the pane it was typed in.
 *
 * <p>Pure, any thread. The marker is thread-confined and never outlives the write it wraps.
 */
public final class MirroredInput {

    private static final ThreadLocal<Boolean> ACTIVE = new ThreadLocal<>();

    private MirroredInput() {
    }

    /** True while the current thread performs a mirrored write. */
    public static boolean active() {
        return Boolean.TRUE.equals(ACTIVE.get());
    }

    /**
     * Runs {@code write} with {@link #active()} true on the current thread, and restores the previous
     * state afterwards, also when {@code write} throws.
     */
    public static void run(@NotNull Write write) throws IOException {
        Objects.requireNonNull(write, "write");
        boolean wasActive = active();
        ACTIVE.set(Boolean.TRUE);
        try {
            write.run();
        } finally {
            if (!wasActive) {
                ACTIVE.remove();
            }
        }
    }

    /** A write to a pane's connector. */
    @FunctionalInterface
    public interface Write {
        void run() throws IOException;
    }
}
