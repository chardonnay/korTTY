package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.settings.SettingsProvider;
import de.kortty.core.LanguageManager;
import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.TerminalOutputHighlighter;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
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
 * Offline generator for the manual's picture of keyword highlighting in terminal output: three real
 * terminal panes, each showing one built-in rule set — <b>Errors and warnings</b> on an application log,
 * <b>Network devices</b> on a switch's interface list and <b>Network addresses</b> on {@code ip}
 * output.
 *
 * <p>The panes are the app's own {@link KorttyTermWidget} with the settings provider a terminal tab
 * uses (default terminal settings, so the default colors), fed by a connector that prints a fixed
 * demo transcript. Every address is a documentation address (RFC 5737, RFC 3849) or loopback, every
 * host name is made up. The highlighting is the real {@link TerminalOutputHighlighter}; its passes
 * are run by hand, so the picture does not depend on timing.
 *
 * <p>Run via the {@code generateHighlightingTerminalScreenshot} Gradle task. Exit 0 = OK.
 */
public final class HighlightingTerminalScreenshotGenerator {

    private static final String OUTPUT_FILE = "app-docs/screenshots/highlighting/terminal-highlighting.png";

    private static final double PANE_WIDTH = 560;
    private static final double TOP_HEIGHT = 176;
    private static final double BOTTOM_HEIGHT = 92;
    private static final double GAP = 4;

    private static final String ESC = "\u001b";
    private static final String HIDE_CURSOR = ESC + "[?25l";

    private HighlightingTerminalScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        Path isolatedHome = Files.createTempDirectory("kortty-highlighting-terminal-screenshot");
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

                List<Pane> panes = List.of(
                    pane(terminal, HighlightBuiltinSets.ERRORS, logTranscript(), connectors),
                    pane(terminal, HighlightBuiltinSets.NETWORK_DEVICES, switchTranscript(), connectors),
                    pane(terminal, HighlightBuiltinSets.NETWORK, addressTranscript(), connectors));

                GridPane grid = new GridPane();
                grid.setHgap(GAP);
                grid.setVgap(GAP);
                grid.setPadding(new Insets(GAP));
                grid.setStyle("-fx-background-color: #3c3f41;");
                grid.add(sized(panes.get(0).widget(), PANE_WIDTH, TOP_HEIGHT), 0, 0);
                grid.add(sized(panes.get(1).widget(), PANE_WIDTH, TOP_HEIGHT), 1, 0);
                grid.add(sized(panes.get(2).widget(), PANE_WIDTH * 2 + GAP, BOTTOM_HEIGHT), 0, 1, 2, 1);

                Stage stage = new Stage();
                stage.setScene(new Scene(grid));
                stage.show();
                for (Pane pane : panes) {
                    pane.widget().start();
                }

                // Let the emulators take in their transcripts, then highlight and let the panes repaint.
                PauseTransition settle = new PauseTransition(Duration.millis(1_500));
                settle.setOnFinished(event -> {
                    try {
                        for (Pane pane : panes) {
                            for (int passes = 0; passes < 50 && pane.highlighter().runPassNow().backlog(); passes++) {
                                // run until the pane has nothing left to evaluate
                            }
                        }
                        PauseTransition paint = new PauseTransition(Duration.millis(700));
                        paint.setOnFinished(painted -> {
                            try {
                                capture(grid);
                            } catch (Throwable t) {
                                failure.compareAndSet(null, stack(t));
                            } finally {
                                for (Pane pane : panes) {
                                    pane.highlighter().close();
                                }
                                stage.close();
                                done.countDown();
                            }
                        });
                        paint.play();
                    } catch (Throwable t) {
                        failure.compareAndSet(null, stack(t));
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

    private record Pane(SithTermFxWidget widget, TerminalOutputHighlighter highlighter) {
    }

    private static Pane pane(ConnectionSettings terminal, String setId, String transcript,
                             List<DemoConnector> connectors) throws Exception {
        SithTermFxWidget widget = new KorttyTermWidget(80, 24, terminalSettingsProvider(terminal));
        DemoConnector connector = new DemoConnector(transcript + HIDE_CURSOR);
        connectors.add(connector);
        widget.setTtyConnector(connector);
        CompiledHighlightSet set = CompiledHighlightSet.compile(HighlightBuiltinSets.byId(setId));
        com.sithtermfx.ui.TerminalPanel panel = widget.getTerminalPanel();
        TerminalOutputHighlighter highlighter = new TerminalOutputHighlighter(widget.getTerminalTextBuffer(), set,
            panel::repaint, () -> { }, () -> false, () -> false, (task, delayMillis) -> { });
        return new Pane(widget, highlighter);
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

    private static Region sized(SithTermFxWidget widget, double width, double height) {
        Region region = (Region) widget.getPane();
        region.setMinSize(width, height);
        region.setPrefSize(width, height);
        region.setMaxSize(width, height);
        return region;
    }

    private static String prompt(String host, String directory) {
        return ESC + "[1;32mops@" + host + ESC + "[0m:" + ESC + "[1;34m" + directory + ESC + "[0m$ ";
    }

    private static String lines(String... lines) {
        return String.join("\r\n", lines);
    }

    private static String logTranscript() {
        return lines(
            prompt("web01", "~") + "tail -n 9 /var/log/shop/orders.log",
            "2026-10-03 09:14:02 INFO  order 1042 stored in 12 ms",
            "2026-10-03 09:14:05 WARN  payment gateway timed out, retrying",
            "2026-10-03 09:14:07 ERROR order 1043 failed: connection refused",
            "java.net.ConnectException: Connection refused",
            "2026-10-03 09:14:09 INFO  order 1044 stored in 9 ms",
            "2026-10-03 09:14:11 WARN  option cache.size is deprecated",
            "2026-10-03 09:14:12 FATAL worker 3 exited: permission denied",
            "2026-10-03 09:14:13 INFO  worker 3 restarted",
            prompt("web01", "~"));
    }

    private static String switchTranscript() {
        return lines(
            "switch01# show interfaces status",
            "Port      Name      Status       Vlan   Speed  Type",
            "Gi1/0/1   uplink    connected    trunk  1000   1000BaseT",
            "Gi1/0/2   web01     connected    10     1000   1000BaseT",
            "Gi1/0/3   web02     notconnect   10     auto   1000BaseT",
            "Gi1/0/4   backup    err-disabled 20     auto   1000BaseT",
            "Te1/1/1   core      connected    trunk  10G    SFP-10GBase-SR",
            "switch01# show interfaces Gi1/0/4 | include protocol",
            "GigabitEthernet1/0/4 is down, line protocol is down",
            "switch01# ");
    }

    private static String addressTranscript() {
        return lines(
            prompt("web01", "~") + "ip -br addr; ip neigh",
            "lo      UNKNOWN  127.0.0.1/8 ::1/128",
            "eth0    UP       192.0.2.17/24 2001:db8::17/64 fe80::1a2b:3cff:fe4d:5e6f/64",
            "192.0.2.1 dev eth0 lladdr 00:1a:2b:3c:4d:5e REACHABLE",
            prompt("web01", "~"));
    }

    private static void capture(GridPane grid) throws Exception {
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#3c3f41"));
        params.setTransform(Transform.scale(2, 2));
        WritableImage image = grid.snapshot(params, null);

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
            return "highlighting-screenshot";
        }

        @Override
        public void close() {
            connected = false;
            closed.countDown();
        }
    }
}
