package de.kortty.memory;

import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryDocument.Segment;
import de.kortty.memory.MemoryDocument.UserText;
import de.kortty.memory.external.ExternalMemoryReader;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
        "(?i)<(/?)(kortty_user_memory|retrieved_context|kortty_model_preset|kortty_external_memory|kortty_ai_skill)");
    private static final Pattern PROMPT_MARKER_WITHOUT_SKILL = Pattern.compile(
        "(?i)<(/?)(kortty_user_memory|retrieved_context|kortty_model_preset|kortty_external_memory)");
    /** The UTF-8 byte-order mark: an editor may write it in front of the first line, and it is kept as user text. */
    private static final String BOM = "﻿";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,32}");
    private static final Pattern SOURCE = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern CREATED = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");
    /** The sha256 attribute of a fence header: a space, the name, and exactly 64 lowercase hex characters before a space. */
    private static final Pattern FENCE_HASH = Pattern.compile(" sha256=[0-9a-f]{64}(?= )");
    private static final List<String> ATTRIBUTE_NAMES = List.of("id", "origin", "sha256", "created", "source");

    private MemoryFormat() {
    }

    /** Splits a memory file into user text and intact korTTY entries; joining them restores {@code text}. */
    public static MemoryDocument parse(String text) {
        String source = text == null ? "" : text;
        // A byte-order mark is part of the user's text; it is kept in front of the first user segment, so a korTTY
        // fence on the first line is still recognised.
        boolean bom = source.startsWith(BOM);
        List<String> lines = splitLines(bom ? source.substring(BOM.length()) : source);
        List<Segment> segments = new ArrayList<>();
        StringBuilder user = new StringBuilder(bom ? BOM : "");
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
     * writes them next to its fences, and removing a fence leaves them behind. Where a fence stood between two
     * pieces of user text, one blank line stands for the separator between them: the separator keeps the two
     * paragraphs apart, so dropping it on a removal is a change of the user's text and shows here.
     */
    public static String userSegments(String text) {
        StringBuilder kept = new StringBuilder();
        boolean fenceBetween = false;
        for (Segment segment : parse(text).segments()) {
            if (segment instanceof KorttyEntry) {
                fenceBetween = true;
                continue;
            }
            String trimmed = trimBlankEdges(((UserText) segment).text());
            if (trimmed.isEmpty()) {
                continue;
            }
            if (fenceBetween && !kept.isEmpty()) {
                kept.append('\n');
            }
            kept.append(trimmed);
            if (!trimmed.endsWith("\n")) {
                kept.append('\n');
            }
            fenceBetween = false;
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
     * The text the secret guard reads when it checks a file that is already stored, a backup or the startup scan: the file
     * as it is, except that the sha256 value of every korTTY fence header is left out. That value is a hash, and the guard
     * would take it for a random token. A header counts whether its entry is intact or was edited since (an edited entry
     * keeps its header with the old hash), so a stored file with an edited entry is not flagged by it. Invisible
     * characters are removed, because a zero-width space inside a keyword would hide it from the guard. Every other part
     * of a fence stays visible. Line numbers are those of {@code text}.
     *
     * <p>A write is checked with {@link #textForSecretCheck(String, String)} instead, which does not take the hash of a
     * header line the stored file did not hold.</p>
     */
    public static String textForSecretCheck(String text) {
        return visibleForSecretCheck(text, null);
    }

    /**
     * {@link #textForSecretCheck(String)} for a write that replaces {@code previous}. The sha256 value of a fence header is
     * left out only when the entry is intact (its hash is the hash of its body) or when the same header line is already
     * in {@code previous}, so an edited entry keeps passing and a header with a new hash is visible to the guard. A
     * secret typed into a fence header is therefore refused.
     */
    public static String textForSecretCheck(String text, String previous) {
        return visibleForSecretCheck(text, previousLines(previous == null ? "" : previous));
    }

    /** The check text; {@code previousLines} null means that every header of the text is taken as stored. */
    private static String visibleForSecretCheck(String text, Set<String> previousLines) {
        StringBuilder visible = new StringBuilder();
        for (Segment segment : parse(text == null ? "" : text).segments()) {
            if (segment instanceof KorttyEntry entry) {
                // An intact entry: its header hash is the hash of a body the guard reads.
                for (String line : splitLines(entry.raw())) {
                    visible.append(withoutFenceHash(line));
                }
                continue;
            }
            for (String line : splitLines(((UserText) segment).text())) {
                boolean stored = previousLines == null || previousLines.contains(stripTerminator(line));
                visible.append(stored ? withoutFenceHash(line) : line);
            }
        }
        return ExternalMemoryReader.sanitize(visible.toString()).text();
    }

    private static Set<String> previousLines(String previous) {
        Set<String> lines = new HashSet<>();
        for (String line : splitLines(previous)) {
            lines.add(stripTerminator(line));
        }
        return lines;
    }

    /**
     * Whether {@code text} still holds the fence of the korTTY entry {@code id}, intact or with an edited body. An entry
     * the user edited keeps its fence, so the id stays in the file; a fence that is gone means the entry is gone.
     */
    public static boolean hasKorttyFence(String text, String id) {
        if (id == null) {
            return false;
        }
        for (String line : splitLines(text == null ? "" : text)) {
            Matcher open = OPEN_LINE.matcher(stripTerminator(line));
            if (open.matches()) {
                Map<String, String> values = headerValues(open.group(1));
                if (ORIGIN.equals(values.get("origin")) && id.equals(values.get("id"))) {
                    return true;
                }
            }
        }
        return false;
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

    /**
     * {@link #escapeMarkers} without the skill tag. The skill block korTTY builds carries its own skill tags, which must
     * reach the model; the text inside that block is escaped with {@link #escapeMarkers} where it is composed.
     */
    public static String escapeMarkersKeepingSkillTags(String text) {
        return text == null ? "" : PROMPT_MARKER_WITHOUT_SKILL.matcher(text).replaceAll("< $1$2");
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
        return trimBlankLines(text, true, true);
    }

    /**
     * The text without its blank lines at the start when {@code leading} is set and at the end when {@code trailing}
     * is set; the other blank lines and each line's ending stay as they are.
     */
    static String trimBlankLines(String text, boolean leading, boolean trailing) {
        List<String> lines = splitLines(text);
        int from = 0;
        int to = lines.size();
        while (leading && from < to && stripTerminator(lines.get(from)).isEmpty()) {
            from++;
        }
        while (trailing && to > from && stripTerminator(lines.get(to - 1)).isEmpty()) {
            to--;
        }
        return String.join("", lines.subList(from, to));
    }

    /**
     * The line without the sha256 attribute when the line is a korTTY fence header, so the line keeps its other attributes
     * and its line ending. Any other line is returned as it is.
     */
    private static String withoutFenceHash(String line) {
        String content = stripTerminator(line);
        if (!isFenceHeader(content)) {
            return line;
        }
        return FENCE_HASH.matcher(content).replaceAll("") + line.substring(content.length());
    }

    /**
     * Whether {@code line} has the shape of a korTTY fence header: the whole opening fence, with attributes that all have
     * known names. The values are not checked, because an edited entry keeps its header with values that no longer match.
     */
    private static boolean isFenceHeader(String line) {
        Matcher open = OPEN_LINE.matcher(line);
        if (!open.matches()) {
            return false;
        }
        for (String token : open.group(1).strip().split(" ")) {
            int equals = token.indexOf('=');
            if (equals <= 0 || !ATTRIBUTE_NAMES.contains(token.substring(0, equals))) {
                return false;
            }
        }
        return true;
    }

    /** The attribute values of a fence header, first occurrence wins; the header's shape is checked by the caller. */
    private static Map<String, String> headerValues(String raw) {
        Map<String, String> values = new HashMap<>();
        for (String token : raw.strip().split(" ")) {
            int equals = token.indexOf('=');
            if (equals > 0) {
                values.putIfAbsent(token.substring(0, equals), token.substring(equals + 1));
            }
        }
        return values;
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
