package de.kortty.policy;

import de.kortty.core.KorttyClipboard;
import javafx.collections.ListChangeListener;
import javafx.scene.Node;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.web.WebView;
import javafx.stage.Window;

/**
 * Window-level enforcement of the enterprise internal-clipboard mode for controls whose built-in
 * copy/cut/paste would otherwise reach the OS clipboard: native {@link TextInputControl}s and
 * WebViews (Monaco editor, HTML views). Installed once at startup and attached to every window —
 * present and future — via {@link Window#getWindows()}; inert unless the policy sets
 * {@code clipboard-mode = "internal"}.
 *
 * <p>The filter runs in the capturing phase, so it sees the shortcut before the control does:
 * copy/cut/paste on a text control are redirected to the internal clipboard, shortcuts inside a
 * {@code MonacoEditorPane} go through its policy-aware actions, and shortcuts inside any other
 * WebView are consumed outright (WebKit's clipboard bridge cannot be redirected). Everything else
 * — notably the terminal, whose copy/paste handler is already policy-aware — passes through
 * untouched.
 */
public final class PolicyClipboardGuard {

    private static final KeyCombination COPY =
        new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination CUT =
        new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination PASTE =
        new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN);

    private PolicyClipboardGuard() {
    }

    /** Installs the guard on all current and future windows. No-op in system clipboard mode. */
    public static void install() {
        if (!KorttyClipboard.isInternalMode()) {
            return;
        }
        Window.getWindows().forEach(PolicyClipboardGuard::attach);
        Window.getWindows().addListener((ListChangeListener<Window>) change -> {
            while (change.next()) {
                change.getAddedSubList().forEach(PolicyClipboardGuard::attach);
            }
        });
    }

    private static void attach(Window window) {
        window.addEventFilter(KeyEvent.KEY_PRESSED, PolicyClipboardGuard::handle);
    }

    private static void handle(KeyEvent event) {
        boolean copy = COPY.match(event);
        boolean cut = CUT.match(event);
        boolean paste = PASTE.match(event);
        if (!copy && !cut && !paste) {
            return;
        }
        Node node = event.getTarget() instanceof Node n ? n : null;
        boolean insideWebView = false;
        while (node != null) {
            if (node instanceof TextInputControl control) {
                if (copy) {
                    KorttyClipboard.copySelection(control);
                } else if (cut && control.isEditable()) {
                    KorttyClipboard.cutSelection(control);
                } else if (paste && control.isEditable()) {
                    KorttyClipboard.pasteInto(control);
                }
                event.consume();
                return;
            }
            if (node instanceof de.kortty.ui.MonacoEditorPane pane) {
                // The WebView below already matched, but Monaco has policy-aware actions.
                if (copy) {
                    pane.copy();
                } else if (cut) {
                    pane.cut();
                } else {
                    pane.paste();
                }
                event.consume();
                return;
            }
            if (node instanceof WebView) {
                // Keep walking up — the WebView may belong to a MonacoEditorPane.
                insideWebView = true;
            }
            node = node.getParent();
        }
        if (insideWebView) {
            // Read-only HTML views (AI chat, guide): block the WebKit clipboard bridge.
            // Copying from these views uses their explicit copy buttons, which are internal.
            event.consume();
        }
    }
}
