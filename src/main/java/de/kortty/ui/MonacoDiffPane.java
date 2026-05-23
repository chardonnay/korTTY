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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.net.URL;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * JavaFX wrapper around Monaco's read-only side-by-side diff editor.
 */
public class MonacoDiffPane extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(MonacoDiffPane.class);
    private static final Gson GSON = new Gson();
    private static final int HOST_READY_RETRY_COUNT = 200;
    private static final Duration HOST_READY_RETRY_DELAY = Duration.millis(25);

    private final WebView webView = new WebView();
    private final String editorId = "kortty-diff-" + UUID.randomUUID();
    private final BooleanProperty ready = new SimpleBooleanProperty(false);
    private final StringBuilder pendingScript = new StringBuilder();

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

    public void setWorkerReadyHandler(Consumer<String> workerReadyHandler) {
        this.workerReadyHandler = workerReadyHandler;
    }

    public void setWorkerFailureHandler(Consumer<String> workerFailureHandler) {
        this.workerFailureHandler = workerFailureHandler;
    }

    public void dispose() {
        if (ready.get()) {
            executeScript("window.korttyMonacoDiff.dispose();");
        }
    }

    private void loadEditor() {
        URL resource = MonacoDiffPane.class.getResource("/monaco/monaco-diff-editor.html");
        if (resource == null) {
            logger.error("Missing bundled Monaco diff editor resource");
            return;
        }
        WebEngine engine = webView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (newState == Worker.State.SUCCEEDED) {
                installBridge(engine);
                bootWhenHostReady(HOST_READY_RETRY_COUNT);
            } else if (newState == Worker.State.FAILED) {
                Throwable failure = engine.getLoadWorker().getException();
                logger.error("Could not load Monaco diff editor WebView", failure);
            }
        });
        engine.load(resource.toExternalForm());
    }

    private void installBridge(WebEngine engine) {
        try {
            Object window = engine.executeScript("window");
            Method setMember = window.getClass().getMethod("setMember", String.class, Object.class);
            setMember.invoke(window, "javaBridge", new Bridge());
        } catch (ReflectiveOperationException | RuntimeException e) {
            logger.error("Could not install Monaco diff Java bridge", e);
        }
    }

    private void bootWhenHostReady(int remainingAttempts) {
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
            return;
        }
        PauseTransition retry = new PauseTransition(HOST_READY_RETRY_DELAY);
        retry.setOnFinished(event -> bootWhenHostReady(remainingAttempts - 1));
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

    public final class Bridge {
        public void onReady() {
            Platform.runLater(() -> {
                ready.set(true);
                flushPendingScripts();
            });
        }

        public void onWorkerReady(String label) {
            logger.debug("Monaco diff worker ready: {}", label);
            Consumer<String> handler = workerReadyHandler;
            if (handler != null) {
                Platform.runLater(() -> handler.accept(label));
            }
        }

        public void onWorkerFailed(String label, String message) {
            String detail = label + ": " + message;
            logger.error("Monaco diff worker failed: {}", detail);
            Consumer<String> handler = workerFailureHandler;
            if (handler != null) {
                Platform.runLater(() -> handler.accept(detail));
            }
        }
    }
}
