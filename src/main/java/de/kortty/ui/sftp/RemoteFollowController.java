package de.kortty.ui.sftp;

import de.kortty.core.RemoteDirectoryChange;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Decides which remote folder the terminal's remote files sidebar lists, from what the shell of the
 * followed pane reports (D23). Pure and JavaFX-free; every input arrives on one thread (the FX
 * thread in the app), and the listener is called on that thread too.
 *
 * <p>Rules:
 * <ul>
 *   <li>A directory from OSC 7, the agent hook or a home hint is followed after a
 *       {@linkplain #DEBOUNCE_MILLIS debounce}, so a burst of {@code cd}s lists once.</li>
 *   <li>A typed {@code cd} is recorded before the shell runs it, also inside an editor, a container
 *       or a nested login. It is held as pending and listed only when the next prompt is
 *       {@linkplain #promptSeen seen with the native identity} or an OSC 133 prompt mark arrives;
 *       after {@link #TYPED_CD_WINDOW_MILLIS} without one it is dropped.</li>
 *   <li>A foreign verdict (another user or host is active) pauses following and drops what is
 *       pending; a native verdict resumes it.</li>
 *   <li>A pane switch follows the newly focused pane, but only an SSH pane that can lend its
 *       session; any other pane leaves the sidebar where it is.</li>
 *   <li>The pin pauses following on the user's wish; unpinning lists the latest followed folder.</li>
 * </ul>
 * The sidebar only browses: nothing here ever types into the terminal (D11).
 */
public final class RemoteFollowController {

    /** How long OSC 7 and agent-hook changes settle before the sidebar lists. */
    public static final long DEBOUNCE_MILLIS = 250;
    /** How long a typed {@code cd} waits for its confirming prompt before it is dropped. */
    public static final long TYPED_CD_WINDOW_MILLIS = 3_000;

    /** Whether the sidebar follows the shell. */
    public enum State {
        /** Lists what the followed pane's shell reports. */
        FOLLOWING,
        /** Another user or host is active in the pane: the last listing stays, nothing is followed. */
        PAUSED_FOREIGN,
        /** The user pinned the sidebar to the folder it shows. */
        PAUSED_USER,
        /** No SSH pane to follow, or its session is gone. */
        UNAVAILABLE
    }

    /** The identity the pane's prompt shows. */
    public enum Verdict {
        /** The session's own user (and host). */
        NATIVE,
        /** Another user or host (su, sudo -i, a nested ssh, a container). */
        FOREIGN,
        /** Nothing to tell from the screen. */
        UNKNOWN
    }

    /** Schedules a task once on the controller's thread; the handle cancels it. */
    @FunctionalInterface
    public interface Scheduler {
        Cancellable schedule(Runnable task, long delayMillis);
    }

    /** A scheduled task's handle. */
    @FunctionalInterface
    public interface Cancellable {
        void cancel();
    }

    /** What the controller asks of the sidebar. */
    public interface Listener {
        /**
         * List {@code path} of pane {@code paneKey}; null means the login folder of the SFTP session.
         */
        void listRequested(String paneKey, @Nullable String path);

        /** The state changed; {@code path} is the folder last followed, or null. */
        void stateChanged(State state, @Nullable String path);
    }

    private final Scheduler scheduler;
    private final Listener listener;

    private State state = State.UNAVAILABLE;
    private @Nullable String activePane;
    private @Nullable String followedPath;
    private boolean userPaused;
    private boolean foreign;

    private @Nullable String debouncedPath;
    private @Nullable Cancellable debounceTimer;
    private @Nullable String pendingTypedPath;
    private @Nullable Cancellable pendingTimer;

    public RemoteFollowController(Scheduler scheduler, Listener listener) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.listener = Objects.requireNonNull(listener, "listener");
    }

    // ------------------------------------------------------------------ inputs

    /**
     * Starts (or restarts) following {@code paneKey}: the sidebar was shown, or the pane connected
     * again. Lists {@code path} unless the verdict is foreign or the user pinned the sidebar.
     *
     * @param path    where the pane's shell is, or null for the login folder
     * @param verdict the pane's identity verdict, taken on the FX thread
     */
    public void activate(String paneKey, @Nullable String path, Verdict verdict) {
        Objects.requireNonNull(paneKey, "paneKey");
        clearTransient();
        activePane = paneKey;
        foreign = verdict == Verdict.FOREIGN;
        if (userPaused) {
            announce(State.PAUSED_USER);
            return;
        }
        if (foreign) {
            // The login folder is safe to list; the shell's folder may belong to another identity.
            followedPath = null;
            announce(State.PAUSED_FOREIGN);
            listener.listRequested(paneKey, null);
            return;
        }
        followedPath = path;
        announce(State.FOLLOWING);
        listener.listRequested(paneKey, path);
    }

    /** The pane's session is back (a reconnect): lists again, as {@link #activate} does. */
    public void reconnected(String paneKey, @Nullable String path, Verdict verdict) {
        activate(paneKey, path, verdict);
    }

    /**
     * Another pane of the tab got the keyboard focus. Only an SSH pane that can lend its session
     * ({@code borrowable}) is followed; any other one leaves the sidebar on the pane it follows.
     */
    public void focusChanged(String paneKey, boolean borrowable, @Nullable String path, Verdict verdict) {
        if (!borrowable || paneKey == null || paneKey.equals(activePane) && state != State.UNAVAILABLE) {
            return;
        }
        activate(paneKey, path, verdict);
    }

    /** The followed pane's shell reported a new working directory. */
    public void directoryChanged(String paneKey, String path, RemoteDirectoryChange.Source source) {
        if (!isActive(paneKey) || path == null || path.isBlank() || source == null) {
            return;
        }
        if (source == RemoteDirectoryChange.Source.TYPED_CD) {
            if (state != State.FOLLOWING) {
                return;
            }
            cancelPending();
            pendingTypedPath = path;
            pendingTimer = scheduler.schedule(this::dropPending, TYPED_CD_WINDOW_MILLIS);
            return;
        }
        // A reported directory supersedes a typed cd that was not confirmed yet.
        cancelPending();
        if (state == State.PAUSED_USER) {
            return;
        }
        if (state != State.FOLLOWING) {
            return;
        }
        debouncedPath = path;
        cancelDebounce();
        debounceTimer = scheduler.schedule(this::fireDebounced, DEBOUNCE_MILLIS);
    }

    /**
     * The identity verdict of the followed pane, checked on a directory change or a prompt mark.
     * Foreign pauses and drops pending changes; native resumes a foreign pause. It never confirms a
     * typed {@code cd}: the screen may still show the line the {@code cd} was typed on.
     */
    public void verdict(String paneKey, Verdict verdict) {
        if (!isActive(paneKey) || verdict == null) {
            return;
        }
        if (verdict == Verdict.FOREIGN) {
            pauseForeign();
        } else if (verdict == Verdict.NATIVE && foreign) {
            foreign = false;
            if (state == State.PAUSED_FOREIGN) {
                setState(State.FOLLOWING);
                listener.listRequested(paneKey, followedPath);
            }
        }
    }

    /**
     * A new prompt appeared after a typed {@code cd}, with this verdict. Native confirms the
     * pending {@code cd} and lists it; foreign pauses; unknown keeps waiting until the window ends.
     */
    public void promptSeen(String paneKey, Verdict verdict) {
        if (!isActive(paneKey) || verdict == null) {
            return;
        }
        if (verdict == Verdict.FOREIGN) {
            pauseForeign();
            return;
        }
        if (verdict == Verdict.NATIVE) {
            verdict(paneKey, Verdict.NATIVE);
            commitPending();
        }
    }

    /** An OSC 133 prompt mark from the followed pane's shell: the typed {@code cd} took effect. */
    public void promptMark(String paneKey) {
        if (isActive(paneKey)) {
            commitPending();
        }
    }

    /** The followed pane's session is gone. */
    public void disconnected(String paneKey) {
        if (!isActive(paneKey)) {
            return;
        }
        clearTransient();
        setState(State.UNAVAILABLE);
    }

    /** No pane can be followed (all panes local or Mosh, or the sidebar was hidden). */
    public void reset() {
        clearTransient();
        activePane = null;
        foreign = false;
        followedPath = null;
        setState(State.UNAVAILABLE);
    }

    /** The pin: true stops following, false follows again from the latest folder. */
    public void setUserPaused(boolean paused) {
        if (paused == userPaused) {
            return;
        }
        userPaused = paused;
        if (activePane == null || state == State.UNAVAILABLE) {
            return;
        }
        if (paused) {
            clearTransient();
            setState(State.PAUSED_USER);
            return;
        }
        if (foreign) {
            setState(State.PAUSED_FOREIGN);
            return;
        }
        setState(State.FOLLOWING);
        listener.listRequested(activePane, followedPath);
    }

    /** The user browsed to {@code path} in the sidebar; following goes on from the next change. */
    public void browsed(@Nullable String path) {
        if (path != null && state == State.FOLLOWING) {
            followedPath = path;
        }
    }

    // ------------------------------------------------------------------ state

    public State state() {
        return state;
    }

    public @Nullable String activePane() {
        return activePane;
    }

    public @Nullable String followedPath() {
        return followedPath;
    }

    public boolean isUserPaused() {
        return userPaused;
    }

    /** Whether a typed {@code cd} waits for its confirming prompt. */
    public boolean hasPending() {
        return pendingTypedPath != null;
    }

    /** The typed {@code cd} waiting for its prompt, or null. */
    public @Nullable String pendingPath() {
        return pendingTypedPath;
    }

    // ------------------------------------------------------------------ internals

    private boolean isActive(String paneKey) {
        return paneKey != null && paneKey.equals(activePane) && state != State.UNAVAILABLE;
    }

    private void fireDebounced() {
        debounceTimer = null;
        String path = debouncedPath;
        debouncedPath = null;
        if (path == null || state != State.FOLLOWING || activePane == null) {
            return;
        }
        if (path.equals(followedPath)) {
            return;
        }
        followedPath = path;
        listener.listRequested(activePane, path);
        listener.stateChanged(state, path);
    }

    private void commitPending() {
        String path = pendingTypedPath;
        cancelPending();
        if (path == null || state != State.FOLLOWING || activePane == null) {
            return;
        }
        cancelDebounce();
        debouncedPath = null;
        if (path.equals(followedPath)) {
            return;
        }
        followedPath = path;
        listener.listRequested(activePane, path);
        listener.stateChanged(state, path);
    }

    private void dropPending() {
        pendingTimer = null;
        pendingTypedPath = null;
    }

    private void pauseForeign() {
        foreign = true;
        clearTransient();
        if (state == State.FOLLOWING) {
            setState(State.PAUSED_FOREIGN);
        }
    }

    private void clearTransient() {
        cancelPending();
        cancelDebounce();
        debouncedPath = null;
    }

    private void cancelPending() {
        Cancellable timer = pendingTimer;
        pendingTimer = null;
        pendingTypedPath = null;
        if (timer != null) {
            timer.cancel();
        }
    }

    private void cancelDebounce() {
        Cancellable timer = debounceTimer;
        debounceTimer = null;
        if (timer != null) {
            timer.cancel();
        }
    }

    /** Sets the state and always tells the listener, also when it stays the same (another pane). */
    private void announce(State next) {
        state = next;
        listener.stateChanged(next, followedPath);
    }

    private void setState(State next) {
        if (next == state) {
            return;
        }
        state = next;
        listener.stateChanged(next, followedPath);
    }
}
