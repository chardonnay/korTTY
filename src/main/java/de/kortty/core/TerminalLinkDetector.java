package de.kortty.core;

import com.sithtermfx.core.util.CharUtils;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds URLs, file paths, e-mail addresses, UUIDs, IP addresses, git hashes and long numbers in one
 * line of terminal text.
 *
 * <p>The text is whatever a program printed, so every pattern has to stay linear on hostile input.
 * Each pattern starts with a one-character lookbehind that only lets a match attempt begin at the
 * start of a run, consumes that run with possessive quantifiers (no backtracking), and is skipped
 * entirely when a cheap prefilter shows its kind cannot occur. Structure that a linear pattern
 * cannot express (octet ranges, IPv6 group counts, trailing punctuation, unbalanced brackets) is
 * checked in plain code afterwards. At most {@value #MAX_INPUT_CHARS} characters are scanned. Nothing
 * here resolves a host name: IP addresses are validated syntactically only.
 *
 * <p>Offsets are cell offsets: they index the given sequence as the terminal buffer stores it.
 * {@link CharUtils#DWC} (the second cell of a double-width character) is skipped while matching and
 * removed from {@link Match#text()}; a surrogate pair stays one character. NUL (an empty cell),
 * whitespace, control characters and invisible format characters such as bidi overrides end a token.
 *
 * <p>Pure and thread-safe.
 */
public final class TerminalLinkDetector {

    /**
     * What a match is. The declaration order is also the overlap priority: when two matches overlap,
     * the earlier kind wins, then the longer match.
     */
    public enum Kind {
        /** {@code http}, {@code https}, {@code ftp}, {@code ftps} or {@code mailto} link. */
        URL,
        /**
         * Absolute, home, dot-relative or Windows drive path, or a relative one with a {@code /} and an
         * extension or line suffix; optionally followed by {@code :N}, {@code :N:M} or {@code (N,M)}.
         */
        PATH,
        EMAIL,
        UUID,
        /** Syntactically valid IPv6 address with an optional {@code %zone}. */
        IPV6,
        /** Dotted IPv4 address with an optional {@code :port}. */
        IPV4,
        /** 7 to 40 lowercase hex characters with at least one digit and one letter. */
        GIT_HASH,
        /** 4 or more digits. */
        NUMBER
    }

    /**
     * One detected token.
     *
     * @param start the first cell offset in the scanned sequence
     * @param end the cell offset after the last cell, including a trailing {@link CharUtils#DWC}
     * @param kind what the token is
     * @param text the token without {@link CharUtils#DWC} characters
     */
    public record Match(int start, int end, Kind kind, String text) {
    }

    /** Characters scanned at most; a token that this cap cuts is dropped. */
    public static final int MAX_INPUT_CHARS = 16 * 1024;

    /** Removed from the end of URLs and paths: sentence punctuation and closing quotes. */
    private static final String TRAILING_PUNCTUATION = ".,;:!?'\"";

    private static final String CLOSING_BRACKETS = ")]}";

    private static final String OPENING_BRACKETS = "([{";

    /** Characters inside one path segment. Separators and the {@code :line} colon are not part of it. */
    private static final String PATH_SEGMENT = "\\p{L}\\p{M}\\p{N}._~+@%\\-";

    /** Letters, digits and underscore: what must not touch an address, hash or number. */
    private static final String WORD = "\\p{L}\\p{N}_";

    /**
     * Characters a URL may contain: RFC 3986 unreserved and reserved characters, {@code %}, braces,
     * and letters, marks and digits of any script for internationalized links. Quotes, angle
     * brackets, backslash, {@code ^}, {@code |}, symbols such as box drawing and powerline glyphs end it.
     */
    private static final String URL_CHARS = "\\p{L}\\p{M}\\p{N}\\-._~:/?#\\[\\]@!$\\&'()*+,;=%{}";

    private static final Pattern URL = Pattern.compile(
        "(?<![A-Za-z0-9+.\\-])(?:(?:https?|ftps?)://|mailto:)[" + URL_CHARS + "]++",
        Pattern.CASE_INSENSITIVE);

    /**
     * A drive path ({@code C:\x}, {@code C:/x}), {@code ~/x}, {@code ./x}, {@code ../x}, an absolute
     * {@code /x} or a relative {@code a/b.c}. A separator right after the root ({@code //host},
     * {@code C://host}) never starts a path and a backslash never precedes one, so UNC and device
     * paths ({@code \\host\share}, {@code //host/share}, {@code \\?\C:\x}) cannot match at all.
     */
    private static final Pattern PATH = Pattern.compile(
        "(?<![" + PATH_SEGMENT + "/\\\\])"
            + "(?:(?<drive>[A-Za-z]:[\\\\/](?![\\\\/]))|(?<home>~/)|(?<dot>\\.\\.?/)|(?<root>/(?!/))|(?=[" + PATH_SEGMENT + "]))"
            + "[" + PATH_SEGMENT + "/\\\\]*+"
            + "(?<suffix>:[0-9]{1,9}+(?![0-9])(?::[0-9]{1,9}+(?![0-9]))?+|\\([0-9]{1,9}+,[0-9]{1,9}+\\))?+");

    /** The named root groups of {@link #PATH}; none of them matching means a relative path. */
    private static final List<String> PATH_ROOTS = List.of("drive", "home", "dot", "root");

    private static final Pattern EMAIL = Pattern.compile(
        "(?<![A-Za-z0-9._%+\\-])(?<local>[A-Za-z0-9._%+\\-]{1,64}+)@"
            + "(?<domain>[A-Za-z0-9\\-]{1,63}+(?:\\.[A-Za-z0-9\\-]{1,63}+)++)(?![A-Za-z0-9\\-])");

    private static final Pattern UUID = Pattern.compile(
        "(?<![" + WORD + "\\-])[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}"
            + "(?![" + WORD + "\\-])");

    /** A candidate run only; {@link #isIpv6(String)} decides. */
    private static final Pattern IPV6 = Pattern.compile(
        "(?<![" + WORD + ":.%])[0-9A-Fa-f:.]{2,}+(?:%[0-9A-Za-z_.\\-]++)?+(?![" + WORD + "])");

    private static final Pattern IPV4 = Pattern.compile(
        "(?<![" + WORD + ".])([0-9]{1,3}+)\\.([0-9]{1,3}+)\\.([0-9]{1,3}+)\\.([0-9]{1,3}+)(?::([0-9]{1,5}+)(?![0-9]))?+"
            + "(?![" + WORD + "]|\\.[0-9])");

    private static final Pattern GIT_HASH = Pattern.compile(
        "(?<![" + WORD + "\\-])[0-9a-f]{7,40}+(?![" + WORD + "\\-])");

    private static final Pattern NUMBER = Pattern.compile(
        "(?<![" + WORD + ".])[0-9]{4,}+(?![" + WORD + "]|\\.[0-9])");

    private static final int MAX_IPV6_LENGTH = 45;

    private static final int MAX_ZONE_LENGTH = 64;

    private TerminalLinkDetector() {
    }

    /**
     * Every token of the requested kinds in {@code line}, ordered by start and never overlapping.
     * Overlaps are resolved among the requested kinds only, with one exception: a path is never
     * reported inside a URL, even when URLs are not requested, because the tail of a URL is no local
     * file.
     *
     * @param line one logical terminal line, as cells (may contain NUL padding and {@link CharUtils#DWC})
     * @param kinds the kinds to look for
     */
    public static List<Match> find(CharSequence line, Set<Kind> kinds) {
        if (line == null || line.isEmpty() || kinds == null || kinds.isEmpty()) {
            return List.of();
        }
        Scan scan = Scan.of(line);
        Set<Kind> requested = EnumSet.copyOf(kinds);
        List<Candidate> candidates = new ArrayList<>();
        for (Kind kind : requested) {
            if (mayContain(kind, scan.text)) {
                collect(kind, scan.text, candidates);
            }
        }
        if (requested.contains(Kind.PATH) && !requested.contains(Kind.URL) && mayContain(Kind.URL, scan.text)) {
            List<Candidate> urls = new ArrayList<>();
            collect(Kind.URL, scan.text, urls);
            TreeMap<Integer, Candidate> urlSpans = new TreeMap<>();
            for (Candidate url : urls) {
                urlSpans.put(url.start, url);
            }
            candidates.removeIf(c -> c.kind == Kind.PATH && overlapsAny(urlSpans, c));
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.kind.ordinal())
            .thenComparingInt(c -> c.start - c.end)
            .thenComparingInt(c -> c.start));
        TreeMap<Integer, Candidate> accepted = new TreeMap<>();
        for (Candidate candidate : candidates) {
            if (!overlapsAny(accepted, candidate)) {
                accepted.put(candidate.start, candidate);
            }
        }
        List<Match> matches = new ArrayList<>(accepted.size());
        for (Candidate candidate : accepted.values()) {
            matches.add(scan.toMatch(candidate));
        }
        return List.copyOf(matches);
    }

    /** Whether {@code candidate} overlaps one of the non-overlapping spans, keyed by their start. */
    private static boolean overlapsAny(TreeMap<Integer, Candidate> spans, Candidate candidate) {
        Map.Entry<Integer, Candidate> before = spans.floorEntry(candidate.start);
        if (before != null && before.getValue().end > candidate.start) {
            return true;
        }
        Map.Entry<Integer, Candidate> after = spans.ceilingEntry(candidate.start);
        return after != null && after.getKey() < candidate.end;
    }

    private static boolean mayContain(Kind kind, String text) {
        return switch (kind) {
            case URL -> text.contains("://") || containsMailto(text);
            case PATH -> text.indexOf('/') >= 0 || text.indexOf('\\') >= 0;
            case EMAIL -> text.indexOf('@') >= 0;
            case UUID -> text.length() >= 36 && text.indexOf('-') >= 0;
            case IPV6 -> text.indexOf(':') >= 0;
            case IPV4 -> text.indexOf('.') >= 0;
            case GIT_HASH, NUMBER -> containsAsciiDigit(text);
        };
    }

    private static void collect(Kind kind, String text, List<Candidate> out) {
        Matcher m = pattern(kind).matcher(text);
        while (m.find()) {
            int end = switch (kind) {
                case URL -> urlEnd(text, m);
                case PATH -> pathEnd(text, m);
                case EMAIL -> isEmail(m.group("local"), m.group("domain")) ? m.end() : -1;
                case UUID -> m.end();
                case IPV6 -> ipv6End(text, m);
                case IPV4 -> ipv4End(m);
                case GIT_HASH -> hasDigitAndLetter(text, m.start(), m.end()) ? m.end() : -1;
                case NUMBER -> m.end();
            };
            if (end > m.start()) {
                out.add(new Candidate(kind, m.start(), end));
            }
        }
    }

    private static Pattern pattern(Kind kind) {
        return switch (kind) {
            case URL -> URL;
            case PATH -> PATH;
            case EMAIL -> EMAIL;
            case UUID -> UUID;
            case IPV6 -> IPV6;
            case IPV4 -> IPV4;
            case GIT_HASH -> GIT_HASH;
            case NUMBER -> NUMBER;
        };
    }

    private static int urlEnd(String text, Matcher m) {
        int start = m.start();
        int end = trimEnd(text, start, m.end());
        int rest = text.indexOf(':', start) + 1;
        if (text.startsWith("//", rest)) {
            rest += 2;
            // a host must follow: a letter, digit or an IPv6 literal, never another slash
            return rest < end && (Character.isLetterOrDigit(text.codePointAt(rest)) || text.charAt(rest) == '[') ? end : -1;
        }
        return rest < end && Character.isLetterOrDigit(text.codePointAt(rest)) ? end : -1; // mailto:
    }

    private static int pathEnd(String text, Matcher m) {
        int start = m.start();
        boolean hasSuffix = m.start("suffix") >= 0;
        int pathEnd = hasSuffix ? m.start("suffix") : trimEnd(text, start, m.end());
        int bodyStart = start;
        for (String root : PATH_ROOTS) {
            if (m.start(root) >= 0) {
                bodyStart = m.end(root);
            }
        }
        if (!containsLetterOrDigit(text, bodyStart, pathEnd)) {
            return -1;
        }
        boolean relative = bodyStart == start;
        if (relative) {
            int lastSlash = pathEnd - 1;
            while (lastSlash >= start && text.charAt(lastSlash) != '/') {
                lastSlash--;
            }
            if (lastSlash < start) {
                return -1; // a bare word or file name is not a path
            }
            if (!hasSuffix && !hasExtension(text, lastSlash + 1, pathEnd)) {
                return -1; // and/or, TCP/IP, 24/7, 10/03, src/main
            }
        }
        return hasSuffix ? m.end() : pathEnd;
    }

    /** Whether the last segment has a dot that is followed by at least one letter (a.txt, app.log.1, .env). */
    private static boolean hasExtension(String text, int segmentStart, int segmentEnd) {
        boolean afterDot = false;
        for (int i = segmentStart; i < segmentEnd; i++) {
            if (text.charAt(i) == '.') {
                afterDot = true;
            } else if (afterDot && Character.isLetter(text.codePointAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isEmail(String local, String domain) {
        if (local.startsWith(".") || local.endsWith(".") || local.contains("..") || domain.length() > 253) {
            return false;
        }
        String[] labels = domain.split("\\.", -1);
        for (String label : labels) {
            if (label.isEmpty() || label.startsWith("-") || label.endsWith("-")) {
                return false;
            }
        }
        String tld = labels[labels.length - 1];
        if (tld.length() < 2) {
            return false;
        }
        for (int i = 0; i < tld.length(); i++) {
            if (!isAsciiLetter(tld.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static int ipv6End(String text, Matcher m) {
        int start = m.start();
        int end = m.end();
        while (end > start && text.charAt(end - 1) == '.') {
            end--; // sentence punctuation after the address or zone
        }
        if (end - start > 2 && text.charAt(end - 1) == ':' && text.charAt(end - 2) != ':') {
            end--; // "from 2001:db8::1: reset"; a trailing '::' belongs to the address
        }
        if (end > start && text.charAt(end - 1) == '%') {
            end--; // a bare % is no zone
        }
        if (end - start > MAX_IPV6_LENGTH + 1 + MAX_ZONE_LENGTH) {
            return -1;
        }
        String candidate = text.substring(start, end);
        int percent = candidate.indexOf('%');
        if (percent >= 0 && candidate.length() - percent - 1 > MAX_ZONE_LENGTH) {
            return -1;
        }
        return isIpv6(percent >= 0 ? candidate.substring(0, percent) : candidate) ? end : -1;
    }

    /**
     * Syntactic IPv6 check: eight groups of one to four hex digits, or fewer around exactly one
     * {@code ::}, optionally ending in a dotted IPv4 address. At least one decimal digit is required,
     * which rejects {@code ::} alone and hex words such as {@code add::bad}. Times ({@code 10:30:00}) and
     * MAC addresses (six groups, no {@code ::}) do not have the group count.
     */
    static boolean isIpv6(String address) {
        if (address.length() < 2 || address.length() > MAX_IPV6_LENGTH || address.indexOf(':') < 0
            || !containsAsciiDigit(address)) {
            return false;
        }
        int compressed = address.indexOf("::");
        if (compressed >= 0 && address.indexOf("::", compressed + 1) >= 0) {
            return false;
        }
        String head = compressed >= 0 ? address.substring(0, compressed) : address;
        String tail = compressed >= 0 ? address.substring(compressed + 2) : "";
        String[] headGroups = head.isEmpty() ? new String[0] : head.split(":", -1);
        String[] tailGroups = tail.isEmpty() ? new String[0] : tail.split(":", -1);
        int groups = 0;
        int total = headGroups.length + tailGroups.length;
        for (int i = 0; i < total; i++) {
            String group = i < headGroups.length ? headGroups[i] : tailGroups[i - headGroups.length];
            boolean last = i == total - 1;
            if (last && group.indexOf('.') >= 0) {
                if (!isIpv4(group)) {
                    return false;
                }
                groups += 2;
            } else if (isHexGroup(group)) {
                groups++;
            } else {
                return false;
            }
        }
        return compressed >= 0 ? groups <= 7 : groups == 8;
    }

    private static boolean isHexGroup(String group) {
        if (group.isEmpty() || group.length() > 4) {
            return false;
        }
        for (int i = 0; i < group.length(); i++) {
            if (Character.digit(group.charAt(i), 16) < 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isIpv4(String dotted) {
        String[] octets = dotted.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            if (octet.isEmpty() || octet.length() > 3 || !isAsciiDigits(octet) || Integer.parseInt(octet) > 255) {
                return false;
            }
        }
        return true;
    }

    private static int ipv4End(Matcher m) {
        for (int group = 1; group <= 4; group++) {
            if (Integer.parseInt(m.group(group)) > 255) {
                return -1;
            }
        }
        String port = m.group(5);
        if (port != null && Integer.parseInt(port) > 65535) {
            return m.end(4); // keep the address, drop an impossible port
        }
        return m.end();
    }

    /**
     * Cuts trailing sentence punctuation and closing brackets that have no opening partner inside the
     * token, so {@code (see https://x.org/a_(b)).} keeps {@code https://x.org/a_(b)}. Linear: the
     * bracket balance is counted once and updated while trimming.
     */
    private static int trimEnd(String text, int start, int end) {
        int[] open = new int[OPENING_BRACKETS.length()];
        int[] close = new int[CLOSING_BRACKETS.length()];
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            int o = OPENING_BRACKETS.indexOf(c);
            if (o >= 0) {
                open[o]++;
            }
            int k = CLOSING_BRACKETS.indexOf(c);
            if (k >= 0) {
                close[k]++;
            }
        }
        while (end > start) {
            char last = text.charAt(end - 1);
            int k = CLOSING_BRACKETS.indexOf(last);
            if (TRAILING_PUNCTUATION.indexOf(last) >= 0) {
                end--;
            } else if (k >= 0 && close[k] > open[k]) {
                close[k]--;
                end--;
            } else {
                break;
            }
        }
        return end;
    }

    private static boolean containsMailto(String text) {
        for (int i = text.indexOf(':', 6); i >= 0; i = text.indexOf(':', i + 1)) {
            if (text.regionMatches(true, i - 6, "mailto", 0, 6)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAsciiDigit(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= '0' && c <= '9') {
                return true;
            }
        }
        return false;
    }

    private static boolean isAsciiDigits(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static boolean isAsciiLetter(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean containsLetterOrDigit(String text, int start, int end) {
        for (int i = start; i < end; i++) {
            if (Character.isLetterOrDigit(text.codePointAt(i))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasDigitAndLetter(String text, int start, int end) {
        boolean digit = false;
        boolean letter = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            digit |= c >= '0' && c <= '9';
            letter |= c >= 'a' && c <= 'f';
        }
        return digit && letter;
    }

    /** Whether a cell ends a token: an empty cell (NUL), whitespace, a control or an invisible format character. */
    private static boolean isDelimiter(char c) {
        return Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c)
            || Character.getType(c) == Character.FORMAT;
    }

    private record Candidate(Kind kind, int start, int end) {
    }

    /**
     * The scanned text: the input up to the cap without {@link CharUtils#DWC} cells and with every
     * delimiter turned into a space, plus the cell offset of each remaining character. When the cap
     * cuts a token, the text ends at the delimiter before it, so the cut token is never seen, not even
     * with its cut end trimmed off.
     */
    private record Scan(CharSequence line, String text, int[] cells, int limit) {

        static Scan of(CharSequence line) {
            int limit = Math.min(line.length(), MAX_INPUT_CHARS);
            StringBuilder text = new StringBuilder(limit);
            int[] cells = new int[limit];
            for (int i = 0; i < limit; i++) {
                char c = line.charAt(i);
                if (c == CharUtils.DWC) {
                    continue;
                }
                cells[text.length()] = i;
                text.append(isDelimiter(c) ? ' ' : c);
            }
            if (line.length() > limit && !isDelimiter(line.charAt(limit))) {
                text.setLength(Math.max(text.lastIndexOf(" "), 0));
            }
            return new Scan(line, text.toString(), cells, limit);
        }

        Match toMatch(Candidate candidate) {
            int start = cells[candidate.start];
            int end = cells[candidate.end - 1] + 1;
            while (end < limit && line.charAt(end) == CharUtils.DWC) {
                end++;
            }
            return new Match(start, end, candidate.kind, text.substring(candidate.start, candidate.end));
        }
    }
}
