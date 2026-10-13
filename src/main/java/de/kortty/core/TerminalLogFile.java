package de.kortty.core;

import de.kortty.model.TerminalLogConfig;

import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * One terminal log file as the Logfile Manager lists it.
 *
 * @param path          the file, absolute and normalized (the logical path, as the logger used it)
 * @param directory     the log folder it was found in (real path)
 * @param parsed        what the name says: start, slug, sequence, part, format, compression
 * @param sizeBytes     size on disk — the compressed size for an archive
 * @param modified      last modification; for a gzipped log that is the moment it was closed,
 *                      because the compressor writes a fresh file
 * @param deletableFrom the first day the retention sweep may delete it, or {@code null} when it
 *                      never expires (plain files, zstd/bzip2 exports, folders without retention)
 * @param live          still being written — by this process or, as far as can be told, another
 */
public record TerminalLogFile(Path path, Path directory, TerminalLogNaming.ParsedName parsed,
                              long sizeBytes, FileTime modified, LocalDate deletableFrom, boolean live,
                              String connectionName, String host, java.util.List<de.kortty.model.TerminalLogBadge> badges,
                              boolean badgeError) {

    public TerminalLogFile {
        badges = java.util.List.copyOf(badges);
    }

    public TerminalLogFile(Path path, Path directory, TerminalLogNaming.ParsedName parsed,
                           long sizeBytes, FileTime modified, LocalDate deletableFrom, boolean live) {
        this(path, directory, parsed, sizeBytes, modified, deletableFrom, live, parsed.slug(), "", java.util.List.of(), false);
    }
    public boolean held() { return badges.stream().anyMatch(de.kortty.model.TerminalLogBadge::hold); }
    public String recordedAddress() {
        return host.isBlank() && TerminalLogIdentity.isAddress(connectionName) ? connectionName : host;
    }
    public String displayConnectionName() { return TerminalLogIdentity.displayName(connectionName, host); }


    private static final double MEBIBYTE = 1024.0 * 1024.0;

    public String name() {
        return path.getFileName().toString();
    }

    /** When this part was opened, from the name. */
    public LocalDateTime startedAt() {
        return parsed.startedAt();
    }

    /** The last moment anything was written: the modification time, or now for a live file. */
    public LocalDateTime endedAt() {
        if (live || modified == null) {
            return LocalDateTime.now();
        }
        return LocalDateTime.ofInstant(modified.toInstant(), ZoneId.systemDefault());
    }

    public TerminalLogConfig.LogFormat format() {
        return parsed.format();
    }

    public TerminalLogNaming.Compression compression() {
        return parsed.compression();
    }

    /** Size in MiB, the unit the connection dialog's size limit uses as well. */
    public double sizeMb() {
        return sizeBytes / MEBIBYTE;
    }
}
