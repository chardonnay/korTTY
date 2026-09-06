package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.VBox;
import javafx.stage.Screen;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Proves the docked layout on real stages: two satellites attach to opposite edges of an anchor,
 * follow it when it moves, stay on screen, and let go when one is dragged away.
 *
 * <p>{@link WindowDockGroupTest} already pins the arithmetic. What it cannot see is whether the
 * listeners are wired to the right properties and whether the group survives a real window manager
 * clamping a stage — which is what this harness exercises. Exit 0 = OK.</p>
 */
public final class SnippetAnalysisDockSmoke {

    private static final double SATELLITE_WIDTH = 360;
    private static final double PREVIEW_WIDTH = 620;

    private SnippetAnalysisDockSmoke() {
    }

    public static void main(String[] args) throws Exception {
        Path sandbox = Files.createTempDirectory("kortty-analysis-dock-home");
        System.setProperty("user.home", sandbox.toString());

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + error));

        AtomicReference<Stage> anchorRef = new AtomicReference<>();
        AtomicReference<Stage> leftRef = new AtomicReference<>();
        AtomicReference<Stage> rightRef = new AtomicReference<>();
        AtomicReference<WindowDockGroup> groupRef = new AtomicReference<>();

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                Rectangle2D screen = Screen.getPrimary().getVisualBounds();

                Stage anchor = stage("anchor", 1000, 640);
                anchor.setX(screen.getMinX() + Math.min(400, screen.getWidth() / 4));
                anchor.setY(screen.getMinY() + 60);
                anchor.show();
                anchorRef.set(anchor);

                Stage right = stage("processing", SATELLITE_WIDTH, 420);
                right.setMinHeight(420);
                right.initOwner(anchor);
                right.show();
                rightRef.set(right);

                Stage left = stage("preview", PREVIEW_WIDTH, 500);
                left.initOwner(anchor);
                left.show();
                leftRef.set(left);

                WindowDockGroup group = new WindowDockGroup(anchor, true);
                group.dock(right, WindowDockGroup.Side.RIGHT, SATELLITE_WIDTH);
                group.dock(left, WindowDockGroup.Side.LEFT, PREVIEW_WIDTH);
                groupRef.set(group);
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            } finally {
                done.countDown();
            }
        });

        if (!done.await(90, TimeUnit.SECONDS)) {
            System.err.println("SnippetAnalysisDockSmoke: the windows were never built");
            System.exit(2);
        }

        if (failure.get() == null) {
            try {
                check(anchorRef.get(), leftRef.get(), rightRef.get(), groupRef.get());
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            }
        }

        onFxRun(() -> {
            leftRef.get().close();
            rightRef.get().close();
            anchorRef.get().close();
        });

        if (failure.get() == null) {
            try {
                checkDialogSatellite();
            } catch (Throwable error) {
                failure.compareAndSet(null, String.valueOf(error));
            }
        }
        Platform.runLater(Platform::exit);

        if (failure.get() != null) {
            System.err.println("SnippetAnalysisDockSmoke FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SnippetAnalysisDockSmoke OK");
        System.exit(0);
    }

    /**
     * The change preview is a {@link javafx.scene.control.Dialog} shown with {@code showAndWait()}:
     * it sizes itself to its pane, and macOS reports that size — and the re-centring over the owner
     * that goes with it — only after the dock has placed the window. Read as the user's own resize
     * and drag, that report once shrank the preview to 1040x700 and left it at the screen edge.
     * The docked width, the anchor's height and the docked position must all survive it.
     */
    private static void checkDialogSatellite() throws Exception {
        AtomicReference<Stage> anchorRef = new AtomicReference<>();
        AtomicReference<Stage> rightRef = new AtomicReference<>();
        AtomicReference<javafx.scene.control.Dialog<Void>> dialogRef = new AtomicReference<>();
        AtomicReference<Stage> previewRef = new AtomicReference<>();
        AtomicReference<WindowDockGroup> groupRef = new AtomicReference<>();
        onFxRun(() -> {
            Rectangle2D screen = Screen.getPrimary().getVisualBounds();
            Stage anchor = stage("anchor", 1000, 640);
            anchor.setX(screen.getMinX() + Math.min(400, screen.getWidth() / 4));
            anchor.setY(screen.getMinY() + 60);
            anchor.show();
            anchorRef.set(anchor);
            Stage right = stage("processing", SATELLITE_WIDTH, 420);
            right.initOwner(anchor);
            right.show();
            rightRef.set(right);
            WindowDockGroup group = new WindowDockGroup(anchor, true);
            group.dock(right, WindowDockGroup.Side.RIGHT, SATELLITE_WIDTH);
            groupRef.set(group);

            javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
            dialog.initOwner(anchor);
            dialog.setTitle("preview dialog");
            dialog.setResizable(true);
            dialog.getDialogPane().setContent(new javafx.scene.layout.StackPane());
            dialog.getDialogPane().setPrefSize(700, 400);
            dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
            dialog.addEventHandler(javafx.scene.control.DialogEvent.DIALOG_SHOWN, event -> {
                Stage stage = (Stage) dialog.getDialogPane().getScene().getWindow();
                previewRef.set(stage);
                group.dock(stage, WindowDockGroup.Side.LEFT, PREVIEW_WIDTH);
            });
            dialogRef.set(dialog);
            Platform.runLater(dialog::showAndWait);
        });
        Thread.sleep(1500);
        Stage anchor = anchorRef.get();
        Stage preview = previewRef.get();
        if (preview == null) {
            throw new AssertionError("the preview dialog never reported DIALOG_SHOWN");
        }
        double width = onFx(preview::getWidth);
        double height = onFx(preview::getHeight);
        double anchorHeight = onFx(anchor::getHeight);
        double expectedX = onFx(() -> anchor.getX() - WindowDockGroup.GAP - PREVIEW_WIDTH);
        double x = onFx(preview::getX);
        System.out.printf("dialog satellite after settling: x=%.0f (expected %.0f) width=%.0f (expected %.0f) height=%.0f (anchor %.0f)%n",
            x, expectedX, width, PREVIEW_WIDTH, height, anchorHeight);
        if (Math.abs(width - PREVIEW_WIDTH) > 1) {
            throw new AssertionError("the docked dialog lost its docked width: " + width + " instead of " + PREVIEW_WIDTH);
        }
        if (Math.abs(height - anchorHeight) > 1) {
            throw new AssertionError("the docked dialog does not follow the anchor's height: " + height + " vs " + anchorHeight);
        }
        if (Math.abs(x - expectedX) > 1) {
            throw new AssertionError("the docked dialog is not beside its anchor: x=" + x + " expected " + expectedX);
        }
        // A window wider than the content inside it shows the bare window background as a blank band.
        double contentWidth = onFx(() -> preview.getScene().getRoot().getLayoutBounds().getWidth());
        double sceneWidth = onFx(() -> preview.getScene().getWidth());
        System.out.printf("dialog satellite content: %.0f inside a %.0f-wide scene%n", contentWidth, sceneWidth);
        if (Math.abs(contentWidth - sceneWidth) > 1) {
            throw new AssertionError("the docked dialog's content does not fill it: " + contentWidth + " of " + sceneWidth);
        }
        requireContentFillsWindow(preview, "after docking");

        // The band was reported long after the opening second, on a preview the dock re-placed at
        // another width when its neighbours changed. Re-dock at a different width and check again.
        WindowDockGroup group = groupRef.get();
        onFxRun(() -> {
            group.undock(preview);
            group.dock(preview, WindowDockGroup.Side.LEFT, PREVIEW_WIDTH + 160);
        });
        Thread.sleep(1500);
        requireContentFillsWindow(preview, "after re-docking wider");
        onFxRun(() -> anchor.setHeight(anchor.getHeight() - 60));
        Thread.sleep(800);
        requireContentFillsWindow(preview, "after the anchor shrank");
        onFxRun(() -> {
            dialogRef.get().close();
            rightRef.get().close();
            anchorRef.get().close();
        });
    }

    /** Window, scene and content must agree, or the window shows a bare band beside the content. */
    private static void requireContentFillsWindow(Stage preview, String phase) throws Exception {
        double stageWidth = onFx(preview::getWidth);
        double sceneWidth = onFx(() -> preview.getScene().getWidth());
        double sceneHeight = onFx(() -> preview.getScene().getHeight());
        double rootWidth = onFx(() -> preview.getScene().getRoot().getLayoutBounds().getWidth());
        double rootHeight = onFx(() -> preview.getScene().getRoot().getLayoutBounds().getHeight());
        System.out.printf("dialog satellite %s: window %.0f wide, scene %.0fx%.0f, content %.0fx%.0f%n",
            phase, stageWidth, sceneWidth, sceneHeight, rootWidth, rootHeight);
        double stageHeight = onFx(preview::getHeight);
        if (Math.abs(stageWidth - sceneWidth) > WindowDockGroup.SCENE_LAG_TOLERANCE
                || stageHeight - sceneHeight > WindowDockGroup.SCENE_HEIGHT_LAG_TOLERANCE
                || stageHeight - sceneHeight < -1) {
            throw new AssertionError(phase + ": the scene does not follow the window: " + sceneWidth + "x" + sceneHeight
                + " inside " + stageWidth + "x" + stageHeight);
        }
        if (Math.abs(rootWidth - sceneWidth) > 1 || Math.abs(rootHeight - sceneHeight) > 1) {
            throw new AssertionError(phase + ": the content does not fill the scene: " + rootWidth + "x" + rootHeight
                + " of " + sceneWidth + "x" + sceneHeight);
        }
    }

    private static void check(Stage anchor, Stage left, Stage right, WindowDockGroup group)
            throws Exception {

        settle();
        Rectangle2D screen = onFx(() -> Screen.getPrimary().getVisualBounds());

        System.out.printf("screen: [%.0f..%.0f] x [%.0f..%.0f]  (screens=%d)%n",
            screen.getMinX(), screen.getMaxX(), screen.getMinY(), screen.getMaxY(),
            onFx(() -> Screen.getScreens().size()));
        report("initial", anchor, left, right);
        requireOnScreen(screen, left, "preview");
        requireOnScreen(screen, right, "processing");
        requireOrdered(left, anchor, right);

        // Moving the anchor must carry both satellites with it.
        onFxRun(() -> anchor.setX(anchor.getX() + 40));
        settle();
        report("after moving the anchor", anchor, left, right);
        requireOrdered(left, anchor, right);
        requireOnScreen(screen, left, "preview");
        requireOnScreen(screen, right, "processing");

        // Resizing the anchor taller must be matched by the satellites, so the trio reads as one.
        onFxRun(() -> anchor.setHeight(Math.min(700, screen.getHeight() - 80)));
        settle();
        double anchorHeight = onFx(anchor::getHeight);
        double rightHeight = onFx(right::getHeight);
        require(Math.abs(anchorHeight - rightHeight) < 2 || rightHeight >= 420,
            "the processing window did not follow the anchor height (anchor=" + anchorHeight
                + ", satellite=" + rightHeight + ")");

        // A width the user chooses is theirs to keep.
        onFxRun(() -> right.setWidth(430));
        settle();
        require(Math.abs(onFx(right::getWidth) - 430) < 2,
            "a user-chosen satellite width was overwritten by the dock");

        // A long anchor drag must not be mistaken for the user tearing a satellite off. On macOS an
        // owned stage is an AppKit child window and is translated natively when its owner moves, so
        // this is the case that decides whether the drag detection is written against absolute
        // positions (wrong) or against the offset from the anchor (right).
        onFxRun(() -> anchor.setX(Math.max(screen.getMinX() + PREVIEW_WIDTH + 40, anchor.getX() - 220)));
        settle();
        report("after a long anchor drag", anchor, left, right);
        require(onFx(() -> group.isDocked(left)) && onFx(() -> group.isDocked(right)),
            "a long anchor drag spuriously undocked a satellite");
        requireOrdered(left, anchor, right);

        // Dragging a satellite itself does break the dock: it stops being repositioned and keeps
        // the offset the user gave it. (It still translates with its owner — that is AppKit, not
        // this group.)
        // Deliberately a separate gesture, well after the anchor stopped moving — the same
        // sequence a user performs when they let go of one window and grab another.
        Thread.sleep(400);
        onFxRun(() -> {
            right.setX(anchor.getX() + 20);
            right.setY(anchor.getY() + 200);
        });
        settle();
        require(!onFx(() -> group.isDocked(right)),
            "dragging a satellite away did not break its dock");
        onFxRun(() -> anchor.setX(anchor.getX() + 30));
        settle();
        double dockedX = onFx(() -> anchor.getX() + anchor.getWidth() + WindowDockGroup.GAP);
        require(Math.abs(onFx(right::getX) - dockedX) > 2,
            "an undocked satellite was snapped back to its dock position");
        require(onFx(() -> group.isDocked(left)),
            "undocking one satellite also detached the other");

        onFxRun(group::dispose);
    }

    // ---------------------------------------------------------------- assertions

    private static void requireOrdered(Stage left, Stage anchor, Stage right) throws Exception {
        double leftMax = onFx(() -> left.getX() + left.getWidth());
        double anchorMin = onFx(anchor::getX);
        double anchorMax = onFx(() -> anchor.getX() + anchor.getWidth());
        double rightMin = onFx(right::getX);
        require(leftMax <= anchorMin + 1,
            "the preview overlaps the anchor (preview ends at " + leftMax
                + ", anchor starts at " + anchorMin + ")");
        require(rightMin >= anchorMax - 1,
            "the processing window overlaps the anchor (anchor ends at " + anchorMax
                + ", processing starts at " + rightMin + ")");
    }

    private static void requireOnScreen(Rectangle2D screen, Stage stage, String name) throws Exception {
        double minX = onFx(stage::getX);
        double maxX = onFx(() -> stage.getX() + stage.getWidth());
        double minY = onFx(stage::getY);
        double maxY = onFx(() -> stage.getY() + stage.getHeight());
        require(minX >= screen.getMinX() - 1 && maxX <= screen.getMaxX() + 1,
            name + " runs off the screen horizontally: " + minX + ".." + maxX);
        require(minY >= screen.getMinY() - 1 && maxY <= screen.getMaxY() + 1,
            name + " runs off the screen vertically: " + minY + ".." + maxY);
    }

    private static void report(String phase, Stage anchor, Stage left, Stage right) throws Exception {
        System.out.printf("%-28s preview[%.0f..%.0f] anchor[%.0f..%.0f] processing[%.0f..%.0f]%n",
            phase,
            onFx(left::getX), onFx(() -> left.getX() + left.getWidth()),
            onFx(anchor::getX), onFx(() -> anchor.getX() + anchor.getWidth()),
            onFx(right::getX), onFx(() -> right.getX() + right.getWidth()));
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    // ---------------------------------------------------------------- FX plumbing

    private static Stage stage(String title, double width, double height) {
        Stage stage = new Stage();
        stage.setTitle(title);
        stage.setScene(new Scene(new VBox(), width, height));
        return stage;
    }

    /** Lets the window manager apply whatever it is going to apply before anything is measured. */
    private static void settle() throws Exception {
        for (int i = 0; i < 3; i++) {
            onFxRun(() -> { });
            Thread.sleep(120);
        }
    }

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
        if (!latch.await(5, TimeUnit.SECONDS)) {
            // Show what the FX thread is doing before giving up on it.
            Thread.getAllStackTraces().forEach((thread, frames) -> {
                if (thread.getName().contains("JavaFX Application Thread")) {
                    System.err.println("FX thread state " + thread.getState() + ":");
                    for (StackTraceElement frame : frames) {
                        System.err.println("    at " + frame);
                    }
                }
            });
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("the FX thread did not respond");
            }
        }
        if (error.get() != null) {
            throw new IllegalStateException(error.get());
        }
        return result.get();
    }

    private static void onFxRun(Runnable work) throws Exception {
        onFx(() -> {
            work.run();
            return null;
        });
    }

    @FunctionalInterface
    private interface FxCall<T> {
        T call() throws Exception;
    }
}
