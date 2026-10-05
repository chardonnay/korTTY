package de.kortty.core.worker;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Every session worker korTTY started that is still running, for the Session processes window and
 * for ending them all when korTTY quits. A worker also ends by itself when korTTY's process goes
 * away, because its stdin closes then.
 */
public final class SessionWorkerRegistry {

    private static final CopyOnWriteArrayList<SessionWorkerProcess> workers = new CopyOnWriteArrayList<>();
    private static final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();

    private SessionWorkerRegistry() {
    }

    static void register(SessionWorkerProcess worker) {
        workers.add(worker);
        fire();
    }

    static void unregister(SessionWorkerProcess worker) {
        if (workers.remove(worker)) {
            fire();
        }
    }

    /** Tells the listeners that a worker's description changed. */
    static void changed() {
        fire();
    }

    /** The workers running now. */
    public static List<SessionWorkerProcess> running() {
        return workers.stream().filter(SessionWorkerProcess::isAlive).toList();
    }

    /** Runs {@code listener} (on any thread) whenever a worker starts or ends. */
    public static void addListener(Runnable listener) {
        listeners.add(listener);
    }

    public static void removeListener(Runnable listener) {
        listeners.remove(listener);
    }

    /** Ends every worker; korTTY calls this when it quits. */
    public static void terminateAll() {
        workers.forEach(SessionWorkerProcess::terminate);
    }

    private static void fire() {
        listeners.forEach(listener -> {
            try {
                listener.run();
            } catch (RuntimeException ignored) {
                // A listener's failure must not stop a worker from being tracked.
            }
        });
    }
}
