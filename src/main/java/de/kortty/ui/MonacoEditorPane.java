package de.kortty.ui;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.IntegerProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.concurrent.Worker;
import javafx.geometry.Bounds;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.IndexRange;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.StackPane;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import javafx.util.Duration;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.ref.WeakReference;
import java.net.URL;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * JavaFX wrapper around a bundled Monaco editor running in a WebView.
 * Keeps a Java-side text/selection mirror so existing UI code can read editor
 * state synchronously while WebView work is executed on the JavaFX thread.
 */
public class MonacoEditorPane extends StackPane {

    private static final Logger logger = LoggerFactory.getLogger(MonacoEditorPane.class);
    private static final Gson GSON = new Gson();
    // Boot-ready budget = HOST_READY_RETRY_COUNT * HOST_READY_RETRY_DELAY. This must comfortably
    // exceed the time the WebView needs to parse + execute the bundled Monaco script and define
    // window.korttyMonaco. In the notarized/packaged app a cold WebView parses the (now minified)
    // bundle noticeably slower than under `./gradlew run`; the previous 5 s budget timed out before
    // korttyMonaco was defined, leaving the editor permanently empty (no caret/typing/paste). The
    // poll is a cheap one-line executeScript, so a generous ~20 s budget costs nothing on success
    // and only delays the give-up log on a genuine failure.
    private static final int HOST_READY_RETRY_COUNT = 800;
    private static final Duration HOST_READY_RETRY_DELAY = Duration.millis(25);
    private static final double UNKNOWN_CARET_X = -1000000000.0;

    private final WebView webView = new WebView();
    private final String editorId = "kortty-editor-" + UUID.randomUUID();
    private final StringProperty text = new SimpleStringProperty("");
    private final ObjectProperty<IndexRange> selection = new SimpleObjectProperty<>(new IndexRange(0, 0));
    private final IntegerProperty caretPosition = new SimpleIntegerProperty(0);
    private final IntegerProperty caretColumn = new SimpleIntegerProperty(1);
    private final DoubleProperty caretVisualX = new SimpleDoubleProperty(Double.NaN);
    private final BooleanProperty ready = new SimpleBooleanProperty(false);
    private final BooleanProperty canUndo = new SimpleBooleanProperty(false);
    private final BooleanProperty canRedo = new SimpleBooleanProperty(false);
    private final IntegerProperty rulerColumn = new SimpleIntegerProperty(0);
    private final DoubleProperty editorContentLeft = new SimpleDoubleProperty(0);
    private final DoubleProperty editorCharacterWidth = new SimpleDoubleProperty(8);
    private final DoubleProperty editorScrollLeft = new SimpleDoubleProperty(0);
    private final StringBuilder pendingScript = new StringBuilder();
    private final UndoManager undoManager = new UndoManager();
    // JavaFX WebEngine binds objects passed to JSObject.setMember via WEAK references, so the bridge
    // must be kept strongly reachable for the life of the WebView. Otherwise the JVM (notably the
    // bundled JDK 25 GC) can collect it, and the next JS->Java up-call crashes natively inside
    // jni_GetMethodID (twkExecuteScript). See WebEngine Javadoc and the AI-skill editor crash.
    private final Bridge javaBridge = new Bridge(this);

    private ContextMenu contextMenu;
    private boolean editable = true;
    private boolean internalTextUpdate;
    private boolean disposed;
    private boolean loadRequested;
    private PauseTransition pendingBootRetry;
    private String language = "plaintext";
    private String fontFamily = "Monospaced";
    private int fontSize = 14;
    private String foregroundColor = "#d4d4d4";
    private String backgroundColor = "#1e1e1e";
    private String cursorStyle = "BLOCK";
    private String cursorColor = "#ff0000";
    private boolean wrapText;
    private boolean lineNumbers = true;
    private Consumer<String> workerReadyHandler;
    private Consumer<String> workerFailureHandler;

    public MonacoEditorPane() {
        this(true);
    }

    /**
     * @param autoLoad when {@code false}, the WebView page (and with it the JS&rarr;Java bridge and
     *                 Monaco web workers) is not loaded until {@link #activate()} is called. This lets
     *                 callers defer the costly, native-heavy WebView boot until the editor is actually
     *                 shown, instead of paying it (and arming the native WebKit path) on construction.
     */
    public MonacoEditorPane(boolean autoLoad) {
        getStyleClass().add("monaco-editor-pane");
        webView.setContextMenuEnabled(false);
        webView.getEngine().setJavaScriptEnabled(true);
        getChildren().add(webView);

        text.addListener((obs, oldValue, newValue) -> {
            if (!internalTextUpdate && ready.get()) {
                executeScript("window.korttyMonaco.setValue(" + jsString(newValue) + ");");
            }
        });

        installInputGuards();
        installContextMenuHandling();
        if (autoLoad) {
            activate();
        }
    }

    /**
     * Loads the WebView page on first call. Idempotent, and a no-op after {@link #dispose()}.
     * The editor boots with whatever text the Java-side mirror currently holds.
     */
    public void activate() {
        if (disposed || loadRequested) {
            return;
        }
        loadRequested = true;
        loadEditor();
    }

    public StringProperty textProperty() {
        return text;
    }

    public ObjectProperty<IndexRange> selectionProperty() {
        return selection;
    }

    public IntegerProperty caretPositionProperty() {
        return caretPosition;
    }

    public IntegerProperty caretColumnProperty() {
        return caretColumn;
    }

    public DoubleProperty caretVisualXProperty() {
        return caretVisualX;
    }

    public BooleanProperty readyProperty() {
        return ready;
    }

    public IntegerProperty rulerColumnProperty() {
        return rulerColumn;
    }

    public DoubleProperty editorContentLeftProperty() {
        return editorContentLeft;
    }

    public DoubleProperty editorCharacterWidthProperty() {
        return editorCharacterWidth;
    }

    public DoubleProperty editorScrollLeftProperty() {
        return editorScrollLeft;
    }

    public boolean isReady() {
        return ready.get();
    }

    public String getText() {
        return text.get();
    }

    public void setText(String value) {
        replaceText(value);
    }

    public void replaceText(String value) {
        String safeValue = value != null ? value : "";
        updateTextMirror(safeValue);
        if (ready.get()) {
            executeScript("window.korttyMonaco.setValue(" + jsString(safeValue) + ");");
        }
    }

    public void replaceText(int start, int end, String replacement) {
        int safeStart = clamp(start, 0, getText().length());
        int safeEnd = clamp(end, safeStart, getText().length());
        String safeReplacement = replacement != null ? replacement : "";
        String current = getText();
        updateTextMirror(current.substring(0, safeStart) + safeReplacement + current.substring(safeEnd));
        if (ready.get()) {
            executeScript("window.korttyMonaco.replaceRange("
                + safeStart + "," + safeEnd + "," + jsString(safeReplacement) + ");");
        }
    }

    public void replaceSelection(String replacement) {
        IndexRange range = getSelection();
        replaceText(range.getStart(), range.getEnd(), replacement);
    }

    public void appendText(String value) {
        replaceText(getText().length(), getText().length(), value);
    }

    public void insertText(int position, String value) {
        replaceText(position, position, value);
    }

    public void clear() {
        replaceText("");
    }

    public int getLength() {
        return getText().length();
    }

    public String getSelectedText() {
        IndexRange range = getSelection();
        String value = getText();
        if (range == null || range.getLength() <= 0) {
            return "";
        }
        int start = clamp(range.getStart(), 0, value.length());
        int end = clamp(range.getEnd(), start, value.length());
        return value.substring(start, end);
    }

    public IndexRange getSelection() {
        IndexRange range = selection.get();
        return range != null ? range : new IndexRange(getCaretPosition(), getCaretPosition());
    }

    public void selectRange(int anchor, int caret) {
        int safeAnchor = clamp(anchor, 0, getText().length());
        int safeCaret = clamp(caret, 0, getText().length());
        selection.set(new IndexRange(Math.min(safeAnchor, safeCaret), Math.max(safeAnchor, safeCaret)));
        caretPosition.set(safeCaret);
        caretColumn.set(caretColumnForOffset(getText(), safeCaret));
        setCaretVisualX(Double.NaN);
        runWhenReady("window.korttyMonaco.selectRange(" + safeAnchor + "," + safeCaret + ");");
    }

    public int getCaretPosition() {
        return caretPosition.get();
    }

    public int getCaretColumn() {
        return caretColumn.get();
    }

    public double getCaretVisualX() {
        return caretVisualX.get();
    }

    public void moveTo(int offset) {
        selectRange(offset, offset);
    }

    public void requestFollowCaret() {
        runWhenReady("window.korttyMonaco.revealCaret();");
    }

    public Optional<Bounds> getCaretBounds() {
        Bounds localBounds = webView.getBoundsInLocal();
        return Optional.of(webView.localToScreen(localBounds));
    }

    public void setEditable(boolean editable) {
        this.editable = editable;
        executeWhenReady("window.korttyMonaco.setReadOnly(" + (!editable) + ");");
    }

    public boolean isEditable() {
        return editable;
    }

    public void setWrapText(boolean wrapText) {
        this.wrapText = wrapText;
        executeWhenReady("window.korttyMonaco.setWrapText(" + wrapText + ");");
    }

    public boolean isWrapText() {
        return wrapText;
    }

    public void setLineNumbers(boolean lineNumbers) {
        this.lineNumbers = lineNumbers;
        executeWhenReady("window.korttyMonaco.setLineNumbers(" + lineNumbers + ");");
    }

    public boolean isLineNumbers() {
        return lineNumbers;
    }

    public void setRulerColumn(int column) {
        int safeColumn = Math.max(0, column);
        rulerColumn.set(safeColumn);
        runWhenReady("window.korttyMonaco.setRulerColumn(" + safeColumn + ");");
    }

    public int getRulerColumn() {
        return rulerColumn.get();
    }

    public void setLanguage(String language) {
        this.language = MonacoLanguageSupport.toMonacoLanguage(language);
        executeWhenReady("window.korttyMonaco.setLanguage(" + jsString(this.language) + ");");
    }

    public String getLanguage() {
        return language;
    }

    public void setFont(String fontFamily, int fontSize) {
        this.fontFamily = fontFamily != null && !fontFamily.isBlank() ? fontFamily : "Monospaced";
        this.fontSize = Math.max(8, fontSize);
        executeWhenReady("window.korttyMonaco.setFont(" + jsString(this.fontFamily) + "," + this.fontSize + ");");
    }

    public void setThemeColors(String foregroundColor, String backgroundColor) {
        this.foregroundColor = normalizeColor(foregroundColor, "#d4d4d4");
        this.backgroundColor = normalizeColor(backgroundColor, "#1e1e1e");
        executeWhenReady("window.korttyMonaco.setTheme(" + themeJson() + ");");
    }

    public void setCursorStyle(String cursorStyle, String cursorColor) {
        this.cursorStyle = cursorStyle != null ? cursorStyle : "BLOCK";
        this.cursorColor = normalizeColor(cursorColor, "#ff0000");
        executeWhenReady("window.korttyMonaco.setCursor(" + jsString(this.cursorStyle) + "," + jsString(this.cursorColor) + ");");
    }

    public void setContextMenu(ContextMenu contextMenu) {
        this.contextMenu = contextMenu;
    }

    public void cut() {
        runWhenReady("window.korttyMonaco.cut();");
    }

    public void copy() {
        runWhenReady("window.korttyMonaco.copy();");
    }

    public void paste() {
        runWhenReady("window.korttyMonaco.paste();");
    }

    public void selectAll() {
        selectRange(0, getText().length());
    }

    public void undo() {
        runWhenReady("window.korttyMonaco.undo();");
    }

    public void redo() {
        runWhenReady("window.korttyMonaco.redo();");
    }

    public boolean isUndoAvailable() {
        return canUndo.get();
    }

    public boolean isRedoAvailable() {
        return canRedo.get();
    }

    public UndoManager getUndoManager() {
        return undoManager;
    }

    public void syncFromEditor() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::syncFromEditor);
            return;
        }
        if (!ready.get()) {
            return;
        }
        Object snapshot = executeScript("window.korttyMonaco.snapshot();");
        if (!(snapshot instanceof String json) || json.isBlank()) {
            return;
        }
        try {
            JsonObject object = JsonParser.parseString(json).getAsJsonObject();
            updateTextMirror(object.has("value") ? object.get("value").getAsString() : "");
            updateSelectionMirror(
                object.has("selectionStart") ? object.get("selectionStart").getAsInt() : 0,
                object.has("selectionEnd") ? object.get("selectionEnd").getAsInt() : 0,
                object.has("caret") ? object.get("caret").getAsInt() : 0,
                object.has("caretColumn") ? object.get("caretColumn").getAsInt() : 1,
                object.has("caretX") ? object.get("caretX").getAsDouble() : UNKNOWN_CARET_X);
            canUndo.set(object.has("canUndo") && object.get("canUndo").getAsBoolean());
            canRedo.set(object.has("canRedo") && object.get("canRedo").getAsBoolean());
        } catch (RuntimeException e) {
            logger.warn("Could not synchronize Monaco editor state", e);
        }
    }

    public void forgetHistory() {
        runWhenReady("window.korttyMonaco.forgetHistory();");
        canUndo.set(false);
        canRedo.set(false);
    }

    public void setWorkerFailureHandler(Consumer<String> workerFailureHandler) {
        this.workerFailureHandler = workerFailureHandler;
    }

    public void setWorkerReadyHandler(Consumer<String> workerReadyHandler) {
        this.workerReadyHandler = workerReadyHandler;
    }

    public void dispose() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::dispose);
            return;
        }
        if (disposed) {
            return;
        }
        // Mark disposed FIRST so any in-flight boot retry / load-worker callback / queued
        // executeScript becomes a no-op and cannot touch a tearing-down WebKit page.
        disposed = true;
        if (pendingBootRetry != null) {
            pendingBootRetry.stop();
            pendingBootRetry = null;
        }
        if (!loadRequested) {
            return;
        }
        WebEngine engine = webView.getEngine();
        try {
            if (ready.get()) {
                engine.executeScript("window.korttyMonaco.dispose();");
            }
            // Detach the Java bridge from the page so no further JS->Java up-calls are possible.
            Object window = engine.executeScript("window");
            if (window instanceof JSObject jsWindow) {
                jsWindow.removeMember("javaBridge");
            }
        } catch (RuntimeException e) {
            logger.debug("Monaco editor dispose cleanup failed", e);
        }
        // Drop the page (and its web workers) so the native WebKit context is released promptly.
        engine.loadContent("");
    }

    private void loadEditor() {
        URL resource = MonacoEditorPane.class.getResource("/monaco/monaco-editor.html");
        if (resource == null) {
            logger.error("Missing bundled Monaco editor resource");
            return;
        }
        WebEngine engine = webView.getEngine();
        engine.getLoadWorker().stateProperty().addListener((obs, oldState, newState) -> {
            if (disposed) {
                return;
            }
            if (newState == Worker.State.SUCCEEDED) {
                // Defer off the load-worker callback: this listener fires while WebKit is still inside
                // its native load-finished dispatch (fwkFireLoadEvent), so calling executeScript here
                // re-enters WebKit re-entrantly and intermittently crashes in JNI get_method_id on
                // macOS. runLater lets WebKit unwind first, then we wire up the bridge on a clean stack.
                Platform.runLater(() -> {
                    if (disposed) {
                        return;
                    }
                    JSObject window = (JSObject) engine.executeScript("window");
                    window.setMember("javaBridge", javaBridge);
                    bootWhenHostReady(HOST_READY_RETRY_COUNT);
                });
            } else if (newState == Worker.State.FAILED) {
                Throwable failure = engine.getLoadWorker().getException();
                logger.error("Could not load Monaco editor WebView", failure);
            }
        });
        engine.load(resource.toExternalForm());
    }

    private void bootWhenHostReady(int remainingAttempts) {
        if (disposed) {
            return;
        }
        Object hostReady = executeScript(
            "typeof window.korttyMonaco === 'object' && typeof window.korttyMonaco.boot === 'function'"
        );
        if (Boolean.TRUE.equals(hostReady)) {
            executeScript("window.korttyMonaco.boot(" + initialConfigJson() + ");");
            return;
        }
        if (remainingAttempts <= 0) {
            Object startupErrors = executeScript("JSON.stringify(window.korttyStartupErrors || [])");
            logger.error("Monaco host script did not become ready. Startup errors: {}", startupErrors);
            return;
        }
        PauseTransition retry = new PauseTransition(HOST_READY_RETRY_DELAY);
        retry.setOnFinished(event -> bootWhenHostReady(remainingAttempts - 1));
        pendingBootRetry = retry;
        retry.play();
    }

    private void installInputGuards() {
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.TAB) {
                webView.requestFocus();
            }
        });
    }

    private void installContextMenuHandling() {
        webView.addEventHandler(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() == MouseButton.SECONDARY && contextMenu != null) {
                contextMenu.show(webView, event.getScreenX(), event.getScreenY());
                event.consume();
            } else if (contextMenu != null) {
                contextMenu.hide();
            }
        });
    }

    private String initialConfigJson() {
        JsonObject json = new JsonObject();
        json.addProperty("id", editorId);
        json.addProperty("value", getText());
        json.addProperty("language", language);
        json.addProperty("readOnly", !editable);
        json.addProperty("wrapText", wrapText);
        json.addProperty("lineNumbers", lineNumbers);
        json.addProperty("fontFamily", fontFamily);
        json.addProperty("fontSize", fontSize);
        json.add("theme", GSON.fromJson(themeJson(), JsonObject.class));
        json.addProperty("cursorStyle", cursorStyle);
        json.addProperty("cursorColor", cursorColor);
        json.addProperty("rulerColumn", rulerColumn.get());
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
            logger.warn("Monaco script failed: {}", script, e);
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

    private void updateTextMirror(String value) {
        internalTextUpdate = true;
        try {
            text.set(value != null ? value : "");
        } finally {
            internalTextUpdate = false;
        }
    }

    private void updateSelectionMirror(int start, int end, int caret) {
        int length = getText().length();
        int safeStart = clamp(start, 0, length);
        int safeEnd = clamp(end, safeStart, length);
        int safeCaret = clamp(caret, 0, length);
        selection.set(new IndexRange(safeStart, safeEnd));
        caretPosition.set(safeCaret);
        caretColumn.set(caretColumnForOffset(getText(), safeCaret));
        setCaretVisualX(Double.NaN);
    }

    private void updateSelectionMirror(double start, double end, double caret, double column, double visualX) {
        updateSelectionMirror(
            (int) Math.round(start),
            (int) Math.round(end),
            (int) Math.round(caret),
            Math.max(1, (int) Math.round(column)),
            visualX);
    }

    private void updateSelectionMirror(int start, int end, int caret, int column, double visualX) {
        int length = getText().length();
        int safeStart = clamp(start, 0, length);
        int safeEnd = clamp(end, safeStart, length);
        int safeCaret = clamp(caret, 0, length);
        selection.set(new IndexRange(safeStart, safeEnd));
        caretPosition.set(safeCaret);
        caretColumn.set(Math.max(1, column));
        setCaretVisualX(visualX);
    }

    private void setCaretVisualX(double visualX) {
        double safeVisualX = visualX > UNKNOWN_CARET_X / 2 && Double.isFinite(visualX) ? visualX : Double.NaN;
        if (Double.compare(caretVisualX.get(), safeVisualX) != 0) {
            caretVisualX.set(safeVisualX);
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int caretColumnForOffset(String text, int caretOffset) {
        String value = text != null ? text : "";
        int safeOffset = Math.max(0, Math.min(caretOffset, value.length()));
        int previousLineBreak = value.lastIndexOf('\n', Math.max(0, safeOffset - 1));
        return Math.max(1, safeOffset - previousLineBreak);
    }

    private static String jsString(String value) {
        return GSON.toJson(Objects.requireNonNullElse(value, ""));
    }

    private static String normalizeColor(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    public final class UndoManager {
        public void forgetHistory() {
            MonacoEditorPane.this.forgetHistory();
        }
    }

    /**
     * Static and holding the pane only via a {@link WeakReference} so the native WebKit page never
     * pins the enclosing pane (and the whole dialog graph) alive; once the pane is otherwise
     * unreachable, up-calls degrade to no-ops. Kept {@code public} for JavaFX's reflective JS&rarr;Java
     * dispatch. The pane retains a strong reference to this instance (see {@code javaBridge}), which
     * is what keeps it from being GC'd while the WebView is live.
     */
    public static final class Bridge {
        private final WeakReference<MonacoEditorPane> paneRef;

        Bridge(MonacoEditorPane pane) {
            this.paneRef = new WeakReference<>(pane);
        }

        public void onReady() {
            MonacoEditorPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            Platform.runLater(() -> {
                pane.ready.set(true);
                pane.flushPendingScripts();
            });
        }

        public void onTextChanged(String value, boolean undoAvailable, boolean redoAvailable) {
            MonacoEditorPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            Platform.runLater(() -> {
                pane.updateTextMirror(value);
                pane.canUndo.set(undoAvailable);
                pane.canRedo.set(redoAvailable);
            });
        }

        public void onSelectionChanged(double start, double end, double caret, double column, double visualX) {
            MonacoEditorPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            Platform.runLater(() -> pane.updateSelectionMirror(start, end, caret, column, visualX));
        }

        public void onLayoutChanged(double contentLeft, double characterWidth, double scrollLeft) {
            MonacoEditorPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            Platform.runLater(() -> {
                pane.editorContentLeft.set(Math.max(0, contentLeft));
                pane.editorCharacterWidth.set(Math.max(1, characterWidth));
                pane.editorScrollLeft.set(Math.max(0, scrollLeft));
            });
        }

        public void onWorkerReady(String label) {
            MonacoEditorPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            logger.debug("Monaco worker ready: {}", label);
            Consumer<String> handler = pane.workerReadyHandler;
            if (handler != null) {
                Platform.runLater(() -> handler.accept(label));
            }
        }

        public void onWorkerFailed(String label, String message) {
            MonacoEditorPane pane = paneRef.get();
            if (pane == null) {
                return;
            }
            String detail = label + ": " + message;
            logger.error("Monaco worker failed: {}", detail);
            Consumer<String> handler = pane.workerFailureHandler;
            if (handler != null) {
                Platform.runLater(() -> handler.accept(detail));
            }
        }
    }
}
