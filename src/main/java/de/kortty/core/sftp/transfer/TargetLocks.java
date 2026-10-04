package de.kortty.core.sftp.transfer;

import java.util.HashSet;
import java.util.Set;

/**
 * Keeps two items of a queue, from the same batch or not, from writing one target (and its part
 * file) at the same time: the second waits, then sees the first one's result as an existing target
 * and goes through conflict handling. Keys are built by the queue: a normalized remote path, or a
 * local path folded to lower case on a case-insensitive file store.
 */
final class TargetLocks {

    /** How often a waiting worker checks whether its item was cancelled. */
    private static final long POLL_MILLIS = 100;

    private final Set<String> held = new HashSet<>();

    /**
     * Takes {@code key}, waiting while another item holds it.
     *
     * @throws TransferCancelledException when {@code cancel} is cancelled while waiting
     */
    void lock(String key, TransferCancellation cancel) throws InterruptedException, TransferCancelledException {
        synchronized (held) {
            while (held.contains(key)) {
                cancel.throwIfCancelled();
                held.wait(POLL_MILLIS);
            }
            cancel.throwIfCancelled();
            held.add(key);
        }
    }

    void unlock(String key) {
        synchronized (held) {
            held.remove(key);
            held.notifyAll();
        }
    }

    /** Whether another item is writing {@code key} right now (its target may not exist yet). */
    boolean isHeld(String key) {
        synchronized (held) {
            return held.contains(key);
        }
    }
}
