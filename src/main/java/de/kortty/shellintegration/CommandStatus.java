package de.kortty.shellintegration;

import de.kortty.shellintegration.CommandBlockStore.CommandBlock;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.Objects;

/**
 * How a command the shell marked with {@code OSC 133} stands, as the command-timestamp gutter shows
 * it: still running, or finished with its exit status and the real time from its {@code C} mark (the
 * command was submitted) to its {@code D} mark (it finished).
 *
 * <p>Every state has its own glyph besides its colour, so it does not depend on telling green from
 * red: {@code ✓} for exit status 0, {@code ✗} for any other, {@code …} while the command runs. A
 * command whose {@code D} mark carried no exit status shows no glyph, only its runtime.
 *
 * @param kind             what the glyph and colour say
 * @param exitStatus       the status {@code D} reported, or {@code null}
 * @param outputStartNanos {@link System#nanoTime()} at {@code C}
 * @param endNanos         {@link System#nanoTime()} at {@code D}, or 0 while the command runs
 */
public record CommandStatus(Kind kind, @Nullable Integer exitStatus, long outputStartNanos, long endNanos) {

    /** The states the gutter tells apart. */
    public enum Kind {
        /** Submitted ({@code C}) and not finished yet. */
        RUNNING("…"),
        /** Finished with exit status 0. */
        SUCCEEDED("✓"),
        /** Finished with any other exit status. */
        FAILED("✗"),
        /** Finished, but the shell reported no exit status. */
        NO_STATUS("");

        private final String glyph;

        Kind(String glyph) {
            this.glyph = glyph;
        }

        /** The character the gutter draws for this state; empty for {@link #NO_STATUS}. */
        public String glyph() {
            return glyph;
        }
    }

    public CommandStatus {
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * The status of {@code block}: finished when its {@code D} mark arrived, running while it was
     * submitted and nothing closed it, and {@code null} for a prompt where no command ran or a
     * command the next prompt closed without a {@code D} mark.
     */
    public static @Nullable CommandStatus of(CommandBlock block) {
        if (block.finished()) {
            Integer exit = block.exitStatus();
            Kind kind = exit == null ? Kind.NO_STATUS : exit == 0 ? Kind.SUCCEEDED : Kind.FAILED;
            return new CommandStatus(kind, exit, block.outputStartNanos(), block.endNanos());
        }
        if (block.running()) {
            return new CommandStatus(Kind.RUNNING, null, block.outputStartNanos(), 0L);
        }
        return null;
    }

    /** Whether the command still runs. */
    public boolean running() {
        return kind == Kind.RUNNING;
    }

    /** The time from {@code C} to {@code D}, or {@code null} while the command runs. */
    public @Nullable Duration runtime() {
        if (running()) {
            return null;
        }
        return Duration.ofNanos(Math.max(0L, endNanos - outputStartNanos));
    }

    /** How long the command has been running at {@code nowNanos} ({@link System#nanoTime()}). */
    public Duration runningFor(long nowNanos) {
        return Duration.ofNanos(Math.max(0L, nowNanos - outputStartNanos));
    }
}
