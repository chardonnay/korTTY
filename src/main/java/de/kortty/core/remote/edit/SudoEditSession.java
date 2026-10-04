package de.kortty.core.remote.edit;

import de.kortty.core.remote.RemoteCommandCancellation;
import de.kortty.core.remote.RemoteCommandException;
import de.kortty.core.remote.RemoteCommandRunner;
import de.kortty.core.remote.SudoCommand;
import de.kortty.ui.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

/**
 * One server file edited as root (D14, D15).
 *
 * <p>{@link #open} reads the file through {@code sudo cat} ({@link SudoEditCommands#read}) straight
 * into a private local folder from {@link RemoteEditTempDirs}, and records a baseline: the
 * {@code uid gid mode} line, the size and the SHA-256. {@link #upload()} first reads the file again
 * through sudo: another owner, mode, size or content means someone else changed it, and the upload
 * stops with a {@link RemoteEditSession.Conflict}. Otherwise the local bytes go to a root-owned
 * stage over stdin, are verified there and written into the target in place
 * ({@link SudoEditCommands#write}); the baseline moves on.
 *
 * <p>The sudo password, when one is needed, is a copy this session owns: it only ever goes to
 * {@link RemoteCommandRunner}, which sends it after sudo's prompt nonce, and {@link #close()} wipes
 * it. It is never part of a command, a log line or an exception text. Not thread-safe: the caller
 * runs one action at a time.
 */
public final class SudoEditSession implements RemoteEdit {

    private static final Logger logger = LoggerFactory.getLogger(SudoEditSession.class);

    /** The largest file that can be edited as root; it is held in memory for each upload. */
    public static final long MAX_FILE_BYTES = 64L * 1024 * 1024;
    /** The stat line is short; anything longer before the first newline is not ours. */
    private static final int MAX_HEADER = 256;
    private static final Duration TIMEOUT = Duration.ofMinutes(10);

    /** What the server had when korTTY last read or wrote it. */
    public record Baseline(String statLine, long size, String sha256) {
    }

    private final RemoteCommandRunner runner;
    private final String remotePath;
    private final Path folder;
    private final Path localFile;
    private char[] secret;
    private Baseline baseline;
    private RemoteEditSession.Conflict conflict;
    private int uploads;
    private Instant lastUpload;
    private boolean hashChecked = true;
    private boolean closed;

    private SudoEditSession(RemoteCommandRunner runner, String remotePath, Path folder, Path localFile,
                            char[] secret, Baseline baseline) {
        this.runner = runner;
        this.remotePath = remotePath;
        this.folder = folder;
        this.localFile = localFile;
        this.secret = secret;
        this.baseline = baseline;
    }

    /**
     * Reads {@code remotePath} as root into a new private folder below {@code tempRoot}.
     *
     * @param secret the sudo password, or empty when sudo needs none; the session keeps its own
     *     copy, so the caller wipes its array as soon as this returns
     * @throws IOException when sudo refuses, the path is not a regular file, or the file is larger
     *     than {@link #MAX_FILE_BYTES} (the folder is removed again then)
     */
    public static SudoEditSession open(RemoteCommandRunner runner, String remotePath, Optional<char[]> secret,
                                       Path tempRoot) throws IOException {
        Objects.requireNonNull(runner, "runner");
        SudoEditCommands.quotedPath(remotePath);
        char[] own = secret.map(char[]::clone).orElse(null);
        Path folder = RemoteEditTempDirs.create(tempRoot);
        try {
            Path local = folder.resolve(RemoteEditNames.safeLocalName(remotePath));
            Baseline baseline;
            try (OutputStream out = Files.newOutputStream(local, StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                makeOwnerOnly(local);
                baseline = readRemote(runner, remotePath, own, out);
            }
            if (baseline == null) {
                throw new IOException(I18n.get("sftp.remoteEdit.error.notFile", remotePath));
            }
            return new SudoEditSession(runner, remotePath, folder, local, own, baseline);
        } catch (IOException | RuntimeException e) {
            RemoteEditTempDirs.delete(folder);
            wipe(own);
            throw e;
        }
    }

    @Override
    public String remotePath() {
        return remotePath;
    }

    @Override
    public Path localFile() {
        return localFile;
    }

    /** The private folder holding the local copy. */
    public Path folder() {
        return folder;
    }

    public Baseline baseline() {
        return baseline;
    }

    @Override
    public String baselineSha256() {
        return baseline.sha256();
    }

    @Override
    public RemoteEditSession.Conflict conflict() {
        return conflict;
    }

    @Override
    public int uploads() {
        return uploads;
    }

    @Override
    public Instant lastUpload() {
        return lastUpload;
    }

    /** Whether the last upload could check the SHA-256 on the server (false: size only). */
    public boolean lastUploadHashChecked() {
        return hashChecked;
    }

    @Override
    public boolean hasUnsyncedChanges() {
        if (closed) {
            return false;
        }
        try {
            return !RemoteEditHashes.sha256(localFile).equals(baseline.sha256());
        } catch (IOException e) {
            return false;
        }
    }

    /** Reads the file again as root: {@code null} when it still matches the baseline, otherwise why not. */
    public RemoteEditSession.Conflict checkRemote() throws IOException {
        requireOpen();
        Baseline now = readRemote(runner, remotePath, secret, OutputStream.nullOutputStream());
        if (now == null) {
            return new RemoteEditSession.Conflict(RemoteEditSession.ConflictKind.GONE);
        }
        if (now.size() != baseline.size() || !now.statLine().equals(baseline.statLine())) {
            return new RemoteEditSession.Conflict(RemoteEditSession.ConflictKind.CHANGED);
        }
        if (!now.sha256().equals(baseline.sha256())) {
            return new RemoteEditSession.Conflict(RemoteEditSession.ConflictKind.CONTENT_CHANGED);
        }
        return null;
    }

    @Override
    public RemoteEditSession.UploadResult upload() throws IOException {
        return upload(false);
    }

    @Override
    public RemoteEditSession.UploadResult forceUpload() throws IOException {
        return upload(true);
    }

    private RemoteEditSession.UploadResult upload(boolean force) throws IOException {
        requireOpen();
        if (!Files.isRegularFile(localFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException(I18n.get("sftp.remoteEdit.error.localMissing", localFile.getFileName()));
        }
        if (Files.size(localFile) > MAX_FILE_BYTES) {
            throw new IOException(I18n.get("sftp.sudoEdit.error.tooLarge", MAX_FILE_BYTES / (1024 * 1024)));
        }
        // One snapshot of the bytes: the editor may save again while they are on their way.
        byte[] bytes = Files.readAllBytes(localFile);
        try {
            String hash = RemoteEditHashes.sha256(bytes);
            if (!force && hash.equals(baseline.sha256())) {
                return RemoteEditSession.UploadResult.UNCHANGED;
            }
            if (!force) {
                RemoteEditSession.Conflict found = checkRemote();
                if (found != null) {
                    conflict = found;
                    return RemoteEditSession.UploadResult.CONFLICT;
                }
            }
            RemoteCommandRunner.Result result = runner.run(
                RemoteCommandRunner.Request.sudo(SudoCommand.wrap(SudoEditCommands.write(remotePath, bytes.length, hash)),
                        Optional.ofNullable(secret))
                    .stdin(new ByteArrayInputStream(bytes))
                    .timeout(TIMEOUT)
                    .outputCap(4096)
                    .cancellation(new RemoteCommandCancellation()));
            String out = result.stdoutText();
            if (result.exitCode() != 0) {
                throw writeFailure(result.exitCode());
            }
            String statLine = null;
            boolean noHash = false;
            for (String line : out.split("\n")) {
                if (line.equals(SudoEditCommands.NO_HASH)) {
                    noHash = true;
                } else if (line.startsWith(SudoEditCommands.WRITTEN)) {
                    statLine = line.substring(SudoEditCommands.WRITTEN.length());
                }
            }
            if (statLine == null) {
                throw new IOException(I18n.get("sftp.sudoEdit.error.write", remotePath));
            }
            if (noHash) {
                logger.info("The server has no sha256sum or shasum; the edit as root was checked by size only");
            }
            hashChecked = !noHash;
            baseline = new Baseline(statLine, bytes.length, hash);
            conflict = null;
            uploads++;
            lastUpload = Instant.now();
            return RemoteEditSession.UploadResult.UPLOADED;
        } finally {
            Arrays.fill(bytes, (byte) 0);
        }
    }

    private IOException writeFailure(int exitCode) {
        return switch (exitCode) {
            case SudoEditCommands.EXIT_NOT_A_FILE -> new IOException(I18n.get("sftp.remoteEdit.error.notFile", remotePath));
            case SudoEditCommands.EXIT_SIZE_MISMATCH, SudoEditCommands.EXIT_HASH_MISMATCH, SudoEditCommands.EXIT_SIGNAL,
                 SudoEditCommands.EXIT_STAGE_WRITE, -1 -> new IOException(I18n.get("sftp.sudoEdit.error.verify", remotePath));
            case SudoEditCommands.EXIT_NO_STAGE -> new IOException(I18n.get("sftp.sudoEdit.error.stage"));
            default -> new IOException(I18n.get("sftp.sudoEdit.error.write", remotePath));
        };
    }

    @Override
    public void saveLocalCopy(Path target) throws IOException {
        Files.copy(localFile, target, StandardCopyOption.REPLACE_EXISTING, LinkOption.NOFOLLOW_LINKS);
    }

    @Override
    public boolean isClosed() {
        return closed;
    }

    /** Deletes the private folder and wipes the password; idempotent. */
    @Override
    public void close() {
        closed = true;
        wipe(secret);
        secret = null;
        RemoteEditTempDirs.delete(folder);
    }

    private void requireOpen() throws IOException {
        if (closed) {
            throw new IOException(I18n.get("sftp.remoteEdit.error.stopped"));
        }
    }

    /**
     * Runs {@link SudoEditCommands#read} and writes the content to {@code out}.
     *
     * @return the remote state, or {@code null} when the path is a link or not a regular file
     */
    static Baseline readRemote(RemoteCommandRunner runner, String path, char[] secret, OutputStream out)
            throws IOException {
        ReadSink sink = new ReadSink(out);
        RemoteCommandRunner.Result result = runner.run(
            RemoteCommandRunner.Request.sudo(SudoCommand.wrap(SudoEditCommands.read(path)), Optional.ofNullable(secret))
                .timeout(TIMEOUT)
                .outputCap(MAX_FILE_BYTES + MAX_HEADER + 1)
                .stdoutSink(sink)
                .cancellation(new RemoteCommandCancellation()));
        if (sink.headerTooLong) {
            throw new RemoteCommandException("The server answered the read with something unexpected.");
        }
        if (result.outputTruncated() || sink.bytes > MAX_FILE_BYTES) {
            throw new IOException(I18n.get("sftp.sudoEdit.error.tooLarge", MAX_FILE_BYTES / (1024 * 1024)));
        }
        if (result.exitCode() == SudoEditCommands.EXIT_NOT_A_FILE) {
            return null;
        }
        if (result.exitCode() != 0 || sink.header == null) {
            throw new IOException(I18n.get("sftp.sudoEdit.error.read", path));
        }
        return new Baseline(sink.header, sink.bytes, HexFormat.of().formatHex(sink.digest.digest()));
    }

    /** Splits the stat line off the stream and hashes and stores the rest. */
    private static final class ReadSink implements RemoteCommandRunner.StdoutSink {
        private final OutputStream out;
        private final MessageDigest digest = RemoteEditHashes.newDigest();
        private final java.io.ByteArrayOutputStream headerBytes = new java.io.ByteArrayOutputStream();
        String header;
        boolean headerTooLong;
        long bytes;

        ReadSink(OutputStream out) {
            this.out = out;
        }

        @Override
        public boolean accept(byte[] data, int offset, int length) throws IOException {
            int start = offset;
            int end = offset + length;
            if (header == null) {
                while (start < end && data[start] != '\n') {
                    headerBytes.write(data[start]);
                    start++;
                }
                if (headerBytes.size() > MAX_HEADER) {
                    headerTooLong = true;
                    return false;
                }
                if (start == end) {
                    return true;
                }
                header = headerBytes.toString(StandardCharsets.UTF_8).strip();
                start++;
            }
            int count = end - start;
            if (count > 0) {
                digest.update(data, start, count);
                out.write(data, start, count);
                bytes += count;
            }
            return true;
        }
    }

    private static void wipe(char[] value) {
        if (value != null) {
            Arrays.fill(value, '\0');
        }
    }

    /** The local copy is readable by its owner only, whatever mode the remote file had. */
    private static void makeOwnerOnly(Path local) throws IOException {
        if (local.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.setPosixFilePermissions(local, PosixFilePermissions.fromString("rw-------"));
        }
    }

}
