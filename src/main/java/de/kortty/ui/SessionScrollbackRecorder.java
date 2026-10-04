package de.kortty.ui;

import de.kortty.shellintegration.PaneOutputClock;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Which panes' output the session snapshot keeps, under which file name, and which of them need
 * their file written again.
 *
 * <p>Every pane that has received output gets a file name ({@link #refOf}) the first time a capture
 * asks, and keeps it while it is open. A pane counts as changed when output arrived after the output
 * its file holds ({@link PaneOutputClock#lastOutputNanos()}), not when its history grew or the user
 * scrolled: {@link #changedPanes} lists exactly those, and only their files are rewritten. A pane
 * without output has nothing to keep and gets no name.
 *
 * <p>The moment recorded for a write is the one read before the pane's rows were read
 * ({@link Job#outputNanos}); output that arrives while the rows are read leaves the pane changed for
 * the next round. Thread-safe: captures run on the JavaFX thread, writes on a background thread.
 * Toolkit-free.
 *
 * @param <P> the pane type; panes are told apart by identity
 */
final class SessionScrollbackRecorder<P> {

    /** One file to write: {@code pane}'s rows, read when the write runs. */
    record Job<P>(P pane, String ref, long outputNanos, Supplier<List<String>> rows) {
        Job {
            Objects.requireNonNull(pane, "pane");
            Objects.requireNonNull(ref, "ref");
            Objects.requireNonNull(rows, "rows");
        }
    }

    private static final class Entry {
        final String ref;
        long writtenNanos = PaneOutputClock.NEVER;

        Entry(String ref) {
            this.ref = ref;
        }
    }

    private final Map<P, Entry> entries = new IdentityHashMap<>();
    private final Supplier<String> newRef;

    SessionScrollbackRecorder() {
        this(() -> UUID.randomUUID().toString());
    }

    SessionScrollbackRecorder(Supplier<String> newRef) {
        this.newRef = Objects.requireNonNull(newRef, "newRef");
    }

    /**
     * The file name the session snapshot names for {@code pane}, assigned now when the pane has
     * received output and has none yet; {@code null} for a pane that never received output.
     */
    synchronized @Nullable String refOf(@Nullable P pane, long lastOutputNanos) {
        if (pane == null) {
            return null;
        }
        Entry entry = entries.get(pane);
        if (entry == null) {
            if (lastOutputNanos == PaneOutputClock.NEVER) {
                return null;
            }
            entry = new Entry(newRef.get());
            entries.put(pane, entry);
        }
        return entry.ref;
    }

    /** Whether {@code pane} has a file name already. */
    synchronized boolean hasRef(@Nullable P pane) {
        return pane != null && entries.containsKey(pane);
    }

    /**
     * The panes of {@code open} that have a file name and received output after their file was
     * last written, as jobs; panes no longer open are forgotten.
     *
     * @param lastOutputNanos when a pane last received output
     * @param rows            reads a pane's rows when the job runs
     */
    synchronized List<Job<P>> changedPanes(Collection<P> open, java.util.function.ToLongFunction<P> lastOutputNanos,
                                           java.util.function.Function<P, Supplier<List<String>>> rows) {
        Set<P> still = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        still.addAll(open);
        entries.keySet().retainAll(still);
        List<Job<P>> jobs = new ArrayList<>();
        for (Map.Entry<P, Entry> e : entries.entrySet()) {
            long output = lastOutputNanos.applyAsLong(e.getKey());
            if (output != PaneOutputClock.NEVER && output != e.getValue().writtenNanos) {
                jobs.add(new Job<>(e.getKey(), e.getValue().ref, output, rows.apply(e.getKey())));
            }
        }
        return jobs;
    }

    /** {@code job}'s file now holds the pane's output up to {@link Job#outputNanos}. */
    synchronized void written(Job<P> job) {
        Entry entry = entries.get(job.pane());
        if (entry != null && entry.ref.equals(job.ref())) {
            entry.writtenNanos = job.outputNanos();
        }
    }

    /** Every file is gone (a purge): each pane with output is written again on the next round. */
    synchronized void filesPurged() {
        for (Entry entry : entries.values()) {
            entry.writtenNanos = PaneOutputClock.NEVER;
        }
    }

    /** Forgets every pane: the setting was turned off. */
    synchronized void clear() {
        entries.clear();
    }

    /** The file names in use. */
    synchronized Set<String> refs() {
        Set<String> refs = new java.util.HashSet<>();
        for (Entry entry : entries.values()) {
            refs.add(entry.ref);
        }
        return refs;
    }
}
