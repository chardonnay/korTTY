package de.kortty.ui;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * Which panes take part in multi-exec: what you type in one of them also goes to the others,
 * whichever tab and window they are in. Panes are told apart by reference, never by
 * {@code equals}, so a pane keeps its membership when its tab moves to another window, and the
 * members keep the order they joined in, which is the order mirrored keys reach them.
 *
 * <p>Every call that changes the members tells the listeners once, however many panes it
 * changed, and a call that changes nothing tells them nothing. A failing listener does not keep
 * the others from being told.
 *
 * <p>The stretch from the first pane joining to the last one leaving is one {@link Session}: what a
 * command typed once in it does in every member belongs together, so its long-command notification
 * is shown once for all of them ({@code TerminalAttentionNotifier}).
 *
 * <p>Generic over the pane type and free of JavaFX, so it is unit-tested without the toolkit; the
 * application's instance lives in {@link MultiExecCoordinator}, which uses it on the FX thread
 * only. It is not thread-safe.
 *
 * @param <K> the pane type
 */
public final class MultiExecMembership<K> {

    private static final Logger logger = LoggerFactory.getLogger(MultiExecMembership.class);

    /**
     * How far the members reach: their number, and in how many tabs and windows they are.
     *
     * @param panes   the members
     * @param tabs    the different tabs that hold them, members without a known tab not counted
     * @param windows the different windows that hold them, members without a known window not counted
     */
    public record Counts(int panes, int tabs, int windows) {

        /** No member. */
        public static final Counts NONE = new Counts(0, 0, 0);
    }

    /**
     * One stretch of multi-exec, from the first pane joining until the last one leaves; panes that
     * join or leave in between do not start a new one. Told apart by reference only, so it can key a
     * weak map: the notifications use it as the slot shared by every member.
     */
    public static final class Session {
        private final long number;

        private Session(long number) {
            this.number = number;
        }

        @Override
        public String toString() {
            return "multi-exec session " + number;
        }
    }

    // Identity keys in join order: an IdentityHashMap has no order, so the order is kept in a
    // LinkedHashMap over an identity wrapper.
    private final Map<Identity<K>, K> members = new LinkedHashMap<>();
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    // Set while there are members, so the listeners already see the session a change started.
    private @Nullable Session session;
    private long sessionCount;

    /** Whether {@code pane} takes part. */
    public boolean contains(@Nullable K pane) {
        return pane != null && members.containsKey(new Identity<>(pane));
    }

    /** The number of members. */
    public int size() {
        return members.size();
    }

    /** Whether no pane takes part. */
    public boolean isEmpty() {
        return members.isEmpty();
    }

    /** The session that runs now, or {@code null} while no pane takes part. */
    public @Nullable Session session() {
        return session;
    }

    /** The session {@code pane} takes part in, or {@code null} when it does not take part. */
    public @Nullable Session sessionOf(@Nullable K pane) {
        return contains(pane) ? session : null;
    }

    /** The members in the order they joined; a copy. */
    public @NotNull List<K> members() {
        return List.copyOf(members.values());
    }

    /** The members besides {@code pane}, in the order they joined; a copy. */
    public @NotNull List<K> othersBesides(@Nullable K pane) {
        List<K> others = new ArrayList<>(members.size());
        for (K member : members.values()) {
            if (member != pane) {
                others.add(member);
            }
        }
        return others;
    }

    /** How many of {@code panes} take part, each pane counted once. */
    public int countIn(@NotNull Collection<? extends K> panes) {
        Set<K> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int count = 0;
        for (K pane : panes) {
            if (pane != null && seen.add(pane) && contains(pane)) {
                count++;
            }
        }
        return count;
    }

    /** Whether {@code panes} has a pane and every one of them takes part, as for a whole tab. */
    public boolean includesAll(@NotNull Collection<? extends K> panes) {
        boolean any = false;
        for (K pane : panes) {
            if (pane == null) {
                continue;
            }
            if (!contains(pane)) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /**
     * Lets {@code pane} take part, or no longer, the opposite of what it does now.
     *
     * @return whether it takes part afterwards; false for {@code null}
     */
    public boolean toggle(@Nullable K pane) {
        if (pane == null) {
            return false;
        }
        boolean include = !contains(pane);
        setAll(List.of(pane), include);
        return include;
    }

    /**
     * Lets every pane of {@code panes} take part ({@code included}) or none of them, as for a tab or
     * a window; panes that already are as asked stay as they are, and the members that stay keep
     * their place in the order.
     *
     * @return whether any pane changed; the listeners are told once when one did
     */
    public boolean setAll(@NotNull Collection<? extends K> panes, boolean included) {
        boolean changed = false;
        for (K pane : panes) {
            if (pane == null) {
                continue;
            }
            Identity<K> key = new Identity<>(pane);
            if (included) {
                if (!members.containsKey(key)) {
                    members.put(key, pane);
                    changed = true;
                }
            } else if (members.remove(key) != null) {
                changed = true;
            }
        }
        if (changed) {
            updateSession();
            fireChanged();
        }
        return changed;
    }

    /**
     * Takes {@code pane} out, as when it closes.
     *
     * @return whether it took part
     */
    public boolean remove(@Nullable K pane) {
        return pane != null && setAll(List.of(pane), false);
    }

    /**
     * Takes every pane out: multi-exec stops.
     *
     * @return whether any pane took part
     */
    public boolean clear() {
        if (members.isEmpty()) {
            return false;
        }
        members.clear();
        updateSession();
        fireChanged();
        return true;
    }

    /** Starts a session when the first pane joined, and ends it when the last one left. */
    private void updateSession() {
        if (members.isEmpty()) {
            session = null;
        } else if (session == null) {
            session = new Session(++sessionCount);
        }
    }

    /**
     * How far the members reach: their number and how many different tabs and windows hold them.
     *
     * @param tabOf    the tab that holds a member, or {@code null} when it is not known
     * @param windowOf the window that holds a member, or {@code null} when it is not known
     */
    public @NotNull Counts counts(@NotNull Function<? super K, ?> tabOf, @NotNull Function<? super K, ?> windowOf) {
        if (members.isEmpty()) {
            return Counts.NONE;
        }
        Set<Object> tabs = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> windows = Collections.newSetFromMap(new IdentityHashMap<>());
        for (K member : members.values()) {
            Object tab = tabOf.apply(member);
            if (tab != null) {
                tabs.add(tab);
            }
            Object window = windowOf.apply(member);
            if (window != null) {
                windows.add(window);
            }
        }
        return new Counts(members.size(), tabs.size(), windows.size());
    }

    /**
     * Tells {@code listener} about every change to the members from now on, until the returned
     * handle is closed.
     */
    public @NotNull AutoCloseable addListener(@NotNull Runnable listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Tells every listener that something changed, as a change to the members does. */
    public void fireChanged() {
        for (Runnable listener : listeners) {
            try {
                listener.run();
            } catch (RuntimeException e) {
                logger.debug("Multi-exec listener failed: {}", e.toString());
            }
        }
    }

    /** A pane as a map key that is equal only to the same pane. */
    private record Identity<K>(K pane) {

        @Override
        public boolean equals(Object other) {
            return other instanceof Identity<?> identity && identity.pane == pane;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(pane);
        }
    }
}
