package de.kortty.memory.external;

import de.kortty.core.AiSecretGuard;
import de.kortty.memory.MemoryFormat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Reads a reference memory file and prepares its text for the prompt: invisible characters are removed and counted,
 * the front matter of a {@code .mdc} rule is removed, the secret guard checks the whole file, and the text sent is cut
 * to {@value #MAX_PROMPT_BYTES} bytes at a paragraph or line boundary.
 *
 * <p>The SHA-256 is that of the file's own bytes, because the user approves those. The parsed document is cached per
 * file and reused while its size and modification time stay the same, so a request re-reads nothing unchanged.</p>
 */
public final class ExternalMemoryReader {

    /** The most text of one file that a request sends. */
    public static final int MAX_PROMPT_BYTES = 16 * 1024;
    /** A larger file is not read further: it can never be approved, and a changed one is not sent. */
    public static final long MAX_FILE_BYTES = 1024L * 1024;
    private static final int CACHE_SIZE = 16;
    private static final String MDC_SUFFIX = ".mdc";
    private static final String FRONT_MATTER_FENCE = "---";

    /**
     * What a read found.
     *
     * @param promptText      the text a request may send, empty when the file is too large or binary
     * @param sha256          the digest of the file's bytes, or {@code null} when the file is too large
     * @param removedInvisible how many invisible characters were removed from the text
     * @param truncated       whether {@code promptText} is shorter than the file's text
     * @param secret          the first possible secret in the whole file, when there is one
     * @param tooLarge        whether the file is over {@value #MAX_FILE_BYTES} bytes
     * @param binary          whether the file holds a NUL byte and so is not text
     * @param fullText        the whole text without invisible characters, for the user to review before approving
     */
    public record Document(String promptText, String sha256, int removedInvisible, boolean truncated,
                           Optional<AiSecretGuard.Finding> secret, boolean tooLarge, boolean binary, String fullText) {

        public Document {
            promptText = promptText == null ? "" : promptText;
            secret = secret == null ? Optional.empty() : secret;
            fullText = fullText == null ? "" : fullText;
        }

        /** Whether the file is text of a size korTTY can read and approve. */
        public boolean readable() {
            return !tooLarge && !binary;
        }
    }

    /**
     * The invisible-character pass over a text.
     *
     * @param text    the text without invisible characters
     * @param removed how many characters were removed
     */
    public record Sanitized(String text, int removed) {
    }

    private record Cached(long size, FileTime modified, Document document) {
    }

    private final Map<Path, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Path, Cached> eldest) {
            return size() > CACHE_SIZE;
        }
    };

    /** The document for {@code file}, which must already be canonical; reads the file unless it is unchanged. */
    public Document read(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class);
        if (attributes.size() > MAX_FILE_BYTES) {
            return new Document("", null, 0, false, Optional.empty(), true, false, "");
        }
        Path key = file.toAbsolutePath().normalize();
        synchronized (cache) {
            Cached cached = cache.get(key);
            if (cached != null && cached.size() == attributes.size()
                && cached.modified().equals(attributes.lastModifiedTime())) {
                return cached.document();
            }
        }
        byte[] bytes = Files.readAllBytes(file);
        Document document = parse(bytes, file.getFileName().toString());
        synchronized (cache) {
            cache.put(key, new Cached(attributes.size(), attributes.lastModifiedTime(), document));
        }
        return document;
    }

    /** Builds the document of already read bytes. Public for tests and for callers that hold the bytes. */
    public static Document parse(byte[] bytes, String fileName) {
        if (containsNul(bytes)) {
            return new Document("", MemoryFormat.sha256(bytes), 0, false, Optional.empty(), false, true, "");
        }
        String raw = new String(bytes, StandardCharsets.UTF_8);
        Sanitized sanitized = sanitize(raw);
        Optional<AiSecretGuard.Finding> secret = AiSecretGuard.check(sanitized.text()).first();
        String body = fileName.toLowerCase(Locale.ROOT).endsWith(MDC_SUFFIX)
            ? stripFrontMatter(sanitized.text()) : sanitized.text();
        Capped capped = cap(body, MAX_PROMPT_BYTES);
        return new Document(capped.text(), MemoryFormat.sha256(bytes), sanitized.removed(), capped.truncated(),
            secret, false, false, sanitized.text());
    }

    /**
     * The text without invisible characters: control characters except tab and line breaks, zero-width and bidirectional
     * controls, line and paragraph separators, variation selectors and Unicode tag characters, which can carry text a
     * reader does not see.
     */
    public static Sanitized sanitize(String text) {
        StringBuilder kept = new StringBuilder(text.length());
        int removed = 0;
        int index = 0;
        while (index < text.length()) {
            int codePoint = text.codePointAt(index);
            if (isInvisible(codePoint)) {
                removed++;
            } else {
                kept.appendCodePoint(codePoint);
            }
            index += Character.charCount(codePoint);
        }
        return new Sanitized(kept.toString(), removed);
    }

    static boolean isInvisible(int codePoint) {
        if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r') {
            return false;
        }
        if (Character.getType(codePoint) == Character.CONTROL) {
            return true;
        }
        return (codePoint >= 0x200B && codePoint <= 0x200F)
            || (codePoint >= 0x2028 && codePoint <= 0x202E)
            || (codePoint >= 0x2066 && codePoint <= 0x2069)
            || codePoint == 0x2060
            || codePoint == 0xFEFF
            || codePoint == 0x061C
            || codePoint == 0x180E
            || (codePoint >= 0xFE00 && codePoint <= 0xFE0F)
            || (codePoint >= 0xE0000 && codePoint <= 0xE007F)
            || (codePoint >= 0xE0100 && codePoint <= 0xE01EF);
    }

    /** The text after a leading {@code ---} front matter block, or the text itself when there is none or it is not closed. */
    static String stripFrontMatter(String text) {
        int firstEnd = text.indexOf('\n');
        if (firstEnd < 0 || !FRONT_MATTER_FENCE.equals(text.substring(0, firstEnd).strip())) {
            return text;
        }
        int lineStart = firstEnd + 1;
        while (lineStart < text.length()) {
            int lineEnd = text.indexOf('\n', lineStart);
            String line = lineEnd < 0 ? text.substring(lineStart) : text.substring(lineStart, lineEnd);
            if (FRONT_MATTER_FENCE.equals(line.strip())) {
                return lineEnd < 0 ? "" : text.substring(lineEnd + 1);
            }
            if (lineEnd < 0) {
                break;
            }
            lineStart = lineEnd + 1;
        }
        return text;
    }

    /** The longest prefix of {@code text} within {@code maxBytes} in UTF-8, cut at a blank line or a line break when one fits. */
    static Capped cap(String text, int maxBytes) {
        if (utf8Length(text) <= maxBytes) {
            return new Capped(text, false);
        }
        int bytes = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = utf8Size(codePoint);
            if (bytes + size > maxBytes) {
                break;
            }
            bytes += size;
            end += Character.charCount(codePoint);
        }
        String prefix = text.substring(0, end);
        int paragraph = prefix.lastIndexOf("\n\n");
        int cut = paragraph > 0 ? paragraph : prefix.lastIndexOf('\n');
        if (cut > 0) {
            prefix = prefix.substring(0, cut);
        }
        return new Capped(prefix.stripTrailing(), true);
    }

    /** A text cut to the size limit. */
    record Capped(String text, boolean truncated) {
    }

    private static boolean containsNul(byte[] bytes) {
        for (byte value : bytes) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static int utf8Size(int codePoint) {
        if (codePoint < 0x80) {
            return 1;
        }
        if (codePoint < 0x800) {
            return 2;
        }
        return codePoint < 0x10000 ? 3 : 4;
    }
}
