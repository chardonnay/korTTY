package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GuideLocationResolver;
import de.kortty.core.LanguageManager;
import de.kortty.model.GlobalSettings;
import de.kortty.model.WindowGeometry;
import javafx.animation.Animation;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.SplitPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.Image;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
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
import java.nio.file.Path;
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
    private final SplitPane splitPane = new SplitPane();
    private final GuideAskPanel askPanel;
    private final PauseTransition geometrySaveDelay = new PauseTransition(Duration.millis(500));

    private boolean disposed;
    private boolean geometryListenersInstalled;
    private String lastInternalLocation;

    /**
     * Builds the guide window — offline {@link WebView}, external-link handler,
     * icon and restored geometry — but does not show it. Use {@link #show}.
     */
    private GuideViewer(KorTTYApplication app, Window owner) {
        this.app = app;

        WebEngine engine = webView.getEngine();
        installExternalLinkHandler(engine);

        splitPane.getItems().add(webView);
        splitPane.getStyleClass().add("guide-split");

        BorderPane root = new BorderPane(splitPane);
        // The AI docs search only exists while AI features are enabled in the global settings;
        // without them the guide window stays a plain viewer (no toolbar, no ask WebView).
        if (isAiSearchAvailable()) {
            askPanel = new GuideAskPanel(app, resolveGuideLanguage(), this::navigateToLocation);
            ToggleButton askToggle = new ToggleButton(I18n.get("guide.ask.toggle"));
            askToggle.setOnAction(event -> toggleAskPanel(askToggle.isSelected()));
            HBox toolbar = new HBox(askToggle);
            toolbar.setAlignment(Pos.CENTER_RIGHT);
            toolbar.setPadding(new Insets(6));
            toolbar.getStyleClass().add("guide-toolbar");
            root.setTop(toolbar);
        } else {
            askPanel = null;
        }
        root.getStyleClass().add("guide-root");
        Scene scene = new Scene(root, DEFAULT_WIDTH, DEFAULT_HEIGHT);
        scene.setFill(Color.web("#07111d"));
        applyGuideStylesheet(scene);

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
        de.kortty.telemetry.Telemetry.track(
            de.kortty.telemetry.TelemetryEvents.TOOL_OPENED, java.util.Map.of("tool", "manual"));
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

    /** Opens the guide directly at a documentation location (e.g. {@code "about/anonymous-data.html"}). */
    public static void show(KorTTYApplication app, Window owner, String location) {
        de.kortty.telemetry.Telemetry.track(
            de.kortty.telemetry.TelemetryEvents.TOOL_OPENED, java.util.Map.of("tool", "manual"));
        GuideViewer existing = openInstance.get();
        if (existing != null && !existing.disposed && existing.stage.isShowing()) {
            existing.navigateToLocation(location);
            existing.stage.toFront();
            existing.stage.requestFocus();
            return;
        }
        GuideViewer viewer = new GuideViewer(app, owner);
        openInstance = new WeakReference<>(viewer);
        viewer.navigateToLocation(location);
        viewer.stage.show();
    }

    /**
     * Loads the bundled {@code /guide/<lang>/index.html} from the classpath,
     * falling back to English and then to an offline-notice page if absent.
     */
    private void loadGuide(WebEngine engine) {
        String url = GuideLocationResolver.pageUrl(resolveGuideLanguage(), "index.html",
            configDirectory());
        if (url == null) {
            logger.warn("Bundled guide not found on classpath; showing online fallback notice");
            engine.loadContent(onlineFallbackHtml());
            return;
        }
        lastInternalLocation = url;
        engine.load(lastInternalLocation);
    }

    /**
     * Guide language for the current locale: the bundled English or German tree, or a language
     * translated locally into the config directory by {@code GuideTranslationGenerator}.
     */
    private String resolveGuideLanguage() {
        try {
            Locale locale = LanguageManager.getInstance().getCurrentLocale();
            return GuideLocationResolver.resolveLanguage(locale, configDirectory());
        } catch (RuntimeException e) {
            return "en";
        }
    }

    private static Path configDirectory() {
        try {
            return KorTTYApplication.getConfigDirectory();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Applies the dark guide stylesheet matching the bundled MkDocs site palette. */
    static void applyGuideStylesheet(Scene scene) {
        URL stylesheet = GuideViewer.class.getResource("/styles/guide-ask.css");
        if (stylesheet != null) {
            scene.getStylesheets().add(stylesheet.toExternalForm());
        } else {
            logger.warn("Guide stylesheet /styles/guide-ask.css not found on classpath");
        }
    }

    /** True when the AI docs search may be offered: AI features are enabled in the settings. */
    private boolean isAiSearchAvailable() {
        try {
            GlobalSettings settings = app != null ? settings() : null;
            return settings != null && settings.isAiFeaturesEnabled();
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** Shows or hides the "ask the manual" AI side panel next to the guide. */
    private void toggleAskPanel(boolean show) {
        if (askPanel == null) {
            return;
        }
        if (show) {
            if (!splitPane.getItems().contains(askPanel)) {
                splitPane.getItems().add(askPanel);
                splitPane.setDividerPositions(0.66);
            }
            askPanel.focusQuestionField();
        } else {
            splitPane.getItems().remove(askPanel);
        }
    }

    /**
     * Navigates the main guide WebView to a documentation location ({@code page.html#anchor})
     * quoted verbatim from the bundled search index. Same-page anchor jumps go through
     * {@code window.location.hash} because a plain {@code load()} with only a fragment change
     * does not re-scroll reliably in WebKit.
     */
    private void navigateToLocation(String location) {
        if (disposed || location == null || location.isBlank()) {
            return;
        }
        String path = location;
        String anchor = null;
        int hash = location.indexOf('#');
        if (hash >= 0) {
            path = location.substring(0, hash);
            anchor = location.substring(hash + 1);
        }
        String pageUrl = GuideLocationResolver.pageUrl(resolveGuideLanguage(), path,
            configDirectory());
        if (pageUrl == null) {
            logger.warn("Guide citation points to a missing page: {}", location);
            return;
        }
        WebEngine engine = webView.getEngine();
        String target = anchor != null && !anchor.isBlank() ? pageUrl + "#" + anchor : pageUrl;
        String current = engine.getLocation();
        if (current != null && current.startsWith(pageUrl)) {
            String safeAnchor = anchor != null ? anchor.replaceAll("[^A-Za-z0-9._-]", "") : "";
            try {
                engine.executeScript("window.location.hash='" + safeAnchor + "'");
            } catch (RuntimeException e) {
                logger.debug("Guide anchor navigation failed for {}", location, e);
            }
        } else {
            engine.load(target);
        }
        lastInternalLocation = target;
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
                return;
            }
            // A locally translated guide is built page by page and its language switcher links
            // into a sibling tree that is not there, so links into missing pages are routine.
            // Redirect them to the bundled page instead of showing an empty window.
            String fallback =
                GuideLocationResolver.fallbackForMissingGeneratedPage(newLoc, configDirectory());
            if (fallback != null) {
                Platform.runLater(() -> {
                    if (!disposed) {
                        engine.getLoadWorker().cancel();
                        engine.load(fallback);
                    }
                });
            } else {
                lastInternalLocation = newLoc;
                trackGuidePageView(newLoc);
            }
        });
    }

    private String lastTrackedGuidePage;

    /**
     * Counts internal guide page views. The raw location is a {@code jar:file:/...} URL
     * containing the install path (PII) — only the bare page filename is sent.
     */
    private void trackGuidePageView(String location) {
        String page = sanitizeGuidePage(location);
        if (page == null || page.equals(lastTrackedGuidePage)) {
            return;
        }
        lastTrackedGuidePage = page;
        de.kortty.telemetry.Telemetry.track(
            de.kortty.telemetry.TelemetryEvents.GUIDE_PAGE_VIEWED, java.util.Map.of("page", page));
    }

    /** Reduces a full location URL to just the page filename, dropping the path, query, and fragment. */
    static String sanitizeGuidePage(String location) {
        if (location == null || location.isBlank()) {
            return null;
        }
        String stripped = location;
        int fragment = stripped.indexOf('#');
        if (fragment >= 0) {
            stripped = stripped.substring(0, fragment);
        }
        int query = stripped.indexOf('?');
        if (query >= 0) {
            stripped = stripped.substring(0, query);
        }
        int lastSlash = stripped.lastIndexOf('/');
        String page = lastSlash >= 0 ? stripped.substring(lastSlash + 1) : stripped;
        return page.isBlank() ? null : page;
    }

    /** True if the location points outside the bundled site (http/https/mailto/ftp) and should open externally. */
    private static boolean isExternal(String location) {
        String lower = location.toLowerCase(Locale.ROOT);
        return lower.startsWith("http://") || lower.startsWith("https://")
            || lower.startsWith("mailto:") || lower.startsWith("ftp://") || lower.startsWith("ftps://");
    }

    /** Opens {@code url} in the system browser via the app's {@code HostServices}. */
    private void openExternal(String url) {
        try {
            app.getHostServices().showDocument(url);
        } catch (Exception e) {
            logger.warn("Could not open external link from guide: {}", url, e);
        }
    }

    /** Sets the window icon from the bundled app icon; failures are ignored. */
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

    /** Returns a minimal offline notice page linking to the online guide, shown when the bundled site is missing. */
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

    /** Restores the saved window position/size, or centers on screen when the stored geometry is missing, too small, or off-screen. */
    private void restoreGeometry() {
        try {
            WindowGeometry stored = settings() != null ? settings().getGuideViewerGeometry() : null;
            // Shares the dialogs' off-screen recovery: a window left on a monitor that is gone,
            // or dragged so far out that no edge is left to grab, comes back into reach.
            WindowGeometry geometry = DialogGeometrySupport.sanitize(
                stored, DialogGeometrySupport.visualScreenBounds());
            if (geometry == null) {
                stage.centerOnScreen();
                return;
            }
            stage.setWidth(geometry.getWidth());
            stage.setHeight(geometry.getHeight());
            stage.setX(geometry.getX());
            stage.setY(geometry.getY());
            stage.setMaximized(stored.isMaximized());
        } catch (Exception e) {
            logger.debug("Could not restore guide viewer geometry", e);
        }
    }

    /** Installs debounced listeners (once, on first show) that persist the window geometry on move/resize/maximize. */
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

    /** Persists the current window position, size and maximized state to the global settings. */
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

    /** Returns the current {@link GlobalSettings}, or {@code null} if the settings manager is unavailable. */
    private GlobalSettings settings() {
        return app.getGlobalSettingsManager() != null ? app.getGlobalSettingsManager().getSettings() : null;
    }

    /**
     * Tears down the viewer on the FX thread: flushes any pending geometry save,
     * stops the WebView, and clears the singleton. Idempotent and thread-safe.
     */
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
        if (askPanel != null) {
            askPanel.dispose();
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
