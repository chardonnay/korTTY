package de.kortty.control;

import de.kortty.codingagent.KeyChordEncoder;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Encodes what {@code pane.send_text} and {@code pane.run} write to a pty, including the DECSET 2004
 * bracketed-paste markers.
 *
 * <p>This mirrors {@code KeyChordEncoder.promptPayload} — {@code \n} becomes {@code \r} because that
 * is what a real key press produces, and the submitting {@code \r} sits <strong>outside</strong> the
 * paste markers so the application receives the payload as pasted text and the carriage return as a
 * key press.
 *
 * <p>Pure, any thread.
 */
public final class BracketedPaste {

    /** The DECSET 2004 start marker, {@code ESC [ 2 0 0 ~}. */
    public static final byte[] START = "\u001b[200~".getBytes(StandardCharsets.US_ASCII);

    /** The DECSET 2004 end marker, {@code ESC [ 2 0 1 ~}. */
    public static final byte[] END = "\u001b[201~".getBytes(StandardCharsets.US_ASCII);

    /** The wire spelling that wraps only a multi-line payload into a pane that supports it. */
    private static final String MODE_AUTO = "auto";

    /** The wire spelling that always wraps. */
    private static final String MODE_ALWAYS = "always";

    /** The wire spelling that never wraps. */
    private static final String MODE_NEVER = "never";

    /** The accepted spellings, published in {@code error.data.known}. */
    private static final List<String> MODES = List.of(MODE_AUTO, MODE_NEVER, MODE_ALWAYS);

    /** The carriage return one submitting write appends. */
    private static final byte[] SUBMIT = {'\r'};

    private BracketedPaste() {
    }

    /**
     * The bytes one text write puts on the pty.
     *
     * @param text the text as the client sent it; {@code \r\n} and {@code \n} both become {@code \r}
     * @param bracketed whether to wrap the payload in the DECSET 2004 markers
     * @param submit whether to append a carriage return outside the markers
     * @return a fresh array, never shared with {@link #START} or {@link #END}
     */
    public static byte[] encode(String text, boolean bracketed, boolean submit) {
        String payload = text == null ? "" : text.replace("\r\n", "\n").replace('\n', '\r');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (bracketed) {
            out.writeBytes(START);
        }
        out.writeBytes(payload.getBytes(StandardCharsets.UTF_8));
        if (bracketed) {
            out.writeBytes(END);
        }
        if (submit) {
            out.writeBytes(SUBMIT);
        }
        return out.toByteArray();
    }

    /**
     * Whether this write is wrapped in the paste markers.
     *
     * @param mode {@code auto} (the default), {@code never} or {@code always}; null or blank means
     *     {@code auto}
     * @param text the text about to be written
     * @param paneSupportsIt what the pane's {@code BracketedPasteTracker} reports
     * @throws ControlApiException {@link ControlErrorCode#INVALID_PARAMS} for any other spelling
     */
    public static boolean shouldBracket(String mode, String text, boolean paneSupportsIt)
            throws ControlApiException {
        String value = mode == null || mode.isBlank() ? MODE_AUTO : mode.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case MODE_AUTO -> paneSupportsIt && KeyChordEncoder.isMultiLine(text);
            case MODE_ALWAYS -> true;
            case MODE_NEVER -> false;
            default -> throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Unknown bracketed-paste mode: " + mode,
                Map.of("param", "bracketed", "known", MODES));
        };
    }
}
