package de.kortty.ui;

import javafx.application.Platform;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * App-wide registry of open snippet editors, so a snippet is edited in at most one place across all
 * main windows, workspaces and standalone editor windows (tool-tab dedupe is per window only).
 *
 * <p>Two kinds of entries: {@link #claim claims} bind an editor to a snippet id (a second editor
 * asking for the same id is refused and should {@link OpenEditor#reveal() reveal} the first), and
 * {@link #track tracking} lists every open editor — claimed or not (unsaved drafts) — for the
 * close/quit guards. {@link #release} drops both.
 *
 * <p>Contract: FX thread only. The check is lenient (one warning) so pure unit tests can drive the
 * registry without a toolkit.
 */
public final class SnippetEditorRegistry {

    private static final Logger logger = LoggerFactory.getLogger(SnippetEditorRegistry.class);

    /** What the registry needs from an open editor, whether it is a window or a hosted tab. */
    public interface OpenEditor {
        /** The edited snippet's id, or {@code null} for a never-saved draft. */
        String snippetId();

        /** Brings the editor to the front (focus its window, select its tab). */
        void reveal();

        boolean hasUnsavedChanges();

        /** The stage that owns the editor, or {@code null} (e.g. the swarm editor). */
        Window ownerStage();

        /** Runs the editor's own unsaved-changes prompt; {@code false} vetoes the close. */
        boolean confirmCloseFromHost();

        /** Closes the editor discarding nothing but asking nothing either (the host already asked). */
        void closeWithoutPrompt();
    }

    private static final Map<String, OpenEditor> CLAIMS = new LinkedHashMap<>();
    private static final List<OpenEditor> EDITORS = new ArrayList<>();
    private static final AtomicBoolean OFF_FX_THREAD_WARNED = new AtomicBoolean();

    private SnippetEditorRegistry() {
    }

    /** The editor that currently holds {@code snippetId}, if any. */
    public static Optional<OpenEditor> find(String snippetId) {
        warnIfOffFxThread();
        if (snippetId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(CLAIMS.get(snippetId));
    }

    /**
     * Binds {@code snippetId} to {@code editor} and tracks the editor. Returns {@code false} when a
     * different editor already holds the id (the caller should reveal that one instead); a repeated
     * claim by the same editor is a no-op returning {@code true}. An editor edits one snippet, so any
     * earlier claim of the same editor (e.g. a draft id before the first save) is dropped.
     */
    public static boolean claim(String snippetId, OpenEditor editor) {
        warnIfOffFxThread();
        Objects.requireNonNull(snippetId, "snippetId");
        Objects.requireNonNull(editor, "editor");
        OpenEditor holder = CLAIMS.get(snippetId);
        if (holder != null && holder != editor) {
            return false;
        }
        CLAIMS.values().removeIf(other -> other == editor);
        CLAIMS.put(snippetId, editor);
        track(editor);
        return true;
    }

    /** Lists {@code editor} for the close/quit guards without binding it to an id. */
    public static void track(OpenEditor editor) {
        warnIfOffFxThread();
        Objects.requireNonNull(editor, "editor");
        if (EDITORS.stream().noneMatch(other -> other == editor)) {
            EDITORS.add(editor);
        }
    }

    /** Forgets {@code editor}: its claims and its tracking entry. Unknown editors are ignored. */
    public static void release(OpenEditor editor) {
        warnIfOffFxThread();
        if (editor == null) {
            return;
        }
        CLAIMS.values().removeIf(other -> other == editor);
        EDITORS.removeIf(other -> other == editor);
    }

    /** Tracked editors whose {@link OpenEditor#ownerStage()} is {@code stage} (identity). */
    public static List<OpenEditor> standaloneOwnedBy(Window stage) {
        warnIfOffFxThread();
        List<OpenEditor> owned = new ArrayList<>();
        for (OpenEditor editor : EDITORS) {
            if (editor.ownerStage() == stage) {
                owned.add(editor);
            }
        }
        return owned;
    }

    /** Every tracked editor, in registration order (snapshot). */
    public static List<OpenEditor> all() {
        warnIfOffFxThread();
        return new ArrayList<>(EDITORS);
    }

    private static void warnIfOffFxThread() {
        boolean onFxThread;
        try {
            onFxThread = Platform.isFxApplicationThread();
        } catch (RuntimeException toolkitUnavailable) {
            return;
        }
        if (!onFxThread && OFF_FX_THREAD_WARNED.compareAndSet(false, true)) {
            logger.warn("SnippetEditorRegistry used off the FX thread ({}); it is FX-thread-only by contract",
                Thread.currentThread().getName(), new IllegalStateException("caller"));
        }
    }
}
