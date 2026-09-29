package de.kortty.ui;

/**
 * Where a {@link ThemeAwareDialog}'s pane lives when it is embedded outside its own window: a
 * main-window tab ({@link DialogHostTab}) or an inner tab of the snippet workspace
 * ({@link SnippetEditorTab}). The dialog routes its close/open/reveal calls through this host.
 */
interface DialogPaneHost {

    /** Removes the pane from its host and runs the dialog's close lifecycle exactly once. */
    void closeProgrammatically();

    /** Whether the pane is currently mounted (e.g. the tab still sits in a tab pane). */
    boolean isAttached();

    /** Brings the host to the front: selects the tab and focuses the window that shows it. */
    void reveal();
}
