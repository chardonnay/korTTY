package de.kortty.core;

import org.apache.sshd.sftp.client.SftpClient;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Writes a {@link SnippetFolderLayout} into a terminal's working directory — over SFTP for SSH
 * sessions, directly on disk for local shells — with every file's mode set from its executable
 * flag ({@code 0755} / {@code 0644}, directories {@code 0755}). Content is written with LF line
 * endings and without a BOM, exactly as the folder export does. FX-free: progress and
 * cancellation are passed in.
 */
public final class SnippetTreeTransferService {

    /** What to do with files that already exist at the target. */
    public enum ConflictPolicy { OVERWRITE, SKIP }

    /** Told about each file before it is written ({@code done} counts the files finished so far). */
    @FunctionalInterface
    public interface ProgressListener {
        void onFile(int done, int total, String relativePath);
    }

    /** The outcome: written and skipped files, and whether the run was cancelled. */
    public record Result(String targetDirectory, List<String> written, List<String> skipped, boolean cancelled) {
        public Result {
            written = List.copyOf(written);
            skipped = List.copyOf(skipped);
        }
    }

    private SnippetTreeTransferService() {
    }

    // ---- SFTP ----

    /** The files of {@code layout} that already exist below {@code targetDirectory}. */
    public static List<String> existingRemote(SftpClient sftp, String targetDirectory, SnippetFolderLayout layout)
            throws IOException {
        List<String> existing = new ArrayList<>();
        for (SnippetFolderLayout.Entry entry : layout.entries()) {
            if (RemotePathSupport.exists(sftp, RemotePathSupport.appendRemotePath(targetDirectory, entry.relativePath()))) {
                existing.add(entry.relativePath());
            }
        }
        return existing;
    }

    /** Uploads {@code layout} below {@code targetDirectory} and sets the modes. */
    public static Result uploadRemote(SftpClient sftp, String targetDirectory, SnippetFolderLayout layout,
                                      ConflictPolicy policy, ProgressListener listener, BooleanSupplier cancelled)
            throws IOException {
        Objects.requireNonNull(sftp, "sftp");
        Objects.requireNonNull(layout, "layout");
        Set<String> existing = policy == ConflictPolicy.SKIP
            ? Set.copyOf(existingRemote(sftp, targetDirectory, layout)) : Set.of();
        for (String directory : layout.directories()) {
            if (isCancelled(cancelled)) {
                return new Result(targetDirectory, List.of(), List.of(), true);
            }
            String remote = RemotePathSupport.appendRemotePath(targetDirectory, directory);
            boolean created = !RemotePathSupport.exists(sftp, remote);
            RemotePathSupport.mkdirs(sftp, remote);
            if (created) {
                setRemoteMode(sftp, remote, SnippetExecutableSupport.EXECUTABLE_MODE);
            }
        }
        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int total = layout.entries().size();
        int done = 0;
        for (SnippetFolderLayout.Entry entry : layout.entries()) {
            if (isCancelled(cancelled)) {
                return new Result(targetDirectory, written, skipped, true);
            }
            notify(listener, done, total, entry.relativePath());
            if (existing.contains(entry.relativePath())) {
                skipped.add(entry.relativePath());
            } else {
                String remote = RemotePathSupport.appendRemotePath(targetDirectory, entry.relativePath());
                RemotePathSupport.mkdirs(sftp, RemotePathSupport.parentRemotePath(remote));
                byte[] bytes = SnippetManager.fileContent(entry.snippet()).getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = sftp.write(remote, EnumSet.of(
                        SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate))) {
                    out.write(bytes);
                }
                setRemoteMode(sftp, remote, entry.mode());
                written.add(entry.relativePath());
            }
            done++;
        }
        notify(listener, done, total, "");
        return new Result(targetDirectory, written, skipped, false);
    }

    private static void setRemoteMode(SftpClient sftp, String remotePath, int mode) throws IOException {
        SftpClient.Attributes attributes = new SftpClient.Attributes();
        attributes.setPermissions(mode);
        sftp.setStat(remotePath, attributes);
    }

    // ---- Local file system ----

    /** The files of {@code layout} that already exist below {@code targetDirectory}. */
    public static List<String> existingLocal(Path targetDirectory, SnippetFolderLayout layout) throws IOException {
        Path root = targetDirectory.toAbsolutePath().normalize();
        List<String> existing = new ArrayList<>();
        for (SnippetFolderLayout.Entry entry : layout.entries()) {
            if (Files.exists(resolveInside(root, entry.relativePath()), LinkOption.NOFOLLOW_LINKS)) {
                existing.add(entry.relativePath());
            }
        }
        return existing;
    }

    /** Writes {@code layout} below the local {@code targetDirectory} and sets the POSIX modes. */
    public static Result writeLocal(Path targetDirectory, SnippetFolderLayout layout, ConflictPolicy policy,
                                    ProgressListener listener, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(targetDirectory, "targetDirectory");
        Objects.requireNonNull(layout, "layout");
        Path root = targetDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IOException("Not a directory: " + root);
        }
        Set<String> existing = policy == ConflictPolicy.SKIP ? Set.copyOf(existingLocal(root, layout)) : Set.of();
        for (String directory : layout.directories()) {
            if (isCancelled(cancelled)) {
                return new Result(root.toString(), List.of(), List.of(), true);
            }
            Path dir = resolveInside(root, directory);
            if (!Files.isDirectory(dir)) {
                Files.createDirectories(dir);
                SnippetManager.applyPosixMode(dir, SnippetExecutableSupport.EXECUTABLE_MODE);
            }
        }
        List<String> written = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int total = layout.entries().size();
        int done = 0;
        for (SnippetFolderLayout.Entry entry : layout.entries()) {
            if (isCancelled(cancelled)) {
                return new Result(root.toString(), written, skipped, true);
            }
            notify(listener, done, total, entry.relativePath());
            if (existing.contains(entry.relativePath())) {
                skipped.add(entry.relativePath());
            } else {
                Path target = resolveInside(root, entry.relativePath());
                if (target.getParent() != null) {
                    Files.createDirectories(target.getParent());
                }
                Files.writeString(target, SnippetManager.fileContent(entry.snippet()), StandardCharsets.UTF_8);
                SnippetManager.applyPosixMode(target, entry.mode());
                written.add(entry.relativePath());
            }
            done++;
        }
        notify(listener, done, total, "");
        return new Result(root.toString(), written, skipped, false);
    }

    private static Path resolveInside(Path root, String relative) throws IOException {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root) || resolved.equals(root)) {
            throw new IOException("Unsafe target path: " + relative);
        }
        return resolved;
    }

    private static boolean isCancelled(BooleanSupplier cancelled) {
        return cancelled != null && cancelled.getAsBoolean();
    }

    private static void notify(ProgressListener listener, int done, int total, String path) {
        if (listener != null) {
            listener.onFile(done, total, path);
        }
    }
}
