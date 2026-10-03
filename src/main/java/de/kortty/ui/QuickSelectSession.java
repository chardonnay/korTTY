package de.kortty.ui;

import de.kortty.core.QuickSelectLabels;
import de.kortty.core.TerminalLinkDetector.Kind;
import de.kortty.ui.QuickSelectScreen.Hit;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * One run of quick select as a state machine over key presses: the labelled matches on screen and
 * the letters typed so far.
 *
 * <p>The decision uses the {@link KeyCode} and the Shift key, never the typed character, so Caps
 * Lock never turns a copy into an open and the keyboard layout's characters do not matter.
 * Typing a label copies its match, Shift with the label's last letter opens it; a letter that only
 * starts labels narrows the choice, Backspace takes the last letter back, and a letter no label
 * continues with is ignored. Modifier keys on their own and the chord that started quick select
 * (held down, it repeats) are ignored; Escape and every other key cancel, including a letter with
 * Ctrl, Alt or Cmd.
 *
 * <p>Toolkit-free: {@link KeyPress#matches} compares a chord without asking the toolkit for the
 * platform's shortcut key.
 */
final class QuickSelectSession {

    /** Key codes that only modify another key; pressing one alone changes nothing. */
    private static final Set<KeyCode> MODIFIERS = EnumSet.of(KeyCode.SHIFT, KeyCode.CONTROL, KeyCode.ALT,
        KeyCode.META, KeyCode.COMMAND, KeyCode.CAPS, KeyCode.ALT_GRAPH, KeyCode.WINDOWS, KeyCode.NUM_LOCK,
        KeyCode.SCROLL_LOCK, KeyCode.SHORTCUT);

    /** What the caller does after a key press. */
    enum Action {
        /** The session goes on; the letters typed so far may have changed. */
        CONTINUE,
        /** Copy the target's text and end the session. */
        COPY,
        /** Open the target (or copy what cannot be opened) and end the session. */
        OPEN,
        /** End the session without doing anything. */
        CANCEL,
        /** Nothing changes: a modifier key or the starting chord. */
        IGNORE
    }

    /**
     * A label and what it picks: one text, shown at one or more places on screen.
     *
     * @param label the letters to type
     * @param kind  what the text is, as found at its bottom-most place
     * @param text  the text as captured
     * @param hits  the places it is shown, bottom row first
     */
    record Target(@NotNull String label, @NotNull Kind kind, @NotNull String text, @NotNull List<Hit> hits) {

        Target {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(text, "text");
            hits = List.copyOf(hits);
        }
    }

    /** The result of a key press: the action, and for COPY and OPEN the target. */
    record Outcome(@NotNull Action action, @Nullable Target target) {

        static final Outcome CONTINUE = new Outcome(Action.CONTINUE, null);
        static final Outcome CANCEL = new Outcome(Action.CANCEL, null);
        static final Outcome IGNORE = new Outcome(Action.IGNORE, null);
    }

    private final List<Target> targets;
    private final @Nullable KeyCombination trigger;
    private String prefix = "";

    private QuickSelectSession(@NotNull List<Target> targets, @Nullable KeyCombination trigger) {
        this.targets = new ArrayList<>(targets);
        this.trigger = trigger;
    }

    /**
     * A session over {@code hits}: each distinct text gets a label from {@code alphabet}, the bottom
     * row first; texts past the alphabet's capacity get none and are not offered.
     *
     * @param hits     the matches on screen, in any order
     * @param alphabet the label letters, see {@link QuickSelectLabels}
     * @param trigger  the chord that starts quick select, ignored while the session runs
     */
    static @NotNull QuickSelectSession of(@NotNull List<Hit> hits, @NotNull String alphabet,
            @Nullable KeyCombination trigger) {
        List<Hit> bottomFirst = new ArrayList<>(hits);
        bottomFirst.sort(Comparator.comparingInt((Hit hit) -> -hit.start().y).thenComparingInt(hit -> hit.start().x));
        List<String> labels = QuickSelectLabels.forTexts(bottomFirst.stream().map(Hit::text).toList(), alphabet);
        Map<String, List<Hit>> hitsByLabel = new LinkedHashMap<>();
        Map<String, Hit> firstByLabel = new LinkedHashMap<>();
        for (int i = 0; i < bottomFirst.size(); i++) {
            String label = labels.get(i);
            if (label == null) {
                continue;
            }
            hitsByLabel.computeIfAbsent(label, key -> new ArrayList<>()).add(bottomFirst.get(i));
            firstByLabel.putIfAbsent(label, bottomFirst.get(i));
        }
        List<Target> targets = new ArrayList<>(hitsByLabel.size());
        hitsByLabel.forEach((label, labelled) -> {
            Hit first = firstByLabel.get(label);
            targets.add(new Target(label, first.kind(), first.text(), labelled));
        });
        return new QuickSelectSession(targets, trigger);
    }

    /** Every target still offered, in label order. */
    @NotNull List<Target> targets() {
        return List.copyOf(targets);
    }

    /** The letters typed so far. */
    @NotNull String prefix() {
        return prefix;
    }

    /** Whether {@code target}'s label still fits the letters typed so far, so it stays marked. */
    boolean shows(@NotNull Target target) {
        return target.label().startsWith(prefix);
    }

    /** Decides what {@code press} does; see the class comment. */
    @NotNull Outcome onKey(@NotNull KeyPress press) {
        KeyCode code = press.code();
        if (MODIFIERS.contains(code) || code.isModifierKey() || code == KeyCode.UNDEFINED) {
            return Outcome.IGNORE;
        }
        if (trigger != null && press.matches(trigger)) {
            return Outcome.IGNORE;
        }
        if (code == KeyCode.ESCAPE || press.ctrl() || press.alt() || press.meta()) {
            return Outcome.CANCEL;
        }
        if (code == KeyCode.BACK_SPACE) {
            if (!prefix.isEmpty()) {
                prefix = prefix.substring(0, prefix.length() - 1);
            }
            return Outcome.CONTINUE;
        }
        if (!code.isLetterKey()) {
            return Outcome.CANCEL;
        }
        String typed = prefix + code.getChar().toLowerCase(Locale.ROOT);
        for (Target target : targets) {
            if (target.label().equals(typed)) {
                return new Outcome(press.shift() ? Action.OPEN : Action.COPY, target);
            }
        }
        for (Target target : targets) {
            if (target.label().startsWith(typed)) {
                prefix = typed;
                return Outcome.CONTINUE;
            }
        }
        return Outcome.CONTINUE;
    }

    /**
     * Keeps only the places {@code stillShown} accepts, for when the screen changed under the
     * labels. A target none of whose places is left is no longer offered.
     *
     * @return whether anything is still offered
     */
    boolean retainHits(@NotNull Predicate<Hit> stillShown) {
        Objects.requireNonNull(stillShown, "stillShown");
        List<Target> kept = new ArrayList<>(targets.size());
        for (Target target : targets) {
            List<Hit> hits = target.hits().stream().filter(stillShown).toList();
            if (hits.size() == target.hits().size()) {
                kept.add(target);
            } else if (!hits.isEmpty()) {
                kept.add(new Target(target.label(), target.kind(), target.text(), hits));
            }
        }
        targets.clear();
        targets.addAll(kept);
        return !targets.isEmpty();
    }
}
