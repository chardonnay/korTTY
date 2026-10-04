package de.kortty.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * One keyboard chord: up to four modifiers and exactly one main key, independent of the JavaFX
 * toolkit, so key bindings can be stored, parsed, compared and validated in plain unit tests.
 *
 * <p>The modifiers follow JavaFX's naming. {@link #shortcut()} is the platform's command key, Cmd
 * on macOS and Ctrl on Windows and Linux, so one stored chord means the same thing on every
 * platform. {@link #control()} is the Control key itself; on Windows and Linux it is the same key as
 * Shortcut, on macOS a different one. A chord is stored in its {@linkplain #canonical() canonical}
 * spelling, {@code "Shortcut+Shift+P"}, {@code "Ctrl+Tab"}, {@code "Alt+Plus"} or {@code "F12"}: the
 * modifiers always in the order Shortcut, Ctrl, Shift, Alt, then the key, so two spellings of one
 * chord can never be stored differently.
 *
 * <p>Two chords collide on a platform when their {@linkplain #physical(Os) physical} keys are the
 * same: {@code Shortcut+K} and {@code Ctrl+K} are one chord on Windows and two on macOS.
 *
 * <p>Generalised from {@link SnippetCompletionShortcut}, which keeps its own stored spelling (there
 * {@code Ctrl} means Monaco's platform-adaptive CtrlCmd) and uses this class's key tables.
 */
public record KeyChord(boolean shortcut, boolean control, boolean shift, boolean alt, String key) {

    /** The platform a chord is resolved for, passed explicitly so every rule is testable on any host. */
    public enum Os {
        MAC, WINDOWS, LINUX;

        /** The platform this JVM runs on. */
        public static Os current() {
            String name = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            if (name.contains("mac")) {
                return MAC;
            }
            return name.contains("win") ? WINDOWS : LINUX;
        }

        public boolean isMac() {
            return this == MAC;
        }
    }

    /**
     * The keys a chord presses on one platform: Shortcut resolved to Cmd (meta) on macOS and to Ctrl
     * elsewhere. Two chords with equal physical keys are the same key press there.
     */
    public record Physical(boolean ctrl, boolean alt, boolean shift, boolean meta, String key) {

        /** Whether no modifier but Shift is held. */
        public boolean unmodified() {
            return !ctrl && !alt && !meta;
        }

        /** Whether exactly these modifiers are held, Shift included. */
        public boolean holdsExactly(boolean ctrl, boolean alt, boolean shift, boolean meta) {
            return this.ctrl == ctrl && this.alt == alt && this.shift == shift && this.meta == meta;
        }
    }

    private static final String SHORTCUT = "Shortcut";
    private static final String CTRL = "Ctrl";
    private static final String SHIFT = "Shift";
    private static final String ALT = "Alt";

    /** Canonical key name to its JavaFX {@code KeyCode} enum name. */
    private static final Map<String, String> JAVAFX_NAMES = javafxNames();

    /** JavaFX {@code KeyCode} enum name to the canonical key name. */
    private static final Map<String, String> KEYS_BY_JAVAFX_NAME = inverse(JAVAFX_NAMES);

    /** Canonical key name to the {@code monaco.KeyCode} member; keys Monaco cannot bind are absent. */
    private static final Map<String, String> MONACO_KEY_CODES = monacoKeyCodes();

    /** Canonical key name by its lower-case spelling and the aliases a hand-edited file may use. */
    private static final Map<String, String> KEYS_BY_LOWER_CASE = keysByLowerCase();

    /** Keys that type a printable character when pressed without a modifier. */
    private static final Set<String> PUNCTUATION = Set.of(
        "Comma", "Period", "Slash", "Backslash", "Semicolon", "Quote", "BracketLeft", "BracketRight",
        "Minus", "Equal", "Backquote", "Plus");

    public KeyChord {
        Objects.requireNonNull(key, "key");
        if (!JAVAFX_NAMES.containsKey(key)) {
            throw new IllegalArgumentException("Not a canonical key name: " + key);
        }
    }

    /**
     * The chord {@code raw} names in the stored spelling, or {@code null} when it names none. Accepts
     * any order, spacing and casing of the parts and both {@code +} and {@code -} as separator:
     * {@code "shift - shortcut - p"} is {@code Shortcut+Shift+P}. {@code Shortcut}, {@code Mod},
     * {@code CtrlCmd}, {@code Cmd}, {@code Command} and {@code Meta} name the platform's command key;
     * {@code Ctrl} and {@code Control} the Control key; {@code Alt}, {@code Option} and {@code Opt}
     * the Alt key. A modifier on its own, a repeated modifier, two main keys or an unknown key name
     * yields {@code null}.
     */
    public static KeyChord parse(String raw) {
        return parse(raw, false);
    }

    /**
     * {@link #parse(String)}, with {@code controlIsShortcut} set for spellings where {@code Ctrl}
     * means the platform's command key, as in {@link SnippetCompletionShortcut} (Monaco's CtrlCmd).
     */
    public static KeyChord parse(String raw, boolean controlIsShortcut) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        boolean shortcut = false;
        boolean control = false;
        boolean shift = false;
        boolean alt = false;
        String key = null;
        for (String part : raw.trim().split("[+\\-]")) {
            String token = part.trim().toLowerCase(Locale.ROOT);
            if (token.isEmpty()) {
                continue;
            }
            switch (token) {
                case "shortcut", "mod", "ctrlcmd", "cmd", "command", "meta" -> {
                    if (shortcut) {
                        return null;
                    }
                    shortcut = true;
                }
                case "ctrl", "control" -> {
                    if (controlIsShortcut) {
                        if (shortcut) {
                            return null;
                        }
                        shortcut = true;
                    } else {
                        if (control) {
                            return null;
                        }
                        control = true;
                    }
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
                    String canonicalKey = KEYS_BY_LOWER_CASE.get(token);
                    if (canonicalKey == null || key != null) {
                        return null;
                    }
                    key = canonicalKey;
                }
            }
        }
        return key == null ? null : new KeyChord(shortcut, control, shift, alt, key);
    }

    /**
     * The chord of one key press, for a key recorder, or {@code null} while only modifiers are held
     * (so holding Ctrl before the real key does not record a chord) and for a key no chord can name.
     * On macOS Cmd becomes {@link #shortcut()} and Control stays {@link #control()}. On Windows and
     * Linux Ctrl becomes {@link #shortcut()}, so the chord moves to Cmd on a Mac; the Windows (Super)
     * key is not supported there and yields {@code null}.
     *
     * @param javafxKeyCodeName the pressed {@code javafx.scene.input.KeyCode} enum name
     */
    public static KeyChord fromKeyPress(String javafxKeyCodeName, boolean shift, boolean control, boolean alt,
                                        boolean meta, Os os) {
        String key = keyForJavaFxName(javafxKeyCodeName);
        if (key == null) {
            return null;
        }
        if (Objects.requireNonNull(os, "os").isMac()) {
            return new KeyChord(meta, control, shift, alt, key);
        }
        return meta ? null : new KeyChord(control, false, shift, alt, key);
    }

    /** The canonical key name of a JavaFX {@code KeyCode} enum name, or {@code null} for keys no chord names. */
    public static String keyForJavaFxName(String javafxKeyCodeName) {
        return javafxKeyCodeName == null ? null : KEYS_BY_JAVAFX_NAME.get(javafxKeyCodeName.toUpperCase(Locale.ROOT));
    }

    /** The {@code monaco.KeyCode} member of a canonical key name, or {@code null} when Monaco cannot bind it. */
    public static String monacoKeyCode(String key) {
        return key == null ? null : MONACO_KEY_CODES.get(key);
    }

    /** The stored spelling: {@code "Shortcut+Ctrl+Shift+Alt+K"}, modifiers in that order. */
    public String canonical() {
        StringBuilder chord = new StringBuilder();
        if (shortcut) {
            chord.append(SHORTCUT).append('+');
        }
        if (control) {
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

    /**
     * The chord as a person reads it on {@code os}: {@code Cmd+Shift+P} or {@code Ctrl+Shift+P},
     * Alt shown as Option on macOS. Where Shortcut and Ctrl are the same key, Ctrl is named once.
     */
    public String displayLabel(Os os) {
        boolean mac = Objects.requireNonNull(os, "os").isMac();
        StringBuilder label = new StringBuilder();
        if (mac) {
            if (control) {
                label.append(CTRL).append('+');
            }
            if (alt) {
                label.append("Option+");
            }
            if (shift) {
                label.append(SHIFT).append('+');
            }
            if (shortcut) {
                label.append("Cmd+");
            }
        } else {
            if (shortcut || control) {
                label.append(CTRL).append('+');
            }
            if (alt) {
                label.append(ALT).append('+');
            }
            if (shift) {
                label.append(SHIFT).append('+');
            }
        }
        return label.append(key).toString();
    }

    /** The keys this chord presses on {@code os}. */
    public Physical physical(Os os) {
        if (Objects.requireNonNull(os, "os").isMac()) {
            return new Physical(control, alt, shift, shortcut, key);
        }
        return new Physical(shortcut || control, alt, shift, false, key);
    }

    /** Whether this chord is the same key press as {@code other} on {@code os}. */
    public boolean collidesWith(KeyChord other, Os os) {
        return other != null && physical(os).equals(other.physical(os));
    }

    /** The JavaFX {@code KeyCode} enum name of the main key, such as {@code "P"}, {@code "DIGIT1"} or {@code "PLUS"}. */
    public String javafxKeyCodeName() {
        return JAVAFX_NAMES.get(key);
    }

    /** Whether the main key is a letter, A to Z. */
    public boolean isLetterKey() {
        return isLetter(key);
    }

    /** Whether the main key is a digit of the top row, 0 to 9. */
    public boolean isDigitKey() {
        return key.length() == 1 && key.charAt(0) >= '0' && key.charAt(0) <= '9';
    }

    /** Whether the main key is a function key, F1 to F12. */
    public boolean isFunctionKey() {
        return key.length() >= 2 && key.charAt(0) == 'F' && Character.isDigit(key.charAt(1));
    }

    /**
     * Whether the main key types a character of its own: a letter, a digit, punctuation or Space.
     * With a modifier such as AltGr or Option such a key types another character instead.
     */
    public boolean typesCharacter() {
        return isLetterKey() || isDigitKey() || PUNCTUATION.contains(key) || "Space".equals(key);
    }

    /** Whether {@code key} is a canonical key name. */
    public static boolean isKeyName(String key) {
        return key != null && JAVAFX_NAMES.containsKey(key);
    }

    @Override
    public String toString() {
        return canonical();
    }

    private static boolean isLetter(String key) {
        return key.length() == 1 && key.charAt(0) >= 'A' && key.charAt(0) <= 'Z';
    }

    private static Map<String, String> javafxNames() {
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("Tab", "TAB");
        keys.put("Space", "SPACE");
        keys.put("Enter", "ENTER");
        keys.put("Escape", "ESCAPE");
        keys.put("Backspace", "BACK_SPACE");
        keys.put("Delete", "DELETE");
        keys.put("Insert", "INSERT");
        keys.put("Home", "HOME");
        keys.put("End", "END");
        keys.put("PageUp", "PAGE_UP");
        keys.put("PageDown", "PAGE_DOWN");
        keys.put("Up", "UP");
        keys.put("Down", "DOWN");
        keys.put("Left", "LEFT");
        keys.put("Right", "RIGHT");
        for (char letter = 'A'; letter <= 'Z'; letter++) {
            keys.put(String.valueOf(letter), String.valueOf(letter));
        }
        for (int digit = 0; digit <= 9; digit++) {
            keys.put(String.valueOf(digit), "DIGIT" + digit);
        }
        for (int function = 1; function <= 12; function++) {
            keys.put("F" + function, "F" + function);
        }
        keys.put("Comma", "COMMA");
        keys.put("Period", "PERIOD");
        keys.put("Slash", "SLASH");
        keys.put("Backslash", "BACK_SLASH");
        keys.put("Semicolon", "SEMICOLON");
        keys.put("Quote", "QUOTE");
        keys.put("BracketLeft", "OPEN_BRACKET");
        keys.put("BracketRight", "CLOSE_BRACKET");
        keys.put("Minus", "MINUS");
        keys.put("Equal", "EQUALS");
        keys.put("Backquote", "BACK_QUOTE");
        // The zoom-in key of View > Zoom In; Monaco has no key code for it.
        keys.put("Plus", "PLUS");
        return Map.copyOf(keys);
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

    private static Map<String, String> keysByLowerCase() {
        Map<String, String> keys = new LinkedHashMap<>();
        for (String key : JAVAFX_NAMES.keySet()) {
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

    private static Map<String, String> inverse(Map<String, String> map) {
        Map<String, String> inverse = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) {
            inverse.put(entry.getValue(), entry.getKey());
        }
        return Map.copyOf(inverse);
    }
}
