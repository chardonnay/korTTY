package de.kortty.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Label;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.transform.Transform;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.io.StringWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Renders the real main window while a guide translation is running and checks that the menu bar
 * shows it.
 *
 * <p>The indicator is the only part of the background translation a user sees while working, and
 * it is the part no unit test can vouch for: the job's numbers are covered by
 * {@code GuideTranslationJobTest}, but whether they reach a visible node in the right place is a
 * question about the window's layout.
 *
 * <p>{@code user.home} is redirected to a throwaway directory before anything touches it, so the
 * window never renders the real profile's connections and the snapshot cannot leak them.
 *
 * <p>Writes {@code build/smoke/mainwindow-translation-indicator.png}. Exit 0 = OK.
 */
public final class MainWindowTranslationIndicatorSmoke {

    private static final long TIMEOUT_SECONDS = 120;

    private MainWindowTranslationIndicatorSmoke() {
    }

    public static void main(String[] args) throws Exception {
        // FIRST: KorTTYApplication resolves the config directory from user.home in a static
        // initializer, so the redirect has to happen before that class is ever touched.
        Path sandbox = Files.createTempDirectory("kortty-indicator-smoke-home");
        System.setProperty("user.home", sandbox.toString());

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + error));

        // The window is built ON the FX thread; everything that waits runs on this one. Doing
        // both inside Platform.startup deadlocks: the waiter would block the very thread the
        // runLater it is waiting for has to execute on.
        AtomicReference<MainWindow> windowRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        CountDownLatch built = new CountDownLatch(1);
        Platform.startup(() -> {
            try {
                de.kortty.KorTTYApplication app = new de.kortty.KorTTYApplication();
                app.init();
                Stage stage = new Stage();
                MainWindow window = new MainWindow(stage);
                stage.setWidth(1280);
                stage.setHeight(760);
                stage.show();
                stageRef.set(stage);
                windowRef.set(window);
            } catch (Throwable error) {
                failure.compareAndSet(null, stack(error));
            } finally {
                built.countDown();
            }
        });
        if (!built.await(90, TimeUnit.SECONDS)) {
            System.err.println("MainWindowTranslationIndicatorSmoke: window was never built");
            System.exit(2);
        }
        if (failure.get() == null) {
            try {
                run(sandbox, windowRef.get(), stageRef.get());
            } catch (Throwable error) {
                failure.compareAndSet(null, stack(error));
            }
        }
        done.countDown();

        boolean finished = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        de.kortty.core.GuideTranslationJob.getInstance().cancel();
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("MainWindowTranslationIndicatorSmoke TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("MainWindowTranslationIndicatorSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("MainWindowTranslationIndicatorSmoke OK");
        System.exit(0);
    }

    private static void run(Path sandbox, MainWindow window, Stage stage) throws Exception {
        Node indicator = onFx(() -> indicatorNode(window));
        require(indicator != null, "the indicator node was not built into the window");
        require(!onFx(indicator::isVisible), "the indicator must be hidden while nothing is running");

        de.kortty.core.GuideTranslationJob job = de.kortty.core.GuideTranslationJob.getInstance();
        require(job.start(new SlowService(), "de", sandbox),
            "the translation job refused to start");

        // Pump the FX thread until the indicator has something real to show. The job publishes
        // from its own thread, so this waits for the UI to catch up rather than assuming it has.
        String shown = awaitIndicatorText(window, stage);
        require(shown != null, "the indicator stayed empty while the job was running");
        require(onFx(indicator::isVisible), "the indicator is not visible during a run");
        require(shown.contains("%"), "the indicator shows no percentage: '" + shown + "'");

        javafx.scene.layout.StackPane bar =
            onFx(() -> firstOfType(indicator, javafx.scene.layout.StackPane.class));
        require(bar != null, "no progress bar in the indicator");
        require(onFx(() -> bar.getChildren().size()) == 2, "the bar has no track and fill");
        // A node can exist, be "visible" and still occupy no space; then the user sees nothing.
        javafx.geometry.Bounds bounds = onFx(() -> bar.localToScene(bar.getBoundsInLocal()));
        require(bounds.getWidth() > 20 && bounds.getHeight() > 2,
            "the progress bar occupies no usable area: " + bounds);
        System.out.printf("  progress bar laid out at %.0fx%.0f%n",
            bounds.getWidth(), bounds.getHeight());

        double fillWidth = onFx(() ->
            ((javafx.scene.layout.Region) bar.getChildren().get(1)).getWidth());
        // The fill must be narrower than the track, or the bar would read as finished from the
        // first moment; and wider than zero, or a started run would look like nothing happening.
        require(fillWidth > 0 && fillWidth < bounds.getWidth(),
            "the bar fill is " + fillWidth + " against a track of " + bounds.getWidth());
        System.out.printf("  indicator visible, text '%s', fill %.0f of %.0f px%n",
            shown, fillWidth, bounds.getWidth());

        // Let it get far enough along that the snapshot shows a bar rather than the minimum
        // sliver — the picture is the point of this smoke.
        for (int i = 0; i < 300; i++) {
            if (de.kortty.core.GuideTranslationJob.getInstance().snapshot().percent() >= 12) {
                break;
            }
            Thread.sleep(100);
        }
        onFxRun(() -> {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();
        });
        double shownFill = onFx(() ->
            ((javafx.scene.layout.Region) bar.getChildren().get(1)).getWidth());
        System.out.printf("  snapshot taken at %d%%, fill %.0f px%n",
            de.kortty.core.GuideTranslationJob.getInstance().snapshot().percent(), shownFill);
        onFxRun(() -> snapshot(stage));

        job.cancel();
        for (int i = 0; i < 200 && job.isRunning(); i++) {
            Thread.sleep(60);
        }
        // Let the listener's runLater land before asking the node what it looks like.
        Thread.sleep(300);
        onFxRun(() -> { });
        require(!onFx(indicator::isVisible), "the indicator must disappear once the job stops");
        System.out.println("  indicator hidden again after the job stopped");
    }

    /** Waits for the indicator label to carry text, pumping the FX thread meanwhile. */
    private static String awaitIndicatorText(MainWindow window, Stage stage) throws Exception {
        for (int i = 0; i < 400; i++) {
            String text = onFx(() -> {
                Node indicator = indicatorNode(window);
                Label label = indicator != null ? firstOfType(indicator, Label.class) : null;
                if (indicator == null || !indicator.isVisible() || label == null
                    || label.getText() == null || label.getText().isBlank()) {
                    return null;
                }
                stage.getScene().getRoot().applyCss();
                stage.getScene().getRoot().layout();
                return label.getText();
            });
            if (text != null) {
                return text;
            }
            Thread.sleep(50);
        }
        return null;
    }

    /** Runs {@code work} on the FX thread and returns its value. Must not be called from it. */
    private static <T> T onFx(FxCall<T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        Platform.runLater(() -> {
            try {
                result.set(work.call());
            } catch (Throwable t) {
                error.set(t);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(20, TimeUnit.SECONDS)) {
            throw new IllegalStateException("the FX thread did not respond");
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return result.get();
    }

    private static void onFxRun(FxRun work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }

    @FunctionalInterface
    private interface FxCall<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    private interface FxRun {
        void run() throws Exception;
    }

    private static Node indicatorNode(MainWindow window) throws Exception {
        Field field = MainWindow.class.getDeclaredField("guideTranslationIndicator");
        field.setAccessible(true);
        Object indicator = field.get(window);
        if (indicator == null) {
            return null;
        }
        Field nodeField = indicator.getClass().getDeclaredField("node");
        nodeField.setAccessible(true);
        return (Node) nodeField.get(indicator);
    }

    private static <T> T firstOfType(Node root, Class<T> type) {
        List<Node> queue = new ArrayList<>();
        queue.add(root);
        for (int i = 0; i < queue.size(); i++) {
            Node node = queue.get(i);
            if (type.isInstance(node)) {
                return type.cast(node);
            }
            if (node instanceof Parent parent) {
                queue.addAll(parent.getChildrenUnmodifiable());
            }
        }
        return null;
    }

    private static void snapshot(Stage stage) throws Exception {
        Scene scene = stage.getScene();
        scene.getRoot().applyCss();
        scene.getRoot().layout();
        SnapshotParameters params = new SnapshotParameters();
        params.setFill(Color.web("#1e1e1e"));
        params.setTransform(Transform.scale(2, 2));
        // Only the top strip: that is where the indicator lives, and a full-window shot would be
        // mostly empty terminal.
        Node top = scene.getRoot() instanceof javafx.scene.layout.BorderPane pane
            ? pane.getTop() : scene.getRoot();
        WritableImage image = (top != null ? top : scene.getRoot()).snapshot(params, null);
        File out = new File("build/smoke/mainwindow-translation-indicator.png");
        File parent = out.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("Cannot create " + parent);
        }
        ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new java.io.PrintWriter(writer));
        return writer.toString();
    }

    /** Slow enough that the window can be rendered mid-run, and never touches a model. */
    private static final class SlowService implements de.kortty.core.TranslationService {
        @Override
        public String translate(String text, String sourceLang, String targetLang) {
            return text;
        }

        @Override
        public List<String> translateBatch(List<String> texts, String s, String t) {
            try {
                Thread.sleep(120);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.copyOf(texts);
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
