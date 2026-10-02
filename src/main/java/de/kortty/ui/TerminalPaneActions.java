package de.kortty.ui;

/**
 * The per-pane terminal commands the right-click menu, <b>Edit &gt; Find</b> and the font-size
 * entries invoke. {@link KorttyTermWidget} implements them on top of SithTermFX's public API.
 *
 * <p>They used to be looked up by reflection on the widget's runtime class. Once the widget became
 * a subclass with an anonymous {@code TerminalPanel}, the lookup found nothing and every one of
 * these menu items silently did nothing. Calling them through this interface keeps a later
 * subclass or API change a compile error instead of a dead menu item.
 */
public interface TerminalPaneActions {

    /** Copies the current selection to the clipboard and keeps it selected; no selection does nothing. */
    void copySelection();

    /** Pastes the clipboard into the pane's session, the same way the paste shortcut does. */
    void paste();

    /**
     * Clears the scrollback and the screen but keeps the prompt line. While a full-screen program
     * uses the alternate screen this does nothing, as with the terminal's own clear shortcut.
     */
    void clearBuffer();

    /** Opens the pane's find bar, or focuses it when it is already open. */
    void showFind();

    /**
     * Enlarges the font by one step through the pane's settings provider. In korTTY that provider
     * shares one size per tab, so every split pane of the tab follows.
     */
    void increaseFontSize();

    /** Shrinks the font by one step; see {@link #increaseFontSize()}. */
    void decreaseFontSize();
}
