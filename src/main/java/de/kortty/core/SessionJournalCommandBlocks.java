package de.kortty.core;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Cuts a stretch of the capture log into commands, so a summary never splits one: a command is a
 * line typed at a shell prompt with everything the terminal printed until the next such line. A
 * line typed while a program runs — {@code y} for "Is this ok [y/N]:", a sudo password — is a
 * reply and stays in the command it answers.
 *
 * <p>Where the shell reports its commands itself (shell integration, {@code OSC 133;D}), the log
 * holds a {@linkplain #finishedNote finished note} after each one, with the exit status. Elsewhere
 * the prompts decide: the line the user typed at and the echo of the typed line after it. A prompt
 * is one of the {@code user@host} shapes {@link SessionJournalShellPrompts} knows, a prompt seen
 * before an earlier command of the same stretch, or a short line ending like a prompt
 * ({@code $ # % > ❯}) that the user typed at.</p>
 *
 * <p>The log writes the typed line before its echo, and the prompt the user typed at as partial
 * lines before both: {@code "daniel@fedora:~$ ya"} (partial), {@code in "yast upgrade"},
 * {@code "daniel@fedora:~$ yast upgrade"}.</p>
 */
final class SessionJournalCommandBlocks {

    /** The note the journal writes when the shell reports a finished command. */
    static final String FINISHED_NOTE = "shell integration: command finished";
    private static final Pattern FINISHED = Pattern.compile(
        Pattern.quote(FINISHED_NOTE) + "(?: \\(exit (-?\\d{1,9})\\))?");
    /** What a prompt typically ends with. */
    private static final String PROMPT_ENDINGS = "$#%>❯➜»λ";
    private static final int MAX_PROMPT_CHARS = 160;
    /** How many entries after a typed line its echo is looked for. */
    private static final int ECHO_LOOKAHEAD = 3;

    /**
     * One command, or the output before the first one.
     *
     * @param entries    its log entries in order, notes and partial lines included; never empty
     * @param command    the typed command line, or {@code null} for output before the first command
     * @param complete   the shell is back at a prompt (or the next command started)
     * @param exitStatus the status the shell reported, or {@code null}
     */
    record Block(List<SessionJournalLogEntry> entries, String command, boolean complete, Integer exitStatus) {
        Block {
            entries = List.copyOf(entries);
        }

        long startSeq() {
            return entries.get(0).seq();
        }

        long endSeq() {
            return entries.get(entries.size() - 1).seq();
        }

        OffsetDateTime startTime() {
            return entries.get(0).timestamp();
        }
    }

    private SessionJournalCommandBlocks() {
    }

    /** The text of the note that marks a command the shell reported finished. */
    static String finishedNote(Integer exitStatus) {
        return exitStatus != null ? FINISHED_NOTE + " (exit " + exitStatus + ")" : FINISHED_NOTE;
    }

    /** True for a note {@link #finishedNote} wrote. */
    static boolean isFinishedNote(SessionJournalLogEntry entry) {
        return entry.kind() == SessionJournalLogEntry.Kind.NOTE && entry.text() != null
            && FINISHED.matcher(entry.text()).matches();
    }

    /** The exit status a finished note carries, or {@code null}. */
    static Integer exitStatusOf(SessionJournalLogEntry entry) {
        if (entry.text() == null) {
            return null;
        }
        Matcher matcher = FINISHED.matcher(entry.text());
        return matcher.matches() && matcher.group(1) != null ? Integer.valueOf(matcher.group(1)) : null;
    }

    /** The commands in {@code entries}, in order; only the last one can be incomplete. */
    static List<Block> cut(List<SessionJournalLogEntry> entries) {
        List<Block> blocks = new ArrayList<>();
        Set<String> prompts = new LinkedHashSet<>();
        List<SessionJournalLogEntry> current = new ArrayList<>();
        String command = null;
        Integer exitStatus = null;
        boolean shellFinished = false;
        long echoSeq = -1;
        SessionJournalLogEntry lastOutput = null;
        for (int i = 0; i < entries.size(); i++) {
            SessionJournalLogEntry entry = entries.get(i);
            if (isFinishedNote(entry)) {
                shellFinished = true;
                exitStatus = exitStatusOf(entry);
                current.add(entry);
                continue;
            }
            if (entry.kind() == SessionJournalLogEntry.Kind.IN) {
                SessionJournalLogEntry echo = echoOf(entries, i);
                String prompt = commandPrompt(entry, lastOutput, echo, shellFinished, prompts);
                if (prompt != null) {
                    if (!prompt.isEmpty() && prompt.length() <= MAX_PROMPT_CHARS) {
                        prompts.add(prompt);
                    }
                    List<SessionJournalLogEntry> carried = new ArrayList<>();
                    if (command == null && onlyPromptLines(current)) {
                        carried.addAll(current); // the prompt the first command was typed at
                    } else if (!current.isEmpty()) {
                        blocks.add(new Block(current, command, true, exitStatus));
                    }
                    current = carried;
                    command = entry.text().strip();
                    exitStatus = null;
                    shellFinished = false;
                    echoSeq = echo != null ? echo.seq() : -1;
                }
                current.add(entry);
                continue;
            }
            if (entry.kind() == SessionJournalLogEntry.Kind.OUT || entry.kind() == SessionJournalLogEntry.Kind.SEED) {
                lastOutput = entry;
            }
            current.add(entry);
        }
        if (!current.isEmpty()) {
            blocks.add(new Block(current, command, shellFinished || endsAtPrompt(current, echoSeq, prompts),
                exitStatus));
        }
        return blocks;
    }

    /**
     * The prompt {@code typed} was entered at — empty when the shell reported it waits, or nothing
     * printed before — or {@code null} when it is a reply to a running program.
     */
    private static String commandPrompt(SessionJournalLogEntry typed, SessionJournalLogEntry before,
                                        SessionJournalLogEntry echo, boolean shellFinished, Set<String> prompts) {
        String text = typed.text();
        if (typed.redacted() || text == null || text.isBlank()) {
            return null; // hidden input answers a password prompt
        }
        String fromBefore = before != null ? promptOf(before, text, prompts) : null;
        if (fromBefore != null) {
            return fromBefore;
        }
        String fromEcho = echo != null ? promptOf(echo, text, prompts) : null;
        if (fromEcho != null) {
            return fromEcho;
        }
        if (shellFinished || before == null) {
            return before != null ? withoutTyped(before.text(), text).strip() : "";
        }
        return null;
    }

    /** The prompt part of {@code line} when it is a prompt the user typed {@code typed} at. */
    private static String promptOf(SessionJournalLogEntry line, String typed, Set<String> prompts) {
        String text = line.text() != null ? line.text().stripTrailing() : "";
        String rest = withoutTyped(text, typed);
        boolean typedAtIt = line.partial() || rest.length() < text.length();
        String prompt = rest.strip();
        return isPrompt(prompt, typedAtIt, prompts) ? prompt : null;
    }

    /** {@code line} without the typed text (or the part of it typed so far) at its end. */
    private static String withoutTyped(String line, String typed) {
        String text = line != null ? line.stripTrailing() : "";
        String command = typed.strip();
        for (int length = Math.min(command.length(), text.length()); length > 0; length--) {
            if (text.endsWith(command.substring(0, length))) {
                return text.substring(0, text.length() - length);
            }
        }
        return text;
    }

    private static boolean isPrompt(String text, boolean typedAtIt, Set<String> prompts) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String prompt = text.strip();
        if (SessionJournalShellPrompts.isIdleLine(prompt) || SessionJournalShellPrompts.promptUser(prompt) != null) {
            return true;
        }
        for (String known : prompts) {
            if (prompt.equals(known) || prompt.startsWith(known + " ")) {
                return true;
            }
        }
        return typedAtIt && prompt.length() <= MAX_PROMPT_CHARS && endsLikePrompt(prompt);
    }

    /**
     * Ends in a prompt character that is not part of a number or a run: {@code ~/src ❯} and
     * {@code mysql>} do, a progress line at {@code 45%} or {@code ####} does not.
     */
    private static boolean endsLikePrompt(String text) {
        char last = text.charAt(text.length() - 1);
        if (PROMPT_ENDINGS.indexOf(last) < 0) {
            return false;
        }
        if (text.length() == 1) {
            return true;
        }
        char before = text.charAt(text.length() - 2);
        return before != last && !Character.isDigit(before);
    }

    /** The first output line after the typed line at {@code index}, unless another line was typed first. */
    private static SessionJournalLogEntry echoOf(List<SessionJournalLogEntry> entries, int index) {
        for (int i = index + 1; i < entries.size() && i <= index + ECHO_LOOKAHEAD; i++) {
            SessionJournalLogEntry entry = entries.get(i);
            if (entry.kind() == SessionJournalLogEntry.Kind.IN) {
                return null;
            }
            if (entry.kind() == SessionJournalLogEntry.Kind.OUT && !entry.partial()) {
                return entry;
            }
        }
        return null;
    }

    /** True when {@code entries} hold nothing but partial lines, notes and blank output. */
    private static boolean onlyPromptLines(List<SessionJournalLogEntry> entries) {
        for (SessionJournalLogEntry entry : entries) {
            boolean output = entry.kind() == SessionJournalLogEntry.Kind.OUT
                || entry.kind() == SessionJournalLogEntry.Kind.SEED;
            boolean blank = entry.text() == null || entry.text().isBlank();
            if (entry.kind() == SessionJournalLogEntry.Kind.NOTE || output && (entry.partial() || blank)) {
                continue;
            }
            return false;
        }
        return true;
    }

    /**
     * True when a prompt appeared after the command (its echo aside): the shell waited again. What
     * follows such a prompt without a new typed line is not the command's — korTTY's own messages
     * and AI-agent replies are written to the terminal while the shell waits, as is the output of
     * a background job.
     */
    private static boolean endsAtPrompt(List<SessionJournalLogEntry> entries, long echoSeq, Set<String> prompts) {
        boolean afterCommand = entries.stream().noneMatch(entry -> entry.kind() == SessionJournalLogEntry.Kind.IN);
        for (SessionJournalLogEntry entry : entries) {
            if (entry.kind() == SessionJournalLogEntry.Kind.IN) {
                afterCommand = true;
                continue;
            }
            boolean output = entry.kind() == SessionJournalLogEntry.Kind.OUT
                || entry.kind() == SessionJournalLogEntry.Kind.SEED;
            if (afterCommand && output && entry.seq() != echoSeq && entry.text() != null && !entry.text().isBlank()
                    && isPrompt(entry.text(), entry.partial(), prompts)) {
                return true;
            }
        }
        return false;
    }
}
