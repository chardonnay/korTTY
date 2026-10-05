package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes failed attempts to start a command or script the shell could not find — almost always
 * a typo — from a summary window before it reaches the AI: the typed line, its echo after the
 * prompt, the shell's "command not found" message and any "did you mean" suggestions after it.
 * A window that held nothing but such attempts is then idle and gets no journal entry.
 *
 * <p>Only the shell's own messages count ({@code bash: x: command not found}, zsh, fish, dash,
 * sudo, Debian/Ubuntu's command-not-found handler), in English and in the languages korTTY ships.
 * A program that runs and then fails to find a file ({@code cat: x: No such file or directory})
 * is a real result and stays.</p>
 */
final class SessionJournalTypoFilter {

    /** How far back the typed line and its echo are searched for, in log entries. */
    private static final int LOOKBACK = 6;

    private static final String NOT_FOUND =
        "(?:command not found|Befehl nicht gefunden|Kommando nicht gefunden|commande introuvable"
            + "|orden no encontrada|comando no encontrado|comando non trovato|comando não encontrado"
            + "|opdracht niet gevonden|naredba nije pronađena|not found"
            + "|No such file or directory|Datei oder Verzeichnis nicht gefunden"
            + "|Aucun fichier ou dossier de ce type|No existe el archivo o el directorio"
            + "|File o directory non esistente|Arquivo ou diretório inexistente"
            + "|Bestand of map bestaat niet)";

    private static final List<Pattern> PATTERNS = List.of(
        // bash, ksh, dash and sudo: "bash: lnks: command not found", "sh: 1: lnks: not found",
        // "bash: Zeile 3: ./x.sh: Datei oder Verzeichnis nicht gefunden"
        Pattern.compile("^\\s*-?(?:(?:ba|z|k|da)?sh|sudo):\\s+(?:(?:line|Zeile|ligne|línea|riga|linha|regel|redak)\\s+)?"
            + "(?:\\d+:\\s+)?(\\S+):\\s+" + NOT_FOUND + "[.…]*\\s*$", Pattern.CASE_INSENSITIVE),
        // zsh: "zsh: command not found: lnks", "zsh: no such file or directory: ./x.sh"
        Pattern.compile("^\\s*zsh:\\s+(?:command not found|no such file or directory|Befehl nicht gefunden"
            + "|Datei oder Verzeichnis nicht gefunden):\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE),
        // fish
        Pattern.compile("^\\s*fish:\\s+Unknown command:?\\s+(\\S+)\\s*$", Pattern.CASE_INSENSITIVE),
        // Debian/Ubuntu command-not-found: "Command 'lnks' not found, did you mean:"
        Pattern.compile("^\\s*Command '([^']+)' not found.*$", Pattern.CASE_INSENSITIVE),
        Pattern.compile("^\\s*Befehl »([^«]+)« nicht gefunden.*$"));

    private SessionJournalTypoFilter() {
    }

    /** The command a shell "not found" message names, or {@code null} for any other line. */
    static String notFoundCommand(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        for (Pattern pattern : PATTERNS) {
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                return matcher.group(1);
            }
        }
        return null;
    }

    /** {@code entries} without the failed start attempts; the input list is not modified. */
    static List<SessionJournalLogEntry> withoutNotFoundAttempts(List<SessionJournalLogEntry> entries) {
        boolean[] drop = new boolean[entries.size()];
        boolean any = false;
        for (int i = 0; i < entries.size(); i++) {
            SessionJournalLogEntry entry = entries.get(i);
            if (!isOutput(entry)) {
                continue;
            }
            String command = notFoundCommand(entry.text());
            if (command == null) {
                continue;
            }
            if (!markTypedLineAndEcho(entries, i, command, drop)) {
                continue; // nothing shows the command was started, e.g. a failed redirect
            }
            any = true;
            drop[i] = true;
            // the handler's suggestions ("Similar command: 'ln'", "Try: sudo apt install …")
            // run until the next prompt or the next typed line
            for (int j = i + 1; j < entries.size(); j++) {
                SessionJournalLogEntry next = entries.get(j);
                if (!isOutput(next) || SessionJournalShellPrompts.promptUser(next.text()) != null
                        || SessionJournalShellPrompts.isIdleLine(next.text())
                        || notFoundCommand(next.text()) != null) {
                    break;
                }
                drop[j] = true;
            }
        }
        if (!any) {
            return entries;
        }
        List<SessionJournalLogEntry> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            if (!drop[i]) {
                result.add(entries.get(i));
            }
        }
        return result;
    }

    /**
     * Marks the typed line and the prompt echo that started {@code command}; false (and nothing
     * marked) when neither is found, so a message without a visible start attempt stays.
     */
    private static boolean markTypedLineAndEcho(
            List<SessionJournalLogEntry> entries, int errorIndex, String command, boolean[] drop) {
        int input = -1;
        int echo = -1;
        for (int j = errorIndex - 1; j >= 0 && j >= errorIndex - LOOKBACK && (input < 0 || echo < 0); j--) {
            if (drop[j]) {
                continue;
            }
            SessionJournalLogEntry entry = entries.get(j);
            if (input < 0 && entry.kind() == SessionJournalLogEntry.Kind.IN
                    && !entry.redacted() && startsCommand(entry.text(), command)) {
                input = j;
            } else if (echo < 0 && isOutput(entry) && SessionJournalShellPrompts.promptUser(entry.text()) != null
                    && startsCommand(afterPrompt(entry.text()), command)) {
                echo = j;
            }
        }
        if (input >= 0) {
            drop[input] = true;
        }
        if (echo >= 0) {
            drop[echo] = true;
        }
        return input >= 0 || echo >= 0;
    }

    /** What was typed after the prompt in an echoed line: everything after the first $ # or % token end. */
    private static String afterPrompt(String line) {
        java.util.regex.Matcher matcher = PROMPT_END.matcher(line);
        return matcher.find() ? line.substring(matcher.end()) : "";
    }

    private static final Pattern PROMPT_END = Pattern.compile("[$#%]\\s+");

    /** True when the typed line runs {@code command}, directly or through sudo. */
    private static boolean startsCommand(String line, String command) {
        if (line == null) {
            return false;
        }
        String[] words = line.strip().split("\\s+");
        int index = 0;
        while (index < words.length - 1 && (words[index].equals("sudo") || words[index].startsWith("-")
                || words[index].contains("="))) {
            index++; // "sudo -E VAR=1 cmd"
        }
        return index < words.length && words[index].equals(command);
    }

    private static boolean isOutput(SessionJournalLogEntry entry) {
        return entry.kind() == SessionJournalLogEntry.Kind.OUT || entry.kind() == SessionJournalLogEntry.Kind.SEED;
    }
}
