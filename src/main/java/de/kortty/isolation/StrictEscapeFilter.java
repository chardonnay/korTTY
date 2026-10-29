package de.kortty.isolation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The strict terminal mode: drops the escape sequences a hostile server could use against the terminal
 * or the computer it runs on, before the emulator (or korTTY's shell integration) sees them. Everything
 * else passes unchanged and in order. Stateful, because a sequence can be split across reads; one filter
 * per session, used from one thread.
 *
 * <p>Dropped:
 * <ul>
 *   <li>OSC 52, clipboard reads and writes, and OSC 5522, kitty's clipboard protocol;</li>
 *   <li>private mode 5522 (paste events of the clipboard protocol) in {@code CSI ? ... h} and
 *       {@code CSI ? ... l}, where the other modes of the sequence still pass, and the query whether the
 *       terminal has it, DECRQM {@code CSI ? 5522 $ p};</li>
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
 *
 * <p>A private-mode CSI ({@code ESC [ ?}) is read the way SithTermFX's {@code ControlSequence} reads it: up
 * to the first char in {@code 0x40..0x7E}, with the parameters made of digits and {@code ;} alone and every
 * other char inside it a stray char, which SithTermFX reads again before the sequence. A sequence without
 * stray chars that names no mode 5522 passes unchanged. One with stray chars passes as SithTermFX would
 * read it: the stray chars first, through this filter, then the sequence rebuilt from its parameters. So
 * mode 5522 cannot hide behind a stray char ({@code CSI ? 55:22 h} sets mode 5522 in SithTermFX). A
 * private-mode CSI longer than {@value #MAX_CSI_LENGTH} characters is dropped. SithTermFX knows no 8-bit
 * CSI ({@code U+009B}), so neither does this filter.
 */
public final class StrictEscapeFilter {

    /** The longest OSC that may pass, in characters. */
    public static final int MAX_STRING_LENGTH = 4096;

    /** The longest window or tab title that may pass, in characters. */
    public static final int MAX_TITLE_LENGTH = 256;

    /** The longest private-mode CSI that may pass, in characters after {@code ESC [}. */
    public static final int MAX_CSI_LENGTH = 512;

    /** The private mode of the clipboard protocol's paste events, and what DECRQM asks about. */
    static final int CLIPBOARD_MODE = 5522;

    /** SithTermFX clamps a CSI parameter to this, as xterm does. */
    private static final int MAX_PARAMETER_VALUE = 65535;

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

    private enum State { NORMAL, ESCAPE, CSI_START, PRIVATE_CSI, CSI_DROP, OSC, OSC_ESCAPE, DROP, DROP_ESCAPE }

    private State state = State.NORMAL;
    /** The OSC so far, introducer included, while it may still pass. */
    private final StringBuilder osc = new StringBuilder();
    /** Whether the OSC being read already went over {@link #MAX_STRING_LENGTH}, so it is dropped at its end. */
    private boolean oscTooLong;
    private long dropped;
    /** The private-mode CSI so far, after {@code ESC [}, marker included, while it is read. */
    private final StringBuilder csi = new StringBuilder();
    /** Its stray chars, in order. */
    private final StringBuilder csiStray = new StringBuilder();
    /** Its complete parameters so far. */
    private final List<Integer> csiParameters = new ArrayList<>();
    private int csiValue;
    private int csiDigits;
    private boolean csiSeenDigit;
    private boolean csiDollar;

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
        } else if (state == State.CSI_START) {
            out.append(ESC).append('[');
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
                    case '[' -> state = State.CSI_START;
                    case 'P', 'X', '^', '_' -> startDrop();
                    case ESC -> out.append(ESC);
                    default -> {
                        out.append(ESC).append(c);
                        state = State.NORMAL;
                    }
                }
            }
            case CSI_START -> {
                if (c == '?') {
                    startPrivateCsi();
                } else {
                    // Any other CSI passes as it is.
                    out.append(ESC).append('[');
                    state = State.NORMAL;
                    accept(c, out);
                }
            }
            case PRIVATE_CSI -> acceptPrivateCsi(c, out);
            case CSI_DROP -> {
                if (c >= 0x40 && c <= 0x7E) {
                    state = State.NORMAL;
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

    private void startPrivateCsi() {
        csi.setLength(0);
        csi.append('?');
        csiStray.setLength(0);
        csiParameters.clear();
        csiValue = 0;
        csiDigits = 0;
        csiSeenDigit = false;
        csiDollar = false;
        state = State.PRIVATE_CSI;
    }

    /** Mirrors {@code ControlSequence.readControlSequence} of SithTermFX 1.2.7 after {@code ESC [ ?}. */
    private void acceptPrivateCsi(char c, StringBuilder out) {
        if (csi.length() >= MAX_CSI_LENGTH) {
            dropped++;
            csi.setLength(0);
            csiStray.setLength(0);
            state = State.CSI_DROP;
            accept(c, out);
            return;
        }
        csi.append(c);
        if (c == ';') {
            if (csiDigits > 0) {
                csiParameters.add(csiValue);
                csiValue = 0;
                csiDigits = 0;
            }
        } else if (c >= '0' && c <= '9') {
            csiValue = Math.min(csiValue * 10 + (c - '0'), MAX_PARAMETER_VALUE);
            csiDigits++;
            csiSeenDigit = true;
        } else if (c == '$') {
            csiDollar = true;
        } else if (c >= 0x40 && c <= 0x7E) {
            finishPrivateCsi(c, out);
        } else {
            csiStray.append(c);
        }
    }

    private void finishPrivateCsi(char finalChar, StringBuilder out) {
        state = State.NORMAL;
        List<Integer> parameters = new ArrayList<>(csiParameters);
        if (csiSeenDigit) {
            parameters.add(csiValue);
        }
        String sequence = csi.toString();
        String stray = csiStray.toString();
        csi.setLength(0);
        csiStray.setLength(0);
        csiParameters.clear();
        // SithTermFX reads the stray chars again first, and then the sequence without them and without $.
        boolean dollar = csiDollar && stray.isEmpty();
        boolean clipboardMode = parameters.contains(CLIPBOARD_MODE);
        if (!clipboardMode && stray.isEmpty()) {
            out.append(ESC).append('[').append(sequence);
            return;
        }
        for (int i = 0; i < stray.length(); i++) {
            accept(stray.charAt(i), out);
        }
        if (clipboardMode && (finalChar == 'h' || finalChar == 'l') && !dollar) {
            parameters.removeIf(parameter -> parameter == CLIPBOARD_MODE);
            dropped++;
            if (parameters.isEmpty()) {
                return;
            }
        } else if (clipboardMode && finalChar == 'p') {
            // DECRQM for the clipboard protocol: no answer, as from a terminal without it. Without its $
            // (lost to a stray char) the sequence is no query, and nothing of it is worth passing.
            dropped++;
            return;
        }
        if (state != State.NORMAL) {
            // A stray char opened a string or an escape: SithTermFX reads the sequence inside it too.
            for (char rebuilt : rebuild(parameters, dollar, finalChar).toCharArray()) {
                accept(rebuilt, out);
            }
            return;
        }
        out.append(rebuild(parameters, dollar, finalChar));
    }

    private static String rebuild(List<Integer> parameters, boolean dollar, char finalChar) {
        StringBuilder sequence = new StringBuilder().append(ESC).append("[?");
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                sequence.append(';');
            }
            sequence.append(parameters.get(i));
        }
        if (dollar) {
            sequence.append('$');
        }
        return sequence.append(finalChar).toString();
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
            case "52", "5522":
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
