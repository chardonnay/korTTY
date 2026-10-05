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
import java.net.SocketAddress;
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

/**
 * The worker's side of a network it does not have: in an empty network namespace (the Linux
 * sandbox) every connection goes through korTTY's {@link NetworkRelay} over Unix sockets in the
 * session folder. The worker gets loopback stand-ins for its destinations — a TCP listener per
 * server, a UDP address for Mosh — whose traffic korTTY forwards only where this connection may go.
 */
final class IsolatedNetwork {

    private static final Logger logger = LoggerFactory.getLogger(IsolatedNetwork.class);

    private final Path folder;

    IsolatedNetwork(Path folder) {
        this.folder = folder;
    }

    /**
     * A loopback address whose connections reach {@code host:port} through korTTY, which refuses
     * anything but this connection's servers.
     */
    InetSocketAddress tcpVia(String host, int port) throws IOException {
        ServerSocket listener = new ServerSocket(0, 8, InetAddress.getLoopbackAddress());
        daemon("Egress-" + port, () -> {
            while (!listener.isClosed()) {
                try {
                    Socket local = listener.accept();
                    daemon("Egress-Connection", () -> relayTcp(local, host, port));
                } catch (IOException e) {
                    return;
                }
            }
        });
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), listener.getLocalPort());
    }

    private void relayTcp(Socket local, String host, int port) {
        try (SocketChannel egress = SocketChannel.open(StandardProtocolFamily.UNIX)) {
            egress.connect(UnixDomainSocketAddress.of(folder.resolve(NetworkRelay.EGRESS_SOCKET)));
            InputStream fromKortty = Channels.newInputStream(egress);
            OutputStream toKortty = Channels.newOutputStream(egress);
            toKortty.write(("CONNECT " + host + " " + port + "\n").getBytes(StandardCharsets.US_ASCII));
            toKortty.flush();
            String answer = NetworkRelay.readLine(fromKortty);
            if (!"OK".equals(answer)) {
                logger.warn("korTTY did not open the connection: {}", answer);
                local.close();
                return;
            }
            NetworkRelay.pumpBothWays(fromKortty, toKortty, local);
        } catch (IOException e) {
            logger.debug("Egress relay ended: {}", e.getMessage());
        } finally {
            try {
                local.close();
            } catch (IOException ignored) {
                // Closed already.
            }
        }
    }

    /** Offers the endpoint on loopback port {@code internalPort} to korTTY as {@value NetworkRelay#ENDPOINT_SOCKET}. */
    void serveEndpoint(int internalPort) throws IOException {
        Path socket = folder.resolve(NetworkRelay.ENDPOINT_SOCKET);
        Files.deleteIfExists(socket);
        ServerSocketChannel listener = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        listener.bind(UnixDomainSocketAddress.of(socket));
        daemon("Endpoint-Unix", () -> {
            while (listener.isOpen()) {
                try {
                    SocketChannel kortty = listener.accept();
                    daemon("Endpoint-Connection", () -> {
                        try (kortty; Socket endpoint = new Socket(InetAddress.getLoopbackAddress(), internalPort)) {
                            NetworkRelay.pumpBothWays(Channels.newInputStream(kortty), Channels.newOutputStream(kortty),
                                endpoint);
                        } catch (IOException e) {
                            logger.debug("Endpoint relay ended: {}", e.getMessage());
                        }
                    });
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    /**
     * A loopback UDP address that stands in for the Mosh server: what is sent there reaches the server
     * through korTTY, and the server's answers come back from it.
     */
    InetSocketAddress udpVia() throws IOException {
        DatagramChannel local = DatagramChannel.open();
        local.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
        SocketChannel relay = SocketChannel.open(StandardProtocolFamily.UNIX);
        relay.connect(UnixDomainSocketAddress.of(folder.resolve(NetworkRelay.UDP_SOCKET)));
        DataOutputStream toKortty = new DataOutputStream(Channels.newOutputStream(relay));
        DataInputStream fromKortty = new DataInputStream(Channels.newInputStream(relay));
        SocketAddress[] client = new SocketAddress[1];
        daemon("Udp-Out", () -> {
            ByteBuffer buffer = ByteBuffer.allocate(65_536);
            try {
                while (local.isOpen()) {
                    buffer.clear();
                    SocketAddress from = local.receive(buffer);
                    synchronized (client) {
                        client[0] = from;
                    }
                    buffer.flip();
                    toKortty.writeShort(buffer.remaining());
                    toKortty.write(buffer.array(), 0, buffer.remaining());
                    toKortty.flush();
                }
            } catch (IOException e) {
                logger.debug("UDP relay to korTTY ended: {}", e.getMessage());
            }
        });
        daemon("Udp-In", () -> {
            byte[] datagram = new byte[65_536];
            try {
                while (local.isOpen()) {
                    int length = fromKortty.readUnsignedShort();
                    fromKortty.readFully(datagram, 0, length);
                    SocketAddress to;
                    synchronized (client) {
                        to = client[0];
                    }
                    if (to != null) {
                        local.send(ByteBuffer.wrap(datagram, 0, length), to);
                    }
                }
            } catch (IOException e) {
                logger.debug("UDP relay from korTTY ended: {}", e.getMessage());
            }
        });
        return (InetSocketAddress) local.getLocalAddress();
    }

    private static void daemon(String name, Runnable body) {
        Thread thread = new Thread(body, name);
        thread.setDaemon(true);
        thread.start();
    }
}
