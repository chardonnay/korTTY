package de.kortty.core.highlight;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * What the highlighting toggle (Cmd/Ctrl+Shift+H, <i>View → Highlighting → Highlighting On</i> and the
 * pane's context menu) does to one pane. Pure, so the rule is unit-tested without a toolkit.
 *
 * <p>The toggle reads the pane's real state — the set it shows now — and never the check mark of the
 * menu item that fired it: JavaFX flips a {@code CheckMenuItem} before its action runs, both for a
 * click and for its accelerator, so the item's state says nothing about the pane's.
 *
 * <ul>
 *   <li>A pane that shows a set is switched off with an explicit {@value TerminalHighlightService#NONE_ID}
 *       choice, so a default or connection set below it no longer applies.</li>
 *   <li>A pane that shows nothing gets, in this order: the set it showed last in this session, the set
 *       it would inherit without a choice of its own (connection, then global default), or
 *       {@value HighlightBuiltinSets#ERRORS}. When the chosen set is the inherited one, the pane's own
 *       choice is cleared instead of pinned, so it keeps following the levels below.</li>
 *   <li>With the master switch off the toggle does nothing: no choice could make a set show.</li>
 * </ul>
 */
public final class HighlightToggle {

    /** The set the toggle falls back to when nothing else is known for a pane. */
    public static final String FALLBACK_SET_ID = HighlightBuiltinSets.ERRORS;

    private HighlightToggle() {
    }

    /**
     * The outcome of one toggle.
     *
     * @param paneOverride the pane's new runtime choice: a set id, {@value TerminalHighlightService#NONE_ID},
     *                     or {@code null} to inherit
     * @param shownSetId the set the pane shows afterwards, or {@code null} for none
     */
    public record Choice(String paneOverride, String shownSetId) {

        /** Whether the pane shows a set after the toggle. */
        public boolean on() {
            return shownSetId != null;
        }
    }

    /**
     * Decides the toggle for a pane.
     *
     * @param enabled the master switch
     * @param shownSetId the set the pane shows now, {@code null} for none
     * @param lastUsedSetId the set the pane showed last in this session, or {@code null}
     * @param inheritedSetId the set the pane would show without a choice of its own, or {@code null}
     * @param known which ids name a set the panes can show
     * @return the pane's new choice, or empty when the toggle does nothing
     */
    public static Optional<Choice> toggle(boolean enabled, String shownSetId, String lastUsedSetId,
                                          String inheritedSetId, Predicate<String> known) {
        if (!enabled) {
            return Optional.empty();
        }
        if (shownSetId != null) {
            return Optional.of(new Choice(TerminalHighlightService.NONE_ID, null));
        }
        String inherited = knownOrNull(inheritedSetId, known);
        String target = knownOrNull(lastUsedSetId, known);
        if (target == null) {
            target = inherited;
        }
        if (target == null) {
            target = knownOrNull(FALLBACK_SET_ID, known);
        }
        if (target == null) {
            return Optional.empty();
        }
        return Optional.of(new Choice(target.equals(inherited) ? null : target, target));
    }

    private static String knownOrNull(String id, Predicate<String> known) {
        if (id == null || id.isBlank()) {
            return null;
        }
        String trimmed = id.trim();
        return !TerminalHighlightService.NONE_ID.equals(trimmed) && known.test(trimmed) ? trimmed : null;
    }
}
