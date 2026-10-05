package de.kortty.isolation;

import java.util.Locale;

/**
 * The strict terminal mode: drops the escape sequences a hostile server could use against the terminal
 * or the computer it runs on, before the emulator (or korTTY's shell integration) sees them. Everything
 * else passes unchanged and in order. Stateful, because a sequence can be split across reads; one filter
 * per session, used from one thread.
 *
 * <p>Dropped:
 * <ul>
 *   <li>OSC 52, clipboard reads and writes;</li>
 *   <li>OSC 8 links whose target is not {@code http(s)} (a {@code file://} or custom-scheme link is a
 *       click away from opening something local); the empty OSC 8 that ends a link passes;</li>
 *   <li>OSC 0/1/2 window and tab titles longer than {@value #MAX_TITLE_LENGTH} characters or with control
 *       characters in them, which can spoof what the tab shows;</li>
 *   <li>any OSC longer than {@value #MAX_STRING_LENGTH} characters;</li>
 *   <li>device control strings (DCS), application program commands (APC), privacy messages (PM) and
 *       start-of-string (SOS), which carry terminal queries, graphics and pass-through to other programs.</li>
 * </ul>
 * Both the 7-bit ({@code ESC ]}) and the 8-bit ({@code U+009D}) introducers are recognized. A string
 * ends with BEL (OSC only) or ST ({@code ESC \} or {@code U+009C}); CAN and SUB cancel it, as on a VT.
 */
public final class StrictEscapeFilter {

    /** The longest OSC that may pass, in characters. */
    public static final int MAX_STRING_LENGTH = 4096;

    /** The longest window or tab title that may pass, in characters. */
    public static final int MAX_TITLE_LENGTH = 256;

    private static final char ESC = 0x1B;
    private static final char BEL = 0x07;
    private static final char CAN = 0x18;
    private static final char SUB = 0x1A;
    private static final char C1_DCS = 0x90;
    private static final char C1_SOS = 0x98;
    private static final char C1_ST = 0x9C;
    private static final char C1_OSC = 0x9D;
    private static final char C1_PM = 0x9E;
    private static final char C1_APC = 0x9F;

    private enum State { NORMAL, ESCAPE, OSC, OSC_ESCAPE, DROP, DROP_ESCAPE }

    private State state = State.NORMAL;
    /** The OSC so far, introducer included, while it may still pass. */
    private final StringBuilder osc = new StringBuilder();
    /** Whether the OSC being read already went over {@link #MAX_STRING_LENGTH}, so it is dropped at its end. */
    private boolean oscTooLong;
    private long dropped;

    /** How many sequences this filter dropped so far. */
    public long droppedCount() {
        return dropped;
    }

    /** Appends to {@code out} what of {@code in[offset, offset+length)} may reach the terminal. */
    public void filter(char[] in, int offset, int length, StringBuilder out) {
        for (int i = offset; i < offset + length; i++) {
            accept(in[i], out);
        }
    }

    /** {@link #filter(char[], int, int, StringBuilder)} for a string; for tests. */
    public String filter(String in) {
        StringBuilder out = new StringBuilder();
        char[] chars = in.toCharArray();
        filter(chars, 0, chars.length, out);
        return out.toString();
    }

    /**
     * Hands back an escape character held at the end of the stream: a lone ESC that no sequence followed
     * belongs to the output. Call when the session ends.
     */
    public void flush(StringBuilder out) {
        if (state == State.ESCAPE) {
            out.append(ESC);
            state = State.NORMAL;
        }
    }

    private void accept(char c, StringBuilder out) {
        switch (state) {
            case NORMAL -> {
                if (c == ESC) {
                    state = State.ESCAPE;
                } else if (c == C1_OSC) {
                    startOsc(String.valueOf(c));
                } else if (c == C1_DCS || c == C1_SOS || c == C1_PM || c == C1_APC) {
                    startDrop();
                } else {
                    out.append(c);
                }
            }
            case ESCAPE -> {
                switch (c) {
                    case ']' -> startOsc("" + ESC + ']');
                    case 'P', 'X', '^', '_' -> startDrop();
                    case ESC -> out.append(ESC);
                    default -> {
                        out.append(ESC).append(c);
                        state = State.NORMAL;
                    }
                }
            }
            case OSC -> {
                if (c == BEL) {
                    finishOsc(String.valueOf(BEL), out);
                } else if (c == C1_ST) {
                    finishOsc(String.valueOf(C1_ST), out);
                } else if (c == ESC) {
                    state = State.OSC_ESCAPE;
                } else if (c == CAN || c == SUB) {
                    cancelOsc();
                } else {
                    appendOsc(c);
                }
            }
            case OSC_ESCAPE -> {
                if (c == '\\') {
                    finishOsc("" + ESC + '\\', out);
                } else {
                    // An ESC that is not ST ends the string without a terminator; the escape starts anew.
                    cancelOsc();
                    state = State.ESCAPE;
                    accept(c, out);
                }
            }
            case DROP -> {
                if (c == C1_ST || c == CAN || c == SUB) {
                    state = State.NORMAL;
                } else if (c == ESC) {
                    state = State.DROP_ESCAPE;
                }
            }
            case DROP_ESCAPE -> state = c == '\\' ? State.NORMAL : c == ESC ? State.DROP_ESCAPE : State.DROP;
        }
    }

    private void startOsc(String introducer) {
        osc.setLength(0);
        osc.append(introducer);
        oscTooLong = false;
        state = State.OSC;
    }

    private void appendOsc(char c) {
        if (oscTooLong) {
            return;
        }
        if (osc.length() >= MAX_STRING_LENGTH) {
            oscTooLong = true;
            osc.setLength(0);
            return;
        }
        osc.append(c);
    }

    private void cancelOsc() {
        osc.setLength(0);
        oscTooLong = false;
        dropped++;
        state = State.NORMAL;
    }

    private void startDrop() {
        dropped++;
        state = State.DROP;
    }

    private void finishOsc(String terminator, StringBuilder out) {
        state = State.NORMAL;
        if (oscTooLong) {
            oscTooLong = false;
            dropped++;
            return;
        }
        int bodyStart = osc.charAt(0) == ESC ? 2 : 1;
        String body = osc.substring(bodyStart);
        if (allowed(body)) {
            out.append(osc).append(terminator);
        } else {
            dropped++;
        }
        osc.setLength(0);
    }

    /** Whether the OSC with this body (what stands between introducer and terminator) may pass. */
    static boolean allowed(String body) {
        int semicolon = body.indexOf(';');
        String command = semicolon >= 0 ? body.substring(0, semicolon) : body;
        String argument = semicolon >= 0 ? body.substring(semicolon + 1) : "";
        switch (command) {
            case "52":
                return false;
            case "8": {
                // OSC 8 ; params ; URI
                int uriStart = argument.indexOf(';');
                String uri = uriStart >= 0 ? argument.substring(uriStart + 1) : "";
                if (uri.isEmpty()) {
                    return true;
                }
                String lower = uri.toLowerCase(Locale.ROOT);
                return lower.startsWith("http://") || lower.startsWith("https://");
            }
            case "0", "1", "2": {
                if (argument.length() > MAX_TITLE_LENGTH) {
                    return false;
                }
                for (int i = 0; i < argument.length(); i++) {
                    char ch = argument.charAt(i);
                    if (ch < 0x20 || (ch >= 0x7F && ch <= 0x9F)) {
                        return false;
                    }
                }
                return true;
            }
            default:
                return true;
        }
    }
}
