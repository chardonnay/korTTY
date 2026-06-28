package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.WindowGeometry;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.scene.Scene;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Locale;

/**
 * In-app documentation viewer ("Anleitung"). Hosts the bundled, fully offline
 * MkDocs guide site in a JavaFX {@link WebView}, loaded from the classpath
 * ({@code /guide/<lang>/index.html}) exactly like the bundled Monaco editor.
 *
 * <p>Design notes mirror the hard-won WebView lessons in {@link MonacoEditorPane}:
 * a {@code disposed} flag guards late load-worker callbacks, the page is dropped
 * on close, and no JS-&gt;Java bridge is used (the guide is read-only; the app
 * version already shows in the site footer and external links are intercepted by
 * URL, not by a bridge), so there is no weak-reference bridge to crash on.</p>
 */
public final class GuideViewer {

    private static final Logger logger = LoggerFactory.getLogger(GuideViewer.class);
    private static final String ONLINE_FALLBACK_URL = "https://chardonnay.github.io/korTTY/";
    private static final double DEFAULT_WIDTH = 1120;
    private static final double DEFAULT_HEIGHT = 820;

    // Single open instance: re-focus instead of opening a second window.
    private static WeakReference<GuideViewer> openInstance = new WeakReference<>(null);

    private final KorTTYApplication app;
    private final Stage stage = new Stage();
    private final WebView webView = new WebView();
    private final PauseTransition geometrySaveDelay = new PauseTransition(Duration.millis(500));

    private boolean disposed;
    private boolean geometryListenersInstalled;
    private String lastInternalLocation;

    private GuideViewer(KorTTYApplication app, Window owner) {
        this.app = app;

        WebEngine engine = webView.getEngine();
        installExternalLinkHandler(engine);

        BorderPane root = new BorderPane(webView);
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.web("#07111d"));

        stage.setScene(scene);
        stage.setTitle(I18n.get("menu.help.guide") + " — " + KorTTYApplication.getAppName());
        applyIcon();
        if (owner != null) {
            stage.initOwner(owner);
        }

        restoreGeometry();
        stage.setOnShown(event -> installGeometryPersistence());
        stage.setOnCloseRequest(event -> dispose());
        stage.setOnHidden(event -> dispose());

        loadGuide(engine);
    }

    /** Opens the guide, or focuses the existing window if one is already open. */
    public static void show(KorTTYApplication app, Window owner) {
        GuideViewer existing = openInstance.get();
        if (existing != null && !existing.disposed && existing.stage.isShowing()) {
            existing.stage.toFront();
            existing.stage.requestFocus();
            return;
        }
        GuideViewer viewer = new GuideViewer(app, owner);
        openInstance = new WeakReference<>(viewer);
        viewer.stage.show();
    }

    private void loadGuide(WebEngine engine) {
        String lang = resolveGuideLanguage();
        URL resource = GuideViewer.class.getResource("/guide/" + lang + "/index.html");
        if (resource == null && !"en".equals(lang)) {
            resource = GuideViewer.class.getResource("/guide/en/index.html");
        }
        if (resource == null) {
            logger.warn("Bundled guide not found on classpath; showing online fallback notice");
            engine.loadContent(onlineFallbackHtml());
            return;
        }
        lastInternalLocation = resource.toExternalForm();
        engine.load(lastInternalLocation);
    }

    /** Picks the bundled guide language from the app's current locale; only en+de ship, others fall back to en. */
    private String resolveGuideLanguage() {
        try {
            Locale locale = LanguageManager.getInstance().getCurrentLocale();
            String code = locale != null ? locale.getLanguage() : "en";
            return "de".equals(code) ? "de" : "en";
        } catch (RuntimeException e) {
            return "en";
        }
    }

    /**
     * Keep navigation inside the bundled site; hand external links (http/https/
     * mailto/...) to the system browser instead of loading them in the WebView.
     */
    private void installExternalLinkHandler(WebEngine engine) {
        engine.locationProperty().addListener((obs, oldLoc, newLoc) -> {
            if (disposed || newLoc == null || newLoc.isBlank()) {
                return;
            }
            if (isExternal(newLoc)) {
                Platform.runLater(() -> {
                    if (disposed) {
                        return;
                    }
                    engine.getLoadWorker().cancel();
                    if (lastInternalLocation != null) {
                        engine.load(lastInternalLocation);
                    }
                    openExternal(newLoc);
                });
            } else {
                lastInternalLocation = newLoc;
            }
        });
    }

    private static boolean isExternal(String location) {
        String lower = location.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
            || lower.startsWith("mailto:") || lower.startsWith("ftp://") || lower.startsWith("ftps://");
    }

    private void openExternal(String url) {
        try {
            app.getHostServices().showDocument(url);
        } catch (Exception e) {
            logger.warn("Could not open external link from guide: {}", url, e);
        }
    }

    private void applyIcon() {
        try {
            URL iconUrl = getClass().getResource("/icon/kortty_icon.png");
            if (iconUrl != null) {
                stage.getIcons().add(new Image(iconUrl.toExternalForm()));
            }
        } catch (RuntimeException e) {
            logger.debug("Could not set guide window icon", e);
        }
    }

    private String onlineFallbackHtml() {
        return "<!doctype html><html><head><meta charset=\"utf-8\">"
            + "<style>body{background:#07111d;color:#e6f3ff;font-family:sans-serif;"
            + "display:flex;flex-direction:column;align-items:center;justify-content:center;"
            + "height:100vh;margin:0}a{color:#38bdf8}</style></head><body>"
            + "<h1>korTTY Guide</h1>"
            + "<p>The bundled guide is not available in this build.</p>"
            + "<p><a href=\"" + ONLINE_FALLBACK_URL + "\">Open the online guide</a></p>"
            + "</body></html>";
    }

    // ---- Geometry persistence (mirrors JobSchedulerDialog, adapted for a Stage) ----

    private void restoreGeometry() {
        try {
            WindowGeometry geometry = settings() != null ? settings().getGuideViewerGeometry() : null;
            if (geometry == null || geometry.getWidth() <= 100 || geometry.getHeight() <= 100) {
                stage.centerOnScreen();
                return;
            }
            stage.setX(geometry.getX());
            stage.setY(geometry.getY());
            stage.setWidth(geometry.getWidth());
            stage.setHeight(geometry.getHeight());
            stage.setMaximized(geometry.isMaximized());
        } catch (Exception e) {
            logger.debug("Could not restore guide viewer geometry", e);
        }
    }

    private void installGeometryPersistence() {
        if (geometryListenersInstalled) {
            return;
        }
        geometryListenersInstalled = true;
        geometrySaveDelay.setOnFinished(event -> saveGeometry());
        ChangeListener<Number> numberListener = (obs, oldV, newV) -> geometrySaveDelay.playFromStart();
        ChangeListener<Boolean> booleanListener = (obs, oldV, newV) -> geometrySaveDelay.playFromStart();
        stage.xProperty().addListener(numberListener);
        stage.yProperty().addListener(numberListener);
        stage.widthProperty().addListener(numberListener);
        stage.heightProperty().addListener(numberListener);
        stage.maximizedProperty().addListener(booleanListener);
    }

    private void saveGeometry() {
        try {
            if (settings() == null) {
                return;
            }
            WindowGeometry geometry = new WindowGeometry(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight());
            geometry.setMaximized(stage.isMaximized());
            settings().setGuideViewerGeometry(geometry);
            app.getGlobalSettingsManager().save();
        } catch (Exception e) {
            logger.debug("Could not save guide viewer geometry", e);
        }
    }

    private GlobalSettings settings() {
        return app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null;
    }

    private void dispose() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::dispose);
            return;
        }
        if (disposed) {
            return;
        }
        // Mark disposed FIRST so any in-flight load-worker callback becomes a no-op.
        disposed = true;
        // Flush any pending debounced geometry save before cancelling the timer, so a
        // quick move/resize-then-close doesn't drop the final window geometry.
        boolean geometrySavePending = geometrySaveDelay.getStatus() == Animation.Status.RUNNING;
        geometrySaveDelay.stop();
        if (geometrySavePending) {
            saveGeometry();
        }
        try {
            // Drop the page so the native WebKit context is released promptly.
            webView.getEngine().loadContent("");
        } catch (RuntimeException e) {
            logger.debug("Guide viewer dispose cleanup failed", e);
        }
        if (openInstance.get() == this) {
            openInstance = new WeakReference<>(null);
        }
    }
}
