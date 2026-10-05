package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.isolation.IsolationLevel;
import de.kortty.isolation.IsolationState;
import de.kortty.isolation.sandbox.SandboxSupport;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.TabPane;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.shape.SVGPath;
import javafx.stage.Stage;

import javax.imageio.ImageIO;
import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Headed JavaFX check of session isolation with real local shells: one tab in a sandbox and incognito,
 * one in its own process, one without isolation. Checks that the tabs show the filled shield and the spy,
 * the outline shield, and nothing; that every marker has a tooltip text for screen readers; and that the
 * sandboxed shell really cannot read korTTY's configuration folder while the unisolated one can. Pass a
 * PNG path via --args to save a snapshot. Needs a display and a working sandbox (macOS, or Linux with
 * bubblewrap); skips with exit 0 otherwise. Run via the {@code sessionIsolationSmoke} Gradle task.
 */
public final class SessionIsolationSmoke {

    private static final long TIMEOUT_MILLIS = 20_000;

    private SessionIsolationSmoke() {
    }

    public static void main(String[] args) throws Exception {
        java.util.Locale.setDefault(java.util.Locale.ENGLISH);
        String snapshotPath = args.length > 0 ? args[0] : null;
        if (!SandboxSupport.availability().available()) {
            System.out.println("SKIP: no working sandbox here: " + SandboxSupport.availability());
            System.exit(0);
        }
        Path probeFile = de.kortty.KorTTYApplication.getConfigDirectory().resolve("isolation").resolve("smoke-probe");
        java.nio.file.Files.createDirectories(probeFile.getParent());
        java.nio.file.Files.writeString(probeFile, "visible\n");
        Platform.startup(() -> LanguageManager.getInstance().initialize(new GlobalSettings()));
        int exit = 1;
        try {
            List<TerminalTab> tabs = onFx(() -> {
                TabPane tabPane = new TabPane();
                List<TerminalTab> created = new ArrayList<>();
                created.add(addTab(tabPane, "sandboxed", IsolationLevel.SANDBOX, true));
                created.add(addTab(tabPane, "process", IsolationLevel.PROCESS, false));
                created.add(addTab(tabPane, "plain", IsolationLevel.NONE, false));
                Stage stage = new Stage();
                Scene scene = new Scene(new BorderPane(tabPane), 720, 200);
                scene.getStylesheets().add(SessionIsolationSmoke.class.getResource("/styles/terminal.css").toExternalForm());
                stage.setScene(scene);
                stage.setTitle("Session isolation smoke");
                stage.show();
                for (TerminalTab tab : created) {
                    tab.connect();
                }
                return created;
            });
            TerminalTab sandboxed = tabs.get(0);
            TerminalTab process = tabs.get(1);
            TerminalTab plain = tabs.get(2);

            waitFor("the sandboxed tab shows its shield",
                () -> onFxQuiet(() -> sandboxed.getIsolationMarkers().shield() == IsolationState.SANDBOXED));
            waitFor("the process tab shows its shield",
                () -> onFxQuiet(() -> process.getIsolationMarkers().shield() == IsolationState.PROCESS));
            check("the plain tab shows no shield",
                onFx(() -> plain.getIsolationMarkers().shield() == IsolationState.NONE
                    && styledNodes(plain, TerminalTab.ISOLATION_MARKER_STYLE_CLASS).isEmpty()));
            check("the sandboxed tab has a filled shield node",
                onFx(() -> styledNodes(sandboxed, "tab-isolation-sandboxed").size() == 1));
            check("the sandboxed tab is incognito and shows the spy",
                onFx(() -> sandboxed.getTerminalView().isIncognito()
                    && styledNodes(sandboxed, TerminalTab.INCOGNITO_MARKER_STYLE_CLASS).size() == 1));
            check("the process tab has an outline shield and no spy",
                onFx(() -> styledNodes(process, "tab-isolation-process").size() == 1
                    && styledNodes(process, TerminalTab.INCOGNITO_MARKER_STYLE_CLASS).isEmpty()));
            check("every marker tells screen readers what it means",
                onFx(() -> {
                    for (TerminalTab tab : tabs) {
                        for (Node node : styledNodes(tab, TerminalTab.ISOLATION_MARKER_STYLE_CLASS)) {
                            if (node.getAccessibleText() == null || node.getAccessibleText().isBlank()) {
                                return false;
                            }
                        }
                    }
                    return true;
                }));
            check("the tab tooltip names the sandbox",
                onFx(() -> sandboxed.getTooltip() != null
                    && sandboxed.getTooltip().getText().contains(SandboxSupport.availability().backendId())));

            String probe = "cat '" + probeFile + "' >/dev/null 2>&1 && echo PROBE-READ || echo PROBE-BLOCKED";
            onFx(() -> {
                sandboxed.getTerminalView().sendInputLine(probe);
                plain.getTerminalView().sendInputLine(probe);
                return null;
            });
            waitFor("the sandboxed shell cannot read the configuration folder",
                () -> onFxQuiet(() -> screenContainsLine(sandboxed, "PROBE-BLOCKED")));
            waitFor("the unisolated shell can",
                () -> onFxQuiet(() -> screenContainsLine(plain, "PROBE-READ")));

            if (snapshotPath != null) {
                onFx(() -> {
                    Scene scene = sandboxed.getTabPane().getScene();
                    javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
                    parameters.setTransform(javafx.scene.transform.Transform.scale(2, 2));
                    WritableImage image = scene.getRoot().snapshot(parameters, null);
                    File file = new File(snapshotPath);
                    if (file.getParentFile() != null) {
                        file.getParentFile().mkdirs();
                    }
                    ImageIO.write(SwingFXUtils.fromFXImage(image, null), "png", file);
                    System.out.println("Snapshot written: " + file.getAbsolutePath());
                    return null;
                });
            }
            onFx(() -> {
                for (TerminalTab tab : tabs) {
                    tab.getTerminalView().cleanup();
                }
                return null;
            });
            System.out.println("OK: session isolation markers and sandbox behave");
            exit = 0;
        } catch (Throwable t) {
            System.err.println("FAIL: " + t.getMessage());
            t.printStackTrace();
        } finally {
            java.nio.file.Files.deleteIfExists(probeFile);
        }
        System.exit(exit);
    }

    private static TerminalTab addTab(TabPane tabPane, String name, IsolationLevel level, boolean incognito) {
        ServerConnection connection = new ServerConnection();
        connection.setName(name);
        connection.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        connection.setLocalShellCommand("/bin/sh");
        connection.setLocalShellWorkingDirectory(System.getProperty("java.io.tmpdir"));
        connection.setIsolationLevel(level);
        connection.setIncognito(incognito);
        TerminalTab tab = new TerminalTab(connection, null);
        tabPane.getTabs().add(tab);
        return tab;
    }

    private static List<Node> styledNodes(TerminalTab tab, String styleClass) {
        List<Node> found = new ArrayList<>();
        if (tab.getGraphic() != null) {
            collect(tab.getGraphic(), styleClass, found);
        }
        return found;
    }

    private static void collect(Node node, String styleClass, List<Node> found) {
        if (node instanceof SVGPath && node.getStyleClass().contains(styleClass)) {
            found.add(node);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, styleClass, found);
            }
        }
    }

    private static boolean screenContainsLine(TerminalTab tab, String text) {
        for (String line : tab.getTerminalView().getTerminalHistory().split("\n")) {
            if (line.strip().equals(text)) {
                return true;
            }
        }
        return false;
    }

    private static void check(String what, boolean ok) {
        if (!ok) {
            throw new AssertionError(what);
        }
        System.out.println("  ok: " + what);
    }

    private static void waitFor(String what, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                System.out.println("  ok: " + what);
                return;
            }
            Thread.sleep(100);
        }
        throw new AssertionError("timed out: " + what);
    }

    private static <T> T onFx(Callable<T> call) throws Exception {
        FutureTask<T> task = new FutureTask<>(call);
        Platform.runLater(task);
        return task.get(TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    private static boolean onFxQuiet(Callable<Boolean> call) {
        try {
            return Boolean.TRUE.equals(onFx(call));
        } catch (Exception e) {
            return false;
        }
    }
}
