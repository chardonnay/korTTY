package de.kortty.core;

import de.kortty.model.TerminalLogConfig;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
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

    /**
     * Every file korTTY's logger could have written, plain or compressed. The greedy slug group
     * hands the <em>last</em> {@code _<digits>} to the sequence, so a slug like {@code web_2}
     * parses as intended. The {@code .gz} subset is exactly what {@link #OWNED_ARCHIVE} accepts,
     * which keeps the manager's listing and the retention sweep in agreement.
     */
    private static final Pattern ANY_LOG = Pattern.compile(
        "^(\\d{4}-\\d{2}-\\d{2})-(\\d{2})-(\\d{2})-(\\d{2})-(.+)_(\\d+)(?:\\.p(\\d+))?"
            + "\\.(log|txt|xml|json)(?:\\.(gz|zst|bz2))?$");

    /** The compression a log file's outer suffix names. */
    public enum Compression {
        NONE(""), GZIP(".gz"), ZSTD(".zst"), BZIP2(".bz2");

        public final String suffix;

        Compression(String suffix) {
            this.suffix = suffix;
        }

        /** By outer suffix; {@link #NONE} for anything else. */
        public static Compression ofName(String fileName) {
            if (fileName == null) {
                return NONE;
            }
            String lower = fileName.toLowerCase(Locale.ROOT);
            for (Compression candidate : values()) {
                if (candidate != NONE && lower.endsWith(candidate.suffix)) {
                    return candidate;
                }
            }
            return NONE;
        }
    }

    /**
     * What a log file's name says about it. {@code startedAt} is when this part was opened —
     * each {@code .p<n>} part carries its own stamp.
     */
    public record ParsedName(LocalDateTime startedAt, String slug, int sequence, int part,
                             TerminalLogConfig.LogFormat format, Compression compression) {
    }

    /** An opened log file together with the sequence number it ended up claiming. */
    public record Allocated(Path file, BufferedWriter writer, int sequence) {
    }

    /** Matches the hidden lock sidecar a logger keeps beside the file it writes. */
    private static final Pattern LOCK_FILE = Pattern.compile("^\\..+\\.lock$");

    private TerminalLogNaming() {
    }

    /**
     * The hidden sidecar a logger locks while it writes {@code logFile}: {@code .<name>.lock} in
     * the same folder. The lock sits on a file of its own, not on the log, because POSIX drops a
     * process's locks on a file as soon as it closes <em>any</em> descriptor of that file — the
     * viewer and the liveness probe both open and close the log, and would silently unlock it.
     */
    public static Path lockFileFor(Path logFile) {
        return logFile.resolveSibling("." + logFile.getFileName() + ".lock");
    }

    /** Whether {@code fileName} is a lock sidecar ({@code .<name>.lock}). */
    public static boolean isLockFile(String fileName) {
        return fileName != null && LOCK_FILE.matcher(fileName).matches();
    }

    /** The log file a lock sidecar belongs to, by name, or {@code null} for any other name. */
    public static String lockedFileName(String lockFileName) {
        return isLockFile(lockFileName) ? lockFileName.substring(1, lockFileName.length() - ".lock".length()) : null;
    }

    /**
     * Reads a file name korTTY's logger produced, or {@code null} for any other file.
     *
     * <p>The date resolves the way {@link #archiveDate(String)} resolves it, so the two agree on
     * which {@code .gz} files are korTTY's; an impossible month or day is not ours. An impossible
     * time of day (the sweep never looks at it) is read as midnight rather than rejecting a file
     * the sweep would still delete.</p>
     */
    public static ParsedName parse(String fileName) {
        if (fileName == null) {
            return null;
        }
        Matcher matcher = ANY_LOG.matcher(fileName);
        if (!matcher.matches()) {
            return null;
        }
        LocalDate date;
        try {
            date = LocalDate.parse(matcher.group(1), DATE_PREFIX);
        } catch (DateTimeParseException e) {
            return null;
        }
        LocalTime time;
        try {
            time = LocalTime.of(Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3)),
                Integer.parseInt(matcher.group(4)));
        } catch (java.time.DateTimeException | NumberFormatException e) {
            time = LocalTime.MIDNIGHT;
        }
        int sequence = saturatingInt(matcher.group(6));
        int part = matcher.group(7) != null ? saturatingInt(matcher.group(7)) : 1;
        TerminalLogConfig.LogFormat format = switch (matcher.group(8)) {
            case "xml" -> TerminalLogConfig.LogFormat.XML;
            case "json" -> TerminalLogConfig.LogFormat.JSON;
            default -> TerminalLogConfig.LogFormat.PLAIN_TEXT;
        };
        Compression compression = matcher.group(9) != null
            ? Compression.ofName("." + matcher.group(9)) : Compression.NONE;
        return new ParsedName(LocalDateTime.of(date, time), matcher.group(5), sequence, part,
            format, compression);
    }

    /** Digits only (the regex guarantees it); absurdly long runs saturate instead of throwing. */
    private static int saturatingInt(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
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
     * <p>While the writer is open, a <em>shared</em> lock is held on a hidden sidecar next to the
     * file ({@link #lockFileFor}). It costs nothing here and lets another korTTY instance (the
     * Logfile Manager's {@link TerminalLogLiveness}) tell that the file is still being written
     * before it deletes or exports it. Closing the writer releases the lock and removes the
     * sidecar. Shared, so nothing is ever blocked by it — on Windows locks are mandatory.</p>
     *
     * @param preferredSeq the number to try first; a session passes its current one so it keeps
     *                     the same number across a daily roll
     */
    public static Allocated open(Path directory, String slug, LocalDateTime stamp,
                                 String extension, int part, int preferredSeq) throws IOException {
        boolean fresh = !Files.isDirectory(directory);
        Files.createDirectories(directory);
        if (fresh) {
            // Terminal output is not redacted beyond known secrets, so a directory korTTY created
            // itself is kept private. A directory the user chose is left as they set it up.
            restrictToOwner(directory, "rwx------");
        }
        int first = Math.max(1, preferredSeq);
        for (int sequence = first; sequence <= MAX_SEQUENCE; sequence++) {
            Path candidate = directory.resolve(fileName(slug, stamp, extension, part, sequence));
            // A compressed sibling means the name is taken even though the plain file is gone.
            if (Files.exists(candidate.resolveSibling(
                candidate.getFileName() + SessionJournalLogCompressor.GZIP_SUFFIX))) {
                continue;
            }
            try {
                Writer plain = new OutputStreamWriter(Files.newOutputStream(candidate,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), StandardCharsets.UTF_8);
                restrictToOwner(candidate, "rw-------");
                return new Allocated(candidate, new LockedWriter(plain, holdLock(candidate)), sequence);
            } catch (FileAlreadyExistsException e) {
                // Someone else won the race for this number; take the next one.
            }
        }
        throw new IOException("No free terminal log name in " + directory
            + " after " + MAX_SEQUENCE + " attempts");
    }

    /** The sidecar lock of an open log: its channel (the lock lives as long as it) and its path. */
    private record SidecarLock(FileChannel channel, Path file) {

        void release() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // The lock is gone with the channel either way.
            }
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
                // A stale sidecar is harmless: with nobody holding it, the probe reads "not live".
            }
        }
    }

    /** Takes the shared lock on the sidecar; {@code null} when the file system will not play along. */
    private static SidecarLock holdLock(Path logFile) {
        Path lockFile = lockFileFor(logFile);
        try {
            // READ as well: a shared lock needs a readable channel.
            FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                StandardOpenOption.READ, StandardOpenOption.WRITE);
            restrictToOwner(lockFile, "rw-------");
            try {
                channel.lock(0L, Long.MAX_VALUE, true);
            } catch (IOException | java.nio.channels.OverlappingFileLockException e) {
                // Best effort: the sidecar still marks the file as open for a probe that finds it.
            }
            return new SidecarLock(channel, lockFile);
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** The log's writer; closing it also gives up the sidecar lock and removes the sidecar. */
    private static final class LockedWriter extends BufferedWriter {

        private final SidecarLock lock;

        LockedWriter(Writer out, SidecarLock lock) {
            super(out);
            this.lock = lock;
        }

        @Override
        public void close() throws IOException {
            try {
                super.close();
            } finally {
                if (lock != null) {
                    lock.release();
                }
            }
        }
    }

    /** Re-applies owner-only mode to a compressed archive, which the compressor creates anew. */
    static void restrictArchiveToOwner(Path archive) {
        if (archive != null) {
            restrictToOwner(archive, "rw-------");
        }
    }

    /** Owner-only permissions where the filesystem has them; a no-op on Windows and on FAT. */
    private static void restrictToOwner(Path path, String permissions) {
        try {
            Files.setPosixFilePermissions(path,
                java.nio.file.attribute.PosixFilePermissions.fromString(permissions));
        } catch (UnsupportedOperationException | IOException e) {
            // Best effort: the log is still written, it is just not locked down.
        }
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
     * {@link SessionJournalLogCompressor#compressGzip(Path)} writes a fresh {@code .gz} without
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
            // SMART resolution: a day past the end of its month (2026-02-30) is clamped to the
            // last day, so only an impossible month or day (13, 32) ends up here.
            return LocalDate.parse(matcher.group(1), DATE_PREFIX);
        } catch (DateTimeParseException e) {
            return null; // digits in the right shape but not a real date (e.g. month 13)
        }
    }
}
