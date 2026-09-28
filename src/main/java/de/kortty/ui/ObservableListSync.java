package de.kortty.ui;

import javafx.collections.ObservableList;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/**
 * Brings an {@link ObservableList} in line with a fresh snapshot through incremental removes,
 * inserts and moves instead of {@code setAll}. A {@code setAll} is one wholesale replace: a
 * {@code TableView} bound to the list drops its selection and scroll position on every refresh.
 * Incremental changes leave untouched rows alone, so only what really changed moves.
 *
 * <p>Items are matched by {@code key}; a surviving key whose instance changed is replaced in place
 * ({@code set}). Pure list logic — no toolkit needed, works on any {@code FXCollections} list.
 */
public final class ObservableListSync {

    private ObservableListSync() {
    }

    /**
     * Makes {@code target} equal to {@code fresh} (same items, same order, first occurrence wins for
     * duplicate keys) using the smallest reasonable set of remove / insert / move / set operations.
     */
    public static <T, K> void sync(ObservableList<T> target, List<T> fresh, Function<T, K> key) {
        Map<K, T> freshByKey = new LinkedHashMap<>();
        for (T item : fresh) {
            freshByKey.putIfAbsent(key.apply(item), item);
        }
        List<T> desired = new ArrayList<>(freshByKey.values());

        // 1. Drop what is gone, and any duplicate key the target still carries.
        Set<K> seen = new HashSet<>();
        for (int i = 0; i < target.size(); ) {
            K k = key.apply(target.get(i));
            if (!freshByKey.containsKey(k) || !seen.add(k)) {
                target.remove(i);
            } else {
                i++;
            }
        }

        // 2. Walk the desired order position by position: keep, replace, move forward, or insert.
        for (int i = 0; i < desired.size(); i++) {
            T want = desired.get(i);
            K k = key.apply(want);
            if (i < target.size()) {
                T have = target.get(i);
                if (Objects.equals(key.apply(have), k)) {
                    if (have != want) {
                        target.set(i, want);
                    }
                    continue;
                }
                int j = indexOfKey(target, key, k, i + 1);
                if (j >= 0) {
                    target.remove(j);
                    target.add(i, want);
                    continue;
                }
            }
            target.add(i, want);
        }

        // 3. After step 1 every remaining key is desired and unique, so nothing can trail; keep the
        //    invariant explicit anyway.
        while (target.size() > desired.size()) {
            target.remove(target.size() - 1);
        }
    }

    /**
     * Decides the order a refresh should produce. With {@code resort} the fresh (already sorted)
     * list wins. Without it, items the list already shows keep their current position (so a usage
     * counter bump does not make a row jump) and items new to the list are appended in
     * {@code freshSorted} order; items missing from {@code freshSorted} are dropped. Instances are
     * always taken from {@code freshSorted}.
     */
    public static <T, K> List<T> reconcileOrder(List<T> current, List<T> freshSorted, Function<T, K> key,
                                                boolean resort) {
        if (resort) {
            return new ArrayList<>(freshSorted);
        }
        Map<K, T> remaining = new LinkedHashMap<>();
        for (T item : freshSorted) {
            remaining.putIfAbsent(key.apply(item), item);
        }
        List<T> ordered = new ArrayList<>(freshSorted.size());
        for (T item : current) {
            T fresh = remaining.remove(key.apply(item));
            if (fresh != null) {
                ordered.add(fresh);
            }
        }
        ordered.addAll(remaining.values());
        return ordered;
    }

    private static <T, K> int indexOfKey(List<T> list, Function<T, K> key, K wanted, int from) {
        for (int i = from; i < list.size(); i++) {
            if (Objects.equals(key.apply(list.get(i)), wanted)) {
                return i;
            }
        }
        return -1;
    }
}
