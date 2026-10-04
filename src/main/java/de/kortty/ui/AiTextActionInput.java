package de.kortty.ui;

import de.kortty.core.AiOutboundRedaction;
import de.kortty.core.RedactionResult;
import de.kortty.core.SessionJournalRedactor;
import de.kortty.model.AiProfile;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * The text a terminal AI action (Summarize, Solve, Ask) sends, checked and masked before the
 * confirm-before-send preview: {@code MainWindow.handleAiTextAction} uses it for a selection and
 * for "Summarize Recent Output" alike, so both go through the same masking, preview and masked
 * count. Toolkit-free, so the rules are tested without a window.
 */
final class AiTextActionInput {

    /** Where the text came from. */
    enum Origin {
        /** The pane's selection. */
        SELECTION,
        /** The pane's recent output, read by {@link TerminalRecentOutputSource}. */
        RECENT_OUTPUT
    }

    /** What {@link #prepare} decided. */
    enum Status {
        /** Send {@link Prepared#outboundText()}. */
        READY,
        /** A blank selection: nothing to do, silently, as before. */
        SKIP,
        /** No recent output to summarize: say so. */
        NO_OUTPUT,
        /** A selection longer than the profile's limit: say so. */
        TOO_LARGE
    }

    /**
     * The checked text.
     *
     * @param outboundText the masked text, when {@link Status#READY}
     * @param count        how many secrets were masked in it
     * @param shortened    whether recent output was cut to the profile's limit (its end is kept)
     */
    record Prepared(@NotNull Status status, @Nullable String outboundText, int count, boolean shortened) {
        static Prepared of(Status status) {
            return new Prepared(status, null, 0, false);
        }
    }

    private AiTextActionInput() {
    }

    /**
     * Checks {@code text} for {@code origin} and masks it for {@code profile} (see
     * {@link AiOutboundRedaction#redactFor}). A selection longer than {@code maxChars} is refused;
     * recent output is cut to its last {@code maxChars} characters instead, since the user did not
     * pick its length.
     */
    static @NotNull Prepared prepare(@NotNull Origin origin, @Nullable AiProfile profile, @Nullable String text,
                                     int maxChars, @Nullable SessionJournalRedactor knownSecrets) {
        if (text == null || text.trim().isEmpty()) {
            return Prepared.of(origin == Origin.RECENT_OUTPUT ? Status.NO_OUTPUT : Status.SKIP);
        }
        String input = text;
        boolean shortened = false;
        if (input.length() > maxChars) {
            if (origin == Origin.SELECTION) {
                return Prepared.of(Status.TOO_LARGE);
            }
            int start = Math.max(0, input.length() - Math.max(0, maxChars));
            if (start < input.length() && Character.isLowSurrogate(input.charAt(start))) {
                start++;
            }
            input = input.substring(start);
            shortened = true;
        }
        RedactionResult masked = AiOutboundRedaction.redactFor(profile, input, knownSecrets);
        return new Prepared(Status.READY, masked.text(), masked.count(), shortened);
    }
}
