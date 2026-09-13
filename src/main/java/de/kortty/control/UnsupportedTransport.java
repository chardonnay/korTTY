package de.kortty.control;

import java.io.IOException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.SocketChannel;
import java.util.Map;
import java.util.Objects;

/**
 * The explicit "this host cannot listen" transport, returned instead of null so every caller has an
 * object to ask and a reason to log.
 *
 * <p>Pure, any thread.
 */
public final class UnsupportedTransport implements ControlApiTransport {

    /** {@link #kind()} of a transport that can never bind. */
    public static final String KIND = "unsupported";

    private final String reason;

    /**
     * @param reason a short, non-secret explanation shown in the Settings status line
     */
    public UnsupportedTransport(String reason) {
        this.reason = Objects.requireNonNull(reason, "reason");
    }

    @Override
    public String kind() {
        return KIND;
    }

    /** Why this host cannot listen. */
    public String reason() {
        return reason;
    }

    @Override
    public EndpointDescriptor bind() throws ControlApiException {
        throw new ControlApiException(ControlErrorCode.UNSUPPORTED, reason,
            Map.of("reason", "unsupported_platform"));
    }

    @Override
    public SocketChannel accept() throws IOException {
        throw new ClosedChannelException();
    }

    @Override
    public void close() {
        // Nothing was ever opened.
    }
}
