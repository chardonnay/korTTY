package com.sithtermfx.ui.split;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Which panes get a key typed in a pane: the one rule set behind broadcast mode, which mirrors a
 * pane's keys into the other panes of its tab, and an {@link InputMirror}, which mirrors them into
 * panes of other tabs and windows too.
 *
 * <ul>
 *   <li>The candidates are the tab's panes while its broadcast mode is on, followed by the mirror's
 *       other members while the pane is one. A pane in both lists gets the key once, at its place
 *       among the tab's panes. Panes are told apart by reference, never by {@code equals}.</li>
 *   <li>The pane the key was typed in never gets it a second time.</li>
 *   <li>A pane without a live connection gets nothing, and the guard holds back a pane that must not
 *       get keys from other panes now: korTTY's holds one that is pacing a paste, one an AI agent run
 *       drives and one whose coding agent waits for a decision.</li>
 *   <li>The key rule ({@link #admit}) can keep one key from some of the remaining panes; korTTY's
 *       sends a key typed at a password prompt only to the panes at one too. It is asked once per
 *       key, and only when there is a pane to send to, because it may read the panes' screens.</li>
 *   <li>A navigation key that runs an action of the pane it is pressed in stays there ({@link
 *       #routeOf}).</li>
 * </ul>
 *
 * <p>Generic over the pane type, with plain lists and predicates, so the choice is unit-tested
 * without the JavaFX toolkit.
 */
public final class BroadcastTargets {

    /** What a navigation key, such as an arrow or Page Up, does in the pane it is pressed in. */
    public enum KeyRoute {
        /**
         * It runs an action of that pane, such as scrolling its scrollback, and goes to no program:
         * neither the pane's own nor, in broadcast mode or multi-exec, any other pane's.
         */
        LOCAL_ACTION,
        /**
         * It goes to the pane's program and, while the pane mirrors its input, to every target,
         * encoded for each target's own program.
         */
        SEND_AND_MIRROR
    }

    /**
     * Which character of a key's KEY_TYPED event the panes that mirror the pane it was pressed in may
     * get, decided by what that pane did with the key's KEY_PRESSED. The character is mirrored only
     * when the pane sends the key to its own program as well, so a key that ran a pane action or was
     * left to a menu shortcut reaches no other pane either.
     *
     * <p>That matters for the control character Ctrl turns a letter into, which the KEY_TYPED of a
     * Ctrl+Shift chord still carries on Windows and Linux: {@code U+0003} after Ctrl+Shift+C, the copy
     * key, would interrupt the program in every mirrored pane, and {@code U+0016} after Ctrl+Shift+V,
     * the paste key, would make the shells there insert the next key literally. SithTermFX sends a
     * control character from the KEY_PRESSED, when the key's text is one, and ignores it in the
     * KEY_TYPED; printable characters it sends from the KEY_TYPED.
     */
    public enum TypedMirror {
        /**
         * The pane ran an action on the key, such as copy or paste, or the key was sent and mirrored
         * from its KEY_PRESSED already (Enter, Backspace, Esc and the navigation keys): its character
         * is not mirrored.
         */
        NONE,
        /**
         * The terminal left the key alone, so it types a printable character into its program and
         * nothing for a control character: only a printable character is mirrored. Also what a
         * KEY_TYPED gets whose KEY_PRESSED never reached the pane.
         */
        PRINTABLE_ONLY,
        /**
         * The terminal sent the key to its program itself, such as Ctrl+C: its character stands for
         * what it sent and is mirrored, a control character included.
         */
        ALL;

        /**
         * What a key may mirror before the terminal saw it.
         *
         * @param paneAction whether an action of the pane, such as copy or paste, runs on the key
         */
        public static @NotNull TypedMirror ofPress(boolean paneAction) {
            return paneAction ? NONE : PRINTABLE_ONLY;
        }

        /**
         * What the key may mirror once the terminal saw its KEY_PRESSED.
         *
         * @param terminalConsumed whether the terminal consumed it, which it does for a key it sent
         */
        public @NotNull TypedMirror afterTerminal(boolean terminalConsumed) {
            return this == PRINTABLE_ONLY && terminalConsumed ? ALL : this;
        }

        /** Whether {@code character}, the key's KEY_TYPED character, goes to the mirrored panes. */
        public boolean mirrors(char character) {
            return switch (this) {
                case NONE -> false;
                case PRINTABLE_ONLY -> !Character.isISOControl(character);
                case ALL -> true;
            };
        }
    }

    private BroadcastTargets() {
    }

    /**
     * The panes a key typed in {@code source} goes to, before any key rule: the panes of
     * {@code tabPanes} and then of {@code mirrorMembers}, each once and in that order, less the
     * source, the panes that are not {@code connected} and those the {@code guard} holds back.
     *
     * @param tabPanes the source's tab's panes while its broadcast mode is on, else none
     * @param mirrorMembers the input mirror's other members while the source is one, else none
     */
    public static <W> @NotNull List<W> resolve(@Nullable W source,
                                               @NotNull List<? extends W> tabPanes,
                                               @NotNull List<? extends W> mirrorMembers,
                                               @NotNull Predicate<? super W> connected,
                                               @NotNull Predicate<? super W> guard) {
        Set<W> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        if (source != null) {
            seen.add(source);
        }
        List<W> targets = new ArrayList<>();
        addTargets(tabPanes, seen, connected, guard, targets);
        addTargets(mirrorMembers, seen, connected, guard, targets);
        return targets;
    }

    /**
     * The panes of {@code targets} that get one key: those the key rule admits, in the same order.
     * The rule is asked once, and not at all when there is no pane to send to.
     *
     * @param targets the key's targets from {@link #resolve}
     * @param keyRule gives, for this key, which panes may get it
     */
    public static <W> @NotNull List<W> admit(@NotNull List<W> targets,
                                             @NotNull Supplier<? extends Predicate<? super W>> keyRule) {
        if (targets.isEmpty()) {
            return targets;
        }
        Predicate<? super W> admitted = keyRule.get();
        List<W> receivers = new ArrayList<>(targets.size());
        for (W target : targets) {
            if (admitted.test(target)) {
                receivers.add(target);
            }
        }
        return receivers;
    }

    /**
     * How many of {@code panes} the guard holds back now: each pane once, counted when it is
     * connected but not accepted. A disconnected pane gets nothing anyway and is not counted.
     */
    public static <W> int countHeld(@NotNull List<? extends W> panes,
                                    @NotNull Predicate<? super W> connected,
                                    @NotNull Predicate<? super W> guard) {
        Set<W> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int held = 0;
        for (W pane : panes) {
            if (seen.add(pane) && connected.test(pane) && !guard.test(pane)) {
                held++;
            }
        }
        return held;
    }

    /**
     * What a navigation key pressed in a pane does. A key that matches an action of the pane which can
     * run now stays in that pane: SithTermFX's scrollback keys, and any later pane action on a
     * navigation key, such as jumping between shell prompts, so no program in another pane gets a key
     * that only scrolled this one. While the action cannot run, or no action matches, the key goes to
     * the pane's program and is mirrored. The alternate screen of a full-screen program such as vim or
     * less has no scrollback, so there every navigation key goes to the program and the action is not
     * asked.
     *
     * @param paneAction whether the first matching pane action can run now, or {@code null} when no
     *     action matches the key
     */
    public static @NotNull KeyRoute routeOf(boolean alternateScreen, @Nullable BooleanSupplier paneAction) {
        if (alternateScreen || paneAction == null || !paneAction.getAsBoolean()) {
            return KeyRoute.SEND_AND_MIRROR;
        }
        return KeyRoute.LOCAL_ACTION;
    }

    private static <W> void addTargets(@NotNull List<? extends W> panes, @NotNull Set<W> seen,
                                       @NotNull Predicate<? super W> connected,
                                       @NotNull Predicate<? super W> guard, @NotNull List<W> targets) {
        for (W pane : panes) {
            if (pane != null && seen.add(pane) && connected.test(pane) && guard.test(pane)) {
                targets.add(pane);
            }
        }
    }
}
