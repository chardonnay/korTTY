package de.kortty.paste;

/** Where the text of a terminal paste comes from. */
public enum PasteSource {

    /** The clipboard: Edit → Paste, the paste shortcut and the terminal's context menu. */
    CLIPBOARD,

    /** The selection a middle-click pastes (the X11 primary selection where there is one). */
    SELECTION,

    /** Text dropped onto the terminal. */
    DROP,

    /**
     * Text the AI assistant wrote, such as a code block of an AI chat answer. It is untrusted, since
     * terminal output or a document can steer what a model writes, so {@link PasteGuard} gives it a
     * strict floor: a line break or a control character always asks, whatever the pane's paste
     * protection says, control and bidi characters are removed before it is sent, and a pane whose
     * input is mirrored (broadcast or multi-exec) never receives it.
     */
    AI
}
