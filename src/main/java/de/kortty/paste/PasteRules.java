package de.kortty.paste;

import java.util.Set;

/**
 * Decides which {@link PasteReason}s a paste raises. An empty set lets the paste through at once.
 *
 * <p>Implementations are pure and called on the JavaFX thread for every paste. {@link PasteDecision}
 * implements korTTY's paste protection.
 */
@FunctionalInterface
public interface PasteRules {

    /** Rules that never ask: every paste goes straight to the pane. */
    PasteRules NONE = (text, bracketed) -> Set.of();

    /**
     * The reasons to confirm this paste.
     *
     * @param text the text as it came from the clipboard, before any sanitizing
     * @param bracketed whether the program in the target pane has enabled bracketed paste
     * @return the reasons; never null, empty when the paste needs no confirmation
     */
    Set<PasteReason> reasons(String text, boolean bracketed);

    /**
     * Whether these rules are the paste protection the pane's connection sets for itself rather than the
     * one of Settings → Terminal, so a confirmation points to the connection's settings instead.
     */
    default boolean setByConnection() {
        return false;
    }
}
