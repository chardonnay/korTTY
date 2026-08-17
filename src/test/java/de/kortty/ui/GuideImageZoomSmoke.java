package de.kortty.ui;

import javafx.application.Platform;
import javafx.embed.swing.SwingFXUtils;
import javafx.scene.Scene;
import javafx.scene.image.WritableImage;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.animation.PauseTransition;
import javafx.stage.Stage;
import javafx.util.Duration;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Headed smoke for the guide's click-to-enlarge overlay
 * ({@code app-docs/site/docs/en/assets/javascripts/kt-zoom.js}).
 *
 * <p>The overlay is plain DOM/JS, so a browser check proves nothing about the surface that
 * matters: the guide is read inside a JavaFX {@link WebView}, whose WebKit is several releases
 * behind the desktop browsers and fails silently — a click that does nothing looks exactly like an
 * image that is not zoomable. This loads the BUNDLED guide page from the classpath (the same URL
 * {@link GuideViewer} loads), clicks a screenshot through the engine, and asserts the overlay
 * opened, sized itself and can zoom a step. Snapshot: {@code build/smoke/guide-image-zoom.png}.
 *
 * <p>Run via the {@code guideImageZoomSmoke} Gradle task after
 * {@code ./gradlew stageGuideIntoResources}, so the bundled guide carries the current script.
 * Exit 0 = OK.
 */
public final class GuideImageZoomSmoke {

    /** A bundled page that carries a screenshot; the settings pages always do. */
    private static final String PAGE = "/guide/en/reference/settings/snippet-editor/index.html";

    private GuideImageZoomSmoke() {
    }

    public static void main(String[] args) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
            failure.compareAndSet(null, "Uncaught on " + t.getName() + ": " + e));

        Platform.startup(() -> {
            try {
                URL page = GuideImageZoomSmoke.class.getResource(PAGE);
                if (page == null) {
                    failure.set("Bundled guide page not found: " + PAGE
                        + " — run ./gradlew stageGuideIntoResources first");
                    done.countDown();
                    return;
                }
                WebView webView = new WebView();
                Stage stage = new Stage();
                Scene scene = new Scene(webView, 1100, 760);
                scene.setFill(Color.web("#07111d"));
                stage.setScene(scene);
                stage.show();

                WebEngine engine = webView.getEngine();
                engine.getLoadWorker().stateProperty().addListener((obs, old, state) -> {
                    if (state != javafx.concurrent.Worker.State.SUCCEEDED) {
                        return;
                    }
                    // One pulse after load so the script's DOMContentLoaded pass has marked the
                    // images and the layout is settled enough to measure the fitted size.
                    Platform.runLater(() -> {
                        try {
                            check(engine, failure);
                        } catch (Exception e) {
                            failure.compareAndSet(null, "Check failed: " + e);
                        }
                        // WebKit paints on its own schedule: snapshotting in the same pulse that
                        // opened the overlay captures the page as it was before the click.
                        PauseTransition settle = new PauseTransition(Duration.seconds(2));
                        settle.setOnFinished(event -> {
                            try {
                                snapshot(scene);
                            } catch (Exception e) {
                                failure.compareAndSet(null, "Snapshot failed: " + e);
                            } finally {
                                stage.hide();
                                done.countDown();
                            }
                        });
                        settle.play();
                    });
                });
                engine.load(page.toExternalForm());
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
        System.out.println("guideImageZoomSmoke OK");
    }

    private static void snapshot(Scene scene) throws Exception {
        WritableImage image = scene.snapshot(null);
        BufferedImage buffered = SwingFXUtils.fromFXImage(image, null);
        File out = new File("build/smoke/guide-image-zoom.png");
        out.getParentFile().mkdirs();
        ImageIO.write(buffered, "png", out);
        System.out.println("Snapshot written: " + out.getAbsolutePath());
    }

    private static void check(WebEngine engine, AtomicReference<String> failure) {
        int marked = asInt(engine.executeScript(
            "document.querySelectorAll('img[data-kt-zoomable]').length"));
        if (marked == 0) {
            failure.set("No image was marked zoomable — is kt-zoom.js in the bundled guide?");
            return;
        }
        engine.executeScript("document.querySelector('img[data-kt-zoomable]').click()");
        Object outcome = engine.executeScript("""
            (function () {
              var o = document.querySelector('.kt-zoom');
              if (!o) { return 'no overlay element'; }
              if (o.hidden) { return 'overlay stayed hidden'; }
              var img = o.querySelector('.kt-zoom__img');
              if (!img || !img.getAttribute('src')) { return 'overlay has no picture'; }
              var fitted = img.getBoundingClientRect().width;
              if (fitted < 200) { return 'picture did not fill the window: ' + fitted; }
              document.querySelector('.kt-zoom__btn[data-kt-zoom=in]').click();
              var zoomed = img.getBoundingClientRect().width;
              if (zoomed <= fitted) { return 'zoom-in did not grow the picture'; }
              return 'ok ' + Math.round(fitted) + ' -> ' + Math.round(zoomed);
            })()""");
        String result = String.valueOf(outcome);
        System.out.println("overlay: " + result);
        if (!result.startsWith("ok")) {
            failure.set("Image zoom overlay failed in WebKit: " + result);
        }
    }

    private static int asInt(Object value) {
        return value instanceof Number number ? number.intValue() : 0;
    }
}
