package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.SerialSettings;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

/** The serial connector over an in-memory {@link SerialLink}, so it runs without a port. */
class SerialTtyConnectorTest {

    /** A link whose received bytes the test feeds; -1 once the "adapter" is unplugged or closed. */
    static final class FakeLink implements SerialLink {
        final LinkedBlockingQueue<byte[]> incoming = new LinkedBlockingQueue<>();
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        volatile boolean unplugged;
        volatile boolean closed;
        volatile int breaks;
        private byte[] pending;
        private int pendingOffset;

        @Override
        public int read(byte[] buffer, int length) throws IOException {
            if (closed || unplugged) {
                return -1;
            }
            if (pending == null) {
                try {
                    pending = incoming.poll(20, TimeUnit.MILLISECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return -1;
                }
                pendingOffset = 0;
                if (pending == null) {
                    return closed || unplugged ? -1 : 0;
                }
            }
            int count = Math.min(length, pending.length - pendingOffset);
            System.arraycopy(pending, pendingOffset, buffer, 0, count);
            pendingOffset += count;
            if (pendingOffset >= pending.length) {
                pending = null;
            }
            return count;
        }

        @Override
        public synchronized void write(byte[] bytes) {
            written.writeBytes(bytes);
        }

        @Override
        public int available() {
            return incoming.isEmpty() ? 0 : 1;
        }

        @Override
        public boolean sendBreak(int millis) {
            breaks++;
            return true;
        }

        @Override
        public void close() {
            closed = true;
        }

        synchronized byte[] writtenBytes() {
            return written.toByteArray();
        }
    }

    private static ServerConnection serialConnection(SerialSettings settings) {
        ServerConnection connection = new ServerConnection();
        connection.setProtocol(ConnectionProtocol.SERIAL);
        connection.setSerialSettings(settings);
        return connection;
    }

    private static SerialSettings port(String name) {
        SerialSettings settings = new SerialSettings();
        settings.setPortName(name);
        return settings;
    }

    private static String readAvailable(SerialTtyConnector connector) throws IOException {
        char[] buffer = new char[256];
        int count = connector.read(buffer, 0, buffer.length);
        return count < 0 ? null : new String(buffer, 0, count);
    }

    @Test
    void decodesReceivedBytesAndTellsDataListeners() throws Exception {
        FakeLink link = new FakeLink();
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(port("COM3")), settings -> link);
        List<String> seen = new ArrayList<>();
        connector.addDataListener(seen::add);

        assertThat(connector.connect()).isTrue();
        link.incoming.add("Router> ".getBytes(StandardCharsets.UTF_8));

        assertThat(readAvailable(connector)).isEqualTo("Router> ");
        assertThat(seen).containsExactly("Router> ");
        assertThat(connector.isConnected()).isTrue();
    }

    @Test
    void keepsAMultiByteCharacterSplitAcrossReadsWhole() throws Exception {
        FakeLink link = new FakeLink();
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(port("COM3")), settings -> link);
        connector.connect();
        byte[] umlaut = "ü".getBytes(StandardCharsets.UTF_8);
        link.incoming.add(new byte[] {umlaut[0]});
        link.incoming.add(new byte[] {umlaut[1], '!'});

        StringBuilder text = new StringBuilder();
        while (text.length() < 2) {
            text.append(readAvailable(connector));
        }
        assertThat(text.toString()).isEqualTo("ü!");
    }

    @Test
    void enterIsSentAsTheConfiguredLineEnding() throws Exception {
        for (SerialSettings.LineEnding ending : SerialSettings.LineEnding.values()) {
            FakeLink link = new FakeLink();
            SerialSettings settings = port("COM3");
            settings.setLineEnding(ending);
            SerialTtyConnector connector = new SerialTtyConnector(serialConnection(settings), s -> link);
            connector.connect();

            connector.write("show ver\r");

            String expected = switch (ending) {
                case CR -> "show ver\r";
                case LF -> "show ver\n";
                case CRLF -> "show ver\r\n";
            };
            assertThat(new String(link.writtenBytes(), StandardCharsets.US_ASCII)).isEqualTo(expected);
        }
    }

    @Test
    void localEchoShowsTypedInputBeforeTheDeviceAnswers() throws Exception {
        FakeLink link = new FakeLink();
        SerialSettings settings = port("COM3");
        settings.setLocalEcho(true);
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(settings), s -> link);
        connector.connect();

        connector.write("AT\r");
        link.incoming.add("OK\r\n".getBytes(StandardCharsets.US_ASCII));

        StringBuilder screen = new StringBuilder();
        while (screen.length() < "AT\r\nOK\r\n".length()) {
            screen.append(readAvailable(connector));
        }
        assertThat(screen.toString()).isEqualTo("AT\r\nOK\r\n");
    }

    @Test
    void inputInterceptorAndActivityListenersSeeTheTypedBytes() throws Exception {
        FakeLink link = new FakeLink();
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(port("COM3")), s -> link);
        connector.connect();
        List<Integer> activity = new ArrayList<>();
        connector.addInputActivityListener(activity::add);
        connector.setInputInterceptor(bytes -> new String(bytes, StandardCharsets.US_ASCII).toUpperCase()
            .getBytes(StandardCharsets.US_ASCII));

        connector.write("en");

        assertThat(new String(link.writtenBytes(), StandardCharsets.US_ASCII)).isEqualTo("EN");
        assertThat(activity).containsExactly(2);
    }

    @Test
    void anUnpluggedAdapterEndsTheSessionAsLost() throws Exception {
        FakeLink link = new FakeLink();
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(port("/dev/ttyUSB0")), s -> link);
        AtomicReference<String> reason = new AtomicReference<>();
        AtomicReference<Boolean> error = new AtomicReference<>();
        connector.setDisconnectListener((why, wasError) -> {
            reason.set(why);
            error.set(wasError);
        });
        connector.connect();

        link.unplugged = true;

        assertThat(readAvailable(connector)).isNull();
        assertThat(connector.isConnected()).isFalse();
        assertThat(connector.wasConnectionLost()).isTrue();
        assertThat(error.get()).isTrue();
        assertThat(reason.get()).contains("/dev/ttyUSB0");
        assertThat(link.closed).isTrue();
        assertThat(connector.waitFor()).isEqualTo(1);
    }

    @Test
    void closingEndsABlockedReadWithoutCountingAsLost() throws Exception {
        FakeLink link = new FakeLink();
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(port("COM3")), s -> link);
        AtomicReference<String> reason = new AtomicReference<>();
        connector.setDisconnectListener((why, wasError) -> reason.set(why));
        connector.connect();
        AtomicReference<String> readResult = new AtomicReference<>("not finished");
        Thread reader = new Thread(() -> {
            try {
                readResult.set(readAvailable(connector));
            } catch (IOException e) {
                readResult.set("error " + e);
            }
        });
        reader.start();
        Thread.sleep(50);

        connector.close();
        reader.join(2000);

        assertThat(reader.isAlive()).isFalse();
        assertThat(readResult.get()).isNull();
        assertThat(connector.wasConnectionLost()).isFalse();
        assertThat(reason.get()).isNull();
        assertThat(connector.waitFor()).isEqualTo(0);
    }

    @Test
    void sendBreakReachesTheLinkOnlyWhileConnected() throws Exception {
        FakeLink link = new FakeLink();
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(port("COM3")), s -> link);
        assertThat(connector.sendBreak()).isFalse();

        connector.connect();
        assertThat(connector.sendBreak()).isTrue();
        assertThat(link.breaks).isEqualTo(1);

        connector.close();
        assertThat(connector.sendBreak()).isFalse();
    }

    @Test
    void aPortThatDoesNotOpenFailsTheConnectWithItsReason() {
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(port("COM9")), s -> {
            throw new SerialOpenException(SerialOpenException.Reason.BUSY, 5, "busy");
        });

        IOException e = expectThrows(IOException.class, connector::connect);

        assertThat(e).hasCauseThat().isInstanceOf(SerialOpenException.class);
        assertThat(e.getMessage()).contains("COM9");
        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    void aPolicyThatDeniesSerialConnectionsKeepsThePortClosed() {
        java.util.concurrent.atomic.AtomicBoolean opened = new java.util.concurrent.atomic.AtomicBoolean();
        SerialTtyConnector connector = new SerialTtyConnector(serialConnection(port("COM3")), s -> {
            opened.set(true);
            return new FakeLink();
        }, () -> false);

        IOException e = expectThrows(IOException.class, connector::connect);

        assertThat(e.getMessage()).isNotEmpty();
        assertThat(opened.get()).isFalse();
        assertThat(connector.isConnected()).isFalse();
    }

    @Test
    void refusesAConnectionOfAnotherProtocol() {
        ServerConnection ssh = new ServerConnection();
        SerialTtyConnector connector = new SerialTtyConnector(ssh, s -> new FakeLink());

        assertThrows(IllegalStateException.class, connector::connect);
    }

    @Test
    void editingTheConnectionLaterDoesNotChangeAnOpenPort() throws Exception {
        ServerConnection connection = serialConnection(port("COM3"));
        AtomicReference<SerialSettings> opened = new AtomicReference<>();
        SerialTtyConnector connector = new SerialTtyConnector(connection, s -> {
            opened.set(s);
            return new FakeLink();
        });

        connection.getSerialSettings().setPortName("COM4");
        connector.connect();

        assertThat(opened.get().getPortName()).isEqualTo("COM3");
    }

    @Test
    void openErrorsMapToTheirOwnMessages() {
        String unix = SerialTtyConnector.describe(
            new SerialOpenException(SerialOpenException.Reason.PERMISSION_DENIED, 13, "x"), "COM3", false);
        String windows = SerialTtyConnector.describe(
            new SerialOpenException(SerialOpenException.Reason.PERMISSION_DENIED, 5, "x"), "COM3", true);
        // Only Unix gets the group hint after the common sentence.
        assertThat(unix).startsWith(windows);
        assertThat(unix.length()).isGreaterThan(windows.length());
        assertThat(windows).contains("COM3");
        assertThat(SerialTtyConnector.describe(
            new SerialOpenException(SerialOpenException.Reason.OTHER, 42, "x"), "COM3", true))
            .contains("42");
    }

    @Test
    void openErrorCodesAreClassifiedPerPlatform() {
        assertThat(JSerialCommLink.reasonFor(5, true)).isEqualTo(SerialOpenException.Reason.BUSY);
        assertThat(JSerialCommLink.reasonFor(2, true)).isEqualTo(SerialOpenException.Reason.NOT_FOUND);
        assertThat(JSerialCommLink.reasonFor(13, false)).isEqualTo(SerialOpenException.Reason.PERMISSION_DENIED);
        assertThat(JSerialCommLink.reasonFor(16, false)).isEqualTo(SerialOpenException.Reason.BUSY);
        assertThat(JSerialCommLink.reasonFor(2, false)).isEqualTo(SerialOpenException.Reason.NOT_FOUND);
        assertThat(JSerialCommLink.reasonFor(99, false)).isEqualTo(SerialOpenException.Reason.OTHER);
    }

    @Test
    void instantEmptyReadsInARowAreAHangupButAQuietLineIsNot() {
        JSerialCommLink.HangupDetector detector = new JSerialCommLink.HangupDetector();
        long waited = JSerialCommLink.READ_TIMEOUT_MS * 1_000_000L;
        for (int i = 0; i < 100; i++) {
            assertThat(detector.hungUp(waited)).isFalse();
        }
        for (int i = 1; i < JSerialCommLink.HangupDetector.HANGUP_INSTANT_READS; i++) {
            assertThat(detector.hungUp(0)).isFalse();
        }
        detector.dataRead();
        for (int i = 1; i < JSerialCommLink.HangupDetector.HANGUP_INSTANT_READS; i++) {
            assertThat(detector.hungUp(0)).isFalse();
        }
        assertThat(detector.hungUp(0)).isTrue();
    }

    @Test
    void lineEndingTranslationLeavesOtherBytesAlone() {
        byte[] bytes = {'a', '\r', 0x03, '\n'};
        assertThat(SerialTtyConnector.translateLineEndings(bytes, SerialSettings.LineEnding.CR)).isSameInstanceAs(bytes);
        assertThat(SerialTtyConnector.translateLineEndings(bytes, SerialSettings.LineEnding.CRLF))
            .isEqualTo(new byte[] {'a', '\r', '\n', 0x03, '\n'});
    }
}
