package de.kortty.core.sftp.transfer;

import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHLimitsExtension;
import org.apache.sshd.sftp.client.extensions.openssh.OpenSSHLimitsExtensionInfo;
import org.apache.sshd.sftp.client.impl.AbstractSftpClient;
import org.apache.sshd.sftp.client.impl.SftpInputStreamAsync;
import org.apache.sshd.sftp.client.impl.SftpOutputStreamAsync;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Copies one file between the local disk and an SFTP server, with progress, cancellation and a
 * start offset (for resuming).
 *
 * <p>The copy is pipelined: it builds on MINA's {@link SftpInputStreamAsync} and
 * {@link SftpOutputStreamAsync}, which keep several read or write requests in flight up to the
 * channel window, so a high-latency link is not reduced to one request per round trip. An offset is
 * applied by starting the async input stream at that position, and by
 * {@link SftpOutputStreamAsync#setOffset(long)} on an explicitly opened handle for uploads.
 *
 * <p>The buffer size comes from the server's {@code limits@openssh.com} extension when it offers
 * one (capped at {@value #MAX_BUFFER_SIZE} bytes), otherwise {@value #DEFAULT_BUFFER_SIZE} bytes.
 * The cancellation token is checked after every buffer; a cancel surfaces as
 * {@link TransferCancelledException}. Handles opened here are always closed again, also on cancel
 * and failure. The SFTP client itself is never closed.
 */
public final class SftpStreamCopier {

    private static final Logger logger = LoggerFactory.getLogger(SftpStreamCopier.class);

    /** Buffer (request) size when the server does not announce its limits. */
    public static final int DEFAULT_BUFFER_SIZE = 32 * 1024;
    /** Upper bound for a buffer even when the server allows more. */
    public static final int MAX_BUFFER_SIZE = 256 * 1024;
    /** Lower bound, against absurdly small announced limits. */
    static final int MIN_BUFFER_SIZE = 4 * 1024;

    /** Open modes of an upload that replaces the remote file (today's behaviour). */
    public static final Set<SftpClient.OpenMode> REPLACE = Collections.unmodifiableSet(
        EnumSet.of(SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate));
    /** Open modes of an upload that continues an existing remote file at an offset. */
    public static final Set<SftpClient.OpenMode> CONTINUE = Collections.unmodifiableSet(
        EnumSet.of(SftpClient.OpenMode.Write));

    /** Server limits per client, so a batch of small files costs the extra round trip only once. */
    private static final Map<SftpClient, Limits> LIMITS = Collections.synchronizedMap(new WeakHashMap<>());

    private SftpStreamCopier() {
    }

    /**
     * Downloads {@code remotePath} into {@code localPath}, starting at {@code offset}.
     *
     * <p>With offset 0 the local file is created or truncated; with a larger offset it must already
     * hold at least {@code offset} bytes and is cut to exactly that length before the tail is
     * appended. A symbolic link at {@code localPath} is never followed.
     *
     * @return the number of bytes copied (excluding the offset)
     */
    public static long download(SftpClient client, String remotePath, Path localPath, long offset,
            TransferProgressListener listener, TransferCancellation cancel) throws IOException {
        Objects.requireNonNull(localPath, "localPath");
        requireOffset(offset);
        cancel.throwIfCancelled();
        Set<OpenOption> options = new HashSet<>();
        options.add(StandardOpenOption.WRITE);
        options.add(LinkOption.NOFOLLOW_LINKS);
        if (offset == 0) {
            options.add(StandardOpenOption.CREATE);
            options.add(StandardOpenOption.TRUNCATE_EXISTING);
        }
        try (FileChannel channel = FileChannel.open(localPath, options)) {
            if (offset > 0) {
                if (channel.size() < offset) {
                    throw new IOException("Local file is shorter than the resume offset: " + localPath);
                }
                channel.truncate(offset);
                channel.position(offset);
            }
            OutputStream out = Channels.newOutputStream(channel);
            long copied = download(client, remotePath, out, offset, listener, cancel);
            channel.force(false);
            return copied;
        }
    }

    /**
     * Downloads {@code remotePath} from {@code offset} to its end into {@code out}, which is
     * flushed but not closed.
     *
     * @return the number of bytes copied (excluding the offset)
     */
    public static long download(SftpClient client, String remotePath, OutputStream out, long offset,
            TransferProgressListener listener, TransferCancellation cancel) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(remotePath, "remotePath");
        Objects.requireNonNull(out, "out");
        requireOffset(offset);
        TransferProgressListener progress = listener == null ? TransferProgressListener.NONE : listener;
        try {
            cancel.throwIfCancelled();
            SftpClient.Attributes attributes = client.stat(remotePath);
            long size = attributes.getSize();
            if (offset > size) {
                throw new IOException("Resume offset " + offset + " is beyond the remote size " + size);
            }
            int bufferSize = bufferSize(client, true);
            long done = offset;
            try (InputStream in = openRead(client, remotePath, offset, size, bufferSize)) {
                byte[] buffer = new byte[bufferSize];
                progress.onProgress(done, size);
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    progress.onProgress(done, Math.max(size, done));
                    cancel.throwIfCancelled();
                }
            }
            out.flush();
            return done - offset;
        } catch (IOException | RuntimeException e) {
            throw translate(e, cancel);
        }
    }

    /**
     * Uploads {@code localPath} to {@code remotePath}: from the start with {@link #REPLACE} when
     * {@code offset} is 0, otherwise its tail from {@code offset} into the existing remote file
     * with {@link #CONTINUE}.
     *
     * @return the number of bytes copied (excluding the offset)
     */
    public static long upload(SftpClient client, String remotePath, Path localPath, long offset,
            TransferProgressListener listener, TransferCancellation cancel) throws IOException {
        return upload(client, remotePath, localPath, offset, offset == 0 ? REPLACE : CONTINUE, listener, cancel);
    }

    /**
     * Uploads the bytes of {@code localPath} from {@code offset} to its end, writing them at the
     * same offset of {@code remotePath}, which is opened with {@code openModes}.
     *
     * @return the number of bytes copied (excluding the offset)
     */
    public static long upload(SftpClient client, String remotePath, Path localPath, long offset,
            Collection<SftpClient.OpenMode> openModes, TransferProgressListener listener,
            TransferCancellation cancel) throws IOException {
        Objects.requireNonNull(localPath, "localPath");
        requireOffset(offset);
        cancel.throwIfCancelled();
        try (FileChannel channel = FileChannel.open(localPath, StandardOpenOption.READ)) {
            long size = channel.size();
            if (offset > size) {
                throw new IOException("Resume offset " + offset + " is beyond the local size " + size);
            }
            channel.position(offset);
            return upload(client, remotePath, Channels.newInputStream(channel), size, offset, openModes,
                listener, cancel);
        }
    }

    /**
     * Uploads what {@code in} delivers to {@code remotePath}, starting at remote position
     * {@code offset}. {@code in} is not closed.
     *
     * @param totalBytes the final size for progress reporting, or {@code -1} when unknown
     * @return the number of bytes copied
     */
    public static long upload(SftpClient client, String remotePath, InputStream in, long totalBytes, long offset,
            Collection<SftpClient.OpenMode> openModes, TransferProgressListener listener,
            TransferCancellation cancel) throws IOException {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(remotePath, "remotePath");
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(openModes, "openModes");
        requireOffset(offset);
        TransferProgressListener progress = listener == null ? TransferProgressListener.NONE : listener;
        try {
            cancel.throwIfCancelled();
            int bufferSize = bufferSize(client, false);
            long done = offset;
            try (OutputStream out = openWrite(client, remotePath, openModes, offset, bufferSize)) {
                byte[] buffer = new byte[bufferSize];
                progress.onProgress(done, totalBytes);
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    done += read;
                    progress.onProgress(done, totalBytes < 0 ? -1 : Math.max(totalBytes, done));
                    cancel.throwIfCancelled();
                }
            }
            return done - offset;
        } catch (IOException | RuntimeException e) {
            throw translate(e, cancel);
        }
    }

    /**
     * The request size for {@code client}: the server's announced read or write limit when it
     * offers {@code limits@openssh.com}, clamped to [{@value #MIN_BUFFER_SIZE},
     * {@value #MAX_BUFFER_SIZE}], else {@value #DEFAULT_BUFFER_SIZE}.
     */
    public static int bufferSize(SftpClient client, boolean forRead) {
        Limits limits = LIMITS.computeIfAbsent(client, SftpStreamCopier::queryLimits);
        long announced = forRead ? limits.maxRead() : limits.maxWrite();
        if (announced <= 0) {
            return DEFAULT_BUFFER_SIZE;
        }
        return (int) Math.max(MIN_BUFFER_SIZE, Math.min(MAX_BUFFER_SIZE, announced));
    }

    private static Limits queryLimits(SftpClient client) {
        try {
            OpenSSHLimitsExtension extension = client.getExtension(OpenSSHLimitsExtension.class);
            if (extension != null && extension.isSupported()) {
                OpenSSHLimitsExtensionInfo info = extension.limits();
                if (info != null) {
                    return new Limits(info.maxReadLength, info.maxWriteLength);
                }
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("SFTP limits extension unavailable: {}", e.toString());
        }
        return Limits.UNKNOWN;
    }

    private static InputStream openRead(SftpClient client, String remotePath, long offset, long size, int bufferSize)
            throws IOException {
        if (client instanceof AbstractSftpClient asyncCapable) {
            SftpClient.CloseableHandle handle = client.open(remotePath, SftpClient.OpenMode.Read);
            try {
                return new SftpInputStreamAsync(asyncCapable, bufferSize, offset, size, remotePath, handle, true);
            } catch (RuntimeException e) {
                closeQuietly(handle);
                throw e;
            }
        }
        // Not a MINA client (a wrapper): its own stream, positioned by skipping.
        InputStream in = client.read(remotePath, bufferSize, SftpClient.OpenMode.Read);
        try {
            in.skipNBytes(offset);
            return in;
        } catch (IOException | RuntimeException e) {
            closeQuietly(in);
            throw e;
        }
    }

    private static OutputStream openWrite(SftpClient client, String remotePath,
            Collection<SftpClient.OpenMode> openModes, long offset, int bufferSize) throws IOException {
        SftpClient.CloseableHandle handle = client.open(remotePath, openModes);
        try {
            if (client instanceof AbstractSftpClient asyncCapable) {
                SftpOutputStreamAsync out = new SftpOutputStreamAsync(asyncCapable, bufferSize, remotePath, handle, true);
                out.setOffset(offset);
                return out;
            }
            return new HandleOutputStream(client, handle, offset);
        } catch (RuntimeException e) {
            closeQuietly(handle);
            throw e;
        }
    }

    private static IOException translate(Exception error, TransferCancellation cancel) {
        if (error instanceof TransferCancelledException cancelled) {
            return cancelled;
        }
        if (cancel.isCancelled()) {
            // The owned channel was closed under the copy: report the cancel, not the broken pipe.
            return new TransferCancelledException(error);
        }
        if (error instanceof IOException io) {
            return io;
        }
        return new IOException(error.getMessage(), error);
    }

    private static void requireOffset(long offset) {
        if (offset < 0) {
            throw new IllegalArgumentException("offset must not be negative: " + offset);
        }
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception e) {
            logger.debug("Closing after a failed open failed: {}", e.toString());
        }
    }

    private record Limits(long maxRead, long maxWrite) {
        static final Limits UNKNOWN = new Limits(0, 0);
    }

    /** Sequential fallback for clients that are not MINA's own implementation. */
    private static final class HandleOutputStream extends OutputStream {
        private final SftpClient client;
        private final SftpClient.CloseableHandle handle;
        private long position;

        HandleOutputStream(SftpClient client, SftpClient.CloseableHandle handle, long position) {
            this.client = client;
            this.handle = handle;
            this.position = position;
        }

        @Override
        public void write(int b) throws IOException {
            write(new byte[] {(byte) b}, 0, 1);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            client.write(handle, position, b, off, len);
            position += len;
        }

        @Override
        public void close() throws IOException {
            handle.close();
        }
    }
}
