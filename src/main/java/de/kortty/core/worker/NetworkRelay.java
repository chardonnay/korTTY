package de.kortty.core.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.DatagramChannel;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * korTTY's side of the network of a session worker that has none of its own (a sandboxed worker on
 * Linux runs in an empty network namespace). Everything goes through Unix sockets in the session's
 * folder, which both sides see:
 *
 * <ul>
 *   <li>{@value #EGRESS_SOCKET}: the worker asks for a TCP connection with one line
 *       {@code CONNECT host port}; korTTY opens it only when host and port are on the allow list
 *       (the server and the jump server of this connection), answers {@code OK} and copies bytes.</li>
 *   <li>{@value #UDP_SOCKET}: Mosh datagrams, each as a 2-byte length and the payload, sent by korTTY
 *       to the one allowed UDP endpoint; the answers come back the same way.</li>
 *   <li>{@value #ENDPOINT_SOCKET}, served by the worker: its loopback SSH endpoint. korTTY offers it
 *       on a loopback TCP port of this computer ({@link #endpointPort()}) for its own SSH client.</li>
 * </ul>
 *
 * The allow list is korTTY's, built from the connection; nothing the worker sends can widen it.
 */
public final class NetworkRelay implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(NetworkRelay.class);

    public static final String EGRESS_SOCKET = "egress.sock";
    public static final String UDP_SOCKET = "udp.sock";
    public static final String ENDPOINT_SOCKET = "endpoint.sock";

    /** One allowed TCP destination. */
    public record Destination(String host, int port) {
        public Destination {
            host = host.trim().toLowerCase(Locale.ROOT);
        }
    }

    private final Path folder;
    private final Set<Destination> allowedTcp;
    private final InetSocketAddress allowedUdp;
    private final List<AutoCloseable> open = new CopyOnWriteArrayList<>();
    private volatile boolean closed;
    private ServerSocket endpointListener;

    /**
     * @param folder     the session's folder, shared with the worker
     * @param allowedTcp the TCP destinations the worker may reach
     * @param allowedUdp the one UDP destination (Mosh), or null for none
     */
    public NetworkRelay(Path folder, Set<Destination> allowedTcp, InetSocketAddress allowedUdp) {
        this.folder = folder;
        this.allowedTcp = Set.copyOf(allowedTcp);
        this.allowedUdp = allowedUdp;
    }

    /** Starts serving the worker's egress (and UDP, when allowed) and korTTY's way to its endpoint. */
    public void start() throws IOException {
        ServerSocketChannel egress = listen(EGRESS_SOCKET);
        daemon("Relay-Egress", () -> acceptLoop(egress, this::serveEgress));
        if (allowedUdp != null) {
            ServerSocketChannel udp = listen(UDP_SOCKET);
            daemon("Relay-Udp", () -> acceptLoop(udp, this::serveUdp));
        }
        endpointListener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        open.add(endpointListener);
        daemon("Relay-Endpoint", this::serveEndpoint);
    }

    /** The loopback port of this computer that leads to the worker's endpoint. */
    public int endpointPort() {
        return endpointListener.getLocalPort();
    }

    private ServerSocketChannel listen(String name) throws IOException {
        Path socket = folder.resolve(name);
        Files.deleteIfExists(socket);
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        channel.bind(UnixDomainSocketAddress.of(socket));
        open.add(channel);
        return channel;
    }

    @FunctionalInterface
    private interface Server {
        void serve(SocketChannel connection) throws IOException;
    }

    private void acceptLoop(ServerSocketChannel listener, Server server) {
        while (!closed) {
            try {
                SocketChannel connection = listener.accept();
                open.add(connection);
                daemon("Relay-Connection", () -> {
                    try {
                        server.serve(connection);
                    } catch (IOException e) {
                        logger.debug("Relay connection ended: {}", e.getMessage());
                    } finally {
                        closeQuietly(connection);
                    }
                });
            } catch (IOException e) {
                if (!closed) {
                    logger.debug("Relay listener ended: {}", e.getMessage());
                }
                return;
            }
        }
    }

    private void serveEgress(SocketChannel connection) throws IOException {
        InputStream in = Channels.newInputStream(connection);
        OutputStream out = Channels.newOutputStream(connection);
        String request = readLine(in);
        String[] parts = request.split(" ");
        if (parts.length != 3 || !"CONNECT".equals(parts[0])) {
            out.write("DENIED\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        Destination wanted;
        try {
            wanted = new Destination(parts[1], Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            out.write("DENIED\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        if (!allowedTcp.contains(wanted)) {
            logger.warn("Session worker asked to connect to a destination outside its connection; refused");
            out.write("DENIED\n".getBytes(StandardCharsets.US_ASCII));
            return;
        }
        Socket target = new Socket();
        open.add(target);
        try {
            target.connect(new InetSocketAddress(wanted.host(), wanted.port()), 15_000);
        } catch (IOException e) {
            out.write(("FAILED " + e.getClass().getSimpleName() + "\n").getBytes(StandardCharsets.US_ASCII));
            closeQuietly(target);
            return;
        }
        out.write("OK\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        pumpBothWays(in, out, target);
    }

    private void serveUdp(SocketChannel connection) throws IOException {
        DatagramChannel udp = DatagramChannel.open();
        open.add(udp);
        udp.connect(allowedUdp);
        DataInputStream in = new DataInputStream(Channels.newInputStream(connection));
        DataOutputStream out = new DataOutputStream(Channels.newOutputStream(connection));
        daemon("Relay-Udp-In", () -> {
            ByteBuffer buffer = ByteBuffer.allocate(65_536);
            try {
                while (!closed) {
                    buffer.clear();
                    int count = udp.read(buffer);
                    if (count > 0) {
                        synchronized (out) {
                            out.writeShort(count);
                            out.write(buffer.array(), 0, count);
                            out.flush();
                        }
                    }
                }
            } catch (IOException e) {
                logger.debug("UDP relay from the server ended: {}", e.getMessage());
            } finally {
                closeQuietly(connection);
                closeQuietly(udp);
            }
        });
        byte[] datagram = new byte[65_536];
        try {
            while (!closed) {
                int length = in.readUnsignedShort();
                in.readFully(datagram, 0, length);
                try {
                    udp.write(ByteBuffer.wrap(datagram, 0, length));
                } catch (IOException e) {
                    // A refused datagram (no route for now) is lost, as on a real network.
                    logger.debug("UDP datagram not sent: {}", e.getMessage());
                }
            }
        } finally {
            closeQuietly(udp);
        }
    }

    private void serveEndpoint() {
        while (!closed) {
            try {
                Socket client = endpointListener.accept();
                open.add(client);
                daemon("Relay-Endpoint-Connection", () -> {
                    try {
                        SocketChannel worker = SocketChannel.open(StandardProtocolFamily.UNIX);
                        open.add(worker);
                        worker.connect(UnixDomainSocketAddress.of(folder.resolve(ENDPOINT_SOCKET)));
                        pumpBothWays(Channels.newInputStream(worker), Channels.newOutputStream(worker), client);
                    } catch (IOException e) {
                        logger.debug("Relay to the worker endpoint ended: {}", e.getMessage());
                    } finally {
                        closeQuietly(client);
                    }
                });
            } catch (IOException e) {
                if (!closed) {
                    logger.debug("Endpoint relay listener ended: {}", e.getMessage());
                }
                return;
            }
        }
    }

    /** Copies {@code in} to {@code socket} and {@code socket} to {@code out} until either side ends. */
    static void pumpBothWays(InputStream in, OutputStream out, Socket socket) throws IOException {
        InputStream fromSocket = socket.getInputStream();
        OutputStream toSocket = socket.getOutputStream();
        Thread reverse = new Thread(() -> {
            try {
                fromSocket.transferTo(out);
            } catch (IOException ignored) {
                // One side ended.
            } finally {
                closeQuietly(out);
                closeQuietly(socket);
            }
        }, "Relay-Pump");
        reverse.setDaemon(true);
        reverse.start();
        try {
            in.transferTo(toSocket);
        } finally {
            closeQuietly(socket);
        }
    }

    /** One line of ASCII up to the line break, at most 512 characters. */
    static String readLine(InputStream in) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0 && c != '\n') {
            if (line.length() >= 512) {
                throw new IOException("request too long");
            }
            line.append((char) c);
        }
        return line.toString();
    }

    private static void daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            if (closeable != null) {
                closeable.close();
            }
        } catch (Exception ignored) {
            // Closing anyway.
        }
    }

    @Override
    public void close() {
        closed = true;
        List<AutoCloseable> all = new ArrayList<>(open);
        open.clear();
        all.forEach(NetworkRelay::closeQuietly);
        for (String name : List.of(EGRESS_SOCKET, UDP_SOCKET)) {
            try {
                Files.deleteIfExists(folder.resolve(name));
            } catch (IOException ignored) {
                // The session folder is deleted anyway.
            }
        }
    }
}
