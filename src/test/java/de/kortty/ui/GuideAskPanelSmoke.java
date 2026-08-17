package de.kortty.ui;

import de.kortty.core.GuideDocsRetriever;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.WritableImage;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed JavaFX smoke harness for the guide "AI search" panel. Replicates the {@link GuideViewer}
 * layout (dark toolbar + SplitPane with the real bundled guide and a {@link GuideAskPanel}),
 * renders a sample answer with citations and snapshots the scene to
 * {@code build/smoke/guide-ask-panel.png} so the dark guide theme can be verified offline.
 * Run via the {@code guideAskPanelSmoke} Gradle task. Exit 0 = OK.
 */
public final class GuideAskPanelSmoke {

    private GuideAskPanelSmoke() {
    }

    public static void main(String[] args) throws Exception {
        // Optional argument: the guide text size in percent. The panel has to grow WITH the
        // guide page, chrome included — run it at 200 to see the question row, buttons, status
        // line and source links scale, not just the answer WebView.
        int fontScalePercent = args.length > 0 ? Integer.parseInt(args[0].trim()) : 100;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                LanguageManager.getInstance().initialize(new GlobalSettings());
                Stage stage = new Stage();

                WebView guideView = new WebView();
                URL guide = GuideAskPanelSmoke.class.getResource("/guide/en/features/ai-tools.html");
                if (guide != null) {
                    guideView.getEngine().load(guide.toExternalForm());
                }

                GuideAskPanel panel = new GuideAskPanel(null, "en", location -> { });
                panel.setFontScale(fontScalePercent);
                guideView.setZoom(fontScalePercent / 100.0);
                SplitPane split = new SplitPane(guideView, panel);
                split.getStyleClass().add("guide-split");
                split.setDividerPositions(0.62);

                ToggleButton toggle = new ToggleButton("AI search");
                toggle.setSelected(true);
                HBox toolbar = new HBox(toggle);
                toolbar.setAlignment(Pos.CENTER_RIGHT);
                toolbar.setPadding(new Insets(6));
                toolbar.getStyleClass().add("guide-toolbar");

                BorderPane root = new BorderPane(split);
                root.setTop(toolbar);
                root.getStyleClass().add("guide-root");
                Scene scene = new Scene(root, 1120, 720);
                scene.setFill(Color.web("#07111d"));
                GuideViewer.applyGuideStylesheet(scene);
                stage.setScene(scene);
                stage.show();

                List<GuideDocsRetriever.Excerpt> excerpts = List.of(
                    new GuideDocsRetriever.Excerpt("features/ai-tools.html#how-the-ai-agent-works",
                        "Terminal AI agent & tools", "How the AI Agent works", "…", false),
                    new GuideDocsRetriever.Excerpt("features/ai-tools.html#running-commands",
                        "Terminal AI agent & tools", "Running commands", "…", false));
                panel.showAnswer(new GuideAskService.Answer("""
                    To run the AI agent in the terminal window, type `agent <goal>` at the prompt \
                    ([How the AI Agent works](features/ai-tools.html#how-the-ai-agent-works)).

                    - The agent probes the system, proposes commands and asks for approval \
                    ([Running commands](features/ai-tools.html#running-commands)).
                    - Use `agent-plan <task>` to review a plan first.

                    **Sources:** [Terminal AI agent & tools](features/ai-tools.html#how-the-ai-agent-works)
                    """, excerpts, false));

                // Let the WebViews finish rendering before the snapshot.
                PauseTransition settle = new PauseTransition(Duration.seconds(3));
                settle.setOnFinished(event -> {
                    try {
                        WritableImage image = scene.snapshot(null);
                        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
                        File out = new File(fontScalePercent == 100
                            ? "build/smoke/guide-ask-panel.png"
                            : "build/smoke/guide-ask-panel-" + fontScalePercent + ".png");
                        out.getParentFile().mkdirs();
                        ImageIO.write(buffered, "png", out);
                        System.out.println("Snapshot written: " + out.getAbsolutePath());
                    } catch (Exception e) {
                        failure.compareAndSet(null, "Snapshot failed: " + e);
                    } finally {
                        stage.hide();
                        done.countDown();
                    }
                });
                settle.play();
            } catch (Exception e) {
                failure.compareAndSet(null, "Setup failed: " + e);
                done.countDown();
            }
        });

        boolean finished = done.await(60, TimeUnit.SECONDS);
        Platform.exit();
        if (!finished) {
            System.err.println("Smoke timed out");
            System.exit(2);
        }
        if (failure.get() != null) {
            System.err.println(failure.get());
            System.exit(1);
        }
        System.out.println("guideAskPanelSmoke OK");
    }
}
