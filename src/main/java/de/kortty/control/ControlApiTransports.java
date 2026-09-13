package de.kortty.control;

import de.kortty.codingagent.desktop.PlatformProbe;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Picks the listener for this host from an injected {@link PlatformProbe}.
 *
 * <p>Pure, any thread. Nothing is probed from {@code System.*} here, so both branches are unit
 * testable on one CI platform — the same shape the Stage-2 desktop backends use.
 */
public final class ControlApiTransports {

    /** The socket file name inside the control directory. */
    public static final String SOCKET_FILE_NAME = "control.sock";

    private ControlApiTransports() {
    }

    /**
     * {@link UnixSocketTransport} on POSIX, {@link LoopbackTokenTransport} on Windows.
     *
     * <p>Windows has had {@code AF_UNIX} since build 17063, but the JDK's unix-domain support there
     * is not something korTTY can require, and the 0700 directory that makes the POSIX socket safe
     * has no equivalent meaning, so Windows gets loopback TCP plus the token instead.
     *
     * @param probe the injected platform facts
     * @param controlDir the verified control directory; the socket is created inside it
     * @throws ControlApiException when {@code controlDir} is missing
     */
    public static ControlApiTransport select(PlatformProbe probe, Path controlDir) throws ControlApiException {
        Objects.requireNonNull(probe, "probe");
        if (probe.isWindows()) {
            return new LoopbackTokenTransport(() -> 0);
        }
        if (controlDir == null) {
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "The unix-domain transport needs a control directory");
        }
        return new UnixSocketTransport(controlDir.resolve(SOCKET_FILE_NAME));
    }
}
