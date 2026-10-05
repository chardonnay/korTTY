package de.kortty.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises shell prompts in captured terminal output, for two journal decisions that must not
 * depend on the AI: whether a window holds any activity at all (a bare prompt is not activity),
 * and which user the commands ran as (a {@code [root@host ~]#} prompt after {@code sudo -i}).
 *
 * <p>Only the common {@code user@host} prompt shapes are recognised — bash/zsh defaults on
 * Debian, Fedora and macOS — plus the {@code bash-5.2#} fallback prompt. A path containing spaces
 * is not matched; that costs a missed detection, never a wrong one.</p>
 */
final class SessionJournalShellPrompts {

    private static final String USER_AT_HOST =
        "\\[?([a-z_][a-z0-9_.-]{0,31})@[A-Za-z0-9_.-]+(?:[: ][^\\s\\]$#%]*)?\\]?\\s?([$#%])";
    private static final String BARE_SHELL = "(?:ba|z|k|da)?sh-[0-9.]+([$#])";
    /** Optional virtualenv/conda prefix like {@code (venv) }. */
    private static final String ENV_PREFIX = "(?:\\([^)]{1,40}\\)\\s*)?";

    /** A prompt with nothing typed after it. */
    private static final Pattern BARE_PROMPT = Pattern.compile(
        "^\\s*" + ENV_PREFIX + "(?:" + USER_AT_HOST + "|" + BARE_SHELL + "|[$#%>❯])\\s*$");
    /** A prompt at the start of a line, possibly followed by the echoed command. */
    private static final Pattern USER_PROMPT = Pattern.compile(
        "^\\s*" + ENV_PREFIX + USER_AT_HOST + "(?:\\s.*)?$");
    private static final Pattern ROOT_SHELL_PROMPT = Pattern.compile(
        "^\\s*" + ENV_PREFIX + BARE_SHELL + "(?:\\s.*)?$");

    private SessionJournalShellPrompts() {
    }

    /** The " (×3)" suffix the summarizer appends to a coalesced run of identical lines. */
    private static final Pattern REPEAT_SUFFIX = Pattern.compile("\\s\\(×\\d+\\)$");

    /** True for a line that is only a shell prompt (or blank) — no command, no output. */
    static boolean isIdleLine(String line) {
        if (line == null || line.isBlank()) {
            return true;
        }
        return BARE_PROMPT.matcher(REPEAT_SUFFIX.matcher(line).replaceFirst("")).matches();
    }

    /**
     * The user a prompt line belongs to, or {@code null} when the line is no recognised prompt.
     * A {@code bash-5.2#} prompt counts as root.
     */
    static String promptUser(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        Matcher matcher = USER_PROMPT.matcher(line);
        if (matcher.matches()) {
            return matcher.group(1);
        }
        Matcher bare = ROOT_SHELL_PROMPT.matcher(line);
        if (bare.matches() && "#".equals(bare.group(1))) {
            return "root";
        }
        return null;
    }

    /**
     * The user other than {@code loginUser} whose prompt appears in {@code outputLines}; root wins
     * over any other user, because a root shell is what the reader must never miss. Null when only
     * the login user's prompts (or none) appear.
     */
    static String switchedUser(List<String> outputLines, String loginUser) {
        if (outputLines == null) {
            return null;
        }
        String login = loginUser != null ? loginUser.strip().toLowerCase(Locale.ROOT) : "";
        String other = null;
        for (String line : outputLines) {
            String user = promptUser(line);
            if (user == null || user.equals(login)) {
                continue;
            }
            if ("root".equals(user)) {
                return "root";
            }
            if (other == null) {
                other = user;
            }
        }
        return other;
    }

    /** True for something that can be a POSIX login name; filters model prose out of {@code runAs}. */
    static boolean isPlausibleUserName(String value) {
        return value != null && value.matches("[a-z_][a-z0-9_.-]{0,31}");
    }
}
