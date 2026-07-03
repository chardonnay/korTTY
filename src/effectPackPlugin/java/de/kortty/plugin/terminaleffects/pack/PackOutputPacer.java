package de.kortty.plugin.terminaleffects.pack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Queue;

/**
 * Splits terminal output into immediate control sequences and paced typewriter segments.
 * High-volume output bypasses pacing entirely so bulk output (e.g. {@code cat} of a large file)
 * is not slowed down.
 */
final class PackOutputPacer {

    static final int HIGH_VOLUME_THRESHOLD = 4096;
    static final int MAX_PENDING_CHARS = 8192;

    private final Queue<OutputSegment> pendingSegments = new ArrayDeque<>();
    private int pendingChars;

    List<OutputSegment> enqueue(String data) {
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        if (data.length() >= HIGH_VOLUME_THRESHOLD || pendingChars + data.length() > MAX_PENDING_CHARS) {
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
        List<OutputSegment> segments = segment(data);
        for (OutputSegment segment : segments) {
            pendingSegments.add(segment);
            pendingChars += segment.text().length();
        }
        return segments;
    }

    OutputSegment poll() {
        OutputSegment segment = pendingSegments.poll();
        if (segment != null) {
            pendingChars = Math.max(0, pendingChars - segment.text().length());
        }
        return segment;
    }

    boolean hasPending() {
        return !pendingSegments.isEmpty();
    }

    static List<OutputSegment> segment(String data) {
        if (data == null || data.isEmpty()) {
            return List.of();
        }
        List<OutputSegment> segments = new ArrayList<>();
        int index = 0;
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
                index += 2;
                continue;
            }
            if (ch == '\n') {
                segments.add(new OutputSegment("\n", DelayMode.LINE_BREAK));
                index++;
                continue;
            }

            int codePoint = data.codePointAt(index);
            int charCount = Character.charCount(codePoint);
            String text = data.substring(index, index + charCount);
            if (!Character.isISOControl(codePoint)) {
                segments.add(new OutputSegment(text, isPunctuationPause(codePoint)
                        ? DelayMode.PUNCTUATION
                        : DelayMode.CHARACTER));
            } else {
                segments.add(new OutputSegment(text, DelayMode.NONE));
            }
            index += charCount;
        }
        return segments;
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

    enum DelayMode {
        NONE,
        CHARACTER,
        PUNCTUATION,
        LINE_BREAK
    }

    record OutputSegment(String text, DelayMode delayMode) {

        OutputSegment {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(delayMode, "delayMode");
        }
    }
}
