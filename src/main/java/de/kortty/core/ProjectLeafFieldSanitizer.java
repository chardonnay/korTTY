package de.kortty.core;

import de.kortty.model.Project;
import de.kortty.model.SessionState;
import de.kortty.model.SplitPaneState;
import de.kortty.model.WindowState;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Removes the session-only fields of split-pane leaves from what korTTY reads or writes.
 *
 * <p>A leaf of a saved split layout may name the working directory its local shell was in
 * ({@code currentDirectory}) and the file its scrollback was saved to ({@code scrollbackRef}).
 * Only korTTY's own session snapshot on this device may carry them. A project file is meant to be
 * shared, so a {@code .kortty} file someone sends must not choose the directory a local shell starts
 * in (a prompt plugin that runs {@code git} there can run code from a prepared directory) or point
 * a pane at a file of its choosing: {@link Source#PROJECT_FILE} drops both, and the tab's own
 * {@code currentDirectory} (its first pane's), the way {@link ProjectManager} already drops inline
 * screen text. {@link Source#SESSION_SNAPSHOT} keeps a directory only when it is an absolute path
 * without control characters ({@link SessionWorkingDirectory#forSnapshot}) and a scrollback reference
 * only when it is a plain name ({@link #isValidScrollbackRef}).
 *
 * <p>The tree is walked without recursion, so a hand-made file nested arbitrarily deep cannot
 * overflow the stack here.
 */
public final class ProjectLeafFieldSanitizer {

    /** Where a split layout comes from. */
    public enum Source {
        /** A {@code .kortty} project file, shareable: no session-only field survives. */
        PROJECT_FILE,
        /** korTTY's own session snapshot on this device: the session-only fields stay, checked. */
        SESSION_SNAPSHOT
    }

    /** A scrollback reference is a plain name: letters, digits and dashes, as a UUID is. */
    private static final Pattern SCROLLBACK_REF = Pattern.compile("[A-Za-z0-9-]{1,64}");

    private ProjectLeafFieldSanitizer() {
    }

    /** Sanitizes the split layout of every tab of every window of {@code project}. */
    public static void sanitize(@Nullable Project project, Source source) {
        Objects.requireNonNull(source, "source");
        if (project == null || project.getWindows() == null) {
            return;
        }
        for (WindowState window : project.getWindows()) {
            if (window == null || window.getTabs() == null) {
                continue;
            }
            for (SessionState session : window.getTabs()) {
                if (session != null) {
                    sanitizeTab(session, source);
                    sanitize(session.getSplitPaneState(), source);
                }
            }
        }
    }

    /** Sanitizes every node of {@code layout}. */
    public static void sanitize(@Nullable SplitPaneState layout, Source source) {
        Objects.requireNonNull(source, "source");
        Deque<SplitPaneState> pending = new ArrayDeque<>();
        if (layout != null) {
            pending.push(layout);
        }
        while (!pending.isEmpty()) {
            SplitPaneState node = pending.pop();
            sanitizeNode(node, source);
            if (node.getLeftChild() != null) {
                pending.push(node.getLeftChild());
            }
            if (node.getRightChild() != null) {
                pending.push(node.getRightChild());
            }
        }
    }

    /** Whether {@code ref} is a plain scrollback file name a session snapshot may carry. */
    public static boolean isValidScrollbackRef(@Nullable String ref) {
        return ref != null && SCROLLBACK_REF.matcher(ref).matches();
    }

    /**
     * The tab's own working directory (its first pane's, {@link SessionState#getCurrentDirectory}) is
     * session-only as well: a project file loses it, a session snapshot keeps it only when
     * {@link SessionWorkingDirectory#forSnapshot} would save it.
     */
    private static void sanitizeTab(SessionState session, Source source) {
        if (source == Source.PROJECT_FILE) {
            session.setCurrentDirectory(null);
            return;
        }
        session.setCurrentDirectory(SessionWorkingDirectory.forSnapshot(session.getCurrentDirectory()));
    }

    private static void sanitizeNode(SplitPaneState node, Source source) {
        if (source == Source.PROJECT_FILE) {
            node.setCurrentDirectory(null);
            node.setScrollbackRef(null);
            return;
        }
        node.setCurrentDirectory(SessionWorkingDirectory.forSnapshot(node.getCurrentDirectory()));
        if (node.getScrollbackRef() != null && !isValidScrollbackRef(node.getScrollbackRef())) {
            node.setScrollbackRef(null);
        }
    }
}
