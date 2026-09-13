package de.kortty.ui;

import de.kortty.codingagent.CodingAgentTestHarness;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.desktop.BadgeIconRenderer;
import javafx.application.Platform;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.image.Image;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

import java.io.File;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Offline generator for the manual's coding-agents screenshots: the docked Coding Agents panel, the
 * status strip inside a status bar, the dashboard rows with agent chips and the badged app icon,
 * all rendered headless from the {@link CodingAgentSmokeFixture} scenario (Claude Code waiting on a
 * permission prompt, Codex working, Gemini CLI done) in the Normal design at 2x.
 *
 * <p>No running app, no real hosts: {@code user.home} is redirected to a throwaway directory before
 * anything touches {@code TerminalTab}, the tabs are never-connected local shells and the working
 * directories are demo paths under {@code ~/proj}. Writes
 * {@code app-docs/screenshots/coding-agents/panel.png}, {@code status-strip.png},
 * {@code dashboard.png} and {@code badge-icon.png} (override the directory with
 * {@code -Dkortty.screenshot.outputDir}). Run via the {@code generateCodingAgentScreenshots}
 * Gradle task; run {@code ./scripts/optimize-png.sh} on the outputs afterwards. Exit 0 = OK.
 */
public final class CodingAgentScreenshotGenerator {

    private static final double SCALE = 2;
    private static final double PANEL_WIDTH = 440;
    private static final double PANEL_HEIGHT = 520;
    private static final double STRIP_WIDTH = 600;
    private static final double STRIP_HEIGHT = 36;
    private static final double DASHBOARD_WIDTH = 380;
    private static final double DASHBOARD_HEIGHT = 224;
    private static final int BADGE_SIZE = 256;

    /** Chrome colours of the Normal design (MainWindow status bar / panel defaults). */
    private static final String PANEL_BG = "#1e1e1e";
    private static final String STATUS_BAR_BG = "#2d2d2d";
    private static final String STATUS_BAR_FG = "#cccccc";
    private static final String DASHBOARD_BG = "#252526";
    private static final String SAMPLE_PROMPT = "Retry the upload";

    private CodingAgentScreenshotGenerator() {
    }

    public static void main(String[] args) throws Exception {
        // FIRST: KorTTYApplication resolves its config directory from user.home in a static initializer.
        Path isolatedHome = Files.createTempDirectory("kortty-coding-agent-screenshots");
        System.setProperty("user.home", isolatedHome.toString());
        Locale.setDefault(Locale.ENGLISH);
        File outputDir = new File(System.getProperty("kortty.screenshot.outputDir",
            "app-docs/screenshots/coding-agents"));

        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) ->
            failure.compareAndSet(null, "Uncaught on " + thread.getName() + ": " + stack(error)));

        Platform.startup(() -> {
            try {
                CodingAgentSmokeFixture.initI18n();
                writePanelAndStrip(outputDir);
                writeDashboard(outputDir);
                writeBadge(outputDir);
            } catch (Throwable error) {
                failure.compareAndSet(null, stack(error));
            } finally {
                done.countDown();
            }
        });

        boolean finished = done.await(120, TimeUnit.SECONDS);
        Platform.runLater(Platform::exit);
        if (!finished) {
            System.err.println("SCREENSHOT GENERATION TIMEOUT");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println("SCREENSHOT GENERATION FAILURE: " + failure.get());
            System.exit(1);
        }
        System.out.println("generateCodingAgentScreenshots OK");
        System.exit(0);
    }

    private static void writePanelAndStrip(File outputDir) throws Exception {
        try (CodingAgentTestHarness harness = new CodingAgentTestHarness()) {
            CodingAgentSmokeFixture fixture = new CodingAgentSmokeFixture(harness);
            fixture.populate(CodingAgentSmokeFixture.PaneIds.synthetic());

            // Panel, docked (bound) at a little more than its default width.
            CodingAgentPanel panel = new CodingAgentPanel(fixture.registry(), fixture.actions(),
                fixture.navigator(), fixture, harness::nowMillis);
            Scene panelScene = new Scene(panel, PANEL_WIDTH, PANEL_HEIGHT);
            AppDesignStyleSupport.registerApplicationBaseStyles(panelScene);
            CodingAgentSmokeFixture.stageFor(panelScene);
            panel.bind();
            panel.applyTheme(null, null);
            // The prompt box in use: Codex (working, in the pane the user is in) is the target.
            panel.selectPane(fixture.ids().codex());
            TextArea prompt = promptArea(panel);
            if (prompt == null) {
                throw new AssertionError("the prompt TextArea was not found");
            }
            prompt.setText(SAMPLE_PROMPT);
            WritableImage panelImage = CodingAgentSmokeFixture.snapshot(panelScene, SCALE, Color.web(PANEL_BG));
            requirePainted(panelImage, "panel");
            CodingAgentSmokeFixture.writePng(panelImage, new File(outputDir, "panel.png"));
            panel.dispose();

            // Strip inside a MainWindow-like status bar: [Ready][spacer][strip].
            CodingAgentStatusStrip strip = new CodingAgentStatusStrip(fixture.registry());
            Label ready = new Label(I18n.get("app.ready"));
            ready.getStyleClass().add("status-label");
            ready.setStyle("-fx-text-fill: " + STATUS_BAR_FG + ";");
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox statusRow = new HBox(8, ready, spacer, strip);
            statusRow.setAlignment(Pos.CENTER_LEFT);
            VBox statusBar = new VBox(statusRow);
            statusBar.getStyleClass().add("status-bar");
            statusBar.setStyle("-fx-padding: 5; -fx-background-color: " + STATUS_BAR_BG + ";");
            Scene stripScene = new Scene(statusBar, STRIP_WIDTH, STRIP_HEIGHT);
            AppDesignStyleSupport.registerApplicationBaseStyles(stripScene);
            CodingAgentSmokeFixture.stageFor(stripScene);
            strip.attach();
            strip.applyTheme(STATUS_BAR_BG, STATUS_BAR_FG);
            WritableImage stripImage = CodingAgentSmokeFixture.snapshot(stripScene, SCALE, Color.web(STATUS_BAR_BG));
            requirePainted(stripImage, "status strip");
            CodingAgentSmokeFixture.writePng(stripImage, new File(outputDir, "status-strip.png"));
            strip.dispose();
        }
    }

    private static void writeDashboard(File outputDir) throws Exception {
        try (CodingAgentTestHarness harness = new CodingAgentTestHarness()) {
            CodingAgentSmokeFixture fixture = new CodingAgentSmokeFixture(harness);
            TabPane tabPane = new TabPane();
            TerminalTab apiTab = fixture.addTab(tabPane, CodingAgentSmokeFixture.TAB_API);
            TerminalTab webTab = fixture.addTab(tabPane, CodingAgentSmokeFixture.TAB_WEB);
            PaneRef claude = CodingAgentSmokeFixture.paneOf(apiTab, 0);
            PaneRef gemini = CodingAgentSmokeFixture.paneOf(apiTab, 1);
            PaneRef codex = CodingAgentSmokeFixture.paneOf(webTab, 0);
            fixture.populate(new CodingAgentSmokeFixture.PaneIds(claude, gemini, codex));

            DashboardView dashboard = new DashboardView(tabPane, (tab, action) -> { }, connection -> null);
            dashboard.setCodingAgentRegistry(fixture.registry());
            dashboard.setPaneLocator(fixture);
            dashboard.setPaneActionHandler((tab, widget, pane, action) -> { });
            Scene scene = new Scene(dashboard, DASHBOARD_WIDTH, DASHBOARD_HEIGHT);
            AppDesignStyleSupport.registerApplicationBaseStyles(scene);
            CodingAgentSmokeFixture.stageFor(scene);
            dashboard.refresh();
            WritableImage image = CodingAgentSmokeFixture.snapshot(scene, SCALE, Color.web(DASHBOARD_BG));
            requirePainted(image, "dashboard");
            CodingAgentSmokeFixture.writePng(image, new File(outputDir, "dashboard.png"));
            dashboard.dispose();
            for (TerminalTab tab : List.of(apiTab, webTab)) {
                try {
                    tab.getTerminalView().cleanup();
                } catch (RuntimeException ignored) {
                    // never connected; cleanup only stops timers
                }
            }
        }
    }

    private static void writeBadge(File outputDir) throws Exception {
        Image base = new Image(Objects.requireNonNull(
            CodingAgentScreenshotGenerator.class.getResource("/icon/kortty_icon.png"), "app icon").toExternalForm());
        Image badged = BadgeIconRenderer.render(base, 3, BADGE_SIZE);
        requirePainted(badged, "badge icon");
        CodingAgentSmokeFixture.writePng(badged, new File(outputDir, "badge-icon.png"));
    }

    private static TextArea promptArea(Node root) {
        String placeholder = I18n.get("codingAgent.panel.prompt.placeholder");
        for (Node node : root.lookupAll(".text-area")) {
            if (node instanceof TextArea area && placeholder.equals(area.getPromptText())) {
                return area;
            }
        }
        return null;
    }

    /** Fails when the image is a single flat colour (nothing was painted). */
    private static void requirePainted(Image image, String what) {
        int w = (int) image.getWidth();
        int h = (int) image.getHeight();
        if (w < 10 || h < 10) {
            throw new AssertionError(what + " snapshot is too small: " + w + "x" + h);
        }
        Color first = image.getPixelReader().getColor(0, 0);
        for (int y = 0; y < h; y += 3) {
            for (int x = 0; x < w; x += 3) {
                if (!image.getPixelReader().getColor(x, y).equals(first)) {
                    return;
                }
            }
        }
        throw new AssertionError(what + " snapshot is blank (single colour " + first + ")");
    }

    private static String stack(Throwable error) {
        StringWriter writer = new StringWriter();
        error.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
