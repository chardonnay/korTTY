package de.kortty.paste;

import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.Nullable;
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
 *   <li>With a {@link PastePacer} and a line delay above 0, a paste of several lines is sent line by
 *       line. A paste into a pane that is still pacing an earlier one is dropped.</li>
 *   <li>The rules and the line delay are read for the pane pasted into, so a pane whose connection
 *       sets its own paste protection follows it while the other panes of the tab follow theirs.</li>
 *   <li>Text from {@link PasteSource#AI} gets a strict floor on top of the pane's rules: a line break
 *       or a control character always asks, even where the pane's connection relaxes paste
 *       protection, and control and bidi characters are removed before it is sent
 *       ({@link PasteSanitizer#stripControlCharacters(String)}). A pane whose input is mirrored
 *       ({@link PasteTarget#broadcastActive()} or {@link PasteTarget#multiExecActive()}) never
 *       receives it, without asking: {@link PasteTarget#send(String)} writes as user input, so the
 *       text would reach every pane of the broadcast or the group.</li>
 *   <li>Only sizes, sources and reason codes are logged, at DEBUG; never the text.</li>
 * </ul>
 *
 * <p>Confined to the JavaFX thread.
 */
public final class PasteGuard {

    private static final Logger logger = LoggerFactory.getLogger(PasteGuard.class);

    private final Function<? super PasteTarget, PasteRules> rules;

    private final PasteConfirmer confirmer;

    /** Sends pastes line by line when {@link #lineDelayMs} asks for it; null sends every paste at once. */
    private final PastePacer pacer;

    private final ToIntFunction<? super PasteTarget> lineDelayMs;

    /** The keys of the panes with a confirmation on screen, compared by reference. */
    private final Set<Object> pending = Collections.newSetFromMap(new IdentityHashMap<>());

    /**
     * @param rules read on every paste, so a settings change applies to the next one
     * @param confirmer asks the user when the rules want confirmation
     */
    public PasteGuard(Supplier<PasteRules> rules, PasteConfirmer confirmer) {
        this(rules, confirmer, null, () -> 0);
    }

    /**
     * @param rules read on every paste, so a settings change applies to the next one
     * @param confirmer asks the user when the rules want confirmation
     * @param pacer sends a paste line by line; null sends every paste at once
     * @param lineDelayMs the pause after each pasted line in milliseconds, read when a paste is sent;
     *     0 sends it at once
     */
    public PasteGuard(Supplier<PasteRules> rules, PasteConfirmer confirmer, PastePacer pacer,
            IntSupplier lineDelayMs) {
        this(perTarget(Objects.requireNonNull(rules, "rules")), confirmer, pacer,
            perTarget(Objects.requireNonNull(lineDelayMs, "lineDelayMs")));
    }

    /**
     * @param rules the rules for the pane pasted into, read on every paste, so a settings change applies
     *     to the next one
     * @param confirmer asks the user when the rules want confirmation
     * @param pacer sends a paste line by line; null sends every paste at once
     * @param lineDelayMs the pause after each pasted line in milliseconds for the pane pasted into, read
     *     when a paste is sent; 0 sends it at once
     */
    public PasteGuard(Function<? super PasteTarget, PasteRules> rules, PasteConfirmer confirmer, PastePacer pacer,
            ToIntFunction<? super PasteTarget> lineDelayMs) {
        this.rules = Objects.requireNonNull(rules, "rules");
        this.confirmer = Objects.requireNonNull(confirmer, "confirmer");
        this.pacer = pacer;
        this.lineDelayMs = Objects.requireNonNull(lineDelayMs, "lineDelayMs");
    }

    private static Function<PasteTarget, PasteRules> perTarget(Supplier<PasteRules> rules) {
        return target -> rules.get();
    }

    private static ToIntFunction<PasteTarget> perTarget(IntSupplier lineDelayMs) {
        return target -> lineDelayMs.getAsInt();
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
        paste(target, text, source, null);
    }

    /** How a {@link #paste(PasteTarget, String, PasteSource, Consumer) paste} ended. */
    public enum Outcome {
        /** The text was handed to the pane (or to its pacer). */
        SENT,
        /** The user declined the confirmation. */
        CANCELLED,
        /**
         * Nothing was sent without the user declining: the pane could not receive it, its input is
         * mirrored (AI text), it is still pacing or asking about another paste, its session changed
         * while the confirmation was open, or the text was empty after cleaning.
         */
        REFUSED
    }

    /**
     * {@link #paste(PasteTarget, String, PasteSource)}, telling {@code outcome} how it ended: at once,
     * or when the confirmation is answered. Called exactly once, on the thread that pastes or answers.
     *
     * @param outcome receives how the paste ended; may be {@code null}
     */
    public void paste(PasteTarget target, String text, PasteSource source, @Nullable Consumer<Outcome> outcome) {
        Consumer<Outcome> report = outcome != null ? outcome : unused -> { };
        if (target == null || text == null || text.isEmpty() || !target.canReceive()) {
            report.accept(Outcome.REFUSED);
            return;
        }
        PasteSource from = source != null ? source : PasteSource.CLIPBOARD;
        if (from == PasteSource.AI && mirrored(target)) {
            logger.debug("AI text refused: the pane's input is mirrored by broadcast or multi-exec ({} chars)",
                text.length());
            report.accept(Outcome.REFUSED);
            return;
        }
        if (isPacing(target)) {
            logger.debug("Paste dropped: the pane is still pacing a paste ({} chars, {})", text.length(), from);
            report.accept(Outcome.REFUSED);
            return;
        }
        boolean bracketed = target.bracketedPasteMode();
        PasteRules current = rules.apply(target);
        Set<PasteReason> reasons = reasonsFor(current, text, bracketed);
        if (from == PasteSource.AI) {
            reasons = withAiFloor(reasons, text);
        }
        if (reasons.isEmpty()) {
            report.accept(send(target, text, from) ? Outcome.SENT : Outcome.REFUSED);
            return;
        }
        Object key = target.key();
        if (!pending.add(key)) {
            logger.debug("Paste dropped: the pane already asks to confirm a paste ({} chars, {})",
                text.length(), from);
            report.accept(Outcome.REFUSED);
            return;
        }
        Object session = target.session();
        PasteConfirmationRequest request = new PasteConfirmationRequest(target.label(), text, reasons, bracketed,
            from, target.broadcastActive(), current != null && current.setByConnection());
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
                    report.accept(Outcome.CANCELLED);
                } else if (!target.canReceive() || target.session() != session) {
                    logger.debug("Confirmed paste dropped: the pane's session changed ({} chars, {})",
                        text.length(), from);
                    report.accept(Outcome.REFUSED);
                } else if (from == PasteSource.AI && mirrored(target)) {
                    logger.debug("Confirmed AI text dropped: the pane's input is mirrored now ({} chars)",
                        text.length());
                    report.accept(Outcome.REFUSED);
                } else {
                    report.accept(send(target, text, from) ? Outcome.SENT : Outcome.REFUSED);
                }
            });
        } catch (RuntimeException e) {
            boolean unanswered = !answered[0];
            answered[0] = true;
            pending.remove(key);
            logger.warn("Paste confirmation failed, nothing was pasted: {}", e.toString());
            if (unanswered) {
                report.accept(Outcome.REFUSED);
            }
        }
    }

    /** Whether the pane with this key has a confirmation on screen. */
    boolean isPending(Object key) {
        return pending.contains(key);
    }

    private static Set<PasteReason> reasonsFor(PasteRules current, String text, boolean bracketed) {
        Set<PasteReason> reasons = current != null ? current.reasons(text, bracketed) : null;
        return reasons != null ? reasons : Set.of();
    }

    /**
     * The pane's reasons plus the ones AI text always raises: {@link PasteReason#MULTI_LINE} for a
     * line break, bracketed or not, and {@link PasteReason#CONTROL_CHARACTERS} for control or bidi
     * characters. A reason the pane's rules raise, such as {@link PasteReason#LARGE}, stays.
     */
    static Set<PasteReason> withAiFloor(Set<PasteReason> reasons, String text) {
        PasteInspection inspection = PasteInspection.of(text);
        EnumSet<PasteReason> merged = EnumSet.noneOf(PasteReason.class);
        merged.addAll(reasons);
        if (inspection.containsLineBreak()) {
            merged.add(PasteReason.MULTI_LINE);
        }
        if (inspection.containsControlCharacters()) {
            merged.add(PasteReason.CONTROL_CHARACTERS);
        }
        return merged.isEmpty() ? Set.of() : Collections.unmodifiableSet(merged);
    }

    /** Whether what the pane receives as user input also reaches other panes; unreadable counts as yes. */
    private static boolean mirrored(PasteTarget target) {
        try {
            return target.broadcastActive() || target.multiExecActive();
        } catch (RuntimeException e) {
            logger.debug("Paste target mirroring unreadable, treating the pane as mirrored: {}", e.toString());
            return true;
        }
    }

    private boolean isPacing(PasteTarget target) {
        return pacer != null && pacer.isPacing(target.key());
    }

    /** Sends the cleaned text; whether anything was handed to the pane or its pacer. */
    private boolean send(PasteTarget target, String text, PasteSource source) {
        String body = source == PasteSource.AI ? PasteSanitizer.stripControlCharacters(text) : text;
        String payload = PasteSanitizer.encode(body, target.bracketedPasteMode(), target.charset());
        if (payload.isEmpty()) {
            logger.debug("Paste dropped: nothing left after removing bracketed-paste markers ({})", source);
            return false;
        }
        try {
            int delayMs = pacer != null ? currentLineDelayMs(target) : 0;
            if (delayMs > 0) {
                pacer.send(target, payload, delayMs);
            } else {
                target.send(payload);
            }
            return true;
        } catch (RuntimeException e) {
            logger.warn("Paste could not be sent ({} chars, {}): {}", payload.length(), source, e.toString());
            return false;
        }
    }

    /** The pane's line delay as it is now; unreadable means 0, so the paste still goes out at once. */
    private int currentLineDelayMs(PasteTarget target) {
        try {
            return PastePacer.clampLineDelayMs(lineDelayMs.applyAsInt(target));
        } catch (RuntimeException e) {
            logger.debug("Paste line delay unreadable, pasting at once: {}", e.toString());
            return 0;
        }
    }
}
