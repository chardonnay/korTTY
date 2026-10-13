package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonParseException;
import de.kortty.model.TerminalLogBadge;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/** Atomic, owner-only sidecars survive the plain-to-gzip rename without touching log contents. */
public final class TerminalLogBadges {
    private static final Gson GSON = new Gson();
    private TerminalLogBadges() { }

    public static Path sidecar(Path log) {
        String name = log.getFileName().toString();
        var compression = TerminalLogNaming.Compression.ofName(name);
        return log.resolveSibling("." + name.substring(0, name.length() - compression.suffix.length()) + ".badges.json");
    }
    public static List<TerminalLogBadge> read(Path log) throws IOException { return readStore(sidecar(log)); }
    public static void write(Path log, List<TerminalLogBadge> badges) throws IOException {
        synchronized (TerminalLogRetention.lockFor(log.toAbsolutePath().getParent())) {
            writeStore(sidecar(log), badges);
        }
    }
    public static boolean held(Path log) throws IOException { return read(log).stream().anyMatch(TerminalLogBadge::hold); }

    public static List<TerminalLogBadge> readStore(Path file) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) { return List.of(); }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 256 * 1024) {
            throw new IOException("Invalid badge store: " + file.getFileName());
        }
        try {
            TerminalLogBadge[] values = GSON.fromJson(Files.readString(file), TerminalLogBadge[].class);
            if (values == null || values.length > 256 || Arrays.stream(values).anyMatch(java.util.Objects::isNull)) {
                throw new IOException("Invalid badge list");
            }
            return List.copyOf(Arrays.asList(values));
        } catch (JsonParseException | IllegalArgumentException e) { throw new IOException("Unreadable badge store", e); }
    }

    public static void writeStore(Path file, List<TerminalLogBadge> badges) throws IOException {
        if (badges.size() > 256 || Files.isSymbolicLink(file)) { throw new IOException("Invalid badge store"); }
        AtomicFileWriter.writeStoreAtomically(file, GSON.toJson(badges), AtomicFileWriter.FileMode.OWNER_ONLY);
    }
}
