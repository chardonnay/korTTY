package de.kortty.ui;

import java.util.List;
import java.util.Objects;

/**
 * Decides what the snippet workspace does when the user browses or opens a snippet. Pure logic,
 * no toolkit: the workspace describes its open inner tabs and whether another editor elsewhere
 * (another main window's workspace) already holds the snippet.
 *
 * <p>Browsing (single click, arrow keys) never steals focus from another window: a snippet open
 * elsewhere is only shown read-only in the preview, with a way to jump there. Opening
 * (double-click, Enter, Edit, typing into the preview) pins the snippet in an editor tab — each
 * snippet at most once across all workspaces.
 */
final class SnippetEditorTabPolicy {

    enum Kind { PREVIEW, PINNED }

    /** One inner tab of the workspace; {@code id} is the snippet id (a draft id for new snippets). */
    record OpenTab(String id, Kind kind) {
    }

    sealed interface Decision permits SelectExisting, ShowInPreview, Promote, OpenPinned, RevealElsewhere, Refuse {
        String id();
    }

    /** A pinned tab for the snippet already exists here: select it. */
    record SelectExisting(String id) implements Decision {
    }

    /** Show the snippet read-only in the (single, reused) preview tab. */
    record ShowInPreview(String id, boolean openElsewhere) implements Decision {
    }

    /** The preview shows this snippet: turn it into a pinned editor tab in place. */
    record Promote(String id) implements Decision {
    }

    /** Open a new pinned editor tab. */
    record OpenPinned(String id) implements Decision {
    }

    /** Another workspace (or window) already edits the snippet: bring that editor forward. */
    record RevealElsewhere(String id) implements Decision {
    }

    /** The snippet cannot be edited (policy-managed): keep it in the preview. */
    record Refuse(String id) implements Decision {
    }

    private SnippetEditorTabPolicy() {
    }

    /** Single click or arrow-key selection of {@code id} in the library. */
    static Decision onSingleClick(List<OpenTab> open, String id, boolean openElsewhere) {
        Objects.requireNonNull(id, "id");
        if (hasTab(open, id, Kind.PINNED)) {
            return new SelectExisting(id);
        }
        return new ShowInPreview(id, openElsewhere);
    }

    /** Double-click, Enter, Edit button or typing into the preview of {@code id}. */
    static Decision onOpen(List<OpenTab> open, String id, boolean policyManaged, boolean openElsewhere) {
        Objects.requireNonNull(id, "id");
        if (hasTab(open, id, Kind.PINNED)) {
            return new SelectExisting(id);
        }
        if (openElsewhere) {
            return new RevealElsewhere(id);
        }
        if (policyManaged) {
            return new Refuse(id);
        }
        if (hasTab(open, id, Kind.PREVIEW)) {
            return new Promote(id);
        }
        return new OpenPinned(id);
    }

    private static boolean hasTab(List<OpenTab> open, String id, Kind kind) {
        if (open == null) {
            return false;
        }
        for (OpenTab tab : open) {
            if (tab != null && tab.kind() == kind && id.equals(tab.id())) {
                return true;
            }
        }
        return false;
    }
}
