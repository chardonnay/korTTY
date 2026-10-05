package de.kortty.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Keeps AI journal text to facts the session actually shows. Every URL, host or file name and
 * every backticked command in a summary must occur in the evidence — the capture log, the
 * journal entries and the screenshot analyses; a statement naming one that does not is dropped,
 * whole. A model that fills a gap with something plausible (a well-known URL for {@code links})
 * then loses that statement instead of putting fiction into the journal.
 *
 * <p>Statements are bullet lines, or sentences within a prose line. Numbers and wording are not
 * checked — only the identifiers a reader would act on.</p>
 */
final class SessionJournalFactCheck {

    private static final Pattern URL = Pattern.compile("https?://([^\\s/`)\\]>\"'«»]+)[^\\s`)\\]>\"'«»]*",
        Pattern.CASE_INSENSITIVE);
    /** host.tld or file.ext — two or more letters after the last dot, so 2.20.2 and z.B. are not one. */
    private static final Pattern DOTTED_NAME = Pattern.compile(
        "(?<![\\w./-])((?:[\\w-]+\\.)+[A-Za-z]{2,})(?![\\w-])");
    private static final Pattern BACKTICKED = Pattern.compile("`([^`\\n]{1,120})`");
    private static final Pattern BULLET = Pattern.compile("^\\s*[-*•]\\s+.*$");
    private static final Pattern SENTENCE_END = Pattern.compile("(?<=[.!?])\\s+(?=[\\p{Lu}`\"„(])");

    private SessionJournalFactCheck() {
    }

    /** The result of a check: the kept text and how many statements were dropped. */
    record Result(String text, int dropped) {
    }

    static Result dropUnverified(String text, String evidence) {
        if (text == null || text.isBlank()) {
            return new Result(text, 0);
        }
        String haystack = normalize(evidence != null ? evidence : "");
        StringBuilder kept = new StringBuilder(text.length());
        int dropped = 0;
        for (String line : text.split("\\R", -1)) {
            if (BULLET.matcher(line).matches() || line.isBlank()) {
                if (line.isBlank() || verified(line, haystack)) {
                    kept.append(line).append('\n');
                } else {
                    dropped++;
                }
                continue;
            }
            List<String> sentences = new ArrayList<>();
            for (String sentence : SENTENCE_END.split(line)) {
                if (verified(sentence, haystack)) {
                    sentences.add(sentence);
                } else {
                    dropped++;
                }
            }
            if (!sentences.isEmpty()) {
                kept.append(String.join(" ", sentences)).append('\n');
            }
        }
        String result = kept.toString().replaceAll("\\n{3,}", "\n\n").strip();
        return new Result(result, dropped);
    }

    /** True when every identifier in {@code statement} occurs in the evidence. */
    static boolean verified(String statement, String haystack) {
        for (String identifier : identifiers(statement)) {
            if (!haystack.contains(normalize(identifier))) {
                return false;
            }
        }
        return true;
    }

    static List<String> identifiers(String statement) {
        List<String> result = new ArrayList<>();
        Matcher url = URL.matcher(statement);
        StringBuilder withoutUrls = new StringBuilder();
        int index = 0;
        while (url.find()) {
            String host = url.group(1).toLowerCase(Locale.ROOT);
            result.add(host.startsWith("www.") ? host.substring(4) : host);
            withoutUrls.append(statement, index, url.start()).append(' ');
            index = url.end();
        }
        withoutUrls.append(statement.substring(index));
        String rest = withoutUrls.toString();
        Matcher backticked = BACKTICKED.matcher(rest);
        while (backticked.find()) {
            String value = backticked.group(1).strip();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        Matcher dotted = DOTTED_NAME.matcher(rest.replace("`", " "));
        while (dotted.find()) {
            String value = dotted.group(1);
            result.add(value.toLowerCase(Locale.ROOT).startsWith("www.") ? value.substring(4) : value);
        }
        return result;
    }

    private static String normalize(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
