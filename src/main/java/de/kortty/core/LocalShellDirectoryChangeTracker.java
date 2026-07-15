package de.kortty.core;

import java.util.Locale;

/**
 * Tracks submitted local-shell input lines and reports commands that may change the interactive
 * shell's working directory. It intentionally only answers the conservative yes/no question; the
 * actual directory remains sourced from the OS or a trusted absolute prompt hint.
 */
final class LocalShellDirectoryChangeTracker {

    private static final int MAX_LINE_LENGTH = 8192;
    private static final String BRACKETED_PASTE_START = "\u001b[200~";
    private static final String BRACKETED_PASTE_END = "\u001b[201~";

    private final StringBuilder inputLine = new StringBuilder();
    private final StringBuilder escapeSequence = new StringBuilder();
    private boolean bracketedPaste;
    private boolean carriageReturnSubmitted;

    synchronized boolean accept(byte[] bytes) {
        if (bytes == null || bytes.length == 0) {
            return false;
        }
        boolean changed = false;
        for (byte value : bytes) {
            int unsigned = value & 0xff;
            char ch = (char) unsigned;

            if (escapeSequence.length() > 0 || ch == '\u001b') {
                consumeEscapeCharacter(ch);
                carriageReturnSubmitted = false;
                continue;
            }
            if (bracketedPaste) {
                // Bracketed paste keeps embedded newlines in the editable input buffer; it does not
                // submit them individually. Preserve a separator so all pasted command segments are
                // still inspected when the user finally presses Enter.
                if (ch == '\r' || ch == '\n') {
                    inputLine.append('\n');
                } else if (unsigned >= 0x20 && unsigned != 0x7f) {
                    inputLine.append(ch);
                }
                trimLine();
                carriageReturnSubmitted = false;
                continue;
            }
            if (ch == '\r' || ch == '\n') {
                if (!(ch == '\n' && carriageReturnSubmitted)) {
                    changed |= mayChangeWorkingDirectory(inputLine.toString());
                    inputLine.setLength(0);
                }
                carriageReturnSubmitted = ch == '\r';
            } else if (ch == '\b' || unsigned == 0x7f) {
                if (inputLine.length() > 0) {
                    inputLine.deleteCharAt(inputLine.length() - 1);
                }
                carriageReturnSubmitted = false;
            } else if (unsigned == 0x15) { // Ctrl+U: readline/cmd clear-line
                inputLine.setLength(0);
                carriageReturnSubmitted = false;
            } else if (unsigned >= 0x20) {
                // Directory-changing command names are ASCII. Keeping non-ASCII UTF-8 bytes as
                // opaque characters preserves boundaries even when a code point spans write calls.
                inputLine.append(ch);
                trimLine();
                carriageReturnSubmitted = false;
            }
        }
        return changed;
    }

    synchronized void reset() {
        inputLine.setLength(0);
        escapeSequence.setLength(0);
        bracketedPaste = false;
        carriageReturnSubmitted = false;
    }

    private void consumeEscapeCharacter(char ch) {
        if (escapeSequence.length() == 0) {
            escapeSequence.append(ch);
            return;
        }
        escapeSequence.append(ch);
        String sequence = escapeSequence.toString();
        if (BRACKETED_PASTE_START.equals(sequence)) {
            bracketedPaste = true;
            escapeSequence.setLength(0);
            return;
        }
        if (BRACKETED_PASTE_END.equals(sequence)) {
            bracketedPaste = false;
            escapeSequence.setLength(0);
            return;
        }
        if (isCompleteEscapeSequence(sequence) || escapeSequence.length() >= 32) {
            escapeSequence.setLength(0);
        }
    }

    private static boolean isCompleteEscapeSequence(String sequence) {
        if (sequence.length() < 2) {
            return false;
        }
        if (sequence.charAt(1) != '[') {
            return sequence.length() >= 2;
        }
        if (sequence.length() < 3) {
            return false;
        }
        char last = sequence.charAt(sequence.length() - 1);
        return last >= '@' && last <= '~';
    }

    private void trimLine() {
        if (inputLine.length() > MAX_LINE_LENGTH) {
            inputLine.delete(0, inputLine.length() - MAX_LINE_LENGTH);
        }
    }

    static boolean mayChangeWorkingDirectory(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        StringBuilder segment = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (int i = 0; i <= line.length(); i++) {
            char ch = i < line.length() ? line.charAt(i) : ';';
            if (escaped) {
                segment.append(ch);
                escaped = false;
                continue;
            }
            if (ch == '\\' && !inSingleQuote) {
                segment.append(ch);
                escaped = true;
                continue;
            }
            if (ch == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                segment.append(ch);
                continue;
            }
            if (ch == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                segment.append(ch);
                continue;
            }
            boolean separator = !inSingleQuote && !inDoubleQuote
                && (ch == ';' || ch == '|' || ch == '&' || ch == '\r' || ch == '\n');
            if (separator) {
                if (segmentMayChangeWorkingDirectory(segment.toString())) {
                    return true;
                }
                segment.setLength(0);
            } else {
                segment.append(ch);
            }
        }
        return false;
    }

    private static boolean segmentMayChangeWorkingDirectory(String rawSegment) {
        String segment = rawSegment == null ? "" : rawSegment.trim();
        if (segment.isEmpty()) {
            return false;
        }
        if (segment.matches("(?i)^[a-z]:$")) {
            return true; // native cmd.exe drive switch, e.g. D:
        }

        String normalized = segment.toLowerCase(Locale.ROOT);
        if (normalized.startsWith("builtin ")) {
            normalized = normalized.substring("builtin ".length()).stripLeading();
        } else if (normalized.startsWith("command ")) {
            normalized = normalized.substring("command ".length()).stripLeading();
        }
        int end = 0;
        while (end < normalized.length() && !Character.isWhitespace(normalized.charAt(end))) {
            end++;
        }
        String command = normalized.substring(0, end);
        return command.equals("cd")
            || command.startsWith("cd.") // cmd.exe accepts cd.. and cd\path without whitespace
            || command.startsWith("cd\\")
            || command.startsWith("cd/")
            || command.equals("chdir")
            || command.equals("pushd")
            || command.equals("popd")
            || command.equals("push-location")
            || command.equals("pop-location")
            || command.equals("set-location")
            || command.equals("sl");
    }
}
