package de.kortty.memory;

import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryDocument.Segment;
import de.kortty.memory.MemoryDocument.UserText;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads and writes the memory file format. Text the user wrote belongs to the user. korTTY entries
 * sit between fence comments and count as korTTY's only while the body still matches the hash
 * recorded in the fence; an edited entry therefore becomes user text.
 *
 * <pre>
 * &lt;!-- kortty:entry id=3fa9 origin=korTTY sha256=… created=2026-11-02 source=chat --&gt;
 * - Perl: declare static variables with {@code state}.
 * &lt;!-- /kortty:entry --&gt;
 * </pre>
 */
public final class MemoryFormat {

    /** The origin a fence must name to count as korTTY's. */
    public static final String ORIGIN = "korTTY";

    private static final Pattern OPEN_LINE = Pattern.compile("<!-- kortty:entry((?: [^\\s<>]+)+) -->");
    private static final Pattern CLOSE_LINE = Pattern.compile("<!-- /kortty:entry -->");
    private static final Pattern FENCE_MARKER = Pattern.compile("<!--\\s*/?kortty:entry\\b.*?-->");
    private static final Pattern FENCE_MARKER_IN_BODY = Pattern.compile("<!--(\\s*/?kortty:entry)");
    private static final Pattern PROMPT_MARKER = Pattern.compile(
        "(?i)<(/?)(kortty_user_memory|retrieved_context|kortty_model_preset|kortty_external_memory)");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern CREATED = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    private static final List<String> ATTRIBUTE_NAMES = List.of("id", "origin", "sha256", "created", "source");

    private MemoryFormat() {
    }

    /** Splits a memory file into user text and intact korTTY entries; joining them restores {@code text}. */
    public static MemoryDocument parse(String text) {
        List<String> lines = splitLines(text == null ? "" : text);
        List<Segment> segments = new ArrayList<>();
        StringBuilder user = new StringBuilder();
        int index = 0;
        while (index < lines.size()) {
            int close = closingLine(lines, index);
            Optional<KorttyEntry> entry = close < 0 ? Optional.empty() : readEntry(lines.subList(index, close + 1));
            if (entry.isPresent()) {
                flush(user, segments);
                segments.add(entry.get());
                index = close + 1;
            } else {
                user.append(lines.get(index));
                index++;
            }
        }
        flush(user, segments);
        return new MemoryDocument(segments);
    }

    /**
     * The user's own content, used to prove that a korTTY write changed nothing the user wrote: every line of
     * user text with its line ending, so a changed CRLF, a changed space or a removed blank line between two
     * paragraphs shows. The blank lines at the start and end of each piece of user text do not count: korTTY
     * writes them next to its fences, and removing a fence leaves them behind.
     */
    public static String userSegments(String text) {
        StringBuilder kept = new StringBuilder();
        for (Segment segment : parse(text).segments()) {
            if (segment instanceof UserText user) {
                String trimmed = trimBlankEdges(user.text());
                kept.append(trimmed);
                if (!trimmed.isEmpty() && !trimmed.endsWith("\n")) {
                    kept.append('\n');
                }
            }
        }
        return kept.toString();
    }

    /** Whether {@code fence} is exactly one intact korTTY entry. */
    public static boolean isKorttyOwned(String fence) {
        MemoryDocument document = parse(fence);
        return document.segments().size() == 1 && document.segments().get(0) instanceof KorttyEntry;
    }

    /** The text without fence comment lines, for prompts that must not see the metadata. */
    public static String withoutFenceMarkers(String text) {
        StringBuilder kept = new StringBuilder();
        for (String line : splitLines(text == null ? "" : text)) {
            if (!FENCE_MARKER.matcher(stripTerminator(line)).find()) {
                kept.append(line);
            }
        }
        return kept.toString();
    }

    /**
     * The text the secret guard reads: the file as it is, except that the sha256 value of each intact korTTY fence
     * is left out. That value is a hash, and the guard would take it for a random token. Every other part of a fence
     * stays visible, so a secret cannot hide in a comment. Line numbers are those of {@code text}.
     */
    public static String textForSecretCheck(String text) {
        StringBuilder checked = new StringBuilder();
        for (String line : splitLines(text == null ? "" : text)) {
            checked.append(withoutSha256(line));
        }
        return checked.toString();
    }

    /** One fence for a new entry. Line breaks in the body become {@code \n}; fence markers inside it are escaped. */
    public static String newEntry(String id, String body, LocalDate created, String source) {
        if (!ID.matcher(id).matches() || !SOURCE.matcher(source).matches()) {
            throw new IllegalArgumentException("Entry id or source has characters the fence format does not allow.");
        }
        String normalized = (body == null ? "" : body).replace("\r\n", "\n").replace('\r', '\n');
        String escaped = FENCE_MARKER_IN_BODY.matcher(normalized).replaceAll("< !--$1");
        StringBuilder fence = new StringBuilder();
        fence.append("<!-- kortty:entry id=").append(id)
            .append(" origin=").append(ORIGIN)
            .append(" sha256=").append(sha256(escaped))
            .append(" created=").append(created)
            .append(" source=").append(source)
            .append(" -->\n");
        if (!escaped.isEmpty()) {
            fence.append(escaped).append('\n');
        }
        return fence.append("<!-- /kortty:entry -->\n").toString();
    }

    /** Escapes the tags that delimit prompt sections, the way {@code RagContextBuilder} does. */
    public static String escapeMarkers(String text) {
        return text == null ? "" : PROMPT_MARKER.matcher(text).replaceAll("< $1$2");
    }

    public static String sha256(String text) {
        return sha256(text.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines.add(text.substring(start, i + 1));
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    static String stripTerminator(String line) {
        String content = line.endsWith("\n") ? line.substring(0, line.length() - 1) : line;
        return content.endsWith("\r") ? content.substring(0, content.length() - 1) : content;
    }

    /** The text without the blank lines at its start and end, each line keeping its line ending. */
    static String trimBlankEdges(String text) {
        List<String> lines = splitLines(text);
        int from = 0;
        int to = lines.size();
        while (from < to && stripTerminator(lines.get(from)).isEmpty()) {
            from++;
        }
        while (to > from && stripTerminator(lines.get(to - 1)).isEmpty()) {
            to--;
        }
        return String.join("", lines.subList(from, to));
    }

    /** {@code line} with the sha256 value removed when it is the opening fence of an intact korTTY entry. */
    private static String withoutSha256(String line) {
        String content = stripTerminator(line);
        Matcher open = OPEN_LINE.matcher(content);
        if (!open.matches()) {
            return line;
        }
        Map<String, String> attributes = attributes(open.group(1));
        if (!intactHeader(attributes)) {
            return line;
        }
        String attributesWithoutSha = open.group(1).replace(" sha256=" + attributes.get("sha256"), "");
        return "<!-- kortty:entry" + attributesWithoutSha + " -->" + line.substring(content.length());
    }

    /** Whether the attributes of an opening fence name korTTY as origin and carry well-formed values. */
    private static boolean intactHeader(Map<String, String> attributes) {
        return attributes != null && ORIGIN.equals(attributes.get("origin"))
            && ID.matcher(attributes.get("id")).matches()
            && CREATED.matcher(attributes.get("created")).matches()
            && SOURCE.matcher(attributes.get("source")).matches()
            && SHA256.matcher(attributes.get("sha256")).matches();
    }

    private static void flush(StringBuilder user, List<Segment> segments) {
        if (!user.isEmpty()) {
            segments.add(new UserText(user.toString()));
            user.setLength(0);
        }
    }

    /** The index of the closing fence line for the opening line at {@code open}, or -1. */
    private static int closingLine(List<String> lines, int open) {
        if (!OPEN_LINE.matcher(stripTerminator(lines.get(open))).matches()) {
            return -1;
        }
        for (int index = open + 1; index < lines.size(); index++) {
            String line = stripTerminator(lines.get(index));
            if (CLOSE_LINE.matcher(line).matches()) {
                return index;
            }
            if (OPEN_LINE.matcher(line).matches()) {
                return -1;
            }
        }
        return -1;
    }

    private static Optional<KorttyEntry> readEntry(List<String> fence) {
        Matcher open = OPEN_LINE.matcher(stripTerminator(fence.get(0)));
        if (!open.matches()) {
            return Optional.empty();
        }
        Map<String, String> attributes = attributes(open.group(1));
        if (!intactHeader(attributes)) {
            return Optional.empty();
        }
        List<String> bodyLines = new ArrayList<>();
        for (int index = 1; index < fence.size() - 1; index++) {
            bodyLines.add(stripTerminator(fence.get(index)));
        }
        String body = String.join("\n", bodyLines);
        String recorded = attributes.get("sha256");
        if (!sha256(body).equals(recorded)) {
            return Optional.empty();
        }
        return Optional.of(new KorttyEntry(attributes.get("id"), attributes.get("created"),
            attributes.get("source"), recorded, body, String.join("", fence)));
    }

    /** The five attributes of an opening fence, or null when one is missing, repeated or unknown. */
    private static Map<String, String> attributes(String raw) {
        Map<String, String> attributes = new HashMap<>();
        for (String token : raw.strip().split(" ")) {
            int equals = token.indexOf('=');
            if (equals <= 0 || attributes.put(token.substring(0, equals), token.substring(equals + 1)) != null) {
                return null;
            }
        }
        return attributes.keySet().containsAll(ATTRIBUTE_NAMES) && attributes.size() == ATTRIBUTE_NAMES.size()
            ? attributes
            : null;
    }
}
