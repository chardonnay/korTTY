package de.kortty.ui;

import de.kortty.core.RemoteDirectoryChange;
import de.kortty.core.SFTPSession;
import de.kortty.core.sftp.SftpLoopbackFixture;
import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.RemoteFollowController;
import de.kortty.ui.sftp.RemoteSidebarBrowser;
import de.kortty.ui.sftp.SftpFileItem;
import org.apache.sshd.client.session.ClientSession;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The remote files sidebar's engine against a loopback MINA server, headless: the follow
 * controller drives {@link RemoteSidebarBrowser} on a borrowed terminal session. It lists
 * {@code /tmp/a}, an OSC 7 change to {@code /tmp/b} lists again, a missing folder keeps the last
 * listing, and hiding the sidebar returns the server's channel count to its baseline while the
 * terminal session stays open. Plus source pins for the FX side, which needs a toolkit to run.
 */
class TerminalRemoteSidebarFixtureTest {

    private final Object viewStandIn = new Object();
    private final Object paneStandIn = new Object();

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private Path tempDir;
    private SftpLoopbackFixture fixture;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-remote-sidebar");
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
    void listsFollowsOsc7AndReleasesItsChannelWhenHidden() throws Exception {
        Files.createDirectories(fixture.root().resolve("tmp/a"));
        Files.createDirectories(fixture.root().resolve("tmp/b"));
        Files.writeString(fixture.root().resolve("tmp/a/one.txt"), "1", StandardCharsets.UTF_8);
        Files.writeString(fixture.root().resolve("tmp/b/two.txt"), "2", StandardCharsets.UTF_8);
        ClientSession terminal = fixture.connect();
        int baseline = fixture.stats().openChannels();
        ServerConnection pane = connection();
        AtomicReference<PaneSessionSupplier.PaneSession> now = new AtomicReference<>(paneSession(pane, terminal));
        // Weakly held by the supplier: fields keep the stand-ins reachable, or a GC empties it.
        PaneSessionSupplier<Object, Object> supplier = new PaneSessionSupplier<>(viewStandIn, paneStandIn,
            PaneSessionSupplier.Identity.of(pane), (view, p) -> now.get());

        List<RemoteSidebarBrowser.Result> results = new CopyOnWriteArrayList<>();
        RemoteSidebarBrowser browser = new RemoteSidebarBrowser(results::add, Runnable::run, () -> { });
        List<Runnable> due = new ArrayList<>();
        RemoteFollowController controller = new RemoteFollowController(
            (task, delay) -> {
                due.add(task);
                return () -> due.remove(task);
            },
            new RemoteFollowController.Listener() {
                @Override
                public void listRequested(String paneKey, String path) {
                    browser.list(paneKey, () -> SFTPSession.attach(supplier, pane, "sidebar"), path);
                }

                @Override
                public void stateChanged(RemoteFollowController.State state, String path) {
                }
            });

        controller.activate("p1", "/tmp/a", RemoteFollowController.Verdict.NATIVE);
        RemoteSidebarBrowser.Listing first = awaitListing(results, "/tmp/a");
        assertThat(names(first)).containsExactly("..", "one.txt").inOrder();
        assertThat(fixture.stats().openChannels()).isEqualTo(baseline + 1);

        controller.directoryChanged("p1", "/tmp/b", RemoteDirectoryChange.Source.OSC7);
        assertThat(due).hasSize(1);
        due.remove(0).run(); // the debounce ran out
        RemoteSidebarBrowser.Listing second = awaitListing(results, "/tmp/b");
        assertThat(names(second)).containsExactly("..", "two.txt").inOrder();
        // Still the one session: following does not open a channel per folder.
        assertThat(fixture.stats().openChannels()).isEqualTo(baseline + 1);

        controller.directoryChanged("p1", "/tmp/gone", RemoteDirectoryChange.Source.OSC7);
        due.remove(0).run();
        await(() -> results.stream().anyMatch(r -> r instanceof RemoteSidebarBrowser.Failure),
            "a missing folder is reported, not thrown");
        RemoteSidebarBrowser.Failure missing = (RemoteSidebarBrowser.Failure) results.stream()
            .filter(r -> r instanceof RemoteSidebarBrowser.Failure).findFirst().orElseThrow();
        assertThat(missing.kind()).isEqualTo(RemoteSidebarBrowser.FailureKind.NOT_FOUND);

        browser.release();
        await(() -> fixture.stats().openChannels() == baseline, "hiding the sidebar closes its SFTP channel");
        assertThat(terminal.isOpen()).isTrue();

        browser.close();
        assertThat(browser.awaitClosed(TIMEOUT.toMillis())).isTrue();
        assertThat(terminal.isOpen()).isTrue();
    }

    @Test
    void relativeAndHomeFoldersResolveAgainstTheLoginFolder() throws Exception {
        java.lang.reflect.Method resolve = RemoteSidebarBrowser.class
            .getDeclaredMethod("resolveFolder", String.class, String.class);
        resolve.setAccessible(true);
        assertThat(resolve.invoke(null, "~", "/home/t")).isEqualTo("/home/t");
        assertThat(resolve.invoke(null, "~/src/../bin", "/home/t")).isEqualTo("/home/t/bin");
        assertThat(resolve.invoke(null, "work", "/home/t")).isEqualTo("/home/t/work");
        assertThat(resolve.invoke(null, null, "/home/t")).isEqualTo("/home/t");
        assertThat(resolve.invoke(null, "/var//log/", "/home/t")).isEqualTo("/var/log");
    }

    @Test
    void theFxSidebarGatesTransfersUpFrontAndClosesWithTheTab() throws IOException {
        String sidebar = source("src/main/java/de/kortty/ui/TerminalRemoteSidebar.java");
        assertThat(sidebar).contains("FileTransferGate.Route.SFTP_UPLOAD");
        assertThat(sidebar).contains("FileTransferGate.Route.SFTP_DOWNLOAD");
        assertThat(sidebar).contains("FileTransferGate.Route.SFTP_DRAG_OUT");
        // Greyed out before a click, and drags rejected while dragging.
        assertThat(sidebar).contains("uploadButton.setDisable(");
        assertThat(sidebar).contains("downloadButton.setDisable(");
        // Browse only: the sidebar never types into the terminal (D11).
        assertThat(sidebar).doesNotContain("sendString(");
        assertThat(sidebar).doesNotContain(".write(");
        String view = source("src/main/java/de/kortty/ui/TerminalView.java");
        int cleanup = view.indexOf("public void cleanup() {");
        assertThat(view.substring(cleanup, view.indexOf("\n    }\n", cleanup))).contains("disposeRemoteSidebar();");
    }

    @Test
    void theSidebarUsesTheFileBrowserLookSoItsLabelsStayReadable() throws IOException {
        String sidebar = source("src/main/java/de/kortty/ui/TerminalRemoteSidebar.java");
        // Modena's light -fx-background under the Normal design hid the app's light label text.
        assertThat(sidebar).doesNotContain("-fx-background-color: -fx-background;");
        assertThat(sidebar).contains("getStyleClass().add(\"file-browser-panel\");");
        assertThat(sidebar).contains("/styles/filebrowser.css");
        assertThat(sidebar).contains("table.getStyleClass().add(\"file-browser-table\");");
        // The emoji type glyphs have no glyph in the table's monospace font: the manager's icons instead.
        assertThat(sidebar).contains("SFTPManagerTab.installTypeIconCell(type);");
    }

    @Test
    void dropsOnTheSidebarNeverFallThroughToTheShellsFolder() throws IOException {
        String sidebar = source("src/main/java/de/kortty/ui/TerminalRemoteSidebar.java");
        // The whole sidebar takes (or refuses) drops, not only its table: a drop on the header or
        // the banner bubbled up to the terminal view and was copied into the shell's folder.
        assertThat(sidebar).contains("\n        setOnDragOver(event -> {");
        assertThat(sidebar).contains("\n        setOnDragDropped(event -> {");
        assertThat(sidebar).doesNotContain("table.setOnDragOver(");
        String view = source("src/main/java/de/kortty/ui/TerminalView.java");
        int start = view.indexOf("private @Nullable SithTermFxWidget fileDropPane(DragEvent event) {");
        assertThat(view.substring(start, view.indexOf("\n    }\n", start)))
            .contains("if (isInsideRemoteSidebarDock(event.getTarget())) {");
    }

    @Test
    void theSidebarChecksTheIdentityBeforeFollowingAndRechecksWhilePaused() throws IOException {
        String sidebar = source("src/main/java/de/kortty/ui/TerminalRemoteSidebar.java");
        int start = sidebar.indexOf("private void onDirectoryChange(");
        String method = sidebar.substring(start, sidebar.indexOf("\n    }\n", start));
        // The reported folder reaches the controller only after the verdict ran.
        assertThat(method).contains("checkThen(() -> {");
        assertThat(method.indexOf("checkThen(() -> {"))
            .isLessThan(method.lastIndexOf("controller.directoryChanged(key, change.path(), change.source());"));
        // exit from su reports no folder: a paused sidebar polls the prompt to resume.
        assertThat(sidebar).contains("updateForeignRecheck(state);");
        assertThat(sidebar).contains("stopForeignRecheck();");
    }

    private static String source(String path) throws IOException {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8).replace("\r\n", "\n");
    }

    private RemoteSidebarBrowser.Listing awaitListing(List<RemoteSidebarBrowser.Result> results, String path)
            throws InterruptedException {
        await(() -> results.stream().anyMatch(r -> r instanceof RemoteSidebarBrowser.Listing l && path.equals(l.path())),
            "the sidebar lists " + path);
        return results.stream()
            .filter(r -> r instanceof RemoteSidebarBrowser.Listing l && path.equals(l.path()))
            .map(RemoteSidebarBrowser.Listing.class::cast)
            .findFirst().orElseThrow();
    }

    private static List<String> names(RemoteSidebarBrowser.Listing listing) {
        return listing.items().stream().map(SftpFileItem::getName).toList();
    }

    private ServerConnection connection() {
        ServerConnection connection = new ServerConnection("pane", "127.0.0.1", fixture.port(), SftpLoopbackFixture.USER);
        connection.setId("pane");
        return connection;
    }

    private static PaneSessionSupplier.PaneSession paneSession(ServerConnection origin, ClientSession session) {
        PaneSessionSupplier.Identity identity = PaneSessionSupplier.Identity.of(origin);
        return new PaneSessionSupplier.PaneSession(identity, identity, session);
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
