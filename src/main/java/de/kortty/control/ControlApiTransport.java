package de.kortty.control;

import java.io.IOException;
import java.nio.channels.SocketChannel;

/**
 * The OS listener underneath the control API: an {@code AF_UNIX} stream socket on POSIX, a loopback
 * TCP socket on Windows.
 *
 * <p>Any thread, never the JavaFX application thread. One thread owns {@link #accept()}; every other
 * method may be called concurrently with it, and {@link #close()} is what unblocks a parked
 * {@code accept()}.
 */
public interface ControlApiTransport extends AutoCloseable {

    /** {@link EndpointDescriptor#TRANSPORT_UNIX}, {@link EndpointDescriptor#TRANSPORT_LOOPBACK}, or {@code "unsupported"}. */
    String kind();

    /**
     * Binds the listener and describes it.
     *
     * <p>The returned descriptor carries <strong>no token</strong>, no pid and no instance id: the
     * server fills those in and only then writes {@code endpoint.json}, so the file's existence
     * implies a bound listener and a readable token.
     *
     * @throws ControlApiException when this host cannot listen at all — a live or foreign leftover
     *     socket, an unsupported platform, or a path the OS refuses
     */
    EndpointDescriptor bind() throws IOException, ControlApiException;

    /**
     * Accepts one client, blocking until there is one.
     *
     * @throws java.nio.channels.AsynchronousCloseException when {@link #close()} runs concurrently
     * @throws java.nio.channels.ClosedChannelException when the transport is already closed
     */
    SocketChannel accept() throws IOException;

    /**
     * Stops listening and unlinks whatever this transport created, unconditionally and idempotently.
     *
     * <p>A transport that never bound successfully unlinks <strong>nothing</strong>, so a refused
     * start never deletes another instance's socket.
     */
    @Override
    void close();
}
