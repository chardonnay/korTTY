package de.kortty.control;

import com.google.gson.JsonObject;
import de.kortty.codingagent.desktop.PlatformProbe;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.SkipException;

/**
 * Shared fixtures for the socket-facing control-API tests: short temp roots, the platform skips and a
 * tiny line client.
 *
 * <p>Any thread; every helper is stateless apart from the objects it returns.
 *
 * <p>Unix-domain socket paths are short-lived but hard-capped by the OS — 106 characters on Linux,
 * 104 on macOS, where the temp directory alone is {@code /var/folders/xy/<32 chars>/T/}. Every test
 * therefore builds its root with {@code Files.createTempDirectory("kt")} and a short socket name, and
 * {@link #requireBindableSocketPath(Path)} raises {@link SkipException} — never a failure — when the
 * result would still be too long.
 */
final class UdsTestSupport {

    /** The longest socket path these tests will attempt; below every measured platform limit. */
    static final int MAX_SOCKET_PATH_CHARS = 100;

    /**
     * Test-owned threads, deliberately <em>not</em> named {@code kortty-control-*} so
     * {@code ControlApiThreadHygieneTest} never mistakes a fixture for a leak.
     */
    private static final ThreadFactory TEST_THREADS = runnable -> {
        Thread thread = new Thread(runnable, "kt-test-worker");
        thread.setDaemon(true);
        return thread;
    };

    private UdsTestSupport() {
    }

    /** A short temp root, safe to use as the parent of a unix-domain socket. */
    static Path newTempRoot() throws IOException {
        return Files.createTempDirectory("kt");
    }

    /** Whether this JVM runs on Windows, where the UDS leg does not apply. */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** Skips the calling test on Windows. */
    static void skipOnWindows() {
        if (isWindows()) {
            throw new SkipException("unix-domain sockets are not exercised on Windows");
        }
    }

    /** Skips the calling test when the filesystem has no POSIX permissions. */
    static void skipWithoutPosix(Path path) {
        if (Files.getFileAttributeView(path, PosixFileAttributeView.class) == null) {
            throw new SkipException("this filesystem has no POSIX permissions");
        }
    }

    /**
     * Skips the calling test when {@code controlDir}'s socket path would exceed
     * {@link #MAX_SOCKET_PATH_CHARS}.
     */
    static void requireBindableSocketPath(Path controlDir) {
        String path = controlDir.resolve(ControlApiTransports.SOCKET_FILE_NAME).toAbsolutePath().toString();
        if (path.length() > MAX_SOCKET_PATH_CHARS) {
            throw new SkipException("the socket path is " + path.length()
                + " characters, over the " + MAX_SOCKET_PATH_CHARS + "-character test limit");
        }
    }

    /** A probe that selects the unix-domain transport. */
    static PlatformProbe posixProbe() {
        return new PlatformProbe("Linux", null, false, true);
    }

    /** A probe that selects the loopback transport. */
    static PlatformProbe windowsProbe() {
        return new PlatformProbe("Windows 11", null, false, true);
    }

    /** The probe this platform would really produce, so the socket tests bind what they can. */
    static PlatformProbe nativeProbe() {
        return isWindows() ? windowsProbe() : posixProbe();
    }

    /** Recursively removes a temp tree; never throws. */
    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A socket that a listener still owns may refuse; the temp dir is disposable.
                }
            });
        } catch (IOException ignored) {
            // Nothing further to do in a test teardown.
        }
    }

    /** Opens a client channel for whichever transport {@code descriptor} describes. */
    static SocketChannel connect(EndpointDescriptor descriptor) throws IOException {
        if (EndpointDescriptor.TRANSPORT_LOOPBACK.equals(descriptor.transport())) {
            return SocketChannel.open(new InetSocketAddress(descriptor.host(), descriptor.port()));
        }
        return SocketChannel.open(UnixDomainSocketAddress.of(descriptor.path()));
    }

    /** One newline-delimited JSON client, mirroring what {@code kortty-cli} will do. */
    static final class Client implements AutoCloseable {

        private final SocketChannel channel;

        private final ControlLineCodec codec;

        Client(EndpointDescriptor descriptor) throws IOException {
            this.channel = connect(descriptor);
            this.codec = new ControlLineCodec(Channels.newInputStream(channel),
                Channels.newOutputStream(channel), ControlApiProtocol.MAX_LINE_BYTES);
        }

        void send(String line) throws IOException {
            codec.writeLine(line);
        }

        /** The next frame, or null once the server closed the connection. */
        JsonObject receive() throws IOException {
            String line;
            try {
                line = codec.readLine();
            } catch (ControlApiException e) {
                throw new UncheckedIOException(new IOException(e));
            }
            if (line == null || line.isBlank()) {
                return null;
            }
            try {
                return ControlJson.parseObjectStrict(line);
            } catch (ControlApiException e) {
                throw new UncheckedIOException(new IOException("Unparseable frame: " + line, e));
            }
        }

        /** Sends {@code auth} with the endpoint's token and returns the reply. */
        JsonObject authenticate(String token) throws IOException {
            send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"auth\",\"params\":{\"token\":\"" + token + "\"}}");
            return receive();
        }

        @Override
        public void close() {
            try {
                channel.close();
            } catch (IOException ignored) {
                // A test teardown has nothing better to do.
            }
        }
    }

    /**
     * A scheduler that runs every scheduled task at once, so the five-second unauthenticated deadline
     * is observable without waiting five seconds.
     */
    static final class ImmediateTimer extends ScheduledThreadPoolExecutor {

        ImmediateTimer() {
            super(1, TEST_THREADS);
        }

        @Override
        public ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
            return super.schedule(command, 0L, unit);
        }
    }

    /** A real scheduler for the tests that must not have their deadlines collapsed. */
    static ScheduledExecutorService newTimer() {
        return new ScheduledThreadPoolExecutor(1, TEST_THREADS);
    }
}
