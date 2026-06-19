package de.kortty.core;

<<<<<<< HEAD
import java.util.List;
=======
import de.kortty.model.TerminalAgentInputHistoryEntry;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525

/**
 * Pure helpers for terminal AI-agent TAB completion: decide whether a TAB at the prompt should offer
 * the agent command variants or the prompt history, list the variants, compute a completion suffix,
 * and extract the prompt from a typed agent command. UI-free so it is unit-testable.
 */
public final class TerminalAgentCompletionSupport {

    /** What a TAB press should offer for the current input. */
    public enum TabContext {
        /** Still typing the command name itself (no space yet) → offer the command variants. */
        COMMAND,
        /** Command name fully typed and followed by a space → offer the prompt history. */
        HISTORY,
        /** Not an agent-command context → leave TAB to the shell. */
        NONE
    }

    private TerminalAgentCompletionSupport() {
    }

    /** The selectable agent command variants for the configured base name. */
    public static List<String> commandOptions(String commandName) {
        String name = TerminalAgentCommandSupport.normalizeCommandName(commandName);
        return List.of(
            name,
            TerminalAgentCommandSupport.getAskCommandName(name),
            TerminalAgentCommandSupport.getPlanCommandName(name));
    }

    public static TabContext classify(String rawBuffer, String commandName, boolean caseInsensitive) {
        if (rawBuffer == null) {
            return TabContext.NONE;
        }
        String text = rawBuffer.stripLeading();
        if (text.isEmpty()) {
            return TabContext.NONE;
        }
        List<String> options = commandOptions(commandName);
        int space = firstWhitespace(text);
        if (space < 0) {
            // No whitespace at all → user is still typing the command name.
            return isPrefixOfAnyOption(text, options, caseInsensitive) ? TabContext.COMMAND : TabContext.NONE;
        }
        // There is whitespace: the first token must be a full command variant for the history context.
        String firstToken = text.substring(0, space);
        return isCommandVariant(firstToken, options, caseInsensitive) ? TabContext.HISTORY : TabContext.NONE;
    }

    /** The characters still missing to complete {@code typed} into {@code chosen}. */
    public static String completionSuffix(String typed, String chosen) {
        String typedText = typed != null ? typed.strip() : "";
        String chosenText = chosen != null ? chosen : "";
        if (chosenText.startsWith(typedText)) {
            return chosenText.substring(typedText.length());
        }
        return chosenText;
    }

    /** The prompt part of a typed agent command (e.g. "agent-ask show files" → "show files"), or "". */
    public static String promptFromRaw(String rawCommand, String commandName, boolean caseInsensitive) {
        TerminalAgentCommandSupport.Invocation invocation =
            TerminalAgentCommandSupport.parseShortcut(rawCommand, commandName, caseInsensitive);
        if (invocation == null || invocation.userPrompt() == null) {
            return "";
        }
        return invocation.userPrompt().trim();
    }

    /**
     * A trailing shell "command not found" fragment that leaked into a captured prompt, e.g.
     * {@code "... bash: agent: Befehl nicht gefunden"} / {@code "... zsh: foo: command not found"}.
     * Matches the {@code <shell>: <token>: <message>} structure ONLY when the message is a recognised
     * localized "not found" error, so ordinary prompts that merely contain a colon are not clipped.
     */
    private static final java.util.regex.Pattern TRAILING_SHELL_ERROR = java.util.regex.Pattern.compile(
        "(?i)\\s+(?:bash|zsh|sh|dash|fish|ksh|tcsh|csh):\\s+\\S+:\\s+.*"
            + "(?:command not found|not found|nicht gefunden|introuvable|encontrad"
            + "|non trovat|niet gevonden|prona[\\u0111d]en).*$");

    /**
     * Cleans a prompt before it is stored in / displayed from the agent input history. Strips a
     * trailing shell "command not found" fragment and rejects entries that are clearly shell
     * tab-completion listings (mostly path/dotfile tokens). Returns the cleaned prompt, or
     * {@code null} when the entry should be dropped entirely.
     */
    public static String sanitizeHistoryPrompt(String prompt) {
        if (prompt == null) {
            return null;
        }
        String cleaned = prompt.strip();
        if (cleaned.isEmpty()) {
            return null;
        }
        cleaned = TRAILING_SHELL_ERROR.matcher(cleaned).replaceAll("").strip();
        if (cleaned.isEmpty() || looksLikeCompletionListing(cleaned)) {
            return null;
        }
        return cleaned;
    }

    /**
     * True when the text looks like a captured shell tab-completion listing (e.g.
     * {@code ".ansible/ .bashrc .config/ .npm/ Scripts/ .viminfo"}) rather than a natural-language
     * task: at least five tokens and at least 80% of them are directory ({@code foo/}) or dotfile
     * ({@code .foo}) entries. Deliberately strict so ordinary prompts that merely reference a few
     * dotfiles (e.g. {@code "compare .bashrc .zshrc .profile .vimrc"}) are never dropped.
     */
    static boolean looksLikeCompletionListing(String text) {
        if (text == null) {
            return false;
        }
        String[] tokens = text.trim().split("\\s+");
        if (tokens.length < 5) {
            return false;
        }
        int pathLike = 0;
        for (String token : tokens) {
            if (token.endsWith("/") || (token.length() > 1 && token.charAt(0) == '.')) {
                pathLike++;
            }
        }
        return pathLike >= 5 && pathLike * 10 >= tokens.length * 8;
    }

<<<<<<< HEAD
=======
    /**
     * Cleans up the stored agent input history: sanitizes each prompt (dropping shell noise / blanks)
     * and collapses entries that are identical except for whitespace into one, keeping the variant with
     * the fewest whitespace characters (the least-corrupted) and the newest timestamp. This removes
     * terminal line-wrap corruption (e.g. "nur" stored as "nu r") when a clean copy of the same prompt
     * exists, and is self-healing: re-running a command cleanly creates a clean entry that absorbs any
     * corrupted twin on the next load. Newest-first order of first appearance is preserved.
     */
    public static List<TerminalAgentInputHistoryEntry> dedupHistoryEntries(
        List<TerminalAgentInputHistoryEntry> stored) {
        List<TerminalAgentInputHistoryEntry> result = new ArrayList<>();
        if (stored == null) {
            return result;
        }
        Map<String, Integer> indexByKey = new HashMap<>();
        for (TerminalAgentInputHistoryEntry entry : stored) {
            if (entry == null || entry.getPrompt() == null) {
                continue;
            }
            String clean = sanitizeHistoryPrompt(entry.getPrompt());
            if (clean == null || clean.isBlank()) {
                continue;
            }
            String key = clean.replaceAll("\\s+", "");
            if (key.isEmpty()) {
                continue;
            }
            long timestamp = entry.getLastUsedEpochMillis();
            Integer existingIndex = indexByKey.get(key);
            if (existingIndex == null) {
                indexByKey.put(key, result.size());
                result.add(new TerminalAgentInputHistoryEntry(clean, timestamp));
            } else {
                TerminalAgentInputHistoryEntry existing = result.get(existingIndex);
                String bestText = countWhitespace(clean) < countWhitespace(existing.getPrompt())
                    ? clean : existing.getPrompt();
                long bestTimestamp = Math.max(timestamp, existing.getLastUsedEpochMillis());
                result.set(existingIndex, new TerminalAgentInputHistoryEntry(bestText, bestTimestamp));
            }
        }
        return result;
    }

    /** True when two history lists are identical in size, prompt text, and timestamps (in order). */
    public static boolean sameHistoryEntries(
        List<TerminalAgentInputHistoryEntry> a, List<TerminalAgentInputHistoryEntry> b) {
        if (a == null || b == null || a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            TerminalAgentInputHistoryEntry x = a.get(i);
            TerminalAgentInputHistoryEntry y = b.get(i);
            if (x == null || y == null) {
                return false;
            }
            if (!java.util.Objects.equals(x.getPrompt(), y.getPrompt())
                || x.getLastUsedEpochMillis() != y.getLastUsedEpochMillis()) {
                return false;
            }
        }
        return true;
    }

    private static int countWhitespace(String value) {
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                count++;
            }
        }
        return count;
    }

>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
    private static int firstWhitespace(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isWhitespace(value.charAt(i))) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPrefixOfAnyOption(String token, List<String> options, boolean caseInsensitive) {
        if (token.isEmpty()) {
            return false;
        }
        for (String option : options) {
            boolean prefix = caseInsensitive
                ? option.toLowerCase(java.util.Locale.ROOT).startsWith(token.toLowerCase(java.util.Locale.ROOT))
                : option.startsWith(token);
            if (prefix) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCommandVariant(String token, List<String> options, boolean caseInsensitive) {
        for (String option : options) {
            boolean equal = caseInsensitive ? option.equalsIgnoreCase(token) : option.equals(token);
            if (equal) {
                return true;
            }
        }
        return false;
    }
}
