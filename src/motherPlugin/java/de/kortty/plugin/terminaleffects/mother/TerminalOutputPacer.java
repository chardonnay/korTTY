package de.kortty.plugin.terminaleffects.mother;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

/**
 * Splits terminal output into immediate control sequences and paced visible line-build segments.
 */
public final class TerminalOutputPacer {

    private static final int HIGH_VOLUME_THRESHOLD = 4096;
    private static final int MAX_PENDING_CHARS = 8192;

    private final Queue<OutputSegment> pendingSegments = new ArrayDeque<>();
    private int pendingChars;

    public List<OutputSegment> enqueue(String data) {
        List<OutputSegment> segments = segment(data);
        if (data != null && !data.isEmpty()
                && (data.length() >= HIGH_VOLUME_THRESHOLD || pendingChars + data.length() > MAX_PENDING_CHARS)) {
            StringBuilder bypassText = new StringBuilder(pendingChars + data.length());
            OutputSegment pendingSegment;
            while ((pendingSegment = pendingSegments.poll()) != null) {
                bypassText.append(pendingSegment.text());
            }
            bypassText.append(data);
            OutputSegment segment = new OutputSegment(bypassText.toString(), DelayMode.NONE);
            pendingSegments.add(segment);
            pendingChars = segment.text().length();
            return List.of(segment);
        }
        for (OutputSegment segment : segments) {
            pendingSegments.add(segment);
            pendingChars += segment.text().length();
        }
        return segments;
    }

    public OutputSegment poll() {
        OutputSegment segment = pendingSegments.poll();
        if (segment != null) {
            pendingChars = Math.max(0, pendingChars - segment.text().length());
        }
        return segment;
    }

    public boolean hasPending() {
        return !pendingSegments.isEmpty();
    }

    public static List<OutputSegment> segment(String data) {
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        List<OutputSegment> segments = new ArrayList<>();
        int index = 0;
        boolean lineStart = true;
        while (index < data.length()) {
            char ch = data.charAt(index);
            if (ch == '\u001B') {
                int end = terminalControlSequenceEnd(data, index);
                if (end > index) {
                    segments.add(new OutputSegment(data.substring(index, end), DelayMode.NONE));
                    index = end;
                    continue;
                }
            }
            if (ch == '\r' && index + 1 < data.length() && data.charAt(index + 1) == '\n') {
                segments.add(new OutputSegment("\r\n", DelayMode.LINE_BREAK));
                lineStart = true;
                index += 2;
                continue;
            }
            if (ch == '\n') {
                segments.add(new OutputSegment(String.valueOf(ch), DelayMode.LINE_BREAK));
                lineStart = true;
                index++;
                continue;
            }
            if (ch == '\r') {
                segments.add(new OutputSegment(String.valueOf(ch), DelayMode.NONE));
                lineStart = true;
                index++;
                continue;
            }

            int codePoint = data.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            String text = data.substring(index, index + charCount);
            if (isPrintablePacedCharacter(codePoint) || codePoint == '\t') {
                DelayMode delayMode = delayModeFor(codePoint, lineStart);
                segments.add(new OutputSegment(text, delayMode));
                lineStart = lineStart && Character.isWhitespace(codePoint);
            } else {
                segments.add(new OutputSegment(text, DelayMode.NONE));
            }
            index += charCount;
        }
        return segments;
    }

    private static boolean isPrintablePacedCharacter(int codePoint) {
        return !Character.isISOControl(codePoint);
    }

    private static DelayMode delayModeFor(int codePoint, boolean lineStart) {
        if (lineStart && !Character.isWhitespace(codePoint)) {
            return DelayMode.LINE_START;
        }
        if (Character.isWhitespace(codePoint)) {
            return DelayMode.WORD_GAP;
        }
        if (isPunctuationPause(codePoint)) {
            return DelayMode.PUNCTUATION;
        }
        return DelayMode.CHARACTER;
    }

    private static boolean isPunctuationPause(int codePoint) {
        return codePoint == ':'
                || codePoint == ';'
                || codePoint == ','
                || codePoint == '.'
                || codePoint == '?'
                || codePoint == '!';
    }

    static int terminalControlSequenceEnd(String text, int start) {
        if (start + 1 >= text.length()) {
            return -1;
        }
        char type = text.charAt(start + 1);
        if (type == '[') {
            for (int i = start + 2; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch >= '@' && ch <= '~') {
                    return i + 1;
                }
            }
            return -1;
        }
        if (type == ']') {
            int bellEnd = text.indexOf('\u0007', start + 2);
            int stEnd = text.indexOf("\u001B\\", start + 2);
            if (bellEnd < 0) {
                return stEnd >= 0 ? stEnd + 2 : -1;
            }
            if (stEnd < 0 || bellEnd < stEnd) {
                return bellEnd + 1;
            }
            return stEnd + 2;
        }
        return start + 2;
    }

    public enum DelayMode {
        NONE,
        CHARACTER,
        WORD_GAP,
        LINE_START,
        LINE_BREAK,
        PUNCTUATION
    }

    public record OutputSegment(String text, DelayMode delayMode) {

        public OutputSegment {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(delayMode, "delayMode");
        }

        public boolean delayed() {
            return delayMode != DelayMode.NONE;
        }
    }
}
