package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SplitPaneState;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Orientation;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of a project's split layout coming back: a local-shell tab is connected, and
 * once its first session is up ({@link TerminalTab#addOnFirstConnected}) a saved four-pane layout,
 * (a / b) | (c / d), is rebuilt around its first pane, the panes connected in the background and
 * attached without a dialog. Checks that every pane opened, that the live layout has the saved tree
 * with every saved divider (after the split controls' own reset to the middle), and that the
 * JavaFX thread kept answering throughout. Pass a PNG path via --args to also save a snapshot. Run
 * via the {@code splitLayoutRestoreSmoke} Gradle task. Exit 0 = OK.
 */
public final class SplitLayoutRestoreSmoke {

    private static final long RESTORE_TIMEOUT_SECONDS = 60;
    private static final double DIVIDER_TOLERANCE = 0.02;

    private SplitLayoutRestoreSmoke() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        String snapshotPath = args.length > 0 ? args[0] : null;
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        AtomicReference<TerminalTab> tabRef = new AtomicReference<>();
        AtomicReference<SplitLayoutRestorePlan.Summary> summaryRef = new AtomicReference<>();
        CountDownLatch restored = new CountDownLatch(1);
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + error));
        SplitPaneState saved = SplitPaneState.createSplit(Orientation.HORIZONTAL, 0.3,
            SplitPaneState.createSplit(Orientation.VERTICAL, 0.7, SplitPaneState.createLeaf(0), SplitPaneState.createLeaf(1)),
            SplitPaneState.createSplit(Orientation.VERTICAL, 0.25, SplitPaneState.createLeaf(2), SplitPaneState.createLeaf(3)));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                ServerConnection connection = new ServerConnection();
                connection.setName("restore-smoke");
                connection.setHost("localhost");
                connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
                connection.setLocalShellWorkingDirectory(System.getProperty("user.home"));
                TerminalTab tab = new TerminalTab(connection, null);
                tabRef.set(tab);
                TabPane tabPane = new TabPane(tab);
                Stage stage = new Stage();
                stageRef.set(stage);
                Scene scene = new Scene(tabPane, 1200, 720);
                scene.getStylesheets().add(SplitLayoutRestoreSmoke.class.getResource("/styles/terminal.css").toExternalForm());
                stage.setScene(scene);
                stage.show();
                tab.addOnFirstConnected(() -> tab.getTerminalView().restoreSplitLayout(saved, summary -> {
                    summaryRef.set(summary);
                    restored.countDown();
                }));
                tab.connect();
            } catch (Throwable error) {
                failure.compareAndSet(null, "Setup failed: " + error);
                restored.countDown();
            }
        });

        // The JavaFX thread must keep answering while the panes connect.
        long slowest = 0;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(RESTORE_TIMEOUT_SECONDS);
        while (restored.getCount() > 0 && System.nanoTime() < deadline) {
            long start = System.nanoTime();
            onFxThread(() -> null);
            slowest = Math.max(slowest, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
            restored.await(50, TimeUnit.MILLISECONDS);
        }
        if (restored.getCount() > 0) {
            failure.compareAndSet(null, "the split layout was not restored within " + RESTORE_TIMEOUT_SECONDS + " s");
        }

        if (failure.get() == null) {
            SplitLayoutRestorePlan.Summary summary = summaryRef.get();
            if (summary.restoredPanes() != 4 || summary.missingPanes() != 0) {
                failure.set("expected 4 restored panes, got " + summary);
            }
        }
        if (failure.get() == null) {
            // The dividers are set two pulses after the last split; give the layout a moment.
            Thread.sleep(1_000);
            SplitPaneState live = onFxThread(() -> tabRef.get().getTerminalView().getSplitState());
            List<String> problems = new ArrayList<>();
            compare("root", saved, live, problems);
            int panes = onFxThread(() -> tabRef.get().getTerminalView().getOrderedWidgets().size());
            if (panes != 4) {
                problems.add("the tab has " + panes + " panes");
            }
            if (!problems.isEmpty()) {
                failure.set(String.join("; ", problems));
            }
        }
        if (failure.get() == null && slowest > 1_000) {
            failure.set("the JavaFX thread did not answer for " + slowest + " ms while the panes connected");
        }
        if (failure.get() == null && snapshotPath != null) {
            onFxThread(() -> {
                WritableImage image = stageRef.get().getScene().snapshot(null);
                ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", new File(snapshotPath));
                return null;
            });
        }

        Platform.runLater(() -> {
            try {
                if (tabRef.get() != null) {
                    tabRef.get().releaseResources();
                }
                if (stageRef.get() != null) {
                    stageRef.get().close();
                }
            } catch (Exception ignored) {
            }
            Platform.exit();
        });
        if (failure.get() != null) {
            System.err.println("SMOKE FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SMOKE OK: four local-shell panes came back after the first connect, in the saved tree"
            + " with the saved dividers, and the JavaFX thread answered within " + slowest + " ms throughout");
        System.exit(0);
    }

    private static void compare(String path, SplitPaneState expected, SplitPaneState actual, List<String> problems) {
        if (actual == null) {
            problems.add(path + ": no layout");
            return;
        }
        if (expected.isLeaf() != actual.isLeaf()) {
            problems.add(path + ": expected " + expected + ", got " + actual);
            return;
        }
        if (expected.isLeaf()) {
            if (!expected.getWidgetIndex().equals(actual.getWidgetIndex())) {
                problems.add(path + ": pane " + actual.getWidgetIndex() + " where " + expected.getWidgetIndex() + " was");
            }
            return;
        }
        if (!expected.getOrientation().equals(actual.getOrientation())) {
            problems.add(path + ": orientation " + actual.getOrientation() + " instead of " + expected.getOrientation());
        }
        if (Math.abs(expected.getDividerPosition() - actual.getDividerPosition()) > DIVIDER_TOLERANCE) {
            problems.add(path + ": divider " + actual.getDividerPosition() + " instead of " + expected.getDividerPosition());
        }
        compare(path + ".first", expected.getLeftChild(), actual.getLeftChild(), problems);
        compare(path + ".second", expected.getRightChild(), actual.getRightChild(), problems);
    }

    private static <T> T onFxThread(java.util.concurrent.Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }
}
