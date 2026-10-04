package de.kortty.core;

import de.kortty.core.KeyChord.Os;
import de.kortty.core.KeyChord.Physical;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * The user's own key bindings for korTTY's actions, stored in {@code global-settings.xml} as one
 * {@code actionId=chord} entry per rebound action ({@code menu.view.commandPalette=Shortcut+Alt+P}),
 * or {@code actionId=none} for an action whose shortcut the user removed. An action id is the i18n
 * key of the action's menu item. Every action without an entry keeps its default chord.
 *
 * <p>The class is immutable and free of the JavaFX toolkit. It knows the rules a chord has to pass
 * before korTTY takes it away from the terminal, and {@link #resolve} applies the overrides to the
 * default keymap so that the keymap in effect never holds a chord twice:
 *
 * <ul>
 *   <li>{@link Problem#RESERVED_SHELL}: keys the shell needs. Plain Ctrl with a letter (Ctrl+C, D, F,
 *       L, P, R, V, ...), Ctrl+Space and Ctrl with {@code [ ] \ /} send control characters; Ctrl+Shift+6
 *       is the Cisco break sequence, Ctrl+Shift+2 NUL and Ctrl+Shift+Minus readline's undo. Ctrl or Alt
 *       with Left, Right, Backspace or Delete move or delete words. On Windows and Linux plain Alt
 *       with a character key is the shell's Meta key (korTTY sends it as Escape and the key).</li>
 *   <li>{@link Problem#ALTGR_RANGE}: chords that type a character. On Windows AltGr arrives as
 *       Ctrl+Alt, so Ctrl+Alt (with or without Shift) and a character key is AltGr typing; on macOS
 *       Option (with or without Shift) and a character key types one (Option+L is {@code @} on a
 *       German Mac).</li>
 *   <li>{@link Problem#TERMINAL}: the terminal's own key actions (SithTermFX and korTTY's settings
 *       provider), which take the key in a focused terminal: Cmd+C, V, K, F and Cmd+Up/Down on macOS,
 *       Ctrl+Shift+C/V and Ctrl+Up/Down on Windows and Linux, Shift+Page Up/Down everywhere.</li>
 *   <li>{@link Problem#SYSTEM}: chords the operating system keeps for itself, such as Cmd+Tab or
 *       Alt+F4.</li>
 *   <li>{@link Problem#NEEDS_MODIFIER}: a key without Cmd, Ctrl or Alt would be lost to typing;
 *       only the function keys F1 to F12 may go without.</li>
 *   <li>{@link Problem#FIXED}: a korTTY shortcut that cannot be rebound (passed in as
 *       {@link Rules#fixedOwner}), such as Ctrl+Tab or Cmd/Ctrl+1..9.</li>
 *   <li>{@link Problem#CONFLICT}: two actions on one chord.</li>
 * </ul>
 *
 * <p>The rules apply to the chords the user chooses. A default chord is never rejected: an override
 * that sets an action back to its own default is no override at all.
 */
public final class KeymapOverrides {

    /** The stored value of an action whose shortcut the user removed. */
    public static final String NONE = "none";

    /** Why an override is not in effect, or why a chord cannot be chosen. */
    public enum Problem {
        /** The action id names no action this korTTY knows (an older version, or a typo). */
        UNKNOWN_ACTION,
        /** Without Cmd, Ctrl or Alt the key would be lost to typing; only F1 to F12 may go without. */
        NEEDS_MODIFIER,
        /** The shell needs the chord (a control character, a word movement or the Meta key). */
        RESERVED_SHELL,
        /** The chord types a character: AltGr (Ctrl+Alt) on Windows, Option on macOS. */
        ALTGR_RANGE,
        /** One of the terminal's own key actions takes the chord in a focused terminal. */
        TERMINAL,
        /** The operating system keeps the chord for itself. */
        SYSTEM,
        /** A korTTY shortcut that cannot be rebound uses the chord. */
        FIXED,
        /** The action must keep a shortcut. */
        REQUIRED,
        /** Another action uses the chord. */
        CONFLICT
    }

    /** An override that is not in effect, and why; {@code conflictWith} names the other action, if any. */
    public record Rejection(String actionId, KeyChord chord, Problem problem, String conflictWith) {
        public Rejection {
            Objects.requireNonNull(actionId, "actionId");
            Objects.requireNonNull(problem, "problem");
        }
    }

    /**
     * What the rules are checked against.
     *
     * @param os         the platform the chords are resolved for
     * @param fixedOwner the id of the korTTY shortcut that cannot be rebound and takes a chord, or
     *                   {@code null} when none does
     * @param required   actions that must keep a shortcut ({@code none} is rejected for them)
     */
    public record Rules(Os os, Function<KeyChord, String> fixedOwner, Set<String> required) {
        public Rules {
            Objects.requireNonNull(os, "os");
            fixedOwner = fixedOwner != null ? fixedOwner : chord -> null;
            required = required != null ? Set.copyOf(required) : Set.of();
        }

        /** The rules of {@code os} with no fixed shortcuts and no required actions. */
        public static Rules of(Os os) {
            return new Rules(os, null, null);
        }
    }

    /**
     * The keymap in effect: every known action with its chord, {@code null} for an action without a
     * shortcut, in the order of the defaults; and the overrides that are not in effect.
     */
    public record Resolution(Map<String, KeyChord> effective, List<Rejection> rejected) {
        public Resolution {
            effective = Collections.unmodifiableMap(new LinkedHashMap<>(effective));
            rejected = List.copyOf(rejected);
        }

        /** The chord of {@code actionId} in effect; {@code null} when it has none or is unknown. */
        public KeyChord chord(String actionId) {
            return effective.get(actionId);
        }

        /** Whether {@code actionId} is an action of the keymap. */
        public boolean knows(String actionId) {
            return effective.containsKey(actionId);
        }
    }

    private static final KeymapOverrides EMPTY = new KeymapOverrides(new TreeMap<>());

    private static final Map<Os, Set<Physical>> TERMINAL_CHORDS = Map.of(
        Os.MAC, physicalSet(Os.MAC, "Shortcut+C", "Shortcut+V", "Shortcut+K", "Shortcut+F",
            "Shortcut+Up", "Shortcut+Down", "Shift+PageUp", "Shift+PageDown"),
        Os.WINDOWS, physicalSet(Os.WINDOWS, "Shortcut+Shift+C", "Shortcut+Shift+V", "Shortcut+Up", "Shortcut+Down",
            "Shift+PageUp", "Shift+PageDown"),
        Os.LINUX, physicalSet(Os.LINUX, "Shortcut+Shift+C", "Shortcut+Shift+V", "Shortcut+Up", "Shortcut+Down",
            "Shift+PageUp", "Shift+PageDown"));

    private static final Map<Os, Set<Physical>> SYSTEM_CHORDS = Map.of(
        Os.MAC, physicalSet(Os.MAC, "Shortcut+Tab", "Shortcut+Shift+Tab", "Shortcut+Space", "Shortcut+Ctrl+Space",
            "Shortcut+Backquote", "Shortcut+H", "Shortcut+Alt+H", "Shortcut+Alt+Escape", "Shortcut+Shift+3",
            "Shortcut+Shift+4", "Shortcut+Shift+5", "Shortcut+Ctrl+Q", "Shortcut+Ctrl+F"),
        Os.WINDOWS, physicalSet(Os.WINDOWS, "Alt+Tab", "Shift+Alt+Tab", "Alt+F4", "Alt+Space", "Alt+Escape",
            "Shortcut+Escape", "Shortcut+Shift+Escape", "Shortcut+Alt+Delete"),
        Os.LINUX, physicalSet(Os.LINUX, "Alt+Tab", "Shift+Alt+Tab", "Alt+F4", "Alt+Space",
            "Shortcut+Alt+Delete", "Shortcut+Alt+Backspace"));

    /** Keys that, with plain Ctrl, send a control character the shell uses. */
    private static final Set<String> CTRL_CONTROL_CHARACTER_KEYS = Set.of(
        "Space", "BracketLeft", "BracketRight", "Backslash", "Slash");

    /** Keys that, with Ctrl+Shift, send a control character: ^^ (Cisco break), ^@ (NUL), ^_ (undo). */
    private static final Set<String> CTRL_SHIFT_CONTROL_CHARACTER_KEYS = Set.of("6", "2", "Minus");

    /** Keys that, with plain Ctrl or Alt, move or delete a word at the shell's prompt. */
    private static final Set<String> WORD_EDITING_KEYS = Set.of("Left", "Right", "Backspace", "Delete");

    /** Action id to chord, {@code Optional.empty()} for {@code none}; sorted by id. */
    private final TreeMap<String, Optional<KeyChord>> overrides;

    private KeymapOverrides(TreeMap<String, Optional<KeyChord>> overrides) {
        this.overrides = overrides;
    }

    /** No overrides: every action keeps its default chord. */
    public static KeymapOverrides empty() {
        return EMPTY;
    }

    /**
     * The overrides stored as {@code entries}. Tolerant of a hand-edited file: an entry without
     * {@code =}, with a blank id or with a value that names no chord is skipped, and of two entries for
     * one action the later wins. Entries for unknown actions are kept, so a newer korTTY's bindings
     * survive a round trip through an older one; {@link #resolve} ignores them.
     */
    public static KeymapOverrides parse(Collection<String> entries) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }
        TreeMap<String, Optional<KeyChord>> parsed = new TreeMap<>();
        for (String entry : entries) {
            if (entry == null) {
                continue;
            }
            int separator = entry.indexOf('=');
            if (separator <= 0) {
                continue;
            }
            String actionId = entry.substring(0, separator).trim();
            String value = entry.substring(separator + 1).trim();
            if (actionId.isEmpty() || actionId.chars().anyMatch(Character::isWhitespace)) {
                continue;
            }
            if (NONE.equals(value.toLowerCase(Locale.ROOT))) {
                parsed.put(actionId, Optional.empty());
                continue;
            }
            KeyChord chord = KeyChord.parse(value);
            if (chord != null) {
                parsed.put(actionId, Optional.of(chord));
            }
        }
        return parsed.isEmpty() ? EMPTY : new KeymapOverrides(parsed);
    }

    /** The stored entries, sorted by action id: {@code id=Shortcut+Alt+P} or {@code id=none}. */
    public List<String> toEntries() {
        List<String> entries = new ArrayList<>(overrides.size());
        for (Map.Entry<String, Optional<KeyChord>> entry : overrides.entrySet()) {
            entries.add(entry.getKey() + "=" + entry.getValue().map(KeyChord::canonical).orElse(NONE));
        }
        return entries;
    }

    public boolean isEmpty() {
        return overrides.isEmpty();
    }

    /** The action ids with an override, sorted. */
    public Set<String> actionIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(overrides.keySet()));
    }

    /** Whether {@code actionId} has an override, a chord or {@code none}. */
    public boolean overrides(String actionId) {
        return overrides.containsKey(actionId);
    }

    /** The overriding chord of {@code actionId}; {@code null} for {@code none} and for no override. */
    public KeyChord chord(String actionId) {
        Optional<KeyChord> chord = overrides.get(actionId);
        return chord != null ? chord.orElse(null) : null;
    }

    /** Whether the user removed the shortcut of {@code actionId}. */
    public boolean isUnbound(String actionId) {
        Optional<KeyChord> chord = overrides.get(actionId);
        return chord != null && chord.isEmpty();
    }

    /** These overrides with {@code actionId} on {@code chord}, or without a shortcut when it is {@code null}. */
    public KeymapOverrides with(String actionId, KeyChord chord) {
        if (actionId == null || actionId.isBlank()) {
            throw new IllegalArgumentException("An action id must not be blank");
        }
        TreeMap<String, Optional<KeyChord>> copy = new TreeMap<>(overrides);
        copy.put(actionId.trim(), Optional.ofNullable(chord));
        return new KeymapOverrides(copy);
    }

    /** These overrides with {@code actionId} back on its default chord. */
    public KeymapOverrides without(String actionId) {
        if (!overrides.containsKey(actionId)) {
            return this;
        }
        TreeMap<String, Optional<KeyChord>> copy = new TreeMap<>(overrides);
        copy.remove(actionId);
        return copy.isEmpty() ? EMPTY : new KeymapOverrides(copy);
    }

    /**
     * Why {@code chord} cannot be given to an action on the platform of {@code rules}, or {@code null}
     * when it can: the rules of the class comment except {@link Problem#CONFLICT}, which depends on
     * the other actions' chords (see {@link #conflicts}).
     */
    public static Problem problemOf(KeyChord chord, Rules rules) {
        Objects.requireNonNull(chord, "chord");
        Objects.requireNonNull(rules, "rules");
        Os os = rules.os();
        Physical keys = chord.physical(os);
        if (keys.unmodified() && !chord.isFunctionKey()) {
            return Problem.NEEDS_MODIFIER;
        }
        if (isReservedForTheShell(chord, keys, os)) {
            return Problem.RESERVED_SHELL;
        }
        if (typesACharacter(chord, keys, os)) {
            return Problem.ALTGR_RANGE;
        }
        if (TERMINAL_CHORDS.get(os).contains(keys)) {
            return Problem.TERMINAL;
        }
        if (SYSTEM_CHORDS.get(os).contains(keys)) {
            return Problem.SYSTEM;
        }
        if (rules.fixedOwner().apply(chord) != null) {
            return Problem.FIXED;
        }
        return null;
    }

    /** The terminal's own key actions on {@code os}, as {@link Problem#TERMINAL} rejects them. */
    public static Set<Physical> terminalChords(Os os) {
        return TERMINAL_CHORDS.get(Objects.requireNonNull(os, "os"));
    }

    /**
     * The chords of {@code keymap} that more than one action uses on {@code os}, each with those
     * actions in keymap order. Actions without a shortcut ({@code null}) never conflict.
     */
    public static Map<Physical, List<String>> conflicts(Map<String, KeyChord> keymap, Os os) {
        Map<Physical, List<String>> byKeys = new LinkedHashMap<>();
        for (Map.Entry<String, KeyChord> entry : keymap.entrySet()) {
            if (entry.getValue() != null) {
                byKeys.computeIfAbsent(entry.getValue().physical(os), keys -> new ArrayList<>()).add(entry.getKey());
            }
        }
        byKeys.values().removeIf(actions -> actions.size() < 2);
        return byKeys;
    }

    /**
     * Applies these overrides to {@code defaults}, every known action with its default chord
     * ({@code null} for one without a shortcut), in display order.
     *
     * <ol>
     *   <li>An override for an action {@code defaults} does not hold is ignored
     *       ({@link Problem#UNKNOWN_ACTION}).</li>
     *   <li>An override back to the action's own default is taken as it is.</li>
     *   <li>{@code none} for a {@linkplain Rules#required required} action is rejected; any other
     *       chord must pass {@link #problemOf}.</li>
     *   <li>Then, as long as some chord is used twice, every overridden action on it goes back to its
     *       default ({@link Problem#CONFLICT}). The defaults never conflict among themselves, so this
     *       ends, at the latest with every action on its default; two actions that swap their
     *       chords both keep their overrides.</li>
     * </ol>
     */
    public Resolution resolve(Map<String, KeyChord> defaults, Rules rules) {
        Objects.requireNonNull(defaults, "defaults");
        Objects.requireNonNull(rules, "rules");
        List<Rejection> rejected = new ArrayList<>();
        Map<String, KeyChord> effective = new LinkedHashMap<>(defaults);
        Set<String> overridden = new LinkedHashSet<>();
        for (Map.Entry<String, Optional<KeyChord>> entry : overrides.entrySet()) {
            String actionId = entry.getKey();
            KeyChord chord = entry.getValue().orElse(null);
            if (!defaults.containsKey(actionId)) {
                rejected.add(new Rejection(actionId, chord, Problem.UNKNOWN_ACTION, null));
                continue;
            }
            KeyChord defaultChord = defaults.get(actionId);
            if (Objects.equals(chord, defaultChord)) {
                continue;
            }
            Problem problem = chord == null
                ? (rules.required().contains(actionId) ? Problem.REQUIRED : null)
                : problemOf(chord, rules);
            if (problem != null) {
                String owner = problem == Problem.FIXED ? rules.fixedOwner().apply(chord) : null;
                rejected.add(new Rejection(actionId, chord, problem, owner));
                continue;
            }
            effective.put(actionId, chord);
            overridden.add(actionId);
        }
        boolean changed = true;
        while (changed) {
            changed = false;
            for (List<String> actions : conflicts(effective, rules.os()).values()) {
                for (String actionId : actions) {
                    if (!overridden.remove(actionId)) {
                        continue;
                    }
                    KeyChord chord = effective.get(actionId);
                    String other = actions.stream().filter(id -> !id.equals(actionId)).findFirst().orElse(null);
                    rejected.add(new Rejection(actionId, chord, Problem.CONFLICT, other));
                    effective.put(actionId, defaults.get(actionId));
                    changed = true;
                }
            }
        }
        return new Resolution(effective, rejected);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof KeymapOverrides that && overrides.equals(that.overrides);
    }

    @Override
    public int hashCode() {
        return overrides.hashCode();
    }

    @Override
    public String toString() {
        return "KeymapOverrides" + toEntries();
    }

    private static boolean isReservedForTheShell(KeyChord chord, Physical keys, Os os) {
        if (keys.holdsExactly(true, false, false, false)) {
            return chord.isLetterKey() || CTRL_CONTROL_CHARACTER_KEYS.contains(chord.key())
                || WORD_EDITING_KEYS.contains(chord.key());
        }
        if (keys.holdsExactly(true, false, true, false)) {
            return CTRL_SHIFT_CONTROL_CHARACTER_KEYS.contains(chord.key());
        }
        if (keys.holdsExactly(false, true, false, false) && WORD_EDITING_KEYS.contains(chord.key())) {
            return true;
        }
        // Windows and Linux: korTTY sends Alt with a key as Escape and the key, readline's Meta key.
        return !os.isMac() && keys.alt() && !keys.ctrl() && !keys.meta() && chord.typesCharacter();
    }

    private static boolean typesACharacter(KeyChord chord, Physical keys, Os os) {
        if (!chord.typesCharacter()) {
            return false;
        }
        return switch (os) {
            case WINDOWS -> keys.ctrl() && keys.alt() && !keys.meta();
            case MAC -> keys.alt() && !keys.ctrl() && !keys.meta();
            case LINUX -> false;
        };
    }

    private static Set<Physical> physicalSet(Os os, String... chords) {
        Set<Physical> set = new LinkedHashSet<>();
        for (String chord : chords) {
            set.add(Objects.requireNonNull(KeyChord.parse(chord), chord).physical(os));
        }
        return Set.copyOf(set);
    }
}
