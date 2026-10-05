package de.kortty.core.worker;

import static com.google.common.truth.Truth.assertThat;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.Channels;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * korTTY's relay for a worker without a network of its own: it connects only this connection's
 * destinations, passes datagrams only to the one Mosh endpoint, and leads korTTY to the worker's endpoint.
 */
class NetworkRelayTest {

    private Path folder;
    private NetworkRelay relay;
    private ServerSocket echo;

    @AfterMethod(alwaysRun = true)
    void cleanup() throws IOException {
        if (relay != null) {
            relay.close();
        }
        if (echo != null) {
            echo.close();
        }
        if (folder != null) {
            try (Stream<Path> paths = Files.walk(folder)) {
                paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    private int startEcho() throws IOException {
        echo = new ServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            while (!echo.isClosed()) {
                try (Socket socket = echo.accept()) {
                    socket.getInputStream().transferTo(socket.getOutputStream());
                } catch (IOException ignored) {
                    return;
                }
            }
        });
        thread.setDaemon(true);
        thread.start();
        return echo.getLocalPort();
    }

    private SocketChannel egress() throws IOException {
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        channel.connect(UnixDomainSocketAddress.of(folder.resolve(NetworkRelay.EGRESS_SOCKET)));
        return channel;
    }

    @Test
    void connectsAnAllowedDestinationAndRefusesAnyOther() throws Exception {
        folder = Files.createTempDirectory("kt-relay");
        int port = startEcho();
        relay = new NetworkRelay(folder, Set.of(new NetworkRelay.Destination("127.0.0.1", port)), null);
        relay.start();

        try (SocketChannel allowed = egress()) {
            OutputStream out = Channels.newOutputStream(allowed);
            InputStream in = Channels.newInputStream(allowed);
            out.write(("CONNECT 127.0.0.1 " + port + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(NetworkRelay.readLine(in)).isEqualTo("OK");
            out.write("ping\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(NetworkRelay.readLine(in)).isEqualTo("ping");
        }

        try (SocketChannel denied = egress()) {
            OutputStream out = Channels.newOutputStream(denied);
            out.write(("CONNECT 127.0.0.1 " + (port == 65535 ? port - 1 : port + 1) + "\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(NetworkRelay.readLine(Channels.newInputStream(denied))).isEqualTo("DENIED");
        }

        try (SocketChannel garbage = egress()) {
            OutputStream out = Channels.newOutputStream(garbage);
            out.write("GET / HTTP/1.0\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();
            assertThat(NetworkRelay.readLine(Channels.newInputStream(garbage))).isEqualTo("DENIED");
        }
    }

    @Test
    void passesDatagramsToTheOneUdpEndpointAndBack() throws Exception {
        folder = Files.createTempDirectory("kt-relay");
        try (DatagramSocket server = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            server.setSoTimeout(5_000);
            relay = new NetworkRelay(folder, Set.of(),
                new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort()));
            relay.start();
            try (SocketChannel udp = SocketChannel.open(StandardProtocolFamily.UNIX)) {
                udp.connect(UnixDomainSocketAddress.of(folder.resolve(NetworkRelay.UDP_SOCKET)));
                DataOutputStream out = new DataOutputStream(Channels.newOutputStream(udp));
                DataInputStream in = new DataInputStream(Channels.newInputStream(udp));
                byte[] hello = "mosh-hello".getBytes(StandardCharsets.US_ASCII);
                out.writeShort(hello.length);
                out.write(hello);
                out.flush();

                DatagramPacket received = new DatagramPacket(new byte[64], 64);
                server.receive(received);
                assertThat(new String(received.getData(), 0, received.getLength(), StandardCharsets.US_ASCII))
                    .isEqualTo("mosh-hello");
                byte[] answer = "mosh-answer".getBytes(StandardCharsets.US_ASCII);
                server.send(new DatagramPacket(answer, answer.length, received.getSocketAddress()));

                int length = in.readUnsignedShort();
                byte[] back = new byte[length];
                in.readFully(back);
                assertThat(new String(back, StandardCharsets.US_ASCII)).isEqualTo("mosh-answer");
            }
        }
    }

    @Test
    void theEndpointPortLeadsToTheWorkersEndpointSocket() throws Exception {
        folder = Files.createTempDirectory("kt-relay");
        relay = new NetworkRelay(folder, Set.of(), null);
        relay.start();
        // Stand-in for the worker: its endpoint socket echoes.
        java.nio.channels.ServerSocketChannel endpoint = java.nio.channels.ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        endpoint.bind(UnixDomainSocketAddress.of(folder.resolve(NetworkRelay.ENDPOINT_SOCKET)));
        Thread worker = new Thread(() -> {
            try (SocketChannel connection = endpoint.accept()) {
                Channels.newInputStream(connection).transferTo(Channels.newOutputStream(connection));
            } catch (IOException ignored) {
                // Test ended.
            }
        });
        worker.setDaemon(true);
        worker.start();
        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), relay.endpointPort())) {
            client.getOutputStream().write("ssh\n".getBytes(StandardCharsets.US_ASCII));
            client.getOutputStream().flush();
            assertThat(NetworkRelay.readLine(client.getInputStream())).isEqualTo("ssh");
        } finally {
            endpoint.close();
        }
    }
}
