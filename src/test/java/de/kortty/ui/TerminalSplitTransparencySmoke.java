package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.util.TermSize;
import com.sithtermfx.ui.settings.DefaultSettingsProvider;
import com.sithtermfx.ui.split.SplitRequest;
import com.sithtermfx.ui.split.TerminalSplitPane;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.Region;
import javafx.stage.Stage;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX regression check for transparent split terminals. It enables transparency before
 * creating two nested splits and verifies that every JavaFX SplitPane inherits the transparent
 * background. Run via the {@code terminalSplitTransparencySmoke} Gradle task. Exit 0 = OK.
 */
public final class TerminalSplitTransparencySmoke {

    private static final String TRANSPARENT_BACKGROUND = "-fx-background-color: transparent";

    private TerminalSplitTransparencySmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            TerminalSplitPane terminalSplitPane = null;
            Stage stage = null;
            try {
                terminalSplitPane = new TerminalSplitPane(
                    DefaultSettingsProvider::new,
                    request -> request == null ? null : new BlockingTtyConnector());
                terminalSplitPane.setBackgroundTransparent(true);

                stage = new Stage();
                stage.setScene(new Scene(terminalSplitPane, 900, 560));
                stage.show();

                terminalSplitPane.split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.HORIZONTAL);
                terminalSplitPane.split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, Orientation.VERTICAL);

                TerminalSplitPane paneToVerify = terminalSplitPane;
                Stage stageToClose = stage;
                Platform.runLater(() -> verify(paneToVerify, stageToClose, failure, done));
            } catch (Throwable error) {
                closeQuietly(terminalSplitPane, stage);
                failure.compareAndSet(null, "Setup failed: " + stack(error));
                done.countDown();
            }
        });

        boolean finished = done.await(30, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SMOKE TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SMOKE FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("SMOKE OK: transparency survived nested terminal splits");
        System.exit(0);
    }

    private static void verify(TerminalSplitPane terminalSplitPane, Stage stage,
                               AtomicReference<String> failure, CountDownLatch done) {
        try {
            stage.getScene().getRoot().applyCss();
            stage.getScene().getRoot().layout();

            check(isTransparent(terminalSplitPane), "outer terminal split container is opaque");
            List<SplitPane> nestedSplitPanes = descendantsOfType(terminalSplitPane, SplitPane.class);
            check(nestedSplitPanes.size() == 2,
                "expected two nested split controls, got " + nestedSplitPanes.size());
            check(nestedSplitPanes.stream().allMatch(TerminalSplitTransparencySmoke::isTransparent),
                "a newly created nested split restored an opaque background");

            terminalSplitPane.setBackgroundTransparent(false);
            check(!isTransparent(terminalSplitPane), "outer split stayed transparent after disabling it");
            check(nestedSplitPanes.stream().noneMatch(TerminalSplitTransparencySmoke::isTransparent),
                "a nested split stayed transparent after disabling it");

            terminalSplitPane.setBackgroundTransparent(true);
            check(nestedSplitPanes.stream().allMatch(TerminalSplitTransparencySmoke::isTransparent),
                "existing nested splits did not become transparent again");
        } catch (Throwable error) {
            failure.compareAndSet(null, "Assertion failed: " + stack(error));
        } finally {
            closeQuietly(terminalSplitPane, stage);
            done.countDown();
        }
    }

    private static boolean isTransparent(Region region) {
        String style = region.getStyle();
        return style != null && style.contains(TRANSPARENT_BACKGROUND);
    }

    private static <T extends Node> List<T> descendantsOfType(Node root, Class<T> type) {
        List<T> matches = new ArrayList<>();
        collectDescendants(root, type, matches);
        return matches;
    }

    private static <T extends Node> void collectDescendants(Node node, Class<T> type, List<T> matches) {
        if (node != null && type.isInstance(node)) {
            matches.add(type.cast(node));
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collectDescendants(child, type, matches);
            }
        }
    }

    private static void closeQuietly(TerminalSplitPane terminalSplitPane, Stage stage) {
        try {
            if (terminalSplitPane != null) terminalSplitPane.closeAll();
        } catch (Exception ignored) {
        }
        try {
            if (stage != null) stage.close();
        } catch (Exception ignored) {
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

    private static final class BlockingTtyConnector implements TtyConnector {
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile boolean connected = true;

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            try {
                closed.await();
                return -1;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return -1;
            }
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
            return false;
        }

        @Override
        public String getName() {
            return "split-transparency-smoke";
        }

        @Override
        public void close() {
            connected = false;
            closed.countDown();
        }
    }
}
