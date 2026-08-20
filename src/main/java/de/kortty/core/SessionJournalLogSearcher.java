package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Streaming full-text search over a journal's capture-log parts. Each part is consumed line by
 * line through {@link SessionJournalLogCompressor#openInput(Path)} — never materialized as a
 * whole — so scanning many large journals stays flat in memory, unlike
 * {@link SessionJournalLogReader#readAfter(Path, long)}.
 *
 * <p>Idle-flushed {@code partial} lines are duplicates of the full line that followed and are
 * skipped. A coalesced entry stands for {@code repeat} occurrences of its line and counts as
 * that many matches toward the total, while producing a single hit.</p>
 */
public final class SessionJournalLogSearcher {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalLogSearcher.class);

    /** Lines between cancellation checks — frequent enough to keep aborts prompt. */
    private static final int CANCEL_CHECK_INTERVAL = 512;
    private static final int SNIPPET_CHARS = 200;

    private SessionJournalLogSearcher() {
    }

    /**
     * What to search for. Blank terms are ignored; a spec without any usable term matches nothing.
     *
     * @param terms         literal substrings, or regular expressions when {@code regex} is true
     * @param matchAll      every term must match the line (AND) instead of any term (OR)
     * @param caseSensitive exact-case matching; default matching is case-insensitive
     * @param regex         interpret the terms as {@link Pattern} regular expressions
     * @param from          inclusive lower timestamp bound, null for unbounded
     * @param to            inclusive upper timestamp bound, null for unbounded
     * @param kinds         entry kinds to search; null or empty means all kinds
     */
    public record Spec(List<String> terms, boolean matchAll, boolean caseSensitive, boolean regex,
                       OffsetDateTime from, OffsetDateTime to,
                       Set<SessionJournalLogEntry.Kind> kinds) {

        /** Case-insensitive any-term substring search over all kinds — the common case. */
        public static Spec ofLiteral(List<String> terms) {
            return new Spec(terms, false, false, false, null, null, null);
        }
    }

    /** One matching log entry; {@code repeat} is the number of occurrences the line stands for. */
    public record Hit(long seq, int part, SessionJournalLogEntry.Kind kind,
                      OffsetDateTime timestamp, String snippet, int repeat) {
    }

    /**
     * @param hits         at most {@code maxHits} matches in log order
     * @param totalMatches exact number of matched occurrences, counted past the hit cap
     * @param truncated    true when more matches exist than {@code hits} carries
     */
    public record Result(List<Hit> hits, long totalMatches, boolean truncated) {

        public static final Result EMPTY = new Result(List.of(), 0, false);
    }

    /**
     * Scans all parts of the journal in log order. Unreadable parts are skipped (the capture log's
     * recovery contract), so this never throws on torn or corrupt files; cancellation returns the
     * hits collected so far.
     *
     * @throws PatternSyntaxException when {@code spec.regex()} is set and a term is not a valid pattern
     */
    public static Result search(Path journalDir, Spec spec, int maxHits, BooleanSupplier cancelled) {
        LineMatcher matcher = LineMatcher.forSpec(spec);
        if (matcher == null) {
            return Result.EMPTY;
        }
        int parts = SessionJournalLogReader.countParts(journalDir);
        List<Hit> hits = new ArrayList<>();
        long totalMatches = 0;
        boolean truncated = false;
        outer:
        for (int part = 1; part <= parts; part++) {
            Path file = SessionJournalLogReader.findPartFile(journalDir, part);
            if (file == null) {
                break;
            }
            SessionJournalLogReader.LineParser parser = SessionJournalLogReader.LineParser.forPartFile(file);
            if (parser == null) {
                continue;
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    SessionJournalLogCompressor.openInput(file), StandardCharsets.UTF_8))) {
                String line;
                int sinceCancelCheck = 0;
                while ((line = reader.readLine()) != null) {
                    if (++sinceCancelCheck >= CANCEL_CHECK_INTERVAL) {
                        sinceCancelCheck = 0;
                        if (cancelled != null && cancelled.getAsBoolean()) {
                            break outer;
                        }
                    }
                    SessionJournalLogEntry entry = parser.parse(line);
                    if (entry == null || entry.partial() || !accepts(spec, entry)) {
                        continue;
                    }
                    String text = entry.text() != null ? entry.text() : "";
                    int matchIndex = matcher.firstMatchIndex(text);
                    if (matchIndex < 0) {
                        continue;
                    }
                    totalMatches += Math.max(1, entry.repeat());
                    if (hits.size() < maxHits) {
                        hits.add(new Hit(entry.seq(), part, entry.kind(), entry.timestamp(),
                            snippet(text, matchIndex), Math.max(1, entry.repeat())));
                    } else {
                        truncated = true;
                    }
                }
            } catch (IOException e) {
                logger.warn("Skipping unreadable session journal log part {}: {}",
                    file.getFileName(), e.getMessage());
            }
        }
        return new Result(List.copyOf(hits), totalMatches, truncated);
    }

    private static boolean accepts(Spec spec, SessionJournalLogEntry entry) {
        if (spec.kinds() != null && !spec.kinds().isEmpty() && !spec.kinds().contains(entry.kind())) {
            return false;
        }
        if (spec.from() != null && entry.timestamp().isBefore(spec.from())) {
            return false;
        }
        return spec.to() == null || !entry.timestamp().isAfter(spec.to());
    }

    /** The matched line trimmed to {@link #SNIPPET_CHARS} centered on the first match. */
    static String snippet(String text, int matchIndex) {
        String flattened = text.replace('\n', ' ').replace('\r', ' ');
        if (flattened.length() <= SNIPPET_CHARS) {
            return flattened.strip();
        }
        int start = Math.max(0, Math.min(matchIndex - SNIPPET_CHARS / 2, flattened.length() - SNIPPET_CHARS));
        int end = Math.min(flattened.length(), start + SNIPPET_CHARS);
        StringBuilder sb = new StringBuilder(SNIPPET_CHARS + 2);
        if (start > 0) {
            sb.append('…');
        }
        sb.append(flattened, start, end);
        if (end < flattened.length()) {
            sb.append('…');
        }
        return sb.toString().strip();
    }

    /** Compiled form of a spec's terms; returns -1 when the line does not match. */
    private interface LineMatcher {

        int firstMatchIndex(String text);

        /** Null when the spec has no usable term. */
        static LineMatcher forSpec(Spec spec) {
            List<String> terms = spec.terms() == null ? List.of()
                : spec.terms().stream().filter(t -> t != null && !t.isBlank()).toList();
            if (terms.isEmpty()) {
                return null;
            }
            if (spec.regex()) {
                int flags = spec.caseSensitive() ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE;
                List<Pattern> patterns = terms.stream().map(t -> Pattern.compile(t, flags)).toList();
                return text -> {
                    int first = -1;
                    for (Pattern pattern : patterns) {
                        Matcher m = pattern.matcher(text);
                        if (m.find()) {
                            if (first < 0) {
                                first = m.start();
                            }
                            if (!spec.matchAll()) {
                                return first;
                            }
                        } else if (spec.matchAll()) {
                            return -1;
                        }
                    }
                    return first;
                };
            }
            boolean fold = !spec.caseSensitive();
            List<String> needles = fold
                ? terms.stream().map(t -> t.toLowerCase(Locale.ROOT)).toList()
                : terms;
            return text -> {
                String haystack = fold ? text.toLowerCase(Locale.ROOT) : text;
                int first = -1;
                for (String needle : needles) {
                    int index = haystack.indexOf(needle);
                    if (index >= 0) {
                        if (first < 0) {
                            first = index;
                        }
                        if (!spec.matchAll()) {
                            return first;
                        }
                    } else if (spec.matchAll()) {
                        return -1;
                    }
                }
                return first;
            };
        }
    }
}
