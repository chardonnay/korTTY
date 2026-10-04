package de.kortty.ui;

import de.kortty.core.RemoteDirectoryChange;
import de.kortty.core.SFTPSession;
import de.kortty.core.sftp.SftpLoopbackFixture;
import de.kortty.core.sftp.SftpSubsystemUnavailableException;
import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.SftpOpenTargetResolver;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

/**
 * "Open SFTP here" in borrowed mode, headless: the pieces the SFTP tab is built from (the pane's
 * {@link PaneSessionSupplier}, {@link SftpOpenTargetResolver} and {@link SFTPSession#attach})
 * against a loopback MINA server. The start folder is listed on the terminal's own session,
 * closing the SFTP side leaves that session open, a split pane with its own origin opens as its
 * own user, Reconnect re-borrows the reconnected session, and a pane re-pointed elsewhere gives the
 * plain failure that sends MainWindow to a separate login.
 */
class SftpOpenHereIntegrationTest {

    private final Object viewStandIn = new Object();
    private final Object paneStandIn = new Object();

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private Path tempDir;
    private SftpLoopbackFixture fixture;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-sftp-open-here");
        fixture = SftpLoopbackFixture.builder(tempDir).start();
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (fixture != null) {
            fixture.close();
        }
        deleteTree(tempDir);
    }

    @Test
    void theStartFolderIsListedOnTheTerminalSessionAndClosingKeepsItOpen() throws Exception {
        Files.createDirectories(fixture.root().resolve("work"));
        Files.writeString(fixture.root().resolve("work/notes.txt"), "x", StandardCharsets.UTF_8);
        ClientSession terminal = fixture.connect();
        ServerConnection pane = connection("pane", SftpLoopbackFixture.USER);
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>(paneSession(pane, terminal));

        SftpOpenTargetResolver.OpenTarget target = SftpOpenTargetResolver.resolve(new SftpOpenTargetResolver.Inputs(
            false, "/work", RemoteDirectoryChange.Source.OSC7, null, "/", false));
        SFTPSession sftp = SFTPSession.attach(supplier(pane, now), pane, "it");

        assertThat(target.startPath()).isEqualTo("/work");
        assertThat(names(sftp, target.startPath())).contains("notes.txt");
        assertThat(sftp.ownsSession()).isFalse();

        sftp.close();
        await(() -> fixture.stats().openChannels() == 0, "the SFTP channel closes on the server");
        assertThat(terminal.isOpen()).isTrue();
    }

    @Test
    void aSplitPaneWithItsOwnOriginOpensAsItsOwnUser() throws Exception {
        ClientSession paneSession = fixture.connect();
        ServerConnection tabConnection = connection("tab", "alice");
        ServerConnection paneConnection = connection("split", SftpLoopbackFixture.USER);
        AtomicReference<PaneSessionSupplier.PaneSession> now =
            new AtomicReference<>(paneSession(paneConnection, paneSession));

        SFTPSession sftp = SFTPSession.attach(supplier(paneConnection, now), paneConnection, "it");
        assertThat(sftp.getConnection()).isSameInstanceAs(paneConnection);
        assertThat(sftp.getConnection().getUsername()).isEqualTo(SftpLoopbackFixture.USER);
        sftp.close();

        // A supplier keyed to the tab's connection never hands out the split pane's session.
        IOException wrongIdentity = expectThrows(IOException.class,
            () -> SFTPSession.attach(supplier(tabConnection, now), tabConnection, "it"));
        assertThat(wrongIdentity).isNotInstanceOf(SftpSubsystemUnavailableException.class);
        assertThat(paneSession.isOpen()).isTrue();
    }

    @Test
    void reconnectReborrowsTheReconnectedSession() throws Exception {
        ServerConnection pane = connection("pane", SftpLoopbackFixture.USER);
        ClientSession first = fixture.connect();
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>(paneSession(pane, first));
        PaneSessionSupplier<Object, Object> supplier = supplier(pane, now);
        SFTPSessionFactoryUnderTest reattach = () -> SFTPSession.attach(supplier, pane, "it");

        SFTPSession attached = reattach.open();
        first.close(true);
        await(() -> !attached.isConnected(), "the borrowed SFTP side notices the closed terminal session");
        attached.close();

        // The pane is gone: the tab's Reconnect fails plainly and offers a separate login instead.
        IOException gone = expectThrows(IOException.class, reattach::open);
        assertThat(gone).isNotInstanceOf(SftpSubsystemUnavailableException.class);

        ClientSession second = fixture.connect();
        now.set(paneSession(pane, second));
        SFTPSession again = reattach.open();
        assertThat(names(again, "/")).isNotNull();
        again.close();
        assertThat(second.isOpen()).isTrue();
    }

    @Test
    void theTabsBorrowedPathGoesThroughItsFactoryAndKeepsTheQueueHooks() throws IOException {
        String tab = Files.readString(Path.of("src/main/java/de/kortty/ui/SFTPManagerTab.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        int start = tab.indexOf("private void connectBorrowed(");
        String borrowed = tab.substring(start, tab.indexOf("\n    }\n", start));
        assertThat(borrowed).contains("borrowedSessions.open()");
        assertThat(borrowed).doesNotContain("new SFTPSession(");
        assertThat(tab).contains("transferQueueHost.onSessionReady(session);");
        assertThat(tab).contains("transferQueueHost.onSessionLost();");
        String window = Files.readString(Path.of("src/main/java/de/kortty/ui/MainWindow.java"), StandardCharsets.UTF_8)
            .replace("\r\n", "\n");
        assertThat(window).contains("de.kortty.core.SFTPSession.attach(request.supplier(), request.paneConnection(), request.label())");
        assertThat(window).contains("Telemetry.track(TelemetryEvents.SFTP_OPENED, Map.of(\"borrowed\", true));");
        assertThat(window).contains("Telemetry.track(TelemetryEvents.SFTP_OPENED, Map.of(\"borrowed\", false));");
    }

    /** The shape of {@code SFTPManagerTab.SessionFactory}, without loading the JavaFX tab class. */
    @FunctionalInterface
    private interface SFTPSessionFactoryUnderTest {
        SFTPSession open() throws Exception;
    }

    private PaneSessionSupplier<Object, Object> supplier(ServerConnection attached,
            AtomicReference<PaneSessionSupplier.PaneSession> now) {
        // The supplier holds view and pane weakly: the stand-ins stay reachable through fields, or a
        // GC during the test makes get() return null as for a closed pane.
        return new PaneSessionSupplier<>(viewStandIn, paneStandIn, PaneSessionSupplier.Identity.of(attached),
            (view, pane) -> now.get());
    }

    private static PaneSessionSupplier.PaneSession paneSession(ServerConnection origin, ClientSession session) {
        PaneSessionSupplier.Identity identity = PaneSessionSupplier.Identity.of(origin);
        return new PaneSessionSupplier.PaneSession(identity, identity, session);
    }

    private ServerConnection connection(String id, String user) {
        ServerConnection connection = new ServerConnection(id, "127.0.0.1", fixture.port(), user);
        connection.setId(id);
        return connection;
    }

    private static List<String> names(SFTPSession sftp, String path) throws IOException {
        List<String> names = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftp.listFiles(path)) {
            names.add(entry.getFilename());
        }
        return names;
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertWithMessage(what).that(condition.getAsBoolean()).isTrue();
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }
}
