package de.kortty.control;

import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one place control-API threads are created: named daemon <strong>platform</strong> threads.
 *
 * <p>Pure, any thread.
 *
 * <p>Platform threads rather than virtual ones, deliberately: the failure that matters here is
 * resource exhaustion by local clients, and a fixed pool makes the cap explicit, observable and
 * testable — a ninth connection is refused with {@code too_many_connections}. Every worker also
 * blocks inside code full of {@code synchronized} (JavaFX, the terminal text buffer), which is
 * carrier-pinning territory. Every thread is a daemon named {@code kortty-control-<role>-<n>}, which
 * is what {@code ControlApiThreadHygieneTest} asserts has disappeared after {@code close()}.
 */
public final class ControlThreads {

    /** Every control-API thread name starts with this. */
    public static final String NAME_PREFIX = "kortty-control-";

    private ControlThreads() {
    }

    /**
     * A factory whose threads are daemons named {@code kortty-control-<namePrefix>-<n>}.
     *
     * @param namePrefix the role, for example {@code "accept"}, {@code "rx"} or {@code "tx"}; an
     *     already-prefixed name is not prefixed twice
     */
    public static ThreadFactory factory(String namePrefix) {
        Objects.requireNonNull(namePrefix, "namePrefix");
        String role = namePrefix.toLowerCase(Locale.ROOT);
        String base = role.startsWith(NAME_PREFIX) ? role : NAME_PREFIX + role;
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, base + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
