package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.settings.SettingsProvider;
import de.kortty.core.LanguageManager;
import de.kortty.core.SFTPSession;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.ui.sftp.RemoteFollowController;
import de.kortty.ui.sftp.RemoteSidebarBrowser;
import de.kortty.ui.sftp.SftpFileItem;
import de.kortty.ui.sftp.SftpFileItemComparators;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.TableView;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;

/**
 * Offline generator for the manual's picture of the remote files sidebar
 * ({@code app-docs/screenshots/terminal/remote-sidebar.png}).
 *
 * <p>The left side is the app's own {@link KorttyTermWidget} with a terminal tab's settings
 * provider, fed with a fixed demo transcript (a {@code cd} into a web root on the made-up host
 * {@code web01}). The right side is the real {@link TerminalRemoteSidebar}, given the matching
 * listing through its own result path ({@code onResult}) instead of a server, so the banner,
 * breadcrumb, buttons and table are exactly what the tab shows. No server, no real hosts.</p>
 *
 * <p>Run via the {@code generateRemoteSidebarScreenshot} Gradle task, then
 * {@code ./scripts/optimize-png.sh}. Exit 0 = OK.</p>
 */
public final class RemoteSidebarScreenshotGenerator {

    private static final String OUTPUT_FILE = "app-docs/screenshots/terminal/remote-sidebar.png";
    private static final double TERMINAL_WIDTH = 600;
    private static final double SIDEBAR_WIDTH = 400;
    private static final double HEIGHT = 440;
    private static final String PANE_KEY = "pane:demo/1";
    private static final String FOLDER = "/var/www/shop";

    private static final String ESC = "\u001b";
    private static final String HIDE_CURSOR = ESC + "[?25l";

    private RemoteSidebarScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-remote-sidebar-screenshot");
        System.setProperty("user.home", isolatedHome.toString());
        System.setProperty("TEST_MODE_KORTTY", "1");
        Locale.setDefault(Locale.ENGLISH);

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        List<DemoConnector> connectors = new ArrayList<>();

        Platform.startup(() -> {
            try {
                GlobalSettings settings = new GlobalSettings();
                settings.setLanguage("en");
                LanguageManager.getInstance().initialize(settings);
                ConnectionSettings terminal = settings.getDefaultTerminalSettings();

                SithTermFxWidget widget = new KorttyTermWidget(80, 24, terminalSettingsProvider(terminal));
                DemoConnector connector = new DemoConnector(transcript() + HIDE_CURSOR);
                connectors.add(connector);
                widget.setTtyConnector(connector);
                Region terminalRegion = (Region) widget.getPane();
                terminalRegion.setMinSize(TERMINAL_WIDTH, HEIGHT);
                terminalRegion.setPrefSize(TERMINAL_WIDTH, HEIGHT);
                terminalRegion.setMaxSize(TERMINAL_WIDTH, HEIGHT);

                TerminalRemoteSidebar sidebar = demoSidebar();
                sidebar.setMinWidth(SIDEBAR_WIDTH);
                sidebar.setPrefWidth(SIDEBAR_WIDTH);
                sidebar.setMaxWidth(SIDEBAR_WIDTH);

                HBox root = new HBox(terminalRegion, new ResizableDivider(Orientation.VERTICAL), sidebar);
                root.setFillHeight(true);
                var terminalCss = RemoteSidebarScreenshotGenerator.class.getResource("/styles/terminal.css");
                if (terminalCss != null) {
                    root.getStylesheets().add(terminalCss.toExternalForm());
                }
                String dynamicCss = ThemeCssSupport.getDynamicStylesheetUrl(
                    ThemeCssSupport.resolveThemeColors(settings, null));
                if (dynamicCss != null) {
                    root.getStylesheets().add(dynamicCss);
                }
                AppDesignStyleSupport.applyToParent(root);

                Stage stage = new Stage(StageStyle.UNDECORATED);
                stage.setScene(new Scene(root));
                stage.setX(-4000);
                stage.setY(-4000);
                stage.show();
                widget.start();

                // Let the emulator take in its transcript and repaint, then capture.
                PauseTransition settle = new PauseTransition(Duration.millis(1_800));
                settle.setOnFinished(event -> {
                    try {
                        root.applyCss();
                        root.layout();
                        capture(root);
                    } catch (Throwable t) {
                        failure.compareAndSet(null, stack(t));
                    } finally {
                        stage.close();
                        done.countDown();
                    }
                });
                settle.play();
            } catch (Throwable t) {
                failure.compareAndSet(null, stack(t));
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        for (DemoConnector connector : connectors) {
            connector.close();
        }
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        String fail = failure.get();
        if (fail != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + fail);
            System.exit(1);
        }
        System.exit(0);
    }

    /** The real sidebar, following the demo pane, with the demo folder delivered as a listing. */
    private static TerminalRemoteSidebar demoSidebar() throws Exception {
        ServerConnection connection = new ServerConnection();
        connection.setName("web01 (demo)");
        connection.setHost("web01.example.com");
        connection.setUsername("demo");
        // A tab that is never connected: the sidebar only reads its "Open in SFTP Manager" handler.
        TerminalView view = new TerminalView(connection, null);
        view.setSftpOpenAtHandler((pane, path) -> { });
        TerminalRemoteSidebar sidebar = new TerminalRemoteSidebar(view);

        RemoteFollowController controller = field(sidebar, "controller");
        controller.activate(PANE_KEY, FOLDER, RemoteFollowController.Verdict.NATIVE);
        // An unconnected session stands in for the borrowed one while the listing arrives, so the
        // transfer buttons show as they do while connected; nothing is ever sent over it.
        RemoteSidebarBrowser browser = field(sidebar, "browser");
        Field session = RemoteSidebarBrowser.class.getDeclaredField("session");
        session.setAccessible(true);
        session.set(browser, new SFTPSession(connection, null));

        List<SftpFileItem> items = new ArrayList<>(demoItems());
        items.sort(SftpFileItemComparators.parentFirst(SftpFileItemComparators.type(true)));
        Method onResult = TerminalRemoteSidebar.class.getDeclaredMethod("onResult", RemoteSidebarBrowser.Result.class);
        onResult.setAccessible(true);
        onResult.invoke(sidebar, new RemoteSidebarBrowser.Listing(1, PANE_KEY, FOLDER, items));

        TableView<SftpFileItem> table = field(sidebar, "table");
        items.stream().filter(item -> "index.php".equals(item.getName())).findFirst()
            .ifPresent(item -> table.getSelectionModel().select(item));
        return sidebar;
    }

    private static List<SftpFileItem> demoItems() {
        String dir = FOLDER + "/";
        return List.of(
            SftpFileItem.parent("/var/www"),
            folder("assets", dir, "2026-09-30 16:12"),
            folder("config", dir, "2026-09-12 08:47"),
            folder("logs", dir, "2026-10-04 09:41"),
            folder("vendor", dir, "2026-08-21 11:05"),
            file("index.php", dir, 4_310, "2026-10-02 14:20"),
            file("composer.json", dir, 1_127, "2026-08-21 11:03"),
            file("robots.txt", dir, 68, "2026-05-17 10:00"),
            file("deploy.sh", dir, 2_934, "2026-09-30 16:10"),
            file("shop-2026-10-03.sql.gz", dir, 18_874_368, "2026-10-03 02:00"));
    }

    private static SftpFileItem folder(String name, String dir, String date) {
        return SftpFileItem.fromDetails(name, dir + name, false, "<DIR>", date, "drwxr-xr-x", "demo", "www-data", -1);
    }

    private static SftpFileItem file(String name, String dir, long bytes, String date) {
        String size = bytes < 1024 ? bytes + " B"
            : bytes < 1024 * 1024 ? String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0)
            : String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
        return SftpFileItem.fromDetails(name, dir + name, true, size, date, "-rw-r--r--", "demo", "www-data", bytes);
    }

    private static String prompt(String directory) {
        return ESC + "[1;32mdemo@web01" + ESC + "[0m:" + ESC + "[1;34m" + directory + ESC + "[0m$ ";
    }

    private static String transcript() {
        return String.join("\r\n",
            prompt("~") + "cd /var/www/shop",
            prompt(FOLDER) + "git log --oneline -3",
            "4f2a9c1 Checkout: show delivery date",
            "9b77e0d Update composer dependencies",
            "c31d5aa Nightly database export",
            prompt(FOLDER) + "ls",
            "assets  composer.json  config  deploy.sh  index.php  logs  robots.txt",
            "shop-2026-10-03.sql.gz  vendor",
            prompt(FOLDER));
    }

    /** The settings provider of a terminal tab (TerminalView's private KorTTYSettingsProvider). */
    private static SettingsProvider terminalSettingsProvider(ConnectionSettings terminal) throws Exception {
        Class<?> type = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        Constructor<?> constructor = type.getDeclaredConstructor(ConnectionSettings.class,
            DynamicFontSizeSettingsProvider.class, IntSupplier.class);
        constructor.setAccessible(true);
        IntSupplier opaque = () -> 0;
        return (SettingsProvider) constructor.newInstance(terminal,
            new DynamicFontSizeSettingsProvider(terminal.getFontSize()), opaque);
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(target);
    }

    private static void capture(Region root) throws Exception {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = root.snapshot(params, null);

        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File outFile = new File(OUTPUT_FILE);
        File parent = outFile.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create output dir: " + parent.getAbsolutePath());
        }
        ImageIO.write(buffered, "png", outFile);
        System.out.println("Generated " + outFile.getAbsolutePath()
            + " (" + buffered.getWidth() + "x" + buffered.getHeight() + ")");
    }

    private static String stack(Throwable t) {
        StringWriter writer = new StringWriter();
        t.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /** Prints the transcript once, then waits silently until it is closed. */
    private static final class DemoConnector implements TtyConnector {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final char[] transcript;
        private int position;
        private volatile boolean connected = true;

        DemoConnector(String transcript) {
            this.transcript = transcript.toCharArray();
        }

        @Override
        public synchronized int read(char[] buffer, int offset, int length) {
            if (position < transcript.length) {
                int count = Math.min(length, transcript.length - position);
                System.arraycopy(transcript, position, buffer, offset, count);
                position += count;
                return count;
            }
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void write(byte[] bytes) {
        }

        @Override
        public void write(String string) {
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public void resize(@NotNull TermSize termSize) {
        }

        @Override
        public int waitFor() throws InterruptedException {
            closed.await();
            return 0;
        }

        @Override
        public boolean ready() {
            return position < transcript.length;
        }

        @Override
        public String getName() {
            return "remote-sidebar-screenshot";
        }

        @Override
        public void close() {
            connected = false;
            closed.countDown();
        }
    }
}
