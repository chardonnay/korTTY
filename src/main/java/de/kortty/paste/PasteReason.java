package de.kortty.paste;

/** Why a paste needs the user's confirmation before it reaches the pane. */
public enum PasteReason {

    /** The text holds a line break that would act as Enter. */
    MULTI_LINE,

    /** The text holds control characters or invisible direction-changing (bidi) characters. */
    CONTROL_CHARACTERS,

    /** The text is larger than the configured threshold. */
    LARGE
}
