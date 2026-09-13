package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.testng.SkipException;
import org.testng.annotations.Test;

import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

/** Live-pty checks for {@link LocalShellTtyConnector#getShellPid()}, the hook coding-agent detection walks from. */
class LocalShellTtyConnectorShellPidTest {

    @Test
    void shellPidIsEmptyBeforeConnect() {
        LocalShellTtyConnector connector = new LocalShellTtyConnector(localShell("/bin/sh"));
        assertThat(connector.getShellPid().isPresent()).isFalse();
        assertThat(connector.isForeignSessionSuspected()).isFalse();
    }

    @Test(timeOut = 30_000)
    void shellPidIsTheLiveShellWhileConnectedAndEmptyAfterClose() throws Exception {
        skipUnlessPosix();
        LocalShellTtyConnector connector = new LocalShellTtyConnector(localShell("/bin/sh"));
        if (!tryConnect(connector)) {
            throw new SkipException("No pty available for a live local shell in this environment");
        }
        Thread reader = startDrainingReader(connector, "test-shell-pid-reader");
        try {
            assertThat(connector.getShellPid().isPresent()).isTrue();
            long pid = connector.getShellPid().getAsLong();
            assertThat(pid).isGreaterThan(0L);
            assertThat(ProcessHandle.of(pid).isPresent()).isTrue();
            assertThat(connector.isForeignSessionSuspected()).isFalse();
        } finally {
            connector.close();
        }
        assertThat(connector.getShellPid().isPresent()).isFalse();
        reader.join(5_000);
    }

    @Test(timeOut = 30_000)
    void remoteClientShellCommandIsForeignAndExposesNoPid() throws Exception {
        skipUnlessPosix();
        LocalShellTtyConnector connector = new LocalShellTtyConnector(localShell("ssh localhost"));
        assertThat(connector.isForeignSessionSuspected()).isFalse();
        assertThat(connector.getShellPid().isPresent()).isFalse();
        Thread reader = null;
        try {
            // connect() may fail when no ssh binary exists; the contract must hold either way.
            if (tryConnect(connector)) {
                reader = startDrainingReader(connector, "test-remote-shell-reader");
            }
            assertThat(connector.isForeignSessionSuspected()).isTrue();
            assertThat(connector.getShellPid().isPresent()).isFalse();
        } finally {
            connector.close();
        }
        if (reader != null) {
            reader.join(5_000);
        }
    }

    private static ServerConnection localShell(String command) {
        ServerConnection connection = new ServerConnection();
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand(command);
        return connection;
    }

    private static void skipUnlessPosix() {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("Live local shell pids are only verified on POSIX");
        }
    }

    private static boolean tryConnect(LocalShellTtyConnector connector) {
        try {
            return connector.connect();
        } catch (Exception e) {
            return false;
        }
    }

    /** Drains shell output so the pty never blocks on a full buffer. */
    private static Thread startDrainingReader(LocalShellTtyConnector connector, String name) {
        Thread reader = new Thread(() -> {
            char[] buf = new char[4096];
            try {
                while (connector.read(buf, 0, buf.length) >= 0) {
                    // discard
                }
            } catch (Exception ignored) {
            }
        }, name);
        reader.setDaemon(true);
        reader.start();
        return reader;
    }
}
