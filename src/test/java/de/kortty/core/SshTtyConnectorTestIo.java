package de.kortty.core;

import de.kortty.model.ServerConnection;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/** Drives a never-connected {@link SshTtyConnector} through its real read and write paths. */
final class SshTtyConnectorTestIo {

    private SshTtyConnectorTestIo() {
    }

    /** A connector configured with an IP, as many connections are; no socket is ever opened. */
    static SshTtyConnector connector() {
        return new SshTtyConnector(new ServerConnection("web", "192.0.2.10", 22, "daniel"), "pw");
    }

    /** Delivers {@code output} as the server would, through {@link SshTtyConnector#read}, in chunks of {@code readSize}. */
    static void receive(SshTtyConnector connector, String output, int readSize) throws Exception {
        field("reader").set(connector, new InputStreamReader(
            new ByteArrayInputStream(output.getBytes(StandardCharsets.UTF_8)), StandardCharsets.UTF_8));
        ((AtomicBoolean) field("connected").get(connector)).set(true);
        char[] buffer = new char[readSize];
        while (connector.read(buffer, 0, readSize) > 0) {
            // every read notifies the connector's own trackers and data listeners
        }
    }

    /** Types {@code input} into the session through {@link SshTtyConnector#write(String)}. */
    static ByteArrayOutputStream type(SshTtyConnector connector, String input) throws Exception {
        ByteArrayOutputStream sent = new ByteArrayOutputStream();
        field("outputStream").set(connector, sent);
        ((AtomicBoolean) field("connected").get(connector)).set(true);
        connector.write(input);
        return sent;
    }

    static String osc7(String host, String path) {
        return "\u001B]7;file://" + host + path + "\u0007";
    }

    private static Field field(String name) throws ReflectiveOperationException {
        Field field = SshTtyConnector.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
