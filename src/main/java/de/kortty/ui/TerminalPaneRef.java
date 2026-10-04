package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.core.DisplayTextSanitizer;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.List;

/**
 * One terminal pane, named for the user, that an AI chat came from: the chat's code blocks are inserted
 * into it or run there ({@link AiCodeBlockTerminalAction}). The tab and the pane are held weakly, so a chat
 * left open never keeps a closed terminal alive; once either is gone, or the pane left its tab,
 * {@link #isOpen()} is false and the chat falls back to the focused pane of the current terminal tab.
 *
 * <p>A reopened saved chat has no pane: nothing binds one.
 */
public final class TerminalPaneRef {

    /** How long a tab title may be in the pane's name. */
    static final int MAX_NAME_CHARS = 80;

    private final WeakReference<TerminalTab> tab;
    private final WeakReference<KorttyTermWidget> pane;
    private final String boundName;

    private TerminalPaneRef(TerminalTab tab, KorttyTermWidget pane, String boundName) {
        this.tab = new WeakReference<>(tab);
        this.pane = new WeakReference<>(pane);
        this.boundName = boundName;
    }

    /**
     * The pane {@code widget} of {@code tab}, or {@code null} when either is missing or the widget is not a
     * korTTY terminal pane (only those take guarded input). FX thread: it reads the tab's panes for the name.
     */
    static @Nullable TerminalPaneRef of(@Nullable TerminalTab tab, @Nullable SithTermFxWidget widget) {
        if (tab == null || !(widget instanceof KorttyTermWidget pane)) {
            return null;
        }
        return new TerminalPaneRef(tab, pane, liveName(tab, pane));
    }

    /**
     * The focused pane of {@code tab}, or {@code null} for no tab or a tab without a terminal pane. FX
     * thread.
     */
    static @Nullable TerminalPaneRef focusedPaneOf(@Nullable TerminalTab tab) {
        if (tab == null) {
            return null;
        }
        try {
            TerminalView view = tab.getTerminalView();
            return view != null ? of(tab, view.getFocusedWidget()) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** The tab, or {@code null} once it was collected. */
    @Nullable TerminalTab tab() {
        return tab.get();
    }

    /** The pane, or {@code null} once it was collected. */
    @Nullable KorttyTermWidget pane() {
        return pane.get();
    }

    /** Whether the pane is still open: its tab is in a window and still shows it. FX thread. */
    boolean isOpen() {
        TerminalTab terminalTab = tab.get();
        KorttyTermWidget widget = pane.get();
        if (terminalTab == null || widget == null || terminalTab.getTabPane() == null) {
            return false;
        }
        try {
            TerminalView view = terminalTab.getTerminalView();
            return view != null && view.hasPane(widget);
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The pane's name as the confirmation and the tooltips show it: the tab's title, and the pane's number
     * when the tab is split ({@link #displayName(String, int, int)}). Read live while the pane is open, so a
     * renamed tab or a new split shows; the name from the binding otherwise. FX thread.
     */
    String displayName() {
        TerminalTab terminalTab = tab.get();
        KorttyTermWidget widget = pane.get();
        if (terminalTab != null && widget != null && isOpen()) {
            return liveName(terminalTab, widget);
        }
        return boundName;
    }

    /** Whether this reference names {@code widget}. */
    boolean refersTo(@Nullable SithTermFxWidget widget) {
        return widget != null && pane.get() == widget;
    }

    private static String liveName(TerminalTab tab, KorttyTermWidget widget) {
        String title = tab.getText();
        int index = 0;
        int count = 1;
        try {
            TerminalView view = tab.getTerminalView();
            List<SithTermFxWidget> panes = view != null ? view.getOrderedWidgets() : List.of();
            count = Math.max(1, panes.size());
            index = panes.indexOf(widget) + 1;
        } catch (RuntimeException e) {
            // The name falls back to the tab's title.
        }
        return displayName(title, index, count);
    }

    /**
     * The name of pane {@code index} (1-based; 0 when unknown) of {@code paneCount} in a tab titled
     * {@code tabTitle}: the sanitized title alone for an unsplit tab, the title and the pane's number for a
     * split one.
     */
    static String displayName(@Nullable String tabTitle, int index, int paneCount) {
        String title = DisplayTextSanitizer.sanitize(tabTitle != null ? tabTitle : "", MAX_NAME_CHARS);
        if (title.isBlank()) {
            title = I18n.get("ai.result.terminal.unnamedTab");
        }
        if (paneCount <= 1 || index <= 0) {
            return title;
        }
        return I18n.get("ai.result.terminal.paneName", title, index);
    }

    @Override
    public String toString() {
        return "TerminalPaneRef[" + boundName + "]";
    }
}
