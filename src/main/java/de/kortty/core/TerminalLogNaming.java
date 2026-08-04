package de.kortty.core;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names and opens the files a {@link TerminalLogger} writes.
 *
 * <p>Every file is called {@code <date>-<time>-<server>_<n>[.p<part>].<ext>}, for example
 * {@code 2026-08-04-14-30-12-web01_1.log}. The date leads so a directory listing sorts
 * chronologically, and {@code _<n>} tells apart connections that were open at the same moment.</p>
 *
 * <p>The sequence number is allocated by <em>creating</em> the file with
 * {@link StandardOpenOption#CREATE_NEW}, not by looking for a free name and then creating it.
 * The look-then-create version has a window between the two steps, and korTTY may well be running
 * twice against the same directory — a second instance, or a folder synced between machines.
 * {@code CREATE_NEW} is atomic at the filesystem, so it is correct across threads and processes
 * alike and needs no shared registry to be right.</p>
 */
public final class TerminalLogNaming {

    /** Colons are illegal in Windows file names, so the time is dash-separated like the date. */
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd-HH-mm-ss");

    private static final DateTimeFormatter DATE_PREFIX = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    /** Beyond this the directory is clearly not being used the way it was meant to be. */
    private static final int MAX_SEQUENCE = 999;

    /** Keeps the whole name well under the 255-byte limit even with a long slug. */
    private static final int MAX_SLUG_CHARS = 64;

    /**
     * Recognises korTTY's own compressed terminal logs, and nothing else.
     *
     * <p>This pattern <em>is</em> the ownership marker: the retention sweep deletes only files it
     * matches, so pointing the log directory at {@code ~/Documents} cannot cost the user anything.
     * Fixed-width date and time, a mandatory {@code _<digits>}, a known extension and a mandatory
     * {@code .gz} together make a false positive implausible.</p>
     */
    private static final Pattern OWNED_ARCHIVE = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2})-\\d{2}-\\d{2}-\\d{2}-.+_\\d+(?:\\.p\\d+)?\\.(?:log|txt|xml|json)\\.gz$");

    /** An opened log file together with the sequence number it ended up claiming. */
    public record Allocated(Path file, BufferedWriter writer, int sequence) {
    }

    private TerminalLogNaming() {
    }

    /**
     * The file-name-safe form of a connection's display name.
     *
     * <p>Delegates to {@link TerminalRecordingService#sanitizeFileName(String)} — that method also
     * produces journal directory names, so it must not be changed here; wrapping it keeps this
     * feature's extra rules local.</p>
     */
    public static String slug(String connectionName) {
        String sanitized = TerminalRecordingService.sanitizeFileName(connectionName);
        // Dots survive the sanitizer, so "..", "." and hidden-file names still have to go.
        sanitized = sanitized.replaceAll("^\\.+", "");
        if (sanitized.length() > MAX_SLUG_CHARS) {
            sanitized = sanitized.substring(0, MAX_SLUG_CHARS);
        }
        return sanitized.isBlank() ? "terminal" : sanitized;
    }

    /**
     * Creates the next free log file in {@code directory} and returns it with its writer.
     *
     * @param preferredSeq the number to try first; a session passes its current one so it keeps
     *                     the same number across a daily roll
     */
    public static Allocated open(Path directory, String slug, LocalDateTime stamp,
                                 String extension, int part, int preferredSeq) throws IOException {
        Files.createDirectories(directory);
        int first = Math.max(1, preferredSeq);
        for (int sequence = first; sequence <= MAX_SEQUENCE; sequence++) {
            Path candidate = directory.resolve(fileName(slug, stamp, extension, part, sequence));
            // A compressed sibling means the name is taken even though the plain file is gone.
            if (Files.exists(candidate.resolveSibling(
                candidate.getFileName() + SessionJournalLogCompressor.GZIP_SUFFIX))) {
                continue;
            }
            try {
                BufferedWriter writer = Files.newBufferedWriter(candidate, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
                return new Allocated(candidate, writer, sequence);
            } catch (FileAlreadyExistsException e) {
                // Someone else won the race for this number; take the next one.
            }
        }
        throw new IOException("No free terminal log name in " + directory
            + " after " + MAX_SEQUENCE + " attempts");
    }

    /** The file name for one specific slot; part 1 carries no part suffix. */
    public static String fileName(String slug, LocalDateTime stamp, String extension, int part, int sequence) {
        return STAMP.format(stamp) + "-" + slug + "_" + sequence
            + (part > 1 ? ".p" + part : "")
            + "." + extension;
    }

    /** Whether this is one of korTTY's own compressed terminal logs. */
    public static boolean isOwnedArchive(String fileName) {
        return fileName != null && OWNED_ARCHIVE.matcher(fileName).matches();
    }

    /**
     * The date encoded in an archive's name, or {@code null} when it is not one of ours.
     *
     * <p>Retention reads the age from here rather than from the file's modification time:
     * {@link SessionJournalLogCompressor#compress(Path)} writes a fresh {@code .gz} without
     * carrying the original timestamp over, so every archive would look brand new and nothing
     * would ever be swept.</p>
     */
    public static LocalDate archiveDate(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher matcher = OWNED_ARCHIVE.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        try {
            return LocalDate.parse(matcher.group(1), DATE_PREFIX);
        } catch (DateTimeParseException e) {
            return null; // digits in the right shape but not a real date (e.g. 2026-02-30)
        }
    }
}
