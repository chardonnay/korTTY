package de.kortty.ui;

import de.kortty.model.Project;
import de.kortty.model.SessionState;
import de.kortty.model.WindowState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.ToIntFunction;

/**
 * The order decisions of saving and opening a project, without JavaFX: which windows a project
 * saves and opens, the key each saved tab goes by while it is restored, where the restored tabs go
 * and which tab ends up selected.
 *
 * <p>The selection cannot use an index. Tab groups sort the restored terminal tabs, and a remote
 * file editor or image viewer tab arrives only after its file has been downloaded, so the n-th
 * restored tab is often another tab than the n-th saved one. Every saved tab therefore gets a key,
 * its session id, and the window selects the tab with the saved active key once it is there. Files
 * saved before the active session id existed name the active tab by its index in the saved list,
 * which is turned into that tab's key, so they profit from the same matching.
 */
final class ProjectRestoreOrder {

    /**
     * The most windows one project opens. korTTY writes one window state per open window, so only a
     * hand-made file reaches it; more would flood the screen.
     */
    static final int MAX_WINDOWS = 32;

    /**
     * How long a restored window waits for its active tab when that is a remote tab still being
     * downloaded; after that it keeps the tab it shows.
     */
    static final long ACTIVE_TAB_WAIT_MILLIS = 10_000;

    /** The prefix of the key of a saved tab without a usable session id; followed by its index. */
    static final String INDEX_KEY_PREFIX = "#";

    private ProjectRestoreOrder() {
    }

    /** The window states to open, in the file's order, without empty entries and at most {@link #MAX_WINDOWS}. */
    static List<WindowState> windowsToRestore(Project project) {
        if (project == null || project.getWindows() == null) {
            return List.of();
        }
        List<WindowState> windows = new ArrayList<>();
        for (WindowState window : project.getWindows()) {
            if (window != null && windows.size() < MAX_WINDOWS) {
                windows.add(window);
            }
        }
        return windows;
    }

    /**
     * The windows a project is saved from: {@code first}, the window Save Project was chosen in, then
     * every other open window in the order they were opened, each once. Opening the project gives the
     * first saved window to the window it is opened in.
     */
    static <W> List<W> captureOrder(W first, List<W> openWindows) {
        Set<W> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<W> order = new ArrayList<>();
        if (first != null && seen.add(first)) {
            order.add(first);
        }
        if (openWindows != null) {
            for (W window : openWindows) {
                if (window != null && seen.add(window)) {
                    order.add(window);
                }
            }
        }
        return order;
    }

    /**
     * Whether a window goes into the project: the window Save Project was chosen in always does, for
     * its geometry; another window only with at least one saved tab, so a window holding nothing but
     * AI or tool tabs does not come back empty.
     */
    static boolean savesWindow(boolean firstWindow, int savedTabs) {
        return firstWindow || savedTabs > 0;
    }

    /**
     * One key per saved tab, in the saved order: the tab's session id, or {@code #index} when it has
     * none (file editor and image viewer tabs of older files) or an earlier tab has the same one.
     */
    static List<String> tabKeys(List<SessionState> tabs) {
        if (tabs == null) {
            return List.of();
        }
        List<String> keys = new ArrayList<>(tabs.size());
        Set<String> used = new HashSet<>();
        for (int index = 0; index < tabs.size(); index++) {
            SessionState tab = tabs.get(index);
            String sessionId = tab != null ? tab.getSessionId() : null;
            boolean usable = sessionId != null && !sessionId.isBlank()
                && !sessionId.startsWith(INDEX_KEY_PREFIX) && used.add(sessionId);
            keys.add(usable ? sessionId : INDEX_KEY_PREFIX + index);
        }
        return keys;
    }

    /**
     * The key of the tab to select once the window is restored: the saved active session id when it
     * names one of the saved tabs; else, for files saved without it, the tab at the saved active
     * index; else {@code null}, and the window keeps the tab the restore left selected.
     *
     * @param keys the window's {@link #tabKeys}
     */
    static String activeTabKey(WindowState window, List<String> keys) {
        if (window == null || keys == null || keys.isEmpty()) {
            return null;
        }
        String activeSessionId = window.getActiveSessionId();
        if (activeSessionId != null && !activeSessionId.isBlank()) {
            // A file that names the active tab by id never falls back to the index: when that tab is
            // gone, the index points at another one.
            return keys.contains(activeSessionId) ? activeSessionId : null;
        }
        int index = window.getActiveTabIndex();
        return index >= 0 && index < keys.size() ? keys.get(index) : null;
    }

    /**
     * The tabs of a window in the order a restore leaves them: tabs the restore did not open stay
     * ahead, in their order, and the restored tabs follow in their saved order. Tab groups sort the
     * terminal tabs afterwards and keep this order inside each group.
     *
     * @param savedIndex a tab's index in the saved list, or a negative number for a tab the restore
     *     did not open
     */
    static <T> List<T> savedOrder(List<T> live, ToIntFunction<T> savedIndex) {
        List<T> others = new ArrayList<>();
        List<T> restored = new ArrayList<>();
        for (T tab : live) {
            if (savedIndex.applyAsInt(tab) < 0) {
                others.add(tab);
            } else {
                restored.add(tab);
            }
        }
        // Stable: two tabs never share an index, but a sort must not reorder what it cannot tell apart.
        restored.sort(Comparator.comparingInt(savedIndex));
        List<T> order = new ArrayList<>(others);
        order.addAll(restored);
        return order;
    }

    /**
     * Where a tab that arrives after the others goes (a remote file editor or image viewer tab): in
     * front of the first tab that came from a later position in the saved list, else at the end.
     * Only tabs that tab groups do not sort count, so the caller passes {@code null} for terminal
     * tabs and for tabs the restore did not open.
     *
     * @param liveSavedIndexes per tab of the window, in its order, the saved index or {@code null}
     */
    static int lateInsertionIndex(List<Integer> liveSavedIndexes, int savedIndex) {
        for (int position = 0; position < liveSavedIndexes.size(); position++) {
            Integer other = liveSavedIndexes.get(position);
            if (other != null && other > savedIndex) {
                return position;
            }
        }
        return liveSavedIndexes.size();
    }

    /** What a restored window does about its active tab. */
    enum Step {
        /** Select the tab at {@link Selection#index()}; the restore is done with the selection. */
        SELECT,
        /** The active tab is still being downloaded: ask again when a late tab arrives or fails. */
        WAIT,
        /** Leave the selection as it is; the restore is done with the selection. */
        KEEP
    }

    /** The answer of {@link #selectActive}: the step and, for {@link Step#SELECT}, the tab's position. */
    record Selection(Step step, int index) {
        static final Selection WAITING = new Selection(Step.WAIT, -1);
        static final Selection KEEPING = new Selection(Step.KEEP, -1);
    }

    /**
     * Decides which tab a restored window selects.
     *
     * @param activeKey the window's {@link #activeTabKey}, or null
     * @param liveKeys per tab of the window, in its current order, the key of the saved tab it was
     *     restored from, or null for a tab the restore did not open
     * @param pendingKeys the keys of the tabs still being downloaded
     * @param timedOut whether {@link #ACTIVE_TAB_WAIT_MILLIS} has passed since the other tabs were in
     * @param userChangedSelection whether the user selected a tab since the window started waiting;
     *     the restore then leaves their choice alone
     */
    static Selection selectActive(String activeKey, List<String> liveKeys, Set<String> pendingKeys,
                                  boolean timedOut, boolean userChangedSelection) {
        if (activeKey == null || userChangedSelection) {
            return Selection.KEEPING;
        }
        int index = liveKeys.indexOf(activeKey);
        if (index >= 0) {
            return new Selection(Step.SELECT, index);
        }
        if (!timedOut && pendingKeys.contains(activeKey)) {
            return Selection.WAITING;
        }
        // The active tab did not come back (no connection, a cancelled password, a missing file).
        return Selection.KEEPING;
    }
}
