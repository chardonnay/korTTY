package de.kortty.memory;

import java.util.ArrayList;
import java.util.List;

/**
 * A memory file split into the text the user wrote and the korTTY entries between them. Joining
 * the segments in order gives back the original text byte for byte.
 */
public record MemoryDocument(List<Segment> segments) {

    public MemoryDocument {
        segments = List.copyOf(segments);
    }

    /** One piece of the file: text of the user, or an entry korTTY wrote and can still vouch for. */
    public sealed interface Segment permits UserText, KorttyEntry {
    }

    /** Text the user wrote, including every line that is not an intact korTTY entry. */
    public record UserText(String text) implements Segment {
    }

    /**
     * An intact korTTY entry.
     *
     * @param body   the lines between the fence lines, joined with {@code \n}
     * @param raw    the fence exactly as it is in the file
     */
    public record KorttyEntry(String id, String created, String source, String sha256, String body, String raw)
        implements Segment {
    }

    public String toText() {
        StringBuilder text = new StringBuilder();
        for (Segment segment : segments) {
            text.append(segment instanceof UserText user ? user.text() : ((KorttyEntry) segment).raw());
        }
        return text.toString();
    }

    public List<KorttyEntry> entries() {
        List<KorttyEntry> entries = new ArrayList<>();
        for (Segment segment : segments) {
            if (segment instanceof KorttyEntry entry) {
                entries.add(entry);
            }
        }
        return entries;
    }
}
