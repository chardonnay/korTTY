package de.kortty.memory;

import de.kortty.memory.MemoryDocument.KorttyEntry;
import de.kortty.memory.MemoryDocument.Segment;
import de.kortty.memory.MemoryDocument.UserText;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The format of {@code archive.md}, where korTTY keeps the entries it took out of a memory file. Each archived fence is
 * preceded by a marker line that names the memory file it came from:
 *
 * <pre>
 * &lt;!-- kortty:archived scope=global --&gt;
 * &lt;!-- kortty:entry id=3fa9 origin=korTTY sha256=… created=2026-11-02 source=chat --&gt;
 * - Perl: declare static variables with {@code state}.
 * &lt;!-- /kortty:entry --&gt;
 * </pre>
 *
 * A fence without a marker was archived before the scope was recorded and belongs to the global memory. Every fence stays
 * exactly as it was in the memory file; the marker lines and the blank lines between the blocks are the only text added.
 * An archived entry is identified by its key, the digest of its fence.
 */
public final class MemoryArchive {

    private static final String MARKER_START = "<!-- kortty:archived scope=";
    private static final Pattern MARKER = Pattern.compile("<!-- kortty:archived scope=(\\S+) -->");
    private static final String GLOBAL_TOKEN = "global";
    private static final String CONNECTION_PREFIX = "connection:";

    /**
     * One archived entry.
     *
     * @param key   the digest of the entry's fence; identifies the entry in the archive
     * @param scope the memory file the entry came from; {@code null} when its marker names a scope this version does not
     *              know, in which case the entry is kept and shown in no file
     * @param entry the fence as it is in the archive
     */
    public record Archived(String key, MemoryScope scope, KorttyEntry entry) {
    }

    /** One part of the archive: a fence with the marker above it, or text that belongs to no block. */
    private record Piece(String marker, KorttyEntry entry, String stray) {
    }

    private MemoryArchive() {
    }

    /** The archived entries of {@code archiveText}, in file order. */
    public static List<Archived> entries(String archiveText) {
        List<Archived> found = new ArrayList<>();
        for (Piece piece : pieces(archiveText)) {
            if (piece.entry() != null) {
                found.add(new Archived(keyOf(piece.entry()), scopeOf(piece.marker()), piece.entry()));
            }
        }
        return List.copyOf(found);
    }

    /** The key of an entry: the digest of its fence as it stands in the file. */
    public static String keyOf(KorttyEntry entry) {
        return MemoryFormat.sha256(entry.raw());
    }

    /**
     * {@code archiveText} with the given fences appended in order, each under the marker of {@code scope}. Every fence
     * must be exactly one intact korTTY entry.
     *
     * @throws IllegalArgumentException when a text is not exactly one intact korTTY entry
     */
    public static String appended(String archiveText, MemoryScope scope, List<String> fences) {
        List<Piece> pieces = new ArrayList<>(pieces(archiveText));
        for (String fence : fences) {
            List<Segment> segments = MemoryFormat.parse(fence).segments();
            if (segments.size() != 1 || !(segments.get(0) instanceof KorttyEntry)) {
                throw new IllegalArgumentException("Only an intact korTTY entry can be archived.");
            }
            pieces.add(new Piece(markerFor(scope), (KorttyEntry) segments.get(0), null));
        }
        return render(pieces);
    }

    /** {@code archiveText} without the blocks whose keys are in {@code keys}; the rest of the text stays. */
    public static String without(String archiveText, Set<String> keys) {
        List<Piece> kept = new ArrayList<>();
        for (Piece piece : pieces(archiveText)) {
            if (piece.entry() == null || !keys.contains(keyOf(piece.entry()))) {
                kept.add(piece);
            }
        }
        return render(kept);
    }

    /** The marker line for {@code scope}, without a line break. */
    static String markerFor(MemoryScope scope) {
        String token = scope.isGlobal()
            ? GLOBAL_TOKEN
            : CONNECTION_PREFIX + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(scope.connectionId().getBytes(StandardCharsets.UTF_8));
        return MARKER_START + token + " -->";
    }

    /**
     * The scope a marker names: the global one for no marker (an old block), {@code null} for a marker this version does not
     * understand.
     */
    static MemoryScope scopeOf(String marker) {
        if (marker == null) {
            return MemoryScope.global();
        }
        Matcher matcher = MARKER.matcher(marker);
        if (!matcher.matches()) {
            return null;
        }
        String token = matcher.group(1);
        if (GLOBAL_TOKEN.equals(token)) {
            return MemoryScope.global();
        }
        if (token.startsWith(CONNECTION_PREFIX)) {
            try {
                String id = new String(Base64.getUrlDecoder().decode(token.substring(CONNECTION_PREFIX.length())),
                    StandardCharsets.UTF_8);
                return MemoryScope.connection(id);
            } catch (IllegalArgumentException malformed) {
                return null;
            }
        }
        return null;
    }

    /** The archive split into blocks and stray text, in file order. */
    private static List<Piece> pieces(String archiveText) {
        List<Segment> segments = MemoryFormat.parse(archiveText == null ? "" : archiveText).segments();
        List<Piece> pieces = new ArrayList<>();
        String pendingMarker = null;
        for (int index = 0; index < segments.size(); index++) {
            Segment segment = segments.get(index);
            if (segment instanceof KorttyEntry entry) {
                pieces.add(new Piece(pendingMarker, entry, null));
                pendingMarker = null;
                continue;
            }
            String text = ((UserText) segment).text();
            pendingMarker = null;
            boolean fenceFollows = index + 1 < segments.size() && segments.get(index + 1) instanceof KorttyEntry;
            if (fenceFollows) {
                List<String> lines = MemoryFormat.splitLines(text);
                String last = lines.isEmpty() ? "" : MemoryFormat.stripTerminator(lines.get(lines.size() - 1));
                if (MARKER.matcher(last).matches()) {
                    pendingMarker = last;
                    text = String.join("", lines.subList(0, lines.size() - 1));
                }
            }
            String stray = MemoryFormat.trimBlankEdges(text);
            if (!stray.isEmpty()) {
                pieces.add(new Piece(null, null, stray));
            }
        }
        return pieces;
    }

    /** The pieces as text: a blank line between the pieces, and a marker directly above its fence. */
    private static String render(List<Piece> pieces) {
        StringBuilder out = new StringBuilder();
        for (Piece piece : pieces) {
            if (!out.isEmpty()) {
                out.append('\n');
            }
            if (piece.entry() == null) {
                out.append(withLineBreak(piece.stray()));
            } else {
                if (piece.marker() != null) {
                    out.append(piece.marker()).append('\n');
                }
                out.append(withLineBreak(piece.entry().raw()));
            }
        }
        return out.toString();
    }

    private static String withLineBreak(String text) {
        return text.endsWith("\n") ? text : text + "\n";
    }
}
