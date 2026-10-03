package de.kortty.ui.sftp;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * What a drag of remote rows in an SFTP Manager tab carries besides any files prepared for the
 * desktop: the tab it started in and the dragged entries. Only that tab can act on it, because only
 * its session reaches those paths; another tab (possibly another server) treats the drag as if the
 * entries were not there and falls back to the prepared files, if any.
 *
 * <p>The text form is the tab id on the first line, then one line per entry: {@code D} (folder) or
 * {@code F} (file), a tab character and the path. Backslashes and line breaks in a path are
 * escaped; a tab in a path needs no escape, as each line is split at its first tab only.
 */
public final class SftpDragPayload {

    private static final char DIRECTORY = 'D';
    private static final char FILE = 'F';

    private SftpDragPayload() {
    }

    /** A dragged remote entry: its absolute path and whether it is a folder. */
    public record Entry(String path, boolean directory) {
        public Entry {
            Objects.requireNonNull(path, "path");
        }

        /** The last path segment, the name the entry gets where it is dropped. */
        public String name() {
            String trimmed = path;
            while (trimmed.length() > 1 && trimmed.endsWith("/")) {
                trimmed = trimmed.substring(0, trimmed.length() - 1);
            }
            int slash = trimmed.lastIndexOf('/');
            return slash < 0 ? trimmed : trimmed.substring(slash + 1);
        }
    }

    /**
     * The payload text for {@code entries} dragged from the tab {@code tabId}.
     *
     * @throws IllegalArgumentException when the tab id is blank or spans several lines
     */
    public static String encode(String tabId, List<Entry> entries) {
        if (tabId == null || tabId.isBlank() || tabId.indexOf('\n') >= 0 || tabId.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("The tab id must be a single non-blank line");
        }
        StringBuilder text = new StringBuilder(tabId);
        for (Entry entry : entries) {
            text.append('\n')
                .append(entry.directory() ? DIRECTORY : FILE)
                .append('\t')
                .append(escape(entry.path()));
        }
        return text.toString();
    }

    /**
     * The entries of {@code payload} if it was dragged from the tab {@code expectedTabId}; empty for
     * another tab's drag, for a payload without entries and for text that is not a payload.
     */
    public static Optional<List<Entry>> decode(String payload, String expectedTabId) {
        if (payload == null || expectedTabId == null) {
            return Optional.empty();
        }
        String[] lines = payload.split("\n", -1);
        if (!lines[0].equals(expectedTabId) || lines.length < 2) {
            return Optional.empty();
        }
        List<Entry> entries = new ArrayList<>(lines.length - 1);
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            if (line.length() < 3 || line.charAt(1) != '\t'
                    || (line.charAt(0) != DIRECTORY && line.charAt(0) != FILE)) {
                return Optional.empty();
            }
            String path = unescape(line.substring(2));
            if (path == null || path.isEmpty()) {
                return Optional.empty();
            }
            entries.add(new Entry(path, line.charAt(0) == DIRECTORY));
        }
        return Optional.of(List.copyOf(entries));
    }

    private static String escape(String path) {
        StringBuilder escaped = new StringBuilder(path.length());
        for (int i = 0; i < path.length(); i++) {
            char c = path.charAt(i);
            switch (c) {
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                default -> escaped.append(c);
            }
        }
        return escaped.toString();
    }

    /** The path with its escapes resolved, or {@code null} for a broken escape. */
    private static String unescape(String text) {
        StringBuilder path = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\\') {
                path.append(c);
                continue;
            }
            if (++i >= text.length()) {
                return null;
            }
            switch (text.charAt(i)) {
                case '\\' -> path.append('\\');
                case 'n' -> path.append('\n');
                case 'r' -> path.append('\r');
                default -> {
                    return null;
                }
            }
        }
        return path.toString();
    }
}
