package com.sithtermfx.ui.split;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX check of each pane's "x" close button and per-pane overlay host. The leaf
 * {@code SplitCell} used to prune the split pane's widget maps against the tree it had not joined
 * yet, so it dropped its own close button and overlay host the moment it registered them: the
 * button kept its default visibility forever (an "x" on a single-pane tab that empties the tab when
 * clicked) and {@link TerminalSplitPane#getWidgetOverlayHost} answered null for every pane.
 *
 * <p>The smoke walks one pane, a split, closing back to one pane, a pane created by a split left
 * as the only pane, and a split of a pane that is no longer in the tree (which must build nothing),
 * and checks after each step that exactly the panes in the tree have an overlay host and a close
 * button, and that the buttons show only while there is more than one pane. Run via the
 * {@code terminalSplitCloseButtonSmoke} Gradle task. Exit 0 = OK.
 */
public final class TerminalSplitCloseButtonSmoke {

    private static final long STEP_TIMEOUT_MILLIS = 10_000;

    private TerminalSplitCloseButtonSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<TerminalSplitPane> paneRef = new AtomicReference<>();
        AtomicReference<Stage> stageRef = new AtomicReference<>();
        List<SithTermFxWidget> created = new CopyOnWriteArrayList<>();
        List<SithTermFxWidget> closed = new CopyOnWriteArrayList<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                TerminalSplitPane splitPane = new TerminalSplitPane(
                    () -> new DynamicFontSizeSettingsProvider(14f), request -> new IdleTtyConnector(), created::add);
                splitPane.setOnWidgetClosed(closed::add);
                paneRef.set(splitPane);
                Stage stage = new Stage();
                stageRef.set(stage);
                stage.setScene(new Scene(splitPane, 900, 560));
                stage.show();
                Thread worker = new Thread(() -> verify(splitPane, created, closed, failure, done),
                    "split-close-button-smoke");
                worker.setDaemon(true);
                worker.start();
            } catch (Throwable error) {
                failure.compareAndSet(null, "Setup failed: " + stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
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
        System.out.println("SMOKE OK: split close buttons show only with more than one pane, and every pane"
            + " keeps its overlay host");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane splitPane, List<SithTermFxWidget> created,
                               List<SithTermFxWidget> closed, AtomicReference<String> failure, CountDownLatch done) {
        try {
            SithTermFxWidget first = onFxThread(() -> splitPane.getAllWidgets().get(0));
            expectPanes(splitPane, "one pane", List.of(first));

            SithTermFxWidget second = onFxThread(() -> splitPane.splitWidget(first,
                SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL, new IdleTtyConnector()));
            check(second != null, "the split did not create a pane");
            expectPanes(splitPane, "after a split", List.of(first, second));

            check(onFxThread(() -> splitPane.closeSplitPane(second)), "closing the second pane was refused");
            expectPanes(splitPane, "after closing back to one pane", List.of(first));
            expectForgotten(splitPane, "the closed pane", second);

            SithTermFxWidget third = onFxThread(() -> splitPane.splitWidget(first,
                SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.VERTICAL, new IdleTtyConnector()));
            check(third != null, "the second split did not create a pane");
            expectPanes(splitPane, "after a second split", List.of(first, third));
            check(onFxThread(() -> splitPane.closeSplitPane(first)), "closing the first pane was refused");
            expectPanes(splitPane, "with the split-created pane left alone", List.of(third));
            expectForgotten(splitPane, "the closed first pane", first);

            // A split of a pane that left the tree has nothing to go beside: it must build no pane at
            // all, so no orphan registers in the maps and the caller still owns its connector.
            int createdBefore = created.size();
            int closedBefore = closed.size();
            IdleTtyConnector unused = new IdleTtyConnector();
            SithTermFxWidget orphan = onFxThread(() -> splitPane.splitWidget(first,
                SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL, unused));
            check(orphan == null, "splitting a pane outside the tree answered a pane");
            check(created.size() == createdBefore,
                "splitting a pane outside the tree built " + (created.size() - createdBefore) + " panes");
            check(closed.size() == closedBefore, "splitting a pane outside the tree fired the close hook");
            check(unused.isConnected(), "splitting a pane outside the tree closed the caller's connector");
            expectPanes(splitPane, "after splitting a pane outside the tree", List.of(third));
        } catch (Throwable error) {
            failure.compareAndSet(null, stack(error));
        } finally {
            done.countDown();
        }
    }

    /** The tree holds exactly {@code panes}, each with its own overlay host and a close button shown only with siblings. */
    private static void expectPanes(TerminalSplitPane splitPane, String step, List<SithTermFxWidget> panes)
            throws Exception {
        List<String> problems = onFxThread(() -> {
            List<String> found = new ArrayList<>();
            List<SithTermFxWidget> inTree = splitPane.getAllWidgets();
            if (!inTree.equals(panes)) {
                found.add("tree holds " + inTree.size() + " panes, expected " + panes.size());
            }
            boolean showButtons = panes.size() > 1;
            // A SplitPane shows its items only once its skin exists, which the next CSS pass creates.
            splitPane.applyCss();
            List<Button> buttons = new ArrayList<>();
            collectCloseButtons(splitPane, buttons);
            if (buttons.size() != panes.size()) {
                found.add(buttons.size() + " close buttons in the scene graph, expected " + panes.size());
            }
            for (int i = 0; i < panes.size(); i++) {
                SithTermFxWidget pane = panes.get(i);
                StackPane wrapper = wrapperOf(pane);
                StackPane host = splitPane.getWidgetOverlayHost(pane);
                if (wrapper == null) {
                    found.add("pane " + i + " has no wrapper in the scene graph");
                    continue;
                }
                if (host != wrapper) {
                    found.add("pane " + i + " overlay host is " + (host == null ? "null" : "not its wrapper"));
                }
                Button button = closeButtonOf(wrapper);
                if (button == null) {
                    found.add("pane " + i + " wrapper has no close button");
                } else if (button.isVisible() != showButtons || button.isManaged() != showButtons) {
                    found.add("pane " + i + " close button visible=" + button.isVisible() + " managed="
                        + button.isManaged() + ", expected " + showButtons);
                }
            }
            return found;
        });
        check(problems.isEmpty(), step + ": " + String.join("; ", problems));
    }

    private static void expectForgotten(TerminalSplitPane splitPane, String what, SithTermFxWidget pane)
            throws Exception {
        check(onFxThread(() -> splitPane.getWidgetOverlayHost(pane)) == null, what + " still has an overlay host");
    }

    /** The leaf wrapper: the StackPane whose user data is the pane. */
    private static StackPane wrapperOf(SithTermFxWidget pane) {
        for (Node node = pane.getPane(); node != null; node = node.getParent()) {
            if (node instanceof StackPane stackPane && stackPane.getUserData() == pane) {
                return stackPane;
            }
        }
        return null;
    }

    private static Button closeButtonOf(StackPane wrapper) {
        for (Node child : wrapper.getChildren()) {
            if (child instanceof Button button && button.getStyleClass().contains("split-close-button")) {
                return button;
            }
        }
        return null;
    }

    private static void collectCloseButtons(Parent parent, List<Button> into) {
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child instanceof Button button && button.getStyleClass().contains("split-close-button")) {
                into.add(button);
            } else if (child instanceof Parent nested) {
                collectCloseButtons(nested, into);
            }
        }
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
            return "split-close-button-smoke";
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }
}
