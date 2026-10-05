package com.sithtermfx.ui.split;

import atlantafx.base.theme.PrimerDark;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Bounds;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of the dividers between split panes in a see-through window, with the
 * AtlantaFX Primer Dark design (which draws them half see-through): in transparent mode every
 * divider, including those of a split made afterwards, is filled with the host's colour at full
 * opacity, so the backdrop (bright red here) no longer shows through as a gap between the panes;
 * leaving transparent mode gives the design's divider back. Pass a file path to also save a
 * snapshot as PNG. Run via the {@code transparentSplitDividerSmoke} Gradle task. Exit 0 = OK.
 */
public final class TransparentSplitDividerSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;

    private static final String FILL = "rgba(54,54,54,0.500)";

    private TransparentSplitDividerSmoke() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        String snapshotPath = args.length > 0 ? args[0] : null;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<TerminalSplitPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
                LanguageManager.getInstance().initialize(new GlobalSettings());
                TerminalSplitPane splitPane = new TerminalSplitPane(
                    () -> new DynamicFontSizeSettingsProvider(14f), request -> new IdleTtyConnector(), widget -> { });
                paneRef.set(splitPane);
                StackPane backdrop = new StackPane(splitPane);
                backdrop.setStyle("-fx-background-color: #ff0000;");
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(backdrop, 960, 600));
                stage.show();
                Thread worker = new Thread(() -> verify(splitPane, snapshotPath, failure, done), "divider-smoke");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable error) {
                failure.compareAndSet(null, "Setup failed: " + stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
        Platform.runLater(() -> {
            try {
                if (paneRef.get() != null) paneRef.get().closeAll();
                if (stageRef.get() != null) stageRef.get().close();
            } catch (Exception ignored) {
            }
            Platform.exit();
        });
        if (!finished) {
            System.err.println("SMOKE TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SMOKE FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SMOKE OK: in transparent mode every split divider is filled at full opacity,"
            + " no backdrop shows between the panes, and the design's divider returns afterwards");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane splitPane, String snapshotPath, AtomicReference<String> failure,
                               CountDownLatch done) {
        try {
            SithTermFxWidget a = onFxThread(() -> splitPane.getAllWidgets().get(0));
            SithTermFxWidget b = onFxThread(() -> splitPane.splitWidget(a,
                SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL, new IdleTtyConnector()));
            check(b != null, "the split did not create a pane");
            settle();
            onFxThread(() -> {
                splitPane.setBackgroundTransparent(true);
                splitPane.setTransparentDividerColor(FILL);
                return null;
            });
            settle();
            // A split made while transparent gets a new split control whose skin builds new dividers.
            SithTermFxWidget c = onFxThread(() -> splitPane.splitWidget(a,
                SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.VERTICAL, new IdleTtyConnector()));
            check(c != null, "the nested split did not create a pane");
            settle();

            onFxThread(() -> {
                List<Region> dividers = dividers(splitPane);
                check(dividers.size() == 2, "expected 2 dividers, found " + dividers.size());
                for (Region divider : dividers) {
                    check(divider.getOpacity() == 1.0, "a divider is drawn at opacity " + divider.getOpacity());
                    check(divider.getBackground() != null && !divider.getBackground().getFills().isEmpty()
                        && Color.web(FILL).equals(divider.getBackground().getFills().get(0).getFill()),
                        "a divider is not filled with the host colour: " + divider.getBackground());
                    WritableImage image = splitPane.getScene().snapshot(null);
                    Bounds bounds = divider.localToScene(divider.getBoundsInLocal());
                    Color pixel = image.getPixelReader().getColor(
                        (int) Math.round(bounds.getCenterX()), (int) Math.round(bounds.getCenterY()));
                    // 50 % grey over the red backdrop: clearly not the bare backdrop.
                    check(pixel.getGreen() > 0.1, "the backdrop shows through a divider: " + pixel);
                }
                if (snapshotPath != null) {
                    ImageIO.write(SwingFXUtils.fromFXImage(splitPane.getScene().snapshot(null), null), "png",
                        new File(snapshotPath));
                }
                return null;
            });

            onFxThread(() -> {
                splitPane.setBackgroundTransparent(false);
                return null;
            });
            settle();
            onFxThread(() -> {
                for (Region divider : dividers(splitPane)) {
                    check(divider.getStyle() == null || divider.getStyle().isEmpty(),
                        "an opaque split keeps the transparent divider style: " + divider.getStyle());
                    check(divider.getOpacity() == 0.5, "the design's divider did not return: " + divider.getOpacity());
                }
                return null;
            });
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            done.countDown();
        }
    }

    private static List<Region> dividers(TerminalSplitPane splitPane) {
        List<Region> dividers = new ArrayList<>();
        for (Node node : splitPane.lookupAll(".split-pane-divider")) {
            if (node.getParent() instanceof SplitPane && node instanceof Region region) {
                dividers.add(region);
            }
        }
        return dividers;
    }
    private static void settle() throws Exception {
        onFxThread(() -> null);
        Thread.sleep(150);
        onFxThread(() -> null);
    }

    private static <T> T onFxThread(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        try {
            return task.get(STEP_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
        } catch (ExecutionException error) {
            if (error.getCause() instanceof AssertionError assertion) {
                throw assertion;
            }
            throw error;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static String stack(Throwable error) {
        java.io.StringWriter writer = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    /** A connected pty that prints nothing; read() blocks until the pane closes it. */
    private static final class IdleTtyConnector implements TtyConnector {
        private final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read(char[] buffer, int offset, int length) throws java.io.IOException {
            try {
                closed.await();
            } catch (InterruptedException error) {
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
            return closed.getCount() > 0;
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
            return false;
        }

        @Override
        public String getName() {
            return "transparent-split-divider-smoke";
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
