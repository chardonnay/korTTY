package de.kortty.core.remote.search;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Hands hits to the caller in batches of {@link #BATCH_SIZE}, counting them. Not thread-safe. */
final class HitBatcher {

    static final int BATCH_SIZE = 100;

    private final Consumer<List<RemoteSearchHit>> sink;
    private List<RemoteSearchHit> pending = new ArrayList<>(BATCH_SIZE);
    private int count;

    HitBatcher(Consumer<List<RemoteSearchHit>> sink) {
        this.sink = sink;
    }

    void add(RemoteSearchHit hit) {
        pending.add(hit);
        count++;
        if (pending.size() >= BATCH_SIZE) {
            flush();
        }
    }

    void flush() {
        if (!pending.isEmpty()) {
            List<RemoteSearchHit> batch = List.copyOf(pending);
            pending = new ArrayList<>(BATCH_SIZE);
            sink.accept(batch);
        }
    }

    int count() {
        return count;
    }
}
