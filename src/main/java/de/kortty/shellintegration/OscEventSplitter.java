package de.kortty.shellintegration;

import java.util.List;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

/**
 * Splits a pane's output into the text SithTermFX should interpret and the {@link OwnedOsc}
 * sequences korTTY handles itself, in stream order. Output is fed in arbitrary chunks; a sequence
 * cut by a chunk boundary is completed by the next chunk.
 *
 * <p>Taking a sequence out is only safe where SithTermFX would have read the very same chars as
 * that OSC, so the splitter follows {@code SithEmulator} and {@code SystemCommandSequence} (vendor
 * 1.2.2) char by char rather than the ECMA-48 grammar:
 * <ul>
 *   <li>An OSC starts with {@code ESC ]}, or with C1 {@code U+009D} where SithTermFX reads a char
 *       on its own: at the start of the output, after a control char or after a sequence.</li>
 *   <li>Within a run of printable text SithTermFX prints {@code U+009D}, unless the run happens to
 *       be cut right before it: runs end at the right margin and at the end of each read, and
 *       there it opens an OSC. The splitter cannot see the cursor, so it treats such a
 *       {@code U+009D} as a foreign OSC that may or may not be open and takes nothing out until
 *       the next terminator ends either reading.</li>
 *   <li>It ends with BEL, C1 ST {@code U+009C} or {@code ESC \}. A bare ESC is part of the body,
 *       so {@code ESC ]} inside an OSC never starts a new one.</li>
 *   <li>A DCS ({@code ESC P}) is read up to the same terminators and passes through untouched,
 *       tmux passthrough included.</li>
 *   <li>A CSI ({@code ESC [}) runs to its first char in {@code 0x40..0x7E}; nothing inside it
 *       starts an OSC. The chars SithTermFX cannot place in a CSI are pushed back and read again
 *       afterwards, followed by the sequence itself; the splitter replays them the same way.</li>
 *   <li>{@code ESC} plus one of {@code # ( ) * + $ @ % . / space} takes one more char; any other
 *       char after ESC ends the escape, ESC and C1 included.</li>
 * </ul>
 *
 * <p>Everything that is not an owned sequence is passed on byte for byte, foreign OSCs included,
 * however long. Whether a sequence is owned is decided by its first chars (at most
 * {@value #MAX_PREFIX_LENGTH}); until then they are held back, never longer. Owned sequences are
 * always taken out, whatever the settings say: SithTermFX ignores all of them, so this changes
 * nothing on screen, and whoever consumes the events decides whether they act.
 *
 * <p>Not thread-safe: one instance per output stream, fed from the thread that reads it.
 */
public final class OscEventSplitter {

    /** Receives the result of {@link #feed}, in stream order. */
    public interface Sink {

        /** Text to pass on; the chars are only valid during the call. */
        void text(char[] chars, int offset, int length);

        /**
         * An owned sequence was taken out here; every char before it has gone to {@link #text}.
         * SithTermFX ended its run of text at the sequence, so whoever passes the text on should
         * end a read here too, event or not: text written in one piece is normalised (NFC) and
         * stored as one piece.
         *
         * @param event what it announced, or {@code null} when it announces nothing korTTY acts on
         */
        void sequence(@Nullable ShellIntegrationEvent event);
    }

    /** The most chars of an OSC body held back before it is known whether korTTY owns it. */
    public static final int MAX_PREFIX_LENGTH = 16;

    static final char ESC = '\u001B';
    static final char BEL = '\u0007';
    static final char C1_ST = '\u009C';
    static final char C1_OSC = '\u009D';

    /** SithTermFX pushes a CSI's stray chars back through a 1024-char array; it fails beyond that. */
    private static final int MAX_CSI_STRAY_CHARS = 1_000;

    private static final List<OwnedOsc> OWNED = List.of(OwnedOsc.values());

    /** ConEmu reuses OSC 9 for numbered commands ({@code 9;4;st;pr} is progress), not text. */
    private static final Pattern CONEMU_SUBCOMMAND = Pattern.compile("[0-9]+(;|$)");
    private static final Pattern STATUS = Pattern.compile("-?[0-9]{1,10}");

    private enum State {
        /** Between sequences; {@link #plainRun} tells whether a run of printable text is open. */
        GROUND,
        /** After ESC. */
        ESCAPE,
        /** After ESC and a char that takes one more ({@code ESC ( B}). */
        ESCAPE_INTERMEDIATE,
        /** Inside {@code ESC [ ...}. */
        CSI,
        /**
         * Inside a DCS or a foreign OSC, passed through up to its terminator; or after a
         * {@code U+009D} in printable text that SithTermFX may or may not read as an OSC
         * ({@link #maybeString}).
         */
        PASSTHROUGH_STRING,
        /** Inside an OSC whose first chars do not yet tell whether korTTY owns it. */
        OSC_PREFIX,
        /** Inside an owned OSC. */
        OWNED_OSC
    }

    // What advance() tells feed() to do with the char it was given.
    /** Pass the char on with the text around it. */
    private static final int PASS = 0;
    /** The char went into {@link #held}. */
    private static final int HOLD = 1;
    /** The char went into {@link #held}, and all of it is to be passed on now. */
    private static final int RELEASE = 2;
    /** Drop the char: it belongs to an owned sequence. */
    private static final int SWALLOW = 3;
    /** Drop the char, which ended an owned sequence; {@link #completed} holds its event, if any. */
    private static final int COMPLETE = 4;

    private State state = State.GROUND;
    /** GROUND only: the last char was printable text, so SithTermFX may read U+009D as text. */
    private boolean plainRun;
    /** PASSTHROUGH_STRING only: opened by a U+009D within printable text, so maybe no string at all. */
    private boolean maybeString;
    /** Inside a string (OSC or DCS body): the previous char was ESC. */
    private boolean afterEscape;
    private int csiPosition;
    private final StringBuilder csiStrayChars = new StringBuilder();
    /** Chars of a possible owned sequence, not passed on yet: ESC, or an OSC introducer and its first chars. */
    private final StringBuilder held = new StringBuilder();
    private int heldIntroducerLength;
    private char[] releaseBuffer = new char[MAX_PREFIX_LENGTH + 2];
    private @Nullable OwnedOsc owned;
    private final StringBuilder ownedPayload = new StringBuilder();
    private boolean ownedOversize;
    private @Nullable ShellIntegrationEvent completed;

    /**
     * Splits the next chunk of output. Text that may start an owned sequence is held back until
     * the following chunks tell; all other text reaches {@code sink} before this returns.
     */
    public void feed(char[] chars, int offset, int length, Sink sink) {
        int end = offset + length;
        int runStart = offset;
        int i = offset;
        while (i < end) {
            if (state == State.GROUND) {
                // Fast path: printable text and C0 controls other than ESC change nothing here.
                while (i < end) {
                    char c = chars[i];
                    if (c >= 0x20) {
                        if (c == C1_OSC) {
                            break;
                        }
                        plainRun = true;
                    } else if (c == ESC) {
                        break;
                    } else {
                        plainRun = false;
                    }
                    i++;
                }
                if (i == end) {
                    break;
                }
            }
            int action = advance(chars[i]);
            if (action != PASS) {
                if (i > runStart) {
                    sink.text(chars, runStart, i - runStart);
                }
                runStart = i + 1;
                if (action == RELEASE) {
                    releaseHeld(sink);
                } else if (action == COMPLETE) {
                    ShellIntegrationEvent event = completed;
                    completed = null;
                    sink.sequence(event);
                }
            }
            i++;
        }
        if (end > runStart) {
            sink.text(chars, runStart, end - runStart);
        }
    }

    private void releaseHeld(Sink sink) {
        int length = held.length();
        if (length > 0) {
            if (releaseBuffer.length < length) {
                releaseBuffer = new char[length];
            }
            held.getChars(0, length, releaseBuffer, 0);
            held.setLength(0);
            sink.text(releaseBuffer, 0, length);
        }
    }

    private int advance(char c) {
        switch (state) {
            case GROUND:
                if (c >= 0x20) {
                    if (c == C1_OSC) {
                        if (!plainRun) {
                            startOsc(c);
                            return HOLD;
                        }
                        // Printed, or an OSC if the run was cut right here: nothing is owned until
                        // a terminator ends the string it may have opened.
                        maybeString = true;
                        afterEscape = false;
                        state = State.PASSTHROUGH_STRING;
                        return PASS;
                    }
                    plainRun = true;
                    return PASS;
                }
                plainRun = false;
                if (c == ESC) {
                    held.append(c);
                    state = State.ESCAPE;
                    return HOLD;
                }
                return PASS;
            case ESCAPE:
                if (c == ']') {
                    startOsc(c);
                    heldIntroducerLength = 2; // ESC ]
                    return HOLD;
                }
                held.append(c);
                state = switch (c) {
                    case '[' -> {
                        csiPosition = 0;
                        csiStrayChars.setLength(0);
                        yield State.CSI;
                    }
                    case 'P' -> {
                        afterEscape = false;
                        maybeString = false;
                        yield State.PASSTHROUGH_STRING;
                    }
                    case '#', '(', ')', '*', '+', '$', '@', '%', '.', '/', ' ' -> State.ESCAPE_INTERMEDIATE;
                    default -> State.GROUND;
                };
                return RELEASE;
            case ESCAPE_INTERMEDIATE:
                state = State.GROUND;
                return PASS;
            case CSI:
                return advanceCsi(c);
            case PASSTHROUGH_STRING:
                if (endsString(c)) {
                    state = State.GROUND;
                    // Where the string was never open, U+009C is printable text.
                    plainRun = maybeString && c == C1_ST;
                    maybeString = false;
                } else {
                    afterEscape = c == ESC;
                }
                return PASS;
            case OSC_PREFIX:
                return advanceOscPrefix(c);
            case OWNED_OSC:
                return advanceOwned(c);
            default:
                throw new IllegalStateException(String.valueOf(state));
        }
    }

    private void startOsc(char introducer) {
        held.append(introducer);
        heldIntroducerLength = 1;
        afterEscape = false;
        state = State.OSC_PREFIX;
    }

    private boolean endsString(char c) {
        return c == BEL || c == C1_ST || (c == '\\' && afterEscape);
    }

    /** Mirrors {@code ControlSequence.readControlSequence}. */
    private int advanceCsi(char c) {
        int position = csiPosition++;
        if (position == 0 && (c == '!' || c == '?' || c == '>')) {
            return PASS;
        }
        if (c == ';' || (c >= '0' && c <= '9')) {
            return PASS;
        }
        if (c >= 0x40 && c <= 0x7E) {
            state = State.GROUND;
            plainRun = false;
            if (!csiStrayChars.isEmpty()) {
                replayStrayChars(c);
            }
            return PASS;
        }
        if (csiStrayChars.length() < MAX_CSI_STRAY_CHARS) {
            csiStrayChars.append(c);
        }
        return PASS;
    }

    /**
     * SithTermFX's {@code ControlSequence.pushBackReordered}: the chars it could not place in the CSI
     * are read again as ordinary output, followed by {@code ESC [ <parameters> <final>}. These chars
     * have already been passed on, so only the state moves. The parameters are left out: they are
     * digits, {@code ;} and {@code !?>}, which cannot change the state wherever they land, and no
     * replayed sequence can be owned (an owned body starts with a digit, which is never stray).
     */
    private void replayStrayChars(char finalChar) {
        String stray = csiStrayChars.toString();
        csiStrayChars.setLength(0);
        for (int i = 0; i < stray.length(); i++) {
            replay(stray.charAt(i));
        }
        replay(ESC);
        replay('[');
        replay(finalChar);
        if (!held.isEmpty()) {
            // Unreachable by the argument above; never let replayed chars out as text.
            held.setLength(0);
            maybeString = false;
            state = state == State.OSC_PREFIX ? State.PASSTHROUGH_STRING : State.GROUND;
        }
    }

    private void replay(char c) {
        int action = advance(c);
        if (action == RELEASE) {
            held.setLength(0);
        } else if (action == COMPLETE) {
            completed = null;
        }
    }

    private int advanceOscPrefix(char c) {
        held.append(c);
        if (endsString(c)) {
            // Ended before it could be told apart from an owned one: not owned.
            state = State.GROUND;
            plainRun = false;
            return RELEASE;
        }
        afterEscape = c == ESC;
        int bodyLength = held.length() - heldIntroducerLength;
        boolean possiblyOwned = false;
        for (OwnedOsc candidate : OWNED) {
            String prefix = candidate.prefix();
            int compared = Math.min(bodyLength, prefix.length());
            if (heldBodyStartsWith(prefix, compared)) {
                if (bodyLength >= prefix.length()) {
                    held.setLength(0);
                    owned = candidate;
                    ownedPayload.setLength(0);
                    ownedOversize = false;
                    afterEscape = false;
                    state = State.OWNED_OSC;
                    return SWALLOW;
                }
                possiblyOwned = true;
            }
        }
        if (possiblyOwned && bodyLength < MAX_PREFIX_LENGTH) {
            return HOLD;
        }
        maybeString = false;
        state = State.PASSTHROUGH_STRING;
        return RELEASE;
    }

    private boolean heldBodyStartsWith(String prefix, int length) {
        for (int k = 0; k < length; k++) {
            if (held.charAt(heldIntroducerLength + k) != prefix.charAt(k)) {
                return false;
            }
        }
        return true;
    }

    private int advanceOwned(char c) {
        OwnedOsc kind = owned;
        if (endsString(c)) {
            if (!ownedOversize && c == '\\') {
                ownedPayload.setLength(ownedPayload.length() - 1); // the ESC of ESC-backslash
            }
            completed = ownedOversize || ownedPayload.length() > kind.maxPayloadLength()
                    ? new ShellIntegrationEvent.Oversize(kind)
                    : parse(kind, ownedPayload.toString());
            ownedPayload.setLength(0);
            ownedOversize = false;
            owned = null;
            afterEscape = false;
            state = State.GROUND;
            plainRun = false;
            return COMPLETE;
        }
        afterEscape = c == ESC;
        if (!ownedOversize) {
            // One char over the cap is kept: it may be the ESC of the terminator.
            if (ownedPayload.length() <= kind.maxPayloadLength()) {
                ownedPayload.append(c);
            } else {
                ownedOversize = true;
                ownedPayload.setLength(0);
            }
        }
        return SWALLOW;
    }

    /**
     * Reads the payload of an owned sequence, the body after {@link OwnedOsc#prefix()} without
     * its terminator.
     *
     * @return the event, or {@code null} for a sequence that announces nothing korTTY acts on: an
     *         unknown OSC 133 mark, a ConEmu {@code OSC 9;<number>} subcommand such as the
     *         {@code 9;4} progress report, or an empty notification
     */
    static @Nullable ShellIntegrationEvent parse(OwnedOsc kind, String payload) {
        return switch (kind) {
            case SHELL_INTEGRATION -> parseMark(payload);
            case NOTIFICATION -> payload.isEmpty() || CONEMU_SUBCOMMAND.matcher(payload).lookingAt()
                    ? null
                    : new ShellIntegrationEvent.RemoteNotification(kind, null, payload);
            case NOTIFY -> parseNotify(payload);
        };
    }

    private static @Nullable ShellIntegrationEvent parseMark(String payload) {
        String[] parameters = payload.split(";", -1);
        return switch (parameters[0]) {
            case "A" -> new ShellIntegrationEvent.PromptStart();
            case "B" -> new ShellIntegrationEvent.CommandStart();
            case "C" -> new ShellIntegrationEvent.OutputStart();
            case "D" -> new ShellIntegrationEvent.CommandFinished(exitStatus(parameters));
            default -> null;
        };
    }

    /** A D mark's first integer parameter; {@code aid=} and {@code cl=} style keys are skipped. */
    private static @Nullable Integer exitStatus(String[] parameters) {
        for (int i = 1; i < parameters.length; i++) {
            if (STATUS.matcher(parameters[i]).matches()) {
                try {
                    return Integer.parseInt(parameters[i]);
                } catch (NumberFormatException outOfRange) {
                    // ten digits beyond the int range: not a status
                }
            }
        }
        return null;
    }

    /** {@code title;body}, where the body is every remaining argument joined with {@code ;} again. */
    private static @Nullable ShellIntegrationEvent parseNotify(String payload) {
        int separator = payload.indexOf(';');
        String title = separator < 0 ? payload : payload.substring(0, separator);
        String body = separator < 0 ? "" : payload.substring(separator + 1);
        if (body.isEmpty()) {
            return title.isEmpty() ? null : new ShellIntegrationEvent.RemoteNotification(OwnedOsc.NOTIFY, null, title);
        }
        return new ShellIntegrationEvent.RemoteNotification(OwnedOsc.NOTIFY, title.isEmpty() ? null : title, body);
    }
}
