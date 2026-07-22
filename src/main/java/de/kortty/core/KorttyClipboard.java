package de.kortty.core;

import de.kortty.policy.ClipboardMode;
import de.kortty.policy.PolicyManager;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;

/**
 * Application-wide clipboard facade. In the default {@link ClipboardMode#SYSTEM} mode it is a thin
 * wrapper around the OS clipboard. Under the enterprise policy's
 * {@code [rule.security] clipboard-mode = "internal"} korTTY is confined to its own in-memory
 * clipboard: {@link #setText} never reaches the OS clipboard (no data exfiltration via copy) and
 * {@link #getText} never reads it (text copied in other applications cannot be pasted into
 * korTTY) — while copy/paste <i>within</i> korTTY keeps working across terminals, SFTP, editors
 * and dialogs, because they all share this internal buffer.
 *
 * <p>Purely application-level, therefore OS-independent — no platform clipboard API is required
 * to enforce the isolation. All korTTY copy/paste paths must go through this facade (or the
 * terminal's policy-aware copy-paste handler, which shares the same buffer).
 */
public final class KorttyClipboard {

    private static volatile String internalText;

    private KorttyClipboard() {
    }

    /** True when the enterprise policy confines korTTY to the internal clipboard. */
    public static boolean isInternalMode() {
        return PolicyManager.effective().clipboardMode() == ClipboardMode.INTERNAL;
    }

    /** Copies {@code text}: to the internal buffer in internal mode, else to the OS clipboard. */
    public static void setText(String text) {
        if (text == null) {
            return;
        }
        if (isInternalMode()) {
            internalText = text;
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    /** The pastable text, or null: internal buffer in internal mode, else the OS clipboard. */
    public static String getText() {
        if (isInternalMode()) {
            return internalText;
        }
        Clipboard clipboard = Clipboard.getSystemClipboard();
        return clipboard.hasString() ? clipboard.getString() : null;
    }

    /** Whether {@link #getText()} would return text. */
    public static boolean hasText() {
        if (isInternalMode()) {
            return internalText != null && !internalText.isEmpty();
        }
        return Clipboard.getSystemClipboard().hasString();
    }

    /**
     * The internal buffer regardless of mode — used by the terminal copy-paste handler, which
     * runs on the AWT side and must not touch the JavaFX clipboard class.
     */
    static String internalBuffer() {
        return internalText;
    }

    static void setInternalBuffer(String text) {
        internalText = text;
    }

    // ---- policy-aware edit actions for native text controls -------------------------------------
    // In system mode these delegate to the control's built-in actions; in internal mode they
    // perform the same edit against the internal buffer so the OS clipboard is never touched.

    public static void copySelection(javafx.scene.control.TextInputControl control) {
        if (!isInternalMode()) {
            control.copy();
            return;
        }
        String selected = control.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            internalText = selected;
        }
    }

    public static void cutSelection(javafx.scene.control.TextInputControl control) {
        if (!isInternalMode()) {
            control.cut();
            return;
        }
        String selected = control.getSelectedText();
        if (selected != null && !selected.isEmpty()) {
            internalText = selected;
            control.replaceSelection("");
        }
    }

    public static void pasteInto(javafx.scene.control.TextInputControl control) {
        if (!isInternalMode()) {
            control.paste();
            return;
        }
        String text = internalText;
        if (text != null && !text.isEmpty()) {
            control.replaceSelection(text);
        }
    }
}
