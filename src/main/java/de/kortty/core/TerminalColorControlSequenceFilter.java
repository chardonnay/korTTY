package de.kortty.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Removes SGR color attributes while preserving other terminal control sequences.
 */
public final class TerminalColorControlSequenceFilter {

    private static final char ESC = '\u001B';

    private String pending = "";

    public String filter(char[] buffer, int offset, int length) {
        if (buffer == null || length <= 0) {
            return "";
        }
        return filter(new String(buffer, offset, length));
    }

    public String filter(CharSequence input) {
        if (input == null || input.isEmpty()) {
            return "";
        }

        String data = pending + input;
        pending = "";
        StringBuilder output = new StringBuilder(data.length());

        int index = 0;
        while (index < data.length()) {
            char current = data.charAt(index);
            if (current != ESC) {
                output.append(current);
                index++;
                continue;
            }

            if (index + 1 >= data.length()) {
                pending = data.substring(index);
                break;
            }

            if (data.charAt(index + 1) != '[') {
                output.append(current);
                index++;
                continue;
            }

            int finalIndex = findCsiFinal(data, index + 2);
            if (finalIndex < 0) {
                pending = data.substring(index);
                break;
            }

            char finalChar = data.charAt(finalIndex);
            if (finalChar == 'm') {
                String rewrittenParams = stripColorSgrParams(data.substring(index + 2, finalIndex));
                if (rewrittenParams != null) {
                    output.append(ESC).append('[').append(rewrittenParams).append('m');
                }
            } else {
                output.append(data, index, finalIndex + 1);
            }
            index = finalIndex + 1;
        }

        return output.toString();
    }

    public void reset() {
        pending = "";
    }

    private static int findCsiFinal(String data, int start) {
        for (int index = start; index < data.length(); index++) {
            char value = data.charAt(index);
            if (value >= 0x40 && value <= 0x7E) {
                return index;
            }
        }
        return -1;
    }

    private static String stripColorSgrParams(String params) {
        if (params == null || params.isEmpty()) {
            return "";
        }

        String[] parts = params.split(";", -1);
        List<String> kept = new ArrayList<>(parts.length);
        boolean removedColor = false;

        int index = 0;
        while (index < parts.length) {
            String part = parts[index];
            Integer code = parseInteger(part);
            if (code == null) {
                if (isColonSeparatedColorPart(part)) {
                    removedColor = true;
                } else {
                    kept.add(part);
                }
                index++;
                continue;
            }

            if (isSimpleColorCode(code)) {
                removedColor = true;
                index++;
                continue;
            }

            if (code == 38 || code == 48) {
                removedColor = true;
                index += extendedColorPartCount(parts, index);
                continue;
            }

            kept.add(part);
            index++;
        }

        if (!removedColor) {
            return params;
        }
        return kept.isEmpty() ? null : String.join(";", kept);
    }

    private static boolean isSimpleColorCode(int code) {
        return (code >= 30 && code <= 37)
            || code == 39
            || (code >= 40 && code <= 47)
            || code == 49
            || (code >= 90 && code <= 97)
            || (code >= 100 && code <= 107);
    }

    private static int extendedColorPartCount(String[] parts, int colorCodeIndex) {
        if (colorCodeIndex + 1 >= parts.length) {
            return 1;
        }
        Integer mode = parseInteger(parts[colorCodeIndex + 1]);
        if (mode != null && mode == 2) {
            return Math.min(5, parts.length - colorCodeIndex);
        }
        if (mode != null && mode == 5) {
            return Math.min(3, parts.length - colorCodeIndex);
        }
        return 1;
    }

    private static boolean isColonSeparatedColorPart(String part) {
        return part != null && (part.startsWith("38:") || part.startsWith("48:"));
    }

    private static Integer parseInteger(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
