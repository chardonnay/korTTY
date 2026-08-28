package de.kortty.core;

import java.util.Locale;

/**
 * Tracks submitted shell input lines and maintains a conservative suspicion that the interactive
 * session no longer belongs to the original login user or host — e.g. after {@code su - root},
 * {@code ssh otherhost} or a shell-opening {@code sudo}. While the suspicion depth is positive,
 * features that resolve file paths against the tab's original identity (such as "open selection in
 * the snippet editor") must be disabled, because both the tracked working directory and the
 * SFTP/local read path would target the wrong account.
 *
 * <p>The depth is a heuristic, not a shell emulation: it increments on session-changing commands,
 * decrements on {@code exit}/{@code logout} and Ctrl-D on an empty input line, and is reset to
 * zero whenever the visible prompt positively confirms the native identity again (see
 * {@link #confirmNativeIdentity()}). That healing path recovers from false increments such as a
 * {@code su} attempt with a wrong password.</p>
 *
 * <p>Plain subshells ({@code bash}, {@code zsh}) are deliberately not tracked — they keep the same
 * user and host, so tracked paths stay valid. Container entries ({@code docker exec},
 * {@code kubectl exec}) are also out of scope; their prompts usually lose the native
 * {@code user@host} shape, so the prompt-side heuristic still withholds a false confirmation.</p>
 */
final class ShellSessionChangeTracker {

    private int suspicionDepth;

    synchronized void onSubmittedLine(String line) {
        if (line == null || line.isBlank()) {
            return;
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
                applySegment(segment.toString());
                segment.setLength(0);
            } else {
                segment.append(ch);
            }
        }
    }

    synchronized void onEndOfFileOnEmptyLine() {
        if (suspicionDepth > 0) {
            suspicionDepth--;
        }
    }

    synchronized boolean isForeignSessionSuspected() {
        return suspicionDepth > 0;
    }

    /** The visible prompt showed the native {@code user@host} identity again. */
    synchronized void confirmNativeIdentity() {
        suspicionDepth = 0;
    }

    synchronized void reset() {
        suspicionDepth = 0;
    }

    private void applySegment(String segment) {
        if (segmentStartsSessionChange(segment)) {
            suspicionDepth++;
        } else if (segmentEndsSession(segment) && suspicionDepth > 0) {
            suspicionDepth--;
        }
    }

    static boolean segmentStartsSessionChange(String rawSegment) {
        String[] words = splitWords(rawSegment);
        if (words.length == 0) {
            return false;
        }
        String command = commandName(words[0]);
        return switch (command) {
            case "su", "ssh", "slogin", "sshpass", "autossh", "mosh", "telnet", "rlogin" -> true;
            case "sudo" -> sudoOpensShell(words);
            default -> false;
        };
    }

    static boolean segmentEndsSession(String rawSegment) {
        String[] words = splitWords(rawSegment);
        if (words.length == 0) {
            return false;
        }
        String command = commandName(words[0]);
        return command.equals("exit") || command.equals("logout");
    }

    /**
     * {@code sudo} changes the session only when it opens a shell: {@code -i}/{@code -s}, or the
     * invoked command is {@code su} or a shell. One-shot commands like {@code sudo systemctl
     * restart x} return immediately and must not raise suspicion.
     */
    private static boolean sudoOpensShell(String[] words) {
        for (int i = 1; i < words.length; i++) {
            String word = words[i];
            if (word.equals("-i") || word.equals("-s") || word.equals("--login")
                || word.equals("--shell")) {
                return true;
            }
            if (word.startsWith("-")) {
                if (word.equals("-u") || word.equals("--user") || word.equals("-g")
                    || word.equals("--group") || word.equals("-p") || word.equals("--prompt")) {
                    i++; // option consumes the next word as its argument
                }
                continue;
            }
            String command = commandName(word);
            return command.equals("su") || command.equals("bash") || command.equals("sh")
                || command.equals("zsh") || command.equals("ksh") || command.equals("dash")
                || command.equals("fish");
        }
        return false;
    }

    private static String[] splitWords(String rawSegment) {
        String segment = rawSegment == null ? "" : rawSegment.trim();
        if (segment.isEmpty()) {
            return new String[0];
        }
        String normalized = segment.toLowerCase(Locale.ROOT);
        for (String prefix : new String[] {"builtin ", "command ", "exec "}) {
            if (normalized.startsWith(prefix)) {
                normalized = normalized.substring(prefix.length()).stripLeading();
                break;
            }
        }
        if (normalized.isEmpty()) {
            return new String[0];
        }
        return normalized.split("\\s+");
    }

    /** Basename of the first word with a Windows {@code .exe} suffix stripped. */
    private static String commandName(String word) {
        String name = word;
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        if (name.endsWith(".exe")) {
            name = name.substring(0, name.length() - ".exe".length());
        }
        return name;
    }
}
