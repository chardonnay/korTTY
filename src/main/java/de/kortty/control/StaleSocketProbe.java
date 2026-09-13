package de.kortty.control;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies a leftover unix-domain socket file so the server knows whether it may unlink it.
 *
 * <p>Pure apart from one connect attempt; any thread, never the JavaFX application thread.
 *
 * <p>Closing a {@code ServerSocketChannel} does <strong>not</strong> unlink its socket file, and
 * binding over a leftover throws {@link java.net.BindException} whether that leftover is live or
 * stale, so the only usable discriminator is a CONNECT probe: a refused connection means nobody is
 * listening (safe to delete), a successful one means another korTTY owns it (refuse to start, delete
 * nothing).
 */
public final class StaleSocketProbe {

    private static final Logger LOG = LoggerFactory.getLogger(StaleSocketProbe.class);

    /** What the path at hand turned out to be. */
    public enum Verdict {

        /** Nothing is there; bind straight away. */
        NONE,

        /** A socket file whose listener is gone; unlink it, then bind. */
        STALE,

        /** A socket with a listener behind it; another korTTY owns this directory. */
        LIVE,

        /** The entry exists but is not a socket we may touch — a regular file, a symlink, a directory. */
        FOREIGN
    }

    private StaleSocketProbe() {
    }

    /**
     * Classifies {@code socketPath} without ever deleting anything.
     *
     * <p>Never throws: a path that cannot be probed at all is reported {@link Verdict#FOREIGN}, which
     * is the verdict that deletes nothing.
     */
    public static Verdict classify(Path socketPath) {
        if (socketPath == null) {
            return Verdict.NONE;
        }
        BasicFileAttributes attributes;
        try {
            attributes = Files.readAttributes(socketPath, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        } catch (NoSuchFileException e) {
            return Verdict.NONE;
        } catch (IOException | RuntimeException e) {
            LOG.debug("control-api: cannot stat {}", socketPath, e);
            return Verdict.FOREIGN;
        }
        if (!attributes.isOther() || attributes.isSymbolicLink()) {
            return Verdict.FOREIGN;
        }
        try (SocketChannel probe = SocketChannel.open(UnixDomainSocketAddress.of(socketPath))) {
            LOG.debug("control-api: {} still has a listener", socketPath);
            return probe.isConnected() ? Verdict.LIVE : Verdict.STALE;
        } catch (ConnectException e) {
            return Verdict.STALE;
        } catch (IOException | RuntimeException e) {
            LOG.debug("control-api: cannot probe {}", socketPath, e);
            return Verdict.FOREIGN;
        }
    }
}
