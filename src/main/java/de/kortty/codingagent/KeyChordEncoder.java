package de.kortty.codingagent;

import de.kortty.paste.PasteSanitizer;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Turns {@link KeyChord}s and prompt text into the bytes written to a pane. Prompt text is
 * normalised to LF line breaks with trailing line breaks removed; a single line is sent as
 * {@code text + CR}, a multi-line prompt is wrapped in bracketed-paste markers (with LF → CR like a
 * real paste) when the pane has enabled DECSET 2004, otherwise the lines are joined with CR.
 * Bracketed-paste markers inside the prompt are removed first ({@link PasteSanitizer}), so a prompt
 * cannot end the paste early and have its remaining lines submitted one by one.
 */
public final class KeyChordEncoder {

    private static final String PASTE_START = "\u001b[200~";
    private static final String PASTE_END = "\u001b[201~";

    private KeyChordEncoder() {
    }

    /** The bytes of one chord. */
    public static byte[] encode(KeyChord chord) {
        return Objects.requireNonNull(chord, "chord").bytes();
    }

    /** The concatenated bytes of the chords in order. */
    public static byte[] encode(List<KeyChord> chords) {
        if (chords == null || chords.isEmpty()) {
            return new byte[0];
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (KeyChord chord : chords) {
            if (chord != null) {
                out.writeBytes(chord.bytes());
            }
        }
        return out.toByteArray();
    }

    /**
     * Parses whitespace-separated chord ids ("y enter", "Ctrl-C").
     *
     * @throws IllegalArgumentException for an unknown id
     */
    public static List<KeyChord> parseAll(String spaceSeparatedIds) {
        List<KeyChord> result = new ArrayList<>();
        if (spaceSeparatedIds == null || spaceSeparatedIds.isBlank()) {
            return result;
        }
        for (String token : spaceSeparatedIds.strip().split("\\s+")) {
            result.add(KeyChord.parse(token)
                .orElseThrow(() -> new IllegalArgumentException("Unknown key chord: " + token)));
        }
        return result;
    }

    /** The payload of a prompt as described in the class comment; "" for null or blank text. */
    public static String promptPayload(String text, boolean bracketedPaste) {
        String normalised = normalise(text);
        if (normalised.isBlank()) {
            return "";
        }
        if (normalised.indexOf('\n') < 0) {
            return normalised + "\r";
        }
        String withCr = normalised.replace('\n', '\r');
        if (bracketedPaste) {
            return PASTE_START + withCr + PASTE_END + "\r";
        }
        return withCr + "\r";
    }

    /** True when the text (after normalisation and trailing-break removal) spans several lines. */
    public static boolean isMultiLine(String text) {
        return normalise(text).indexOf('\n') >= 0;
    }

    /**
     * Bracketed-paste markers are removed, CRLF and lone CR become LF, and trailing line breaks are
     * dropped. Removing the markers first keeps the blank check, the multi-line decision and the
     * payload in agreement: a line break that only a marker kept from being trailing is trailing.
     */
    static String normalise(String text) {
        if (text == null) {
            return "";
        }
        String result = PasteSanitizer.stripBracketMarkers(text).replace("\r\n", "\n").replace('\r', '\n');
        int end = result.length();
        while (end > 0 && result.charAt(end - 1) == '\n') {
            end--;
        }
        return result.substring(0, end);
    }
}
