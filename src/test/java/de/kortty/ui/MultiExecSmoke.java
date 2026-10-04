package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import com.sithtermfx.ui.split.TerminalSplitPane.PaneOverlayLayer;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.event.Event;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of multi-exec across two tabs: two split panes (standing in for two tabs, each
 * with two panes) share one {@link MultiExecCoordinator}. Three panes take part, one of them in the
 * other tab and one whose connection never returns from a write. Typed characters and Enter reach the
 * member in the other tab, the non-member gets nothing, and the FX thread keeps answering while the
 * stalled pane holds its write. A pane its tab's guard holds back gets nothing and is counted, the
 * members show the badge and the outline, the status chip counts panes and tabs, and Stop takes every
 * pane out. Pass a PNG path via --args to also save a snapshot. Run via the {@code multiExecSmoke}
 * Gradle task. Exit 0 = OK.
 */
public final class MultiExecSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;
    private static final long RESPONSIVE_MILLIS = 1_000;

    private MultiExecSmoke() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        String snapshotPath = args.length > 0 ? args[0] : null;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<List<TerminalSplitPane>> tabsRef = new AtomicReference<>();
        StalledConnector stalled = new StalledConnector();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                MultiExecCoordinator coordinator = new MultiExecCoordinator();
                TerminalSplitPane tabA = newTab(coordinator);
                TerminalSplitPane tabB = newTab(coordinator);
                tabsRef.set(List.of(tabA, tabB));
                MultiExecStatusBar chip = new MultiExecStatusBar();
                chip.setOnStop(coordinator::stop);
                coordinator.addListener(() -> chip.update(coordinator.counts(splitPane -> "window"),
                    coordinator.countHeld()));
                HBox statusBar = new HBox(chip);
                statusBar.getStyleClass().add("status-bar");
                HBox tabs = new HBox(4, tabA, tabB);
                HBox.setHgrow(tabA, Priority.ALWAYS);
                HBox.setHgrow(tabB, Priority.ALWAYS);
                BorderPane root = new BorderPane(tabs);
                root.setBottom(statusBar);
                Stage stage = new Stage();
                stageRef.set(stage);
                Scene scene = new Scene(root, 1200, 560);
                scene.getStylesheets().add(MultiExecSmoke.class.getResource("/styles/terminal.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                Thread worker = new Thread(() -> verify(coordinator, tabA, tabB, chip, stalled, snapshotPath,
                    failure, done), "multi-exec-smoke");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable error) {
                failure.compareAndSet(null, "Setup failed: " + stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(90, TimeUnit.SECONDS);
        stalled.release();
        Platform.runLater(() -> {
            try {
                if (tabsRef.get() != null) {
                    tabsRef.get().forEach(TerminalSplitPane::closeAll);
                }
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
        System.out.println("SMOKE OK: typed keys reach the member in the other tab, a stalled member does not"
            + " freeze the UI, held and non-member panes get nothing, the markers follow and Stop ends it");
        System.exit(0);
    }

    /** A tab's split pane whose panes are registered with {@code coordinator}, as TerminalView does. */
    private static TerminalSplitPane newTab(MultiExecCoordinator coordinator) {
        AtomicReference<TerminalSplitPane> self = new AtomicReference<>();
        TerminalSplitPane splitPane = new TerminalSplitPane(() -> new DynamicFontSizeSettingsProvider(14f),
            request -> new RecordingConnector(), widget -> coordinator.register(widget, self::get));
        self.set(splitPane);
        splitPane.setInputMirror(coordinator);
        return splitPane;
    }

    private static void verify(MultiExecCoordinator coordinator, TerminalSplitPane tabA, TerminalSplitPane tabB,
                               MultiExecStatusBar chip, StalledConnector stalled, String snapshotPath,
                               AtomicReference<String> failure, CountDownLatch done) {
        try {
            SithTermFxWidget a1 = onFxThread(() -> tabA.getAllWidgets().get(0));
            SithTermFxWidget b1 = onFxThread(() -> tabB.getAllWidgets().get(0));
            SithTermFxWidget a2 = onFxThread(() -> tabA.splitWidget(a1, SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL,
                Orientation.VERTICAL, stalled));
            SithTermFxWidget b2 = onFxThread(() -> tabB.splitWidget(b1, SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL,
                Orientation.VERTICAL, new RecordingConnector()));
            check(a2 != null && b2 != null, "the splits did not create panes");
            RecordingConnector inB1 = recorder(b1);
            RecordingConnector inB2 = recorder(b2);

            onFxThread(() -> coordinator.setPanes(List.of(a1, a2, b1), true));
            settle();
            check(onFxThread(coordinator::memberCount) == 3, "three panes should take part");
            check(onFxThread(() -> coordinator.ownerOf(b1)) == tabB, "b1 belongs to the other tab");
            expectBadge(tabA, a1, true, "a1");
            expectBadge(tabB, b1, true, "b1");
            expectBadge(tabB, b2, false, "b2");
            String status = onFxThread(() -> ((Label) chip.getChildren().get(1)).getText());
            check(status.contains("3") && status.contains("2"), "the chip shows 3 panes in 2 tabs: " + status);
            check(onFxThread(chip::isVisible), "the chip is hidden while panes take part");

            long typing = timed(() -> {
                typeInto(a1, "l");
                typeInto(a1, "s");
                pressInto(a1, KeyCode.ENTER);
            });
            check(typing < RESPONSIVE_MILLIS, "typing with a stalled member took " + typing + " ms");
            check(stalled.awaitWrite(), "the stalled member never got its write");
            for (int i = 0; i < 5; i++) {
                long answer = timed(() -> { });
                check(answer < RESPONSIVE_MILLIS, "the FX thread took " + answer + " ms while a member stalls");
            }
            check(inB1.await("ls\r"), "the member in the other tab got '" + inB1.text() + "', expected ls and Enter");
            check(inB2.text().isEmpty(), "a pane that does not take part got '" + inB2.text() + "'");

            // b1's tab holds it back now, as it would while it paces a paste or an agent drives it.
            onFxThread(() -> {
                tabB.setMirrorTargetGuard(widget -> widget != b1);
                return null;
            });
            check(onFxThread(coordinator::countHeld) == 1, "the held member is not counted");
            timed(() -> typeInto(a1, "x"));
            Thread.sleep(300);
            check(!inB1.text().contains("x"), "a held member got a key");
            onFxThread(() -> {
                tabB.setMirrorTargetGuard(null);
                return null;
            });

            // A zoomed member shows its multi-exec badge left of the zoom badge, which counts the
            // hidden member a2 as receiving the input.
            check(onFxThread(() -> tabA.zoomWidget(a1)), "a1 did not zoom");
            settle();
            double[] badges = onFxThread(() -> {
                Pane layer = tabA.paneOverlay(a1, PaneOverlayLayer.DECORATION);
                Label mirror = null;
                Label zoom = null;
                for (Node child : layer.getChildren()) {
                    if (child instanceof Label label && label.getStyleClass().contains("kortty-pane-mirror-badge")) {
                        mirror = label;
                    } else if (child instanceof Label label && label.getStyleClass().contains("kortty-pane-zoom-badge")) {
                        zoom = label;
                    }
                }
                check(mirror != null && zoom != null, "the zoomed member lacks a badge");
                check(zoom.getText().contains("1"), "the zoom badge does not count a2: " + zoom.getText());
                return new double[] {mirror.getLayoutX() + mirror.getWidth(), zoom.getLayoutX()};
            });
            check(badges[0] <= badges[1], "the multi-exec badge overlaps the zoom badge: " + badges[0] + " > "
                + badges[1]);
            if (snapshotPath != null) {
                snapshot(tabA, snapshotPath.replace(".png", "-zoomed.png"));
            }
            onFxThread(tabA::unzoom);
            settle();

            if (snapshotPath != null) {
                snapshot(tabA, snapshotPath);
            }

            onFxThread(() -> {
                coordinator.stop();
                return null;
            });
            settle();
            check(onFxThread(coordinator::memberCount) == 0, "Stop left members");
            expectBadge(tabA, a1, false, "a1 after Stop");
            check(!onFxThread(chip::isVisible), "the chip stays after Stop");
            String before = inB1.text();
            timed(() -> typeInto(a1, "y"));
            Thread.sleep(300);
            check(inB1.text().equals(before), "a key went to b1 after Stop");

            onFxThread(() -> {
                coordinator.setPanes(List.of(a1, b1), true);
                coordinator.forget(b1);
                return null;
            });
            check(onFxThread(() -> coordinator.otherMembers(a1)).isEmpty(), "a closed pane is still mirrored into");
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            done.countDown();
        }
    }

    private static void typeInto(SithTermFxWidget pane, String character) {
        Node target = pane.getTerminalPanel().getCanvas();
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED,
            false, false, false, false));
    }

    private static void pressInto(SithTermFxWidget pane, KeyCode code) {
        Node target = pane.getTerminalPanel().getCanvas();
        Event.fireEvent(target, new KeyEvent(KeyEvent.KEY_PRESSED, "", "", code, false, false, false, false));
    }

    /** How long {@code action} takes on the FX thread, the wait for its turn included, in milliseconds. */
    private static long timed(Runnable action) throws Exception {
        long start = System.nanoTime();
        onFxThread(() -> {
            action.run();
            return null;
        });
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    private static void expectBadge(TerminalSplitPane splitPane, SithTermFxWidget pane, boolean shown, String name)
            throws Exception {
        Boolean visible = onFxThread(() -> {
            Pane layer = splitPane.paneOverlay(pane, PaneOverlayLayer.DECORATION);
            if (layer == null) {
                return false;
            }
            for (Node child : layer.getChildren()) {
                if (child instanceof Label label && label.getStyleClass().contains("kortty-pane-mirror-badge")) {
                    return label.isVisible();
                }
            }
            return false;
        });
        check(visible == shown, name + (shown ? " shows no multi-exec badge" : " shows a multi-exec badge"));
    }

    private static RecordingConnector recorder(SithTermFxWidget pane) {
        TtyConnector connector = pane.getTtyConnector();
        check(connector instanceof RecordingConnector, "unexpected connector " + connector);
        return (RecordingConnector) connector;
    }

    private static void snapshot(TerminalSplitPane splitPane, String path) throws Exception {
        WritableImage image = onFxThread(() -> splitPane.getScene().getRoot().snapshot(null, null));
        File file = new File(path);
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
        System.out.println("Snapshot written to " + file.getAbsolutePath());
    }

    /** Two pulses, so the layers and a CSS pass have run. */
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

    /** A connected pty that records what is written to it; read() blocks until it is closed. */
    private static class RecordingConnector implements TtyConnector {
        private final CountDownLatch closed = new CountDownLatch(1);
        private final StringBuilder written = new StringBuilder();

        String text() {
            synchronized (written) {
                return written.toString();
            }
        }

        /** Waits until the recorded text contains {@code expected}. */
        boolean await(String expected) throws InterruptedException {
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline) {
                if (text().contains(expected)) {
                    return true;
                }
                Thread.sleep(25);
            }
            return false;
        }

        @Override
        public int read(char[] buffer, int offset, int length) {
            try {
                closed.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            return -1;
        }

        @Override
        public void write(byte[] bytes) {
            synchronized (written) {
                written.append(new String(bytes, StandardCharsets.UTF_8));
            }
        }

        @Override
        public void write(String string) {
            synchronized (written) {
                written.append(string);
            }
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
            return "multi-exec-smoke";
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /** A connection whose writes never return, like an SSH channel whose peer stopped reading. */
    private static final class StalledConnector extends RecordingConnector {
        private final CountDownLatch released = new CountDownLatch(1);
        private final CountDownLatch writing = new CountDownLatch(1);

        boolean awaitWrite() throws InterruptedException {
            return writing.await(3, TimeUnit.SECONDS);
        }

        void release() {
            released.countDown();
        }

        @Override
        public void write(byte[] bytes) {
            stall();
        }

        @Override
        public void write(String string) {
            stall();
        }

        private void stall() {
            writing.countDown();
            try {
                released.await();
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
