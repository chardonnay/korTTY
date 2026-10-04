package de.kortty.core;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/**
 * Turns the bytes of an XML file that does not parse into text that can: decodes them as UTF-8,
 * dropping byte sequences that are not UTF-8, and removes every character XML 1.0 cannot hold —
 * C0 controls other than tab/LF/CR, U+FFFE, U+FFFF and lone surrogates. JAXB writes such
 * characters raw, so a single one in any string makes the whole document unreadable.
 */
final class XmlCharacterSanitizer {

    /** The sanitised text and how many characters (or malformed byte sequences) were removed. */
    record Result(String text, int removedCount) {
    }

    private XmlCharacterSanitizer() {
    }

    static Result sanitize(byte[] content) {
        int removed = 0;
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        ByteBuffer in = ByteBuffer.wrap(content);
        CharBuffer out = CharBuffer.allocate(content.length + 1);
        // UTF-8 never yields more chars than bytes, so the output buffer cannot overflow.
        CoderResult result;
        while ((result = decoder.decode(in, out, true)).isError()) {
            in.position(in.position() + result.length());
            removed++;
        }
        decoder.flush(out);
        out.flip();

        StringBuilder text = new StringBuilder(out.length());
        int i = 0;
        while (i < out.length()) {
            int codePoint = Character.codePointAt(out, i);
            int width = Character.charCount(codePoint);
            if (isXml10Char(codePoint)) {
                text.appendCodePoint(codePoint);
            } else {
                removed++;
            }
            i += width;
        }
        // A leading BOM would precede the XML declaration in the decoded text.
        if (text.length() > 0 && text.charAt(0) == '﻿') {
            text.deleteCharAt(0);
        }
        return new Result(text.toString(), removed);
    }

    /** The XML 1.0 {@code Char} production; a lone surrogate arrives here as its own code point. */
    static boolean isXml10Char(int codePoint) {
        return codePoint == 0x9 || codePoint == 0xA || codePoint == 0xD
            || (codePoint >= 0x20 && codePoint <= 0xD7FF)
            || (codePoint >= 0xE000 && codePoint <= 0xFFFD)
            || (codePoint >= 0x10000 && codePoint <= 0x10FFFF);
    }
}
