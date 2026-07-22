package de.kortty.ui;

import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Back/forward navigation history for the file browser's current root, modelled
 * like a web browser: navigating to a new location pushes the previous one onto
 * the back stack and clears the forward stack; {@link #back()} and
 * {@link #forward()} move between visited locations without recording new steps.
 *
 * <p>Pure data structure with no JavaFX or filesystem dependencies so it can be
 * unit-tested in isolation.
 */
final class FileBrowserHistory {

    private final Deque<Path> back = new ArrayDeque<>();
    private final Deque<Path> forward = new ArrayDeque<>();
    private Path current;

    /**
     * Records a jump to {@code target}. A jump to the current location is a
     * no-op; any other jump pushes the current location onto the back stack and
     * discards the forward history.
     */
    void navigate(Path target) {
        if (target == null || target.equals(current)) {
            return;
        }
        if (current != null) {
            back.push(current);
        }
        forward.clear();
        current = target;
    }

    /** Moves one step back; returns the new current location (unchanged if the back stack is empty). */
    Path back() {
        if (back.isEmpty()) {
            return current;
        }
        if (current != null) {
            forward.push(current);
        }
        current = back.pop();
        return current;
    }

    /** Moves one step forward; returns the new current location (unchanged if the forward stack is empty). */
    Path forward() {
        if (forward.isEmpty()) {
            return current;
        }
        if (current != null) {
            back.push(current);
        }
        current = forward.pop();
        return current;
    }

    boolean canGoBack() {
        return !back.isEmpty();
    }

    boolean canGoForward() {
        return !forward.isEmpty();
    }

    Path current() {
        return current;
    }
}
