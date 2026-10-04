package de.kortty.core;

/**
 * The keyboard shortcut that opens the snippet editor's AI completion list.
 *
 * <p>Users configure it in <b>Settings &rarr; Snippet Editor</b>, so the value has to survive a
 * round trip through {@code global-settings.xml}, a JavaFX key recorder and Monaco's keybinding
 * registry. This class owns all three views of it and is the single source of truth for the
 * mapping, so the Monaco page needs no key table of its own: it receives the resolved
 * {@link Binding} and only looks the name up in {@code monaco.KeyCode}.</p>
 *
 * <ul>
 *   <li><b>Canonical</b> — what is stored and shown: {@code "Shift+Tab"}, {@code "Ctrl+Space"},
 *       {@code "Ctrl+Alt+K"}. Modifiers always in the order Ctrl, Shift, Alt, then exactly one
 *       main key, so two spellings of the same chord can never be stored differently.</li>
 *   <li><b>Binding</b> — the modifier flags plus the Monaco {@code KeyCode} name the page needs.</li>
 *   <li><b>Display label</b> — the canonical form with {@code Ctrl} shown as {@code Cmd} on macOS,
 *       matching Monaco's own {@code CtrlCmd} modifier, which is Cmd there and Ctrl everywhere else.</li>
 * </ul>
 *
 * <p>Parsing and the key tables are {@link KeyChord}'s, the general chord of korTTY's key bindings.
 * This class keeps the stored spelling, where {@code Ctrl} is the platform's command key, and admits
 * only the keys Monaco can bind.</p>
 */
public final class SnippetCompletionShortcut {

    /** The shortcut a fresh installation uses: Shift+Tab, next to Monaco's own Ctrl+Space. */
    public static final String DEFAULT = "Shift+Tab";

    /** Ctrl (Cmd on macOS), Shift and Alt — one, two or three keys plus the main key. */
    public static final int MAX_MODIFIERS = 3;

    private static final String CTRL = "Ctrl";
    private static final String SHIFT = "Shift";
    private static final String ALT = "Alt";

    private SnippetCompletionShortcut() {
    }

    /**
     * A resolved shortcut. The page ORs the set modifiers (Monaco {@code KeyMod.CtrlCmd},
     * {@code KeyMod.Shift}, {@code KeyMod.Alt}) with {@code monaco.KeyCode[monacoKeyCode]}.
     */
    public record Binding(boolean ctrlCmd, boolean shift, boolean alt, String monacoKeyCode) {
    }

    /**
     * The stored spelling of {@code raw}, or {@code null} when it names no usable chord. Accepts any
     * order, spacing and casing of the parts and both {@code +} and {@code -} as separator, so a
     * hand-edited settings file still works: {@code "alt - shift - tab"} normalizes to
     * {@code "Shift+Alt+Tab"}. A modifier on its own, a repeated modifier, more than one main key or
     * an unknown key name yields {@code null}.
     */
    public static String normalize(String raw) {
        KeyChord chord = monacoChord(raw);
        return chord == null ? null : join(chord.shortcut(), chord.shift(), chord.alt(), chord.key());
    }

    /** Whether {@code raw} names a usable chord (see {@link #normalize(String)}). */
    public static boolean isValid(String raw) {
        return normalize(raw) != null;
    }

    /** The stored value of {@code raw}, falling back to {@link #DEFAULT} when it is unusable. */
    public static String normalizeOrDefault(String raw) {
        String normalized = normalize(raw);
        return normalized != null ? normalized : DEFAULT;
    }

    /** What the Monaco page needs to register the chord, or {@code null} when {@code raw} is unusable. */
    public static Binding binding(String raw) {
        KeyChord chord = monacoChord(raw);
        if (chord == null) {
            return null;
        }
        return new Binding(chord.shortcut(), chord.shift(), chord.alt(), KeyChord.monacoKeyCode(chord.key()));
    }

    /**
     * The chord for one key press in the settings recorder, or {@code null} while only modifiers are
     * held down (so holding Ctrl before the real key does not record {@code "Ctrl"} by itself).
     *
     * @param javafxKeyCodeName the pressed {@code javafx.scene.input.KeyCode} enum name
     * @param shortcutDown      the platform's Ctrl/Cmd modifier ({@code KeyEvent.isShortcutDown()})
     */
    public static String fromKeyPress(String javafxKeyCodeName, boolean shortcutDown, boolean shiftDown,
                                      boolean altDown) {
        String key = KeyChord.keyForJavaFxName(javafxKeyCodeName);
        if (key == null || KeyChord.monacoKeyCode(key) == null) {
            return null;
        }
        return join(shortcutDown, shiftDown, altDown, key);
    }

    /**
     * The chord as it is shown to the user: {@code Ctrl} becomes {@code Cmd} on macOS, because the
     * modifier is Monaco's platform-adaptive {@code CtrlCmd} and pressing Ctrl there does nothing.
     */
    public static String displayLabel(String raw, boolean macOs) {
        String normalized = normalizeOrDefault(raw);
        return macOs ? normalized.replace(CTRL + "+", "Cmd+") : normalized;
    }

    /** Whether this JVM runs on macOS, for {@link #displayLabel(String, boolean)}. */
    public static boolean isMacOs() {
        return KeyChord.Os.current().isMac();
    }

    /** {@code raw} as a chord whose Ctrl is the command key, or {@code null} when Monaco cannot bind it. */
    private static KeyChord monacoChord(String raw) {
        KeyChord chord = KeyChord.parse(raw, true);
        return chord == null || KeyChord.monacoKeyCode(chord.key()) == null ? null : chord;
    }

    private static String join(boolean ctrl, boolean shift, boolean alt, String key) {
        StringBuilder chord = new StringBuilder();
        if (ctrl) {
            chord.append(CTRL).append('+');
        }
        if (shift) {
            chord.append(SHIFT).append('+');
        }
        if (alt) {
            chord.append(ALT).append('+');
        }
        return chord.append(key).toString();
    }
}
