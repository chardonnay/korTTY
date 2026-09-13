package de.kortty.codingagent;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Test helper: records every {@link CodingAgentEvent} it receives (from any thread) and lets a test
 * wait until at least a given number of events have arrived.
 */
final class RecordingListener implements Consumer<CodingAgentEvent> {

    private final List<CodingAgentEvent> events = new CopyOnWriteArrayList<>();
    private final Object arrival = new Object();

    @Override
    public void accept(CodingAgentEvent event) {
        events.add(event);
        synchronized (arrival) {
            arrival.notifyAll();
        }
    }

    /** Snapshot of the events received so far, in delivery order. */
    List<CodingAgentEvent> events() {
        return List.copyOf(events);
    }

    int size() {
        return events.size();
    }

    CodingAgentEvent last() {
        List<CodingAgentEvent> snapshot = events();
        if (snapshot.isEmpty()) {
            throw new AssertionError("no event recorded yet");
        }
        return snapshot.get(snapshot.size() - 1);
    }

    /** Waits until at least {@code count} events were received; false on timeout. */
    boolean await(int count, long millis) throws InterruptedException {
        long deadline = System.nanoTime() + millis * 1_000_000L;
        synchronized (arrival) {
            while (events.size() < count) {
                long remaining = (deadline - System.nanoTime()) / 1_000_000L;
                if (remaining <= 0) {
                    return false;
                }
                arrival.wait(remaining);
            }
        }
        return true;
    }
}
