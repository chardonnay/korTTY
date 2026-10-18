package de.kortty.core.sftp;

import de.kortty.core.RemotePathSupport;
import de.kortty.core.remote.search.RemoteTreeReader;
import de.kortty.core.sftp.FolderComparison.Entry;
import org.apache.sshd.sftp.client.SftpClient;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/**
 * Reads everything below a local or a server folder for {@link FolderComparison}: files and
 * folders with size and modification time, paths relative to the folder. Symbolic links are left
 * out on both sides (a transfer never follows a linked folder either), as is whatever
 * {@code excluded} matches, folders with all they contain. Off the FX thread.
 */
public final class FolderSnapshots {

    /** More entries than this on one side stop the comparison instead of exhausting memory. */
    public static final int MAX_ENTRIES = 100_000;

    /** The comparison was stopped, or a side has more than {@link #MAX_ENTRIES} entries. */
    public static final class StoppedException extends IOException {
        private final boolean tooMany;

        StoppedException(String message, boolean tooMany) {
            super(message);
            this.tooMany = tooMany;
        }

        /** True when it stopped at {@link #MAX_ENTRIES}, false when it was cancelled. */
        public boolean tooMany() {
            return tooMany;
        }
    }

    /** Reports how many entries were read so far; called now and then from the reading thread. */
    @FunctionalInterface
    public interface Progress {
        void entries(int count);
    }

    private FolderSnapshots() {
    }

    /** Everything below the local folder {@code root}. */
    public static List<Entry> local(Path root, Predicate<String> excluded, BooleanSupplier cancelled,
                                    Progress progress) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Deque<String> folders = new ArrayDeque<>();
        folders.add("");
        while (!folders.isEmpty()) {
            String folder = folders.poll();
            Path directory = folder.isEmpty() ? root : root.resolve(folder);
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
                for (Path child : stream) {
                    check(cancelled, entries.size());
                    String relative = folder.isEmpty()
                        ? String.valueOf(child.getFileName())
                        : folder + "/" + child.getFileName();
                    if (excluded.test(relative)) {
                        continue;
                    }
                    BasicFileAttributes attributes =
                        Files.readAttributes(child, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                    if (attributes.isSymbolicLink() || attributes.isOther()) {
                        continue;
                    }
                    entries.add(new Entry(relative, attributes.isDirectory(), attributes.size(),
                        attributes.lastModifiedTime().toMillis()));
                    if (attributes.isDirectory()) {
                        folders.add(relative);
                    }
                    report(progress, entries.size());
                }
            }
        }
        return entries;
    }

    /** Everything below the server folder {@code root}, read through {@code reader}. */
    public static List<Entry> remote(RemoteTreeReader reader, String root, Predicate<String> excluded,
                                     BooleanSupplier cancelled, Progress progress) throws IOException {
        List<Entry> entries = new ArrayList<>();
        Deque<String> folders = new ArrayDeque<>();
        folders.add("");
        while (!folders.isEmpty()) {
            String folder = folders.poll();
            String directory = folder.isEmpty() ? root : RemotePathSupport.appendRemotePath(root, folder);
            for (SftpClient.DirEntry child : reader.list(directory)) {
                check(cancelled, entries.size());
                String name = child.getFilename();
                if (name == null || name.isEmpty() || ".".equals(name) || "..".equals(name)) {
                    continue;
                }
                String relative = folder.isEmpty() ? name : folder + "/" + name;
                if (excluded.test(relative)) {
                    continue;
                }
                SftpClient.Attributes attributes = child.getAttributes();
                if (attributes == null || attributes.isSymbolicLink()
                        || !(attributes.isDirectory() || attributes.isRegularFile())) {
                    continue;
                }
                FileTime modified = attributes.getModifyTime();
                entries.add(new Entry(relative, attributes.isDirectory(), attributes.getSize(),
                    modified == null ? 0 : modified.toMillis()));
                if (attributes.isDirectory()) {
                    folders.add(relative);
                }
                report(progress, entries.size());
            }
        }
        return entries;
    }

    private static void check(BooleanSupplier cancelled, int count) throws StoppedException {
        if (cancelled.getAsBoolean()) {
            throw new StoppedException("cancelled", false);
        }
        if (count >= MAX_ENTRIES) {
            throw new StoppedException("more than " + MAX_ENTRIES + " entries", true);
        }
    }

    private static void report(Progress progress, int count) {
        if (progress != null && count % 250 == 0) {
            progress.entries(count);
        }
    }
}
