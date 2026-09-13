package de.kortty.control;

import de.kortty.codingagent.KeyChord;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * The control API's own key-name vocabulary.
 *
 * <p>It deliberately does not call {@code KeyChordEncoder.parseAll}, whose
 * {@link IllegalArgumentException} would surface as {@code internal_error} instead of the syntax
 * error the CLI contract requires, and it does not extend {@code KeyChord}, whose 14 ids belong to
 * the coding-agents feature. Those 14 ids are canonical names here and {@code ControlKeyTableTest}
 * asserts, by looping {@code KeyChord.values()}, that this table produces byte-identical output for
 * each of them.
 *
 * <p>Vocabulary: {@code enter|return}, {@code esc|escape}, {@code tab}, {@code shift+tab},
 * {@code backspace}, {@code space}, {@code up}, {@code down}, {@code left}, {@code right},
 * {@code home}, {@code end}, {@code pageup}, {@code pagedown}, {@code delete}, {@code insert},
 * {@code f1}…{@code f12}, {@code ctrl+a}…{@code ctrl+z}, {@code y}, {@code n}, plus any single
 * printable character. {@code -} and {@code _} are accepted in place of {@code +}, and multi-character
 * names are case-insensitive; a single character is always taken literally, so {@code A} and
 * {@code a} stay distinct rather than collapsing onto one key.
 *
 * <p>Pure, any thread.
 */
public final class ControlKeyTable {

    /** The ESC byte that starts every CSI and SS3 sequence. */
    private static final String ESC = "\u001b";

    /** Canonical name to byte sequence, in the order {@link #knownKeys()} publishes. */
    private static final Map<String, byte[]> TABLE = buildTable();

    /** Alias to canonical name. */
    private static final Map<String, String> ALIASES = Map.of(
        "return", "enter",
        "escape", "esc");

    /** The published vocabulary, aliases included. */
    private static final List<String> KNOWN = buildKnown();

    private ControlKeyTable() {
    }

    /** The full published vocabulary, in a stable order; what {@code api.schema.keys} carries. */
    public static List<String> knownKeys() {
        return KNOWN;
    }

    /**
     * The bytes one key name writes to the pty.
     *
     * @throws ControlApiException {@link ControlErrorCode#UNKNOWN_KEY} with {@code data.known} listing
     *     the vocabulary — never {@link IllegalArgumentException}
     */
    public static byte[] encode(String keyName) throws ControlApiException {
        String canonical = canonicalise(keyName);
        byte[] bytes = TABLE.get(canonical);
        if (bytes != null) {
            return bytes.clone();
        }
        return canonical.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * The concatenated bytes of several key names, in order.
     *
     * @throws ControlApiException {@link ControlErrorCode#EMPTY_INPUT} for an empty list,
     *     {@link ControlErrorCode#UNKNOWN_KEY} for an unknown name
     */
    public static byte[] encodeAll(List<String> keyNames) throws ControlApiException {
        requireKeys(keyNames);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (String name : keyNames) {
            out.writeBytes(encode(name));
        }
        return out.toByteArray();
    }

    /**
     * The canonical spelling of each name, which is what a {@link WriteResult} reports back.
     *
     * @throws ControlApiException {@link ControlErrorCode#EMPTY_INPUT} for an empty list,
     *     {@link ControlErrorCode#UNKNOWN_KEY} for an unknown name
     */
    public static List<String> normalise(List<String> keyNames) throws ControlApiException {
        requireKeys(keyNames);
        List<String> result = new ArrayList<>(keyNames.size());
        for (String name : keyNames) {
            result.add(canonicalise(name));
        }
        return List.copyOf(result);
    }

    /**
     * The matching {@code KeyChord}, or empty when the name maps to none — including for a
     * single printable character whose case differs from the chord's id, which must keep its own
     * bytes. {@code agent.send_keys} uses this to delegate the common case to
     * {@code CodingAgentActions}.
     */
    public static Optional<KeyChord> asKeyChord(String keyName) {
        String canonical;
        try {
            canonical = canonicalise(keyName);
        } catch (ControlApiException e) {
            return Optional.empty();
        }
        return KeyChord.parse(canonical).filter(chord -> chord.id().equals(canonical));
    }

    /** Splits {@code "ctrl+c enter"} into {@code ["ctrl+c", "enter"]}. */
    public static List<String> split(String spaceSeparated) {
        if (spaceSeparated == null || spaceSeparated.isBlank()) {
            return List.of();
        }
        return List.of(spaceSeparated.strip().split("\\s+"));
    }

    private static void requireKeys(List<String> keyNames) throws ControlApiException {
        if (keyNames == null || keyNames.isEmpty()) {
            throw new ControlApiException(ControlErrorCode.EMPTY_INPUT, "No keys given",
                Map.of("param", "keys"));
        }
    }

    private static String canonicalise(String keyName) throws ControlApiException {
        if (keyName == null) {
            throw unknown("null");
        }
        String value = keyName.strip();
        if (value.isEmpty()) {
            throw unknown(keyName);
        }
        if (value.codePointCount(0, value.length()) == 1) {
            if (isPrintable(value.codePointAt(0))) {
                return value;
            }
            throw unknown(keyName);
        }
        String lower = value.toLowerCase(Locale.ROOT).replace('-', '+').replace('_', '+');
        String canonical = ALIASES.getOrDefault(lower, lower);
        if (!TABLE.containsKey(canonical)) {
            throw unknown(keyName);
        }
        return canonical;
    }

    private static boolean isPrintable(int codePoint) {
        if (Character.isISOControl(codePoint) || !Character.isDefined(codePoint)) {
            return false;
        }
        int type = Character.getType(codePoint);
        return type != Character.UNASSIGNED && type != Character.CONTROL
            && type != Character.SURROGATE && type != Character.PRIVATE_USE;
    }

    private static ControlApiException unknown(String keyName) {
        return new ControlApiException(ControlErrorCode.UNKNOWN_KEY,
            "Unknown key name: " + keyName,
            Map.of("param", "keys", "key", keyName, "known", KNOWN));
    }

    private static Map<String, byte[]> buildTable() {
        Map<String, byte[]> table = new LinkedHashMap<>();
        put(table, "enter", "\r");
        put(table, "esc", ESC);
        put(table, "tab", "\t");
        put(table, "shift+tab", ESC + "[Z");
        put(table, "backspace", "\u007f");
        put(table, "space", " ");
        put(table, "up", ESC + "OA");
        put(table, "down", ESC + "OB");
        put(table, "left", ESC + "OD");
        put(table, "right", ESC + "OC");
        put(table, "home", ESC + "[H");
        put(table, "end", ESC + "[F");
        put(table, "pageup", ESC + "[5~");
        put(table, "pagedown", ESC + "[6~");
        put(table, "delete", ESC + "[3~");
        put(table, "insert", ESC + "[2~");
        put(table, "f1", ESC + "OP");
        put(table, "f2", ESC + "OQ");
        put(table, "f3", ESC + "OR");
        put(table, "f4", ESC + "OS");
        put(table, "f5", ESC + "[15~");
        put(table, "f6", ESC + "[17~");
        put(table, "f7", ESC + "[18~");
        put(table, "f8", ESC + "[19~");
        put(table, "f9", ESC + "[20~");
        put(table, "f10", ESC + "[21~");
        put(table, "f11", ESC + "[23~");
        put(table, "f12", ESC + "[24~");
        for (char letter = 'a'; letter <= 'z'; letter++) {
            table.put("ctrl+" + letter, new byte[] {(byte) (letter - 'a' + 1)});
        }
        put(table, "y", "y");
        put(table, "n", "n");
        return Collections.unmodifiableMap(table);
    }

    private static void put(Map<String, byte[]> table, String name, String sequence) {
        table.put(name, sequence.getBytes(StandardCharsets.US_ASCII));
    }

    private static List<String> buildKnown() {
        List<String> known = new ArrayList<>();
        for (String name : TABLE.keySet()) {
            known.add(name);
            if ("enter".equals(name)) {
                known.add("return");
            } else if ("esc".equals(name)) {
                known.add("escape");
            }
        }
        return List.copyOf(known);
    }
}
