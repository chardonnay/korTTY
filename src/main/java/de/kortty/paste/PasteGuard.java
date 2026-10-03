package de.kortty.paste;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one path every terminal paste takes in korTTY. It asks the {@link PasteRules} whether the
 * paste needs confirmation, asks the {@link PasteConfirmer} when it does, and writes the payload
 * from {@link PasteSanitizer#encode(String, boolean, java.nio.charset.Charset)} to the
 * {@link PasteTarget}: never a bracketed-paste marker from the text itself, line breaks as Enter,
 * and wrapped in markers when the program in the pane has enabled bracketed paste.
 *
 * <ul>
 *   <li>Each pane has at most one pending confirmation. A second paste into a pane that is already
 *       asking is dropped; on macOS one Cmd+V reaches both the terminal's paste action and the
 *       Edit menu.</li>
 *   <li>A confirmed paste is sent only if the pane can still receive text and still runs the session
 *       it was asked for: after a reconnect it is dropped instead of reaching a session nobody
 *       confirmed it for. Whether to bracket it is decided when it is sent.</li>
 *   <li>Only sizes, sources and reason codes are logged, at DEBUG; never the text.</li>
 * </ul>
 *
 * <p>Confined to the JavaFX thread.
 */
public final class PasteGuard {

    private static final Logger logger = LoggerFactory.getLogger(PasteGuard.class);

    private final Supplier<PasteRules> rules;

    private final PasteConfirmer confirmer;

    /** The keys of the panes with a confirmation on screen, compared by reference. */
    private final Set<Object> pending = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * @param rules read on every paste, so a settings change applies to the next one
     * @param confirmer asks the user when the rules want confirmation
     */
    public PasteGuard(Supplier<PasteRules> rules, PasteConfirmer confirmer) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.confirmer = Objects.requireNonNull(confirmer, "confirmer");
    }

    /**
     * Pastes {@code text} into {@code target}, after a confirmation when the rules ask for one.
     * Nothing happens for null or empty text or a target that cannot receive text.
     *
     * @param target the pane to paste into
     * @param text the text as it came from the clipboard
     * @param source where the text came from
     */
    public void paste(PasteTarget target, String text, PasteSource source) {
        if (target == null || text == null || text.isEmpty() || !target.canReceive()) {
            return;
        }
        PasteSource from = source != null ? source : PasteSource.CLIPBOARD;
        boolean bracketed = target.bracketedPasteMode();
        Set<PasteReason> reasons = reasonsFor(text, bracketed);
        if (reasons.isEmpty()) {
            send(target, text, from);
            return;
        }
        Object key = target.key();
        if (!pending.add(key)) {
            logger.debug("Paste dropped: the pane already asks to confirm a paste ({} chars, {})",
                text.length(), from);
            return;
        }
        Object session = target.session();
        PasteConfirmationRequest request = new PasteConfirmationRequest(target.label(), text, reasons, bracketed,
            from, target.broadcastActive());
        logger.debug("Paste needs confirmation ({} chars, {}, {})", text.length(), from, reasons);
        boolean[] answered = {false};
        try {
            confirmer.confirm(request, accepted -> {
                if (answered[0]) {
                    return;
                }
                answered[0] = true;
                pending.remove(key);
                if (!Boolean.TRUE.equals(accepted)) {
                    logger.debug("Paste cancelled ({} chars, {})", text.length(), from);
                } else if (!target.canReceive() || target.session() != session) {
                    logger.debug("Confirmed paste dropped: the pane's session changed ({} chars, {})",
                        text.length(), from);
                } else {
                    send(target, text, from);
                }
            });
        } catch (RuntimeException e) {
            answered[0] = true;
            pending.remove(key);
            logger.warn("Paste confirmation failed, nothing was pasted: {}", e.toString());
        }
    }

    /** Whether the pane with this key has a confirmation on screen. */
    boolean isPending(Object key) {
        return pending.contains(key);
    }

    private Set<PasteReason> reasonsFor(String text, boolean bracketed) {
        PasteRules current = rules.get();
        Set<PasteReason> reasons = current != null ? current.reasons(text, bracketed) : null;
        return reasons != null ? reasons : Set.of();
    }

    private static void send(PasteTarget target, String text, PasteSource source) {
        String payload = PasteSanitizer.encode(text, target.bracketedPasteMode(), target.charset());
        if (payload.isEmpty()) {
            logger.debug("Paste dropped: nothing left after removing bracketed-paste markers ({})", source);
            return;
        }
        try {
            target.send(payload);
        } catch (RuntimeException e) {
            logger.warn("Paste could not be sent ({} chars, {}): {}", payload.length(), source, e.toString());
        }
    }
}
