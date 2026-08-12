package de.kortty.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Worker;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * JavaFX wrapper around Monaco's read-only side-by-side diff editor.
 */
public class MonacoDiffPane extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(MonacoDiffPane.class);
    private static final Gson GSON = new Gson();
    // ~20 s boot-ready budget: a cold packaged-app WebView parses the bundled Monaco diff script
    // slower than under `./gradlew run`; the old 5 s budget could time out before the diff host
    // became ready, leaving an empty diff view. See MonacoEditorPane for the full rationale.
    private static final int HOST_READY_RETRY_COUNT = 800;
    private static final Duration HOST_READY_RETRY_DELAY = Duration.millis(25);

    private final WebView webView = new WebView();
    private final String editorId = "kortty-diff-" + UUID.randomUUID();
    // Strong reference required: JavaFX WebEngine holds setMember objects only via weak references,
    // so a GC'd bridge turns subsequent JS->Java up-calls into a native jni_GetMethodID crash.
    private final Bridge javaBridge = new Bridge(this);
    private final BooleanProperty ready = new SimpleBooleanProperty(false);
    private final StringBuilder pendingScript = new StringBuilder();
    private boolean disposed;
    private PauseTransition pendingBootRetry;

    private String originalText = "";
    private String modifiedText = "";
    private String originalLanguage = "plaintext";
    private String modifiedLanguage = "plaintext";
    private String fontFamily = "Monospaced";
    private int fontSize = 14;
    private String foregroundColor = "#d4d4d4";
    private String backgroundColor = "#1e1e1e";
    private Consumer<String> workerReadyHandler;
    private Consumer<String> workerFailureHandler;
    private Consumer<String> changeReasonRangesHandler;

    public MonacoDiffPane() {
        getStyleClass().add("monaco-diff-pane");
        webView.setContextMenuEnabled(false);
        webView.getEngine().setJavaScriptEnabled(true);
        getChildren().add(webView);
        loadEditor();
    }

    public BooleanProperty readyProperty() {
        return ready;
    }

    public boolean isReady() {
        return ready.get();
    }

    public void setComparison(
            String originalText,
            String modifiedText,
            String originalLanguage,
            String modifiedLanguage) {

        this.originalText = Objects.requireNonNullElse(originalText, "");
        this.modifiedText = Objects.requireNonNullElse(modifiedText, "");
        this.originalLanguage = MonacoLanguageSupport.toMonacoLanguage(originalLanguage);
        this.modifiedLanguage = MonacoLanguageSupport.toMonacoLanguage(modifiedLanguage);
        runWhenReady("window.korttyMonacoDiff.setValue("
                + jsString(this.originalText) + ","
                + jsString(this.modifiedText) + ","
                + jsString(this.originalLanguage) + ","
                + jsString(this.modifiedLanguage) + ");");
    }

    public void setFont(String fontFamily, int fontSize) {
        this.fontFamily = fontFamily != null && !fontFamily.isBlank() ? fontFamily : "Monospaced";
        this.fontSize = Math.max(8, fontSize);
        executeWhenReady("window.korttyMonacoDiff.setFont("
                + jsString(this.fontFamily) + "," + this.fontSize + ");");
    }

    public void setThemeColors(String foregroundColor, String backgroundColor) {
        this.foregroundColor = normalizeColor(foregroundColor, "#d4d4d4");
        this.backgroundColor = normalizeColor(backgroundColor, "#1e1e1e");
        executeWhenReady("window.korttyMonacoDiff.setTheme(" + themeJson() + ");");
    }

    /**
     * Adds hover annotations ("why did this change?") to the modified side. Expects a JSON array of
     * {@code { finding, anchor, reason }} objects. The change highlighting itself comes from Monaco's
     * own diff; these decorations only attach the reason on hover, anchored by the verbatim line.
     * Queued until the diff host is ready and always runs after the pending {@code setComparison}.
     */
    public void setChangeReasons(String reasonsJson) {
        runWhenReady("window.korttyMonacoDiff.setChangeReasons("
                + jsString(reasonsJson != null ? reasonsJson : "[]") + ");");
    }

    /**
     * Restricts the reason decorations to a single finding id, mutes the diff's own colouring of the
     * other changed blocks and scrolls to the finding's first place, so a staged rewrite can be
     * reviewed one finding at a time. A blank id restores every annotation and the full colouring.
     */
    public void setReasonFilter(String finding) {
        runWhenReady("window.korttyMonacoDiff.setReasonFilter("
                + jsString(finding != null ? finding : "") + ");");
    }

    public void setWorkerReadyHandler(Consumer<String> workerReadyHandler) {
        this.workerReadyHandler = workerReadyHandler;
    }

    /**
     * Receives the resolved line ranges (modified side) of the change reasons whenever the diff host
     * (re-)applies its hover decorations: a JSON array of {@code { idx, start, end }} objects, where
     * {@code idx} echoes the reason's index from {@link #setChangeReasons}. Cosmetic — used to label the
     * explanation cards with "Lines 23-40".
     */
    public void setChangeReasonRangesHandler(Consumer<String> changeReasonRangesHandler) {
        this.changeReasonRangesHandler = changeReasonRangesHandler;
    }

    public void setWorkerFailureHandler(Consumer<String> workerFailureHandler) {
        this.workerFailureHandler = workerFailureHandler;
    }

    public void dispose() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::dispose);
            return;
        }
        if (disposed) {
            return;
        }
        disposed = true;
        if (pendingBootRetry != null) {
            pendingBootRetry.stop();
            pendingBootRetry = null;
        }
        WebEngine engine = webView.getEngine();
        try {
            if (ready.get()) {
                engine.executeScript("window.korttyMonacoDiff.dispose();");
            }
        } catch (RuntimeException e) {
            logger.debug("Monaco diff dispose cleanup failed", e);
        }
        // Replacing the page drops the JS-side javaBridge member and the Monaco web workers.
        engine.loadContent("");
    }

    private void loadEditor() {
        // Load from a file: URL (resources extracted to a temp dir), NOT the jar: URL that
        // getResource() returns in the packaged app: a jar:-origin page's CSP blocks its relative
        // shared monaco-host.js/.css siblings, so the diff editor never boots. See MonacoResourceBundle.
        String pageUrl = MonacoResourceBundle.diffEditorHtmlUrl();
        if (pageUrl == null) {
            logger.error("Bundled Monaco diff editor resources could not be prepared");
            notifyHostUnavailable("monaco diff resources could not be extracted");
            return;
        }
        WebEngine engine = webView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (disposed) {
                return;
            }
            if (newState == Worker.State.SUCCEEDED) {
                // Defer off the load-worker callback to avoid re-entering WebKit while it is still
                // inside its native load-finished dispatch (crashes in JNI get_method_id on macOS).
                Platform.runLater(() -> {
                    if (disposed) {
                        return;
                    }
                    installBridge(engine);
                    bootWhenHostReady(HOST_READY_RETRY_COUNT);
                });
            } else if (newState == Worker.State.FAILED) {
                Throwable failure = engine.getLoadWorker().getException();
                logger.error("Could not load Monaco diff editor WebView", failure);
                notifyHostUnavailable("page load failed: " + failure);
            }
        });
        engine.load(pageUrl);
    }

    // Surface a boot/load failure instead of leaving a silently-empty WebView.
    private void notifyHostUnavailable(String detail) {
        Consumer<String> handler = workerFailureHandler;
        if (handler != null) {
            Platform.runLater(() -> handler.accept("host: " + detail));
        }
    }

    private void installBridge(WebEngine engine) {
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            window.setMember("javaBridge", javaBridge);
            logger.debug("Installed Monaco diff Java bridge");
        } catch (RuntimeException e) {
            logger.error("Could not install Monaco diff Java bridge", e);
        }
    }

    private void bootWhenHostReady(int remainingAttempts) {
        if (disposed) {
            return;
        }
        Object hostReady = executeScript(
            "typeof window.korttyMonacoDiff === 'object' && typeof window.korttyMonacoDiff.boot === 'function'"
        );
        if (Boolean.TRUE.equals(hostReady)) {
            executeScript("window.korttyMonacoDiff.boot(" + initialConfigJson() + ");");
            return;
        }
        if (remainingAttempts <= 0) {
            Object startupErrors = executeScript("JSON.stringify(window.korttyStartupErrors || [])");
            logger.error("Monaco diff host script did not become ready. Startup errors: {}", startupErrors);
            notifyHostUnavailable("diff host script not ready; startupErrors=" + startupErrors);
            return;
        }
        PauseTransition retry = new PauseTransition(HOST_READY_RETRY_DELAY);
        retry.setOnFinished(event -> bootWhenHostReady(remainingAttempts - 1));
        pendingBootRetry = retry;
        retry.play();
    }

    private String initialConfigJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", editorId);
        json.addProperty("originalValue", originalText);
        json.addProperty("modifiedValue", modifiedText);
        json.addProperty("originalLanguage", originalLanguage);
        json.addProperty("modifiedLanguage", modifiedLanguage);
        json.addProperty("fontFamily", fontFamily);
        json.addProperty("fontSize", fontSize);
        json.add("theme", GSON.fromJson(themeJson(), JsonObject.class));
        return GSON.toJson(json);
    }

    private String themeJson() {
        JsonObject json = new JsonObject();
        json.addProperty("foreground", foregroundColor);
        json.addProperty("background", backgroundColor);
        return GSON.toJson(json);
    }

    private void runWhenReady(String script) {
        if (ready.get()) {
            executeScript(script);
            return;
        }
        pendingScript.append(script).append('\n');
    }

    private void executeWhenReady(String script) {
        if (ready.get()) {
            executeScript(script);
        }
    }

    private Object executeScript(String script) {
        if (disposed) {
            return null;
        }
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> executeScript(script));
            return null;
        }
        try {
            return webView.getEngine().executeScript(script);
        } catch (RuntimeException e) {
            logger.warn("Monaco diff script failed: {}", script, e);
            return null;
        }
    }

    private void flushPendingScripts() {
        if (pendingScript.isEmpty()) {
            return;
        }
        String script = pendingScript.toString();
        pendingScript.setLength(0);
        executeScript(script);
    }

    private static String jsString(String value) {
        return GSON.toJson(Objects.requireNonNullElse(value, ""));
    }

    private static String normalizeColor(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    /**
     * Static, holding the pane only weakly, so the native WebKit page cannot pin the enclosing pane
     * (and its dialog) alive. Public for JavaFX's reflective JS&rarr;Java dispatch; kept strongly
     * reachable for the WebView's lifetime via the {@code javaBridge} field.
     */
    public static final class Bridge {
        private final WeakReference<MonacoDiffPane> paneRef;

        Bridge(MonacoDiffPane pane) {
            this.paneRef = new WeakReference<>(pane);
        }

        public void onReady() {
            MonacoDiffPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            Platform.runLater(() -> {
                pane.ready.set(true);
                pane.flushPendingScripts();
            });
        }

        public void onWorkerReady(String label) {
            MonacoDiffPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            logger.debug("Monaco diff worker ready: {}", label);
            Consumer<String> handler = pane.workerReadyHandler;
            if (handler != null) {
                Platform.runLater(() -> handler.accept(label));
            }
        }

        public void onChangeReasonRanges(String rangesJson) {
            MonacoDiffPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            Consumer<String> handler = pane.changeReasonRangesHandler;
            if (handler != null) {
                Platform.runLater(() -> handler.accept(rangesJson));
            }
        }

        public void onWorkerFailed(String label, String message) {
            MonacoDiffPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            String detail = label + ": " + message;
            logger.error("Monaco diff worker failed: {}", detail);
            Consumer<String> handler = pane.workerFailureHandler;
            if (handler != null) {
                Platform.runLater(() -> handler.accept(detail));
            }
        }
    }
}
