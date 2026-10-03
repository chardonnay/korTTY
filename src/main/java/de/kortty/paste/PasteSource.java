package de.kortty.paste;

/** Where the text of a terminal paste comes from. */
public enum PasteSource {

    /** The clipboard: Edit → Paste, the paste shortcut and the terminal's context menu. */
    CLIPBOARD,

    /** The selection a middle-click pastes (the X11 primary selection where there is one). */
    SELECTION,

    /** Text dropped onto the terminal. */
    DROP
}
