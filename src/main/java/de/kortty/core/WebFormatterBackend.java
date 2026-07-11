package de.kortty.core;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Lazy JavaFX WebView host for the browser builds of Prettier and sql-formatter.
 *
 * <p>All engine state is confined to the JavaFX application thread. Public formatting call sites
 * already run on background workers; those workers are serialized here and wait only for the same
 * bounded period used by process formatters. A failed or timed-out generation is discarded before
 * the next request so a broken Promise or WebEngine cannot poison later formatting requests.</p>
 */
final class WebFormatterBackend {

    private static final Logger logger = LoggerFactory.getLogger(WebFormatterBackend.class);

    private final ReentrantLock requestLock = new ReentrantLock(true);
    private final AtomicLong requestSequence = new AtomicLong();
    private final Map<String, CompletableFuture<String>> requests = new ConcurrentHashMap<>();

    // JavaFX-thread-confined state.
    private WebView webView;
    private WebEngine engine;
    private Bridge bridge;
    private JSObject formatterApi;
    private LoadState loadState = LoadState.EMPTY;
    private volatile long engineGeneration;
    private Request waitingForLoad;

    private enum LoadState {
        EMPTY,
        LOADING,
        READY,
        BROKEN
    }

    private record Request(
        String id,
        String formatterId,
        String parser,
        String source,
        Integer lineWidth,
        CompletableFuture<String> result) {
    }

    private static final class Holder {
        private static final WebFormatterBackend INSTANCE = new WebFormatterBackend();
    }

    private WebFormatterBackend() {
    }

    static boolean isBundledAvailable() {
        return WebFormatterResourceBundle.isBundled();
    }

    static String format(
        String formatterId,
        String parser,
        String source,
        Integer lineWidth,
        int timeoutSeconds
    ) throws CodeFormatterService.FormatterException {
        return Holder.INSTANCE.formatRequest(formatterId, parser, source, lineWidth, timeoutSeconds);
    }

    private String formatRequest(
        String formatterId,
        String parser,
        String source,
        Integer lineWidth,
        int timeoutSeconds
    ) throws CodeFormatterService.FormatterException {
        if (Platform.isFxApplicationThread()) {
            throw new CodeFormatterService.FormatterException(
                "Web formatter must be called from a background thread");
        }

        long timeoutNanos = TimeUnit.SECONDS.toNanos(timeoutSeconds);
        long deadline = System.nanoTime() + timeoutNanos;
        boolean locked = false;
        Request request = null;
        try {
            locked = requestLock.tryLock(timeoutNanos, TimeUnit.NANOSECONDS);
            if (!locked) {
                throw timeoutFailure(timeoutSeconds);
            }

            long remaining = remainingNanos(deadline);
            if (remaining <= 0) {
                throw timeoutFailure(timeoutSeconds);
            }

            String hostUrl;
            try {
                hostUrl = WebFormatterResourceBundle.hostUrl();
            } catch (IOException | RuntimeException e) {
                throw new CodeFormatterService.FormatterException(
                    "Could not prepare bundled web formatter resources", e);
            }

            CompletableFuture<String> result = new CompletableFuture<>();
            request = new Request(
                Long.toString(requestSequence.incrementAndGet()),
                formatterId,
                parser,
                source,
                lineWidth,
                result);
            requests.put(request.id(), result);

            Request submittedRequest = request;
            try {
                Platform.runLater(() -> submitOnFx(submittedRequest, hostUrl));
            } catch (IllegalStateException e) {
                throw new CodeFormatterService.FormatterException(
                    "JavaFX toolkit is not available for bundled web formatting", e);
            }

            remaining = remainingNanos(deadline);
            if (remaining <= 0) {
                result.completeExceptionally(timeoutFailure(timeoutSeconds));
                resetAfterRequestFailure();
                throw timeoutFailure(timeoutSeconds);
            }
            try {
                return result.get(remaining, TimeUnit.NANOSECONDS);
            } catch (TimeoutException e) {
                result.completeExceptionally(e);
                resetAfterRequestFailure();
                throw timeoutFailure(timeoutSeconds);
            } catch (ExecutionException e) {
                resetAfterRequestFailure();
                Throwable cause = e.getCause();
                if (cause instanceof CodeFormatterService.FormatterException formatterFailure) {
                    throw formatterFailure;
                }
                throw new CodeFormatterService.FormatterException("Bundled web formatter failed", cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            resetAfterRequestFailure();
            throw new CodeFormatterService.FormatterException("Formatter was interrupted", e);
        } finally {
            if (request != null) {
                requests.remove(request.id(), request.result());
            }
            if (locked) {
                requestLock.unlock();
            }
        }
    }

    private void submitOnFx(Request request, String hostUrl) {
        if (request.result().isDone()) {
            return;
        }
        if (loadState == LoadState.BROKEN) {
            resetEngineOnFx(engineGeneration);
        }
        if (loadState == LoadState.READY) {
            dispatchOnFx(request);
            return;
        }
        if (loadState == LoadState.LOADING) {
            // Calls are serialized; seeing a second request here means the prior caller timed out.
            failOnFx(request, "Previous formatter engine load is still pending", null);
            return;
        }

        waitingForLoad = request;
        long generation = ++engineGeneration;
        loadState = LoadState.LOADING;
        try {
            webView = new WebView();
            webView.setContextMenuEnabled(false);
            engine = webView.getEngine();
            engine.setJavaScriptEnabled(true);
            engine.getLoadWorker().stateProperty().addListener((observable, oldState, newState) -> {
                if (generation != engineGeneration || loadState != LoadState.LOADING) {
                    return;
                }
                if (newState == Worker.State.SUCCEEDED) {
                    // Let WebKit unwind its native load callback before entering it again via JSObject.
                    Platform.runLater(() -> finishLoadOnFx(generation));
                } else if (newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                    Throwable failure = engine != null ? engine.getLoadWorker().getException() : null;
                    failWaitingLoadOnFx(generation, "Formatter host page could not be loaded", failure);
                }
            });
            engine.load(hostUrl);
        } catch (RuntimeException e) {
            failWaitingLoadOnFx(generation, "Formatter WebView could not be created", e);
        }
    }

    private void finishLoadOnFx(long generation) {
        if (generation != engineGeneration || loadState != LoadState.LOADING || engine == null) {
            return;
        }
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            bridge = new Bridge(this, generation);
            window.setMember("javaBridge", bridge);
            Object api = window.getMember("korttyFormatter");
            if (!(api instanceof JSObject jsApi)) {
                throw new IllegalStateException("window.korttyFormatter is not available");
            }
            formatterApi = jsApi;
            loadState = LoadState.READY;
            Request request = waitingForLoad;
            waitingForLoad = null;
            if (request != null) {
                dispatchOnFx(request);
            }
        } catch (RuntimeException e) {
            failWaitingLoadOnFx(generation, "Formatter host page did not initialize", e);
        }
    }

    private void dispatchOnFx(Request request) {
        if (request.result().isDone()) {
            return;
        }
        if (loadState != LoadState.READY || formatterApi == null) {
            failOnFx(request, "Formatter engine is not ready", null);
            return;
        }
        try {
            // JSObject.call marshals each value as a function argument. The source is never pasted
            // into an executeScript string, so quotes, </script>, and arbitrary code remain data.
            formatterApi.call(
                "format",
                request.id(),
                request.formatterId(),
                request.parser(),
                request.source(),
                request.lineWidth());
        } catch (RuntimeException e) {
            failOnFx(request, "Could not invoke bundled web formatter", e);
        }
    }

    private void failWaitingLoadOnFx(long generation, String message, Throwable cause) {
        if (generation != engineGeneration) {
            return;
        }
        Request request = waitingForLoad;
        waitingForLoad = null;
        if (request != null) {
            completeFailure(request.id(), generation, message, cause);
        } else {
            markBrokenOnFx(generation);
        }
    }

    private void failOnFx(Request request, String message, Throwable cause) {
        completeFailure(request.id(), engineGeneration, message, cause);
    }

    private void completeSuccess(String requestId, long generation, String result) {
        if (generation != engineGeneration) {
            return;
        }
        CompletableFuture<String> future = requests.get(requestId);
        if (future != null) {
            future.complete(result);
        }
    }

    private void completeFailure(String requestId, long generation, String message, Throwable cause) {
        if (generation != engineGeneration) {
            return;
        }
        CompletableFuture<String> future = requests.get(requestId);
        if (future != null) {
            CodeFormatterService.FormatterException failure = cause == null
                ? new CodeFormatterService.FormatterException(message)
                : new CodeFormatterService.FormatterException(message, cause);
            future.completeExceptionally(failure);
        }
        markBrokenOnFx(generation);
    }

    private void markBrokenOnFx(long generation) {
        if (generation != engineGeneration) {
            return;
        }
        loadState = LoadState.BROKEN;
        // Avoid unloading/re-entering WebKit from inside a JavaScript-to-Java bridge callback.
        Platform.runLater(() -> resetEngineOnFx(generation));
    }

    private void resetAfterRequestFailure() {
        long failedGeneration = engineGeneration;
        try {
            Platform.runLater(() -> resetEngineOnFx(failedGeneration));
        } catch (IllegalStateException e) {
            logger.debug("JavaFX toolkit stopped before the formatter engine could be reset", e);
        }
    }

    private void resetEngineOnFx(long expectedGeneration) {
        if (expectedGeneration != engineGeneration || loadState == LoadState.EMPTY) {
            return;
        }
        long discardedGeneration = engineGeneration;
        engineGeneration++;
        loadState = LoadState.EMPTY;
        Request abandoned = waitingForLoad;
        waitingForLoad = null;
        if (abandoned != null && !abandoned.result().isDone()) {
            abandoned.result().completeExceptionally(
                new CodeFormatterService.FormatterException("Formatter engine was reset"));
        }

        WebEngine discardedEngine = engine;
        engine = null;
        formatterApi = null;
        bridge = null;
        webView = null;
        if (discardedEngine != null) {
            try {
                discardedEngine.getLoadWorker().cancel();
                Object window = discardedEngine.executeScript("window");
                if (window instanceof JSObject jsWindow) {
                    jsWindow.removeMember("javaBridge");
                }
                discardedEngine.loadContent("");
            } catch (RuntimeException e) {
                logger.debug("Formatter WebView generation {} cleanup failed", discardedGeneration, e);
            }
        }
    }

    private static long remainingNanos(long deadline) {
        return deadline - System.nanoTime();
    }

    private static CodeFormatterService.FormatterException timeoutFailure(int timeoutSeconds) {
        return new CodeFormatterService.FormatterException(
            "Formatter timed out after " + timeoutSeconds + " seconds");
    }

    /** Kept public because JavaFX WebEngine invokes bridge methods reflectively. */
    public static final class Bridge {
        private final WebFormatterBackend backend;
        private final long generation;

        private Bridge(WebFormatterBackend backend, long generation) {
            this.backend = backend;
            this.generation = generation;
        }

        public void onSuccess(String requestId, String result) {
            backend.completeSuccess(requestId, generation, result);
        }

        public void onFailure(String requestId, String message) {
            backend.completeFailure(
                requestId,
                generation,
                message == null || message.isBlank() ? "Bundled web formatter failed" : message,
                null);
        }
    }
}
