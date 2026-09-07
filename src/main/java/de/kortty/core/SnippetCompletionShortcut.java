package de.kortty.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

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
 */
public final class SnippetCompletionShortcut {

    /** The shortcut a fresh installation uses: Shift+Tab, next to Monaco's own Ctrl+Space. */
    public static final String DEFAULT = "Shift+Tab";

    /** Ctrl (Cmd on macOS), Shift and Alt — one, two or three keys plus the main key. */
    public static final int MAX_MODIFIERS = 3;

    private static final String CTRL = "Ctrl";
    private static final String SHIFT = "Shift";
    private static final String ALT = "Alt";

    /** Canonical key name to the {@code monaco.KeyCode} member the page resolves it with. */
    private static final Map<String, String> MONACO_KEY_CODES = monacoKeyCodes();

    /** JavaFX {@code KeyCode} enum name to the canonical key name, for the settings recorder. */
    private static final Map<String, String> JAVAFX_KEY_NAMES = javafxKeyNames();

    /** Canonical key name by its lower-case spelling, so stored values are matched case-insensitively. */
    private static final Map<String, String> KEYS_BY_LOWER_CASE = keysByLowerCase();

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
        if (raw == null || raw.isBlank()) {
            return null;
        }
        boolean ctrl = false;
        boolean shift = false;
        boolean alt = false;
        String key = null;
        for (String part : raw.trim().split("[+\\-]")) {
            String token = part.trim();
            if (token.isEmpty()) {
                continue;
            }
            switch (token.toLowerCase(Locale.ROOT)) {
                case "ctrl", "control", "cmd", "command", "meta", "mod", "ctrlcmd" -> {
                    if (ctrl) {
                        return null;
                    }
                    ctrl = true;
                }
                case "shift" -> {
                    if (shift) {
                        return null;
                    }
                    shift = true;
                }
                case "alt", "option", "opt" -> {
                    if (alt) {
                        return null;
                    }
                    alt = true;
                }
                default -> {
                    String canonicalKey = KEYS_BY_LOWER_CASE.get(token.toLowerCase(Locale.ROOT));
                    if (canonicalKey == null || key != null) {
                        return null;
                    }
                    key = canonicalKey;
                }
            }
        }
        return key == null ? null : join(ctrl, shift, alt, key);
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
        String normalized = normalize(raw);
        if (normalized == null) {
            return null;
        }
        String[] parts = normalized.split("\\+");
        String key = parts[parts.length - 1];
        boolean ctrl = false;
        boolean shift = false;
        boolean alt = false;
        for (int i = 0; i < parts.length - 1; i++) {
            switch (parts[i]) {
                case CTRL -> ctrl = true;
                case SHIFT -> shift = true;
                case ALT -> alt = true;
                default -> {
                    return null;
                }
            }
        }
        return new Binding(ctrl, shift, alt, MONACO_KEY_CODES.get(key));
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
        if (javafxKeyCodeName == null) {
            return null;
        }
        String key = JAVAFX_KEY_NAMES.get(javafxKeyCodeName.toUpperCase(Locale.ROOT));
        return key == null ? null : join(shortcutDown, shiftDown, altDown, key);
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
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");
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

    private static Map<String, String> monacoKeyCodes() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("Tab", "Tab");
        keys.put("Space", "Space");
        keys.put("Enter", "Enter");
        keys.put("Escape", "Escape");
        keys.put("Backspace", "Backspace");
        keys.put("Delete", "Delete");
        keys.put("Insert", "Insert");
        keys.put("Home", "Home");
        keys.put("End", "End");
        keys.put("PageUp", "PageUp");
        keys.put("PageDown", "PageDown");
        keys.put("Up", "UpArrow");
        keys.put("Down", "DownArrow");
        keys.put("Left", "LeftArrow");
        keys.put("Right", "RightArrow");
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            keys.put(String.valueOf(letter), "Key" + letter);
        }
        for (int digit = 0; digit <= 9; digit++) {
            keys.put(String.valueOf(digit), "Digit" + digit);
        }
        for (int function = 1; function <= 12; function++) {
            keys.put("F" + function, "F" + function);
        }
        keys.put("Comma", "Comma");
        keys.put("Period", "Period");
        keys.put("Slash", "Slash");
        keys.put("Backslash", "Backslash");
        keys.put("Semicolon", "Semicolon");
        keys.put("Quote", "Quote");
        keys.put("BracketLeft", "BracketLeft");
        keys.put("BracketRight", "BracketRight");
        keys.put("Minus", "Minus");
        keys.put("Equal", "Equal");
        keys.put("Backquote", "Backquote");
        return Map.copyOf(keys);
    }

    private static Map<String, String> javafxKeyNames() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("TAB", "Tab");
        keys.put("SPACE", "Space");
        keys.put("ENTER", "Enter");
        keys.put("ESCAPE", "Escape");
        keys.put("BACK_SPACE", "Backspace");
        keys.put("DELETE", "Delete");
        keys.put("INSERT", "Insert");
        keys.put("HOME", "Home");
        keys.put("END", "End");
        keys.put("PAGE_UP", "PageUp");
        keys.put("PAGE_DOWN", "PageDown");
        keys.put("UP", "Up");
        keys.put("DOWN", "Down");
        keys.put("LEFT", "Left");
        keys.put("RIGHT", "Right");
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            keys.put(String.valueOf(letter), String.valueOf(letter));
        }
        for (int digit = 0; digit <= 9; digit++) {
            keys.put("DIGIT" + digit, String.valueOf(digit));
        }
        for (int function = 1; function <= 12; function++) {
            keys.put("F" + function, "F" + function);
        }
        keys.put("COMMA", "Comma");
        keys.put("PERIOD", "Period");
        keys.put("SLASH", "Slash");
        keys.put("BACK_SLASH", "Backslash");
        keys.put("SEMICOLON", "Semicolon");
        keys.put("QUOTE", "Quote");
        keys.put("OPEN_BRACKET", "BracketLeft");
        keys.put("CLOSE_BRACKET", "BracketRight");
        keys.put("MINUS", "Minus");
        keys.put("EQUALS", "Equal");
        keys.put("BACK_QUOTE", "Backquote");
        return Map.copyOf(keys);
    }

    private static Map<String, String> keysByLowerCase() {
        Map<String, String> keys = new LinkedHashMap<>();
        for (String key : MONACO_KEY_CODES.keySet()) {
            keys.put(key.toLowerCase(Locale.ROOT), key);
        }
        // Spellings a hand-edited settings file or another platform's naming may use.
        keys.put("esc", "Escape");
        keys.put("return", "Enter");
        keys.put("del", "Delete");
        keys.put("ins", "Insert");
        keys.put("uparrow", "Up");
        keys.put("downarrow", "Down");
        keys.put("leftarrow", "Left");
        keys.put("rightarrow", "Right");
        keys.put("pgup", "PageUp");
        keys.put("pgdn", "PageDown");
        return Map.copyOf(keys);
    }
}
