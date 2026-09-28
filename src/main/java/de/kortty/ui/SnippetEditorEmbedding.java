package de.kortty.ui;

import de.kortty.core.SnippetManager;
import de.kortty.model.Snippet;

/**
 * What an embedded {@link SnippetEditDialog} needs from its host (the snippet workspace). An
 * embedded editor is never shown as a window: it has no dialog buttons, persists through the
 * injected manager and reports every successful save so the host can refresh its library.
 */
interface SnippetEditorEmbedding {

    /** The manager the editor persists to (injected for correctness and test isolation). */
    SnippetManager snippetManager();

    /**
     * Called on the FX thread after the editor saved {@code saved} successfully.
     *
     * @param created {@code true} when this save created the snippet (first save of a draft)
     */
    void snippetPersisted(SnippetEditDialog editor, Snippet saved, boolean created);

    /** The editor's analysis side panel opened, {@code panelWidth} wide (the host may make room). */
    default void analysisPanelShown(SnippetEditDialog editor, double panelWidth) {
    }

    /** The editor's analysis side panel closed. */
    default void analysisPanelHidden(SnippetEditDialog editor) {
    }
}
