package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalLogConfig;
import de.kortty.model.TerminalLogKnownFolder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.DirectoryIteratorException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Logfile Manager's view of the terminal log folders: which folders to list, the retention each
 * one is swept with, and which of their files are korTTY's and still being written.
 *
 * <p>Retention is the reason this is more than a directory listing. {@link TerminalLogRetention}
 * sweeps one folder with one retention, but several connections can share a folder while being
 * configured differently, and a folder can be remembered after its connection was deleted. The
 * manager must show the date a file would really be deleted, so it applies the sweep's own rule
 * to the most aggressive retention any enabled connection or remembered folder asks for. Disabled
 * and incognito connections write nothing new, so they do not get a say in how long files live.</p>
 *
 * <p>Folders are keyed by their real path. Two connections can reach one folder through a symlink
 * or a mounted alias, and listing it twice would show every file twice, with two possibly
 * different retentions.</p>
 */
public final class TerminalLogCatalog {

    private static final Logger logger = LoggerFactory.getLogger(TerminalLogCatalog.class);

    /** Newest first; within one start time the lower sequence and part come first. */
    private static final Comparator<TerminalLogFile> NEWEST_FIRST = Comparator
        .comparing(TerminalLogFile::startedAt).reversed()
        .thenComparingInt(file -> file.parsed().sequence())
        .thenComparingInt(file -> file.parsed().part())
        .thenComparing(TerminalLogFile::name);

    /**
     * One folder in the listing.
     *
     * @param path          the real path, the key used to tell folders apart
     * @param display       the logical path as configured, used to name the files
     * @param retentionDays the sweep's retention for this folder, or {@code null} when no enabled
     *                      connection or remembered folder asks for one
     * @param manual        listed only because the user added it by hand
     */
    public record Folder(Path path, Path display, Integer retentionDays, boolean manual) {
    }

    private TerminalLogCatalog() {
    }

    /**
     * The folders to list: the default folder, every saved connection's folder and every remembered
     * folder, deduplicated by real path.
     *
     * <p>The default folder is listed even before anything was written there. It is where new logs
     * go, so the manager has to show it, and an empty listing is the honest answer. Every other
     * folder that does not exist is skipped, because listing it would only invite creating it.</p>
     */
    public static List<Folder> folders(Collection<ServerConnection> connections, List<TerminalLogKnownFolder> known) {
        Map<Path, Builder> byKey = new LinkedHashMap<>();

        Path defaultDir = TerminalLogger.defaultDirectory();
        Builder defaults = new Builder(defaultDir);
        defaults.listedBy(false);
        byKey.put(TerminalLogger.realOrNormalized(defaultDir), defaults);

        if (connections != null) {
            for (ServerConnection connection : connections) {
                if (connection == null) {
                    continue;
                }
                TerminalLogConfig config = connection.getLogConfig();
                Builder builder = builderFor(byKey, TerminalLogger.resolveDirectory(config));
                if (builder == null) {
                    continue;
                }
                builder.listedBy(false);
                // A connection that will not write any more must not shorten or extend the life
                // of what it already wrote; the folder still shows, just without a retention.
                if (config != null && config.isEnabled() && !connection.isIncognito()) {
                    builder.retain(config.getRetentionDays());
                }
            }
        }

        if (known != null) {
            for (TerminalLogKnownFolder entry : known) {
                if (entry == null) {
                    continue;
                }
                Path dir = normalizedOrNull(entry.getPath());
                Builder builder = dir != null ? builderFor(byKey, dir) : null;
                if (builder == null) {
                    continue;
                }
                builder.listedBy(entry.isManual());
                builder.retain(entry.getRetentionDays());
            }
        }

        List<Folder> folders = new ArrayList<>(byKey.size());
        for (Map.Entry<Path, Builder> item : byKey.entrySet()) {
            folders.add(item.getValue().toFolder(item.getKey()));
        }
        return List.copyOf(folders);
    }

    /**
     * Lists the korTTY log files in the folders, newest first.
     *
     * <p>Blocking file I/O, so call it off the FX thread. One folder that cannot be read is logged
     * at debug and left out; it must not hide the logs in every other folder.</p>
     *
     * <p>{@code today} is the day the caller judges expiry against. The listing does not need it
     * (each file's deletable date is computed from its name), so it is accepted to keep the
     * listing and the sweep driven by the same clock.</p>
     */
    public static List<TerminalLogFile> scan(Collection<Folder> folders, LocalDate today, Instant now) throws IOException {
        List<TerminalLogFile> files = new ArrayList<>();
        if (folders != null) {
            for (Folder folder : folders) {
                if (folder == null) {
                    continue;
                }
                try {
                    files.addAll(scanFolder(folder, now));
                } catch (IOException | DirectoryIteratorException e) {
                    logger.debug("Skipping terminal log folder {}: {}", folder.path(), e.getMessage());
                }
            }
        }
        files.sort(NEWEST_FIRST);
        return files;
    }

    /**
     * The first day the retention sweep may delete a file, or {@code null} when it never expires.
     *
     * <p>Mirrors {@link TerminalLogRetention#sweep}, which deletes an archive once its date is before
     * {@code today - R}. That is first true on {@code date + R + 1}, so the listing shows the day
     * the sweep would actually act, not the day the window closes.</p>
     */
    static LocalDate deletableFrom(String name, Integer retention) {
        LocalDate archived = TerminalLogNaming.archiveDate(name);
        if (archived == null || retention == null || retention <= 0) {
            return null;
        }
        return archived.plusDays(retention + 1L);
    }

    /**
     * Remembers a folder the logger wrote into, or refreshes its retention and last-used time.
     *
     * <p>The remembered list is what keeps Quick Connect sessions and deleted connections findable.
     * Only a new entry or a changed retention is saved: the last-used time is a bookkeeping hint,
     * and writing the whole settings file for it on every connect would be needless disk work.</p>
     *
     * @return whether the remembered list changed and was therefore saved
     */
    public static boolean rememberFolder(GlobalSettingsManager settings, Path dir, int retentionDays) {
        GlobalSettings current = settingsOf(settings);
        if (current == null || dir == null) {
            return false;
        }
        boolean changed = upsert(current, dir, retentionDays);
        if (changed) {
            saveInBackground(settings);
        }
        return changed;
    }

    /**
     * Forgets a remembered folder. The folder and its files are left alone; only the entry goes.
     *
     * @return whether an entry was removed and the change saved
     */
    public static boolean forgetFolder(GlobalSettingsManager settings, Path dir) {
        GlobalSettings current = settingsOf(settings);
        if (current == null || dir == null) {
            return false;
        }
        boolean removed = remove(current, dir);
        if (removed) {
            saveInBackground(settings);
        }
        return removed;
    }

    // ---- Folder construction ----

    /** Returns the builder for a folder's real path, creating it only for a folder that exists. */
    private static Builder builderFor(Map<Path, Builder> byKey, Path dir) {
        if (dir == null) {
            return null;
        }
        Path key = TerminalLogger.realOrNormalized(dir);
        Builder builder = byKey.get(key);
        if (builder == null && Files.isDirectory(dir)) {
            builder = new Builder(dir);
            byKey.put(key, builder);
        }
        return builder;
    }

    /** Accumulates how a folder came to be listed and the retention it is swept with. */
    private static final class Builder {
        private final Path display;
        private Integer retentionDays;
        private boolean listedOtherwise;
        private boolean listedByManualEntry;

        Builder(Path display) {
            this.display = display;
        }

        /** Records one reason the folder is listed. Only a manual known-folder entry is a reason the user, not a logger, is responsible for. */
        void listedBy(boolean manualEntry) {
            if (manualEntry) {
                listedByManualEntry = true;
            } else {
                listedOtherwise = true;
            }
        }

        /** The shortest positive retention wins: the folder is swept by the strictest request. */
        void retain(int days) {
            if (days > 0) {
                retentionDays = retentionDays == null ? days : Math.min(retentionDays, days);
            }
        }

        Folder toFolder(Path key) {
            return new Folder(key, display, retentionDays, listedByManualEntry && !listedOtherwise);
        }
    }

    // ---- Scanning ----

    private static List<TerminalLogFile> scanFolder(Folder folder, Instant now) throws IOException {
        Path directory = folder.path();
        if (!Files.isDirectory(directory)) {
            // Listed when the dialog opened, gone since: nothing to show, nothing to report.
            return List.of();
        }

        Map<String, BasicFileAttributes> owned = new HashMap<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                String name = entry.getFileName().toString();
                if (TerminalLogNaming.isLockFile(name)) {
                    removeOrphanLock(entry, name);
                    continue;
                }
                if (TerminalLogNaming.parse(name) == null) {
                    continue;
                }
                BasicFileAttributes attributes;
                try {
                    // NOFOLLOW_LINKS: a symlink to a log is not a log of this folder.
                    attributes = Files.readAttributes(entry, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                } catch (IOException e) {
                    logger.debug("Skipping unreadable terminal log {}: {}", name, e.getMessage());
                    continue;
                }
                if (attributes.isRegularFile()) {
                    owned.put(name, attributes);
                }
            }
        }

        List<TerminalLogFile> files = new ArrayList<>();
        for (Map.Entry<String, BasicFileAttributes> item : owned.entrySet()) {
            String name = item.getKey();
            BasicFileAttributes attributes = item.getValue();
            TerminalLogNaming.ParsedName parsed = TerminalLogNaming.parse(name);
            if (parsed.compression() == TerminalLogNaming.Compression.NONE
                && owned.containsKey(name + SessionJournalLogCompressor.GZIP_SUFFIX)) {
                // The logger is gzipping this file right now; the archive is the one to show.
                continue;
            }
            Path path = folder.display().resolve(name);
            // One rule for the listing, delete and export (TerminalLogLiveness) — including an
            // archive the logger is still producing from its plain sibling.
            boolean live = TerminalLogLiveness.isLive(path, parsed.compression(),
                attributes.lastModifiedTime(), now);
            Map<String, String> metadata = Map.of();
            try (var reader = new java.io.BufferedReader(new java.io.InputStreamReader(
                    TerminalLogCodecs.openInput(path), java.nio.charset.StandardCharsets.UTF_8))) {
                metadata = TerminalLogDocument.metadata(reader, parsed.format());
            } catch (IOException | RuntimeException e) {
                logger.debug("Cannot read terminal log metadata {}: {}", name, e.getMessage());
            }
            List<de.kortty.model.TerminalLogBadge> badges = List.of();
            boolean badgeError = false;
            try { badges = TerminalLogBadges.read(path); }
            catch (IOException e) {
                badgeError = true;
                logger.warn("Cannot read terminal log badges {}: {}", name, e.getMessage());
            }
            boolean held = badges.stream().anyMatch(de.kortty.model.TerminalLogBadge::hold);
            files.add(new TerminalLogFile(path, directory, parsed, attributes.size(),
                attributes.lastModifiedTime(), held || badgeError ? null : deletableFrom(name, folder.retentionDays()), live,
                metadata.getOrDefault("connection", parsed.slug()), metadata.getOrDefault("host", ""), badges, badgeError));
        }
        return files;
    }

    /**
     * Removes a lock sidecar a crashed logger left behind: its log is gone and nobody holds it.
     * Best effort — a sidecar that stays is harmless, it only ever says "not live".
     */
    private static void removeOrphanLock(Path lockFile, String name) {
        String logName = TerminalLogNaming.lockedFileName(name);
        if (logName == null || TerminalLogNaming.parse(logName) == null
            || Files.exists(lockFile.resolveSibling(logName))
            || TerminalLogLiveness.isLockHeld(lockFile)) {
            return;
        }
        try {
            Files.deleteIfExists(lockFile);
        } catch (IOException e) {
            logger.debug("Could not remove the stale lock sidecar {}: {}", name, e.getMessage());
        }
    }

    // ---- Remembered folders ----

    private static GlobalSettings settingsOf(GlobalSettingsManager settings) {
        return settings != null ? settings.getSettings() : null;
    }

    /**
     * Inserts or refreshes the entry for {@code dir}.
     *
     * @return whether the entry is new or its retention changed, which is what needs saving
     */
    static boolean upsert(GlobalSettings settings, Path dir, int retentionDays) {
        String key = normalizedKey(dir);
        int retention = Math.max(0, Math.min(retentionDays, GlobalSettings.MAX_LOG_RETENTION_DAYS));
        String now = Instant.now().toString();

        List<TerminalLogKnownFolder> updated = new ArrayList<>(settings.getTerminalLogKnownFolders());
        for (TerminalLogKnownFolder entry : updated) {
            if (entry != null && key.equals(entryKey(entry))) {
                boolean retentionChanged = entry.getRetentionDays() != retention;
                entry.setPath(key);
                entry.setRetentionDays(retention);
                entry.setLastUsed(now);
                settings.setTerminalLogKnownFolders(updated);
                return retentionChanged;
            }
        }
        updated.add(new TerminalLogKnownFolder(key, retention, now, false));
        settings.setTerminalLogKnownFolders(updated);
        return true;
    }

    /** Removes the entry for {@code dir}, if there is one. */
    static boolean remove(GlobalSettings settings, Path dir) {
        String key = normalizedKey(dir);
        List<TerminalLogKnownFolder> kept = new ArrayList<>();
        boolean removed = false;
        for (TerminalLogKnownFolder entry : settings.getTerminalLogKnownFolders()) {
            if (entry == null) {
                continue;
            }
            if (key.equals(entryKey(entry))) {
                removed = true;
            } else {
                kept.add(entry);
            }
        }
        if (removed) {
            settings.setTerminalLogKnownFolders(kept);
        }
        return removed;
    }

    /**
     * Saves without holding up the caller. A failure is logged, not thrown: the folder list is a
     * convenience, and a failed write must not break the connect that triggered it.
     */
    private static void saveInBackground(GlobalSettingsManager settings) {
        settings.saveAsync().exceptionally(failure -> {
            logger.warn("Could not save the remembered terminal log folders: {}", failure.getMessage());
            return null;
        });
    }

    private static String normalizedKey(Path dir) {
        return dir.toAbsolutePath().normalize().toString();
    }

    /** The normalized key of a stored entry, or {@code null} when the stored path is unusable. */
    private static String entryKey(TerminalLogKnownFolder entry) {
        Path dir = normalizedOrNull(entry.getPath());
        return dir != null ? dir.toString() : null;
    }

    private static Path normalizedOrNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Path.of(value).toAbsolutePath().normalize();
        } catch (InvalidPathException e) {
            return null;
        }
    }
}
