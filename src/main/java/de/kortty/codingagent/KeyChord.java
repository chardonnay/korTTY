package de.kortty.codingagent;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Optional;

/**
 * A key the user can send to a coding agent, with the bytes a real key press produces in the
 * terminal: Enter is CR (not LF), arrows are SS3 sequences, Ctrl+C is ETX.
 */
public enum KeyChord {
    ENTER("enter", "\r"),
    ESC("esc", "\u001b"),
    TAB("tab", "\t"),
    BACKSPACE("backspace", "\u007f"),
    SPACE("space", " "),
    UP("up", "\u001bOA"),
    DOWN("down", "\u001bOB"),
    RIGHT("right", "\u001bOC"),
    LEFT("left", "\u001bOD"),
    CTRL_C("ctrl+c", new byte[] {0x03}),
    CTRL_D("ctrl+d", new byte[] {0x04}),
    CTRL_U("ctrl+u", new byte[] {0x15}),
    Y("y", "y"),
    N("n", "n");

    private final String id;
    private final byte[] bytes;

    KeyChord(String id, String sequence) {
        this(id, sequence.getBytes(StandardCharsets.US_ASCII));
    }

    KeyChord(String id, byte[] bytes) {
        this.id = id;
        this.bytes = bytes;
    }

    /** Stable lower-case id ("enter", "ctrl+c"). */
    public String id() {
        return id;
    }

    /** A fresh copy of the bytes the chord writes to the pty. */
    public byte[] bytes() {
        return bytes.clone();
    }

    /** Case-insensitive lookup; "ctrl-c" and "CTRL+C" both resolve to {@link #CTRL_C}. */
    public static Optional<KeyChord> parse(String id) {
        if (id == null) {
            return Optional.empty();
        }
        String normalised = id.strip().toLowerCase(Locale.ROOT).replace('-', '+').replace('_', '+');
        if (normalised.isEmpty()) {
            return Optional.empty();
        }
        for (KeyChord chord : values()) {
            if (chord.id.equals(normalised)) {
                return Optional.of(chord);
            }
        }
        return Optional.empty();
    }
}
