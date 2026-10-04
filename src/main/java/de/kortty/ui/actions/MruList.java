package de.kortty.ui.actions;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A most-recently-used list: adding an item moves it to the front, an item is held at most once,
 * and the oldest item falls out once the list is full. Not thread-safe; the palette uses it on the
 * FX thread only.
 *
 * @param <T> the item type, compared with {@code equals}
 */
public final class MruList<T> {

    /** The capacity of the palette's list of recent choices. */
    public static final int DEFAULT_CAPACITY = 20;

    private final int capacity;
    private final List<T> items = new ArrayList<>();

    public MruList(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be at least 1: " + capacity);
        }
        this.capacity = capacity;
    }

    /** Puts {@code item} first, removing an earlier copy, and drops the oldest item beyond the capacity. */
    public void add(T item) {
        Objects.requireNonNull(item, "item");
        items.remove(item);
        items.add(0, item);
        while (items.size() > capacity) {
            items.remove(items.size() - 1);
        }
    }

    /** Removes {@code item}; returns whether it was in the list. */
    public boolean remove(T item) {
        return items.remove(item);
    }

    /** The position of {@code item}, 0 for the most recent, or -1 when it is not in the list. */
    public int indexOf(T item) {
        return items.indexOf(item);
    }

    public boolean contains(T item) {
        return items.contains(item);
    }

    /** A copy of the items, most recent first. */
    public List<T> items() {
        return List.copyOf(items);
    }

    public int size() {
        return items.size();
    }

    public int capacity() {
        return capacity;
    }

    public void clear() {
        items.clear();
    }
}
