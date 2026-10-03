package de.kortty.paste;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Objects;
import java.util.Set;

/**
 * What a {@link PasteConfirmer} shows the user before a paste reaches a pane.
 *
 * @param label the pane as the user knows it, usually the connection's name
 * @param text the text as it came from the clipboard
 * @param reasons why the paste needs confirmation; never empty
 * @param bracketed whether the program in the pane had enabled bracketed paste when asked
 * @param source where the text came from
 * @param broadcastActive whether broadcast mode was on in the pane's tab; the paste still goes to
 *     this pane only
 */
public record PasteConfirmationRequest(String label, String text, Set<PasteReason> reasons, boolean bracketed,
        PasteSource source, boolean broadcastActive) {

    public PasteConfirmationRequest {
        label = label == null ? "" : label;
        Objects.requireNonNull(text, "text");
        // In the enum's order, so a confirmation lists the reasons the same way every time.
        reasons = reasons.isEmpty() ? Set.of() : Collections.unmodifiableSet(EnumSet.copyOf(reasons));
        Objects.requireNonNull(source, "source");
    }
}
