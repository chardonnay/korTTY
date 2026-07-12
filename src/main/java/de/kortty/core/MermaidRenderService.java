package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;
import netscape.javascript.JSObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lazy, serialized Mermaid renderer backed by one isolated JavaFX WebView.
 *
 * <p>The bundled browser API is confined to the FX thread. Callers receive futures, while a
 * timeout/cancellation discards the WebEngine generation so a failed Promise cannot poison later
 * renders. Rendered SVG is sanitized again in Java before it leaves this service.</p>
 */
public final class MermaidRenderService {

    private static final Logger logger = LoggerFactory.getLogger(MermaidRenderService.class);
    private static final Gson GSON = new Gson();
    private static final Type NODE_BOUNDS_TYPE = new TypeToken<List<NodeBounds>>() { }.getType();
    private static final Duration DEFAULT_REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration IDLE_TIMEOUT = Duration.ofSeconds(60);
    private static final String TEST_TIMEOUT_PROPERTY = "kortty.internal.test.mermaidTimeoutMillis";
    private static final String TEST_HANG_PROPERTY = "kortty.internal.test.mermaidHangRequest";
    private static final int MAX_SOURCE_BYTES = 32 * 1024;
    private static final int MAX_EDGES = 300;
    private static final Pattern EDGE_PATTERN = Pattern.compile(
        "-->>|->>|--\\)|-\\)|<\\|--|<\\|\\.\\.|--\\|>|\\*--|--\\*|o--|--o|<-->|"
            + "<\\.\\.|\\.\\.>|\\.\\.\\||\\|\\.\\.|==>|-\\.->|~~~|--x|-x|-->|<--|--|\\.\\.|->");
    private static final Pattern URL_PATTERN = Pattern.compile(
        "(?i)\\b(?:https?|ftp)://|\\bfile:(?://|/)|\\bjavascript\\s*:|"
            + "\\bdata\\s*:(?:image|text|application)/|(?:src|href)\\s*=|url\\s*\\(");
    private static final Pattern CLICK_PATTERN = Pattern.compile("(?im)(?:^|;)\\s*click\\s+");
    private static final Pattern IMAGE_OR_ICON_PATTERN = Pattern.compile(
        "(?i)@\\s*\\{[^}\\r\\n]*(?:(?:icon|img|image)\\s*:|shape\\s*:\\s*(?:icon|image))"
            + "|\\bfa:fa-[a-z0-9_-]+");
    private static final AtomicLong TEST_HANG_DISPATCH_COUNT = new AtomicLong();

    public enum Theme {
        LIGHT,
        DARK
    }

    public record RenderRequest(
        String source,
        Theme theme,
        String backgroundColor,
        boolean includePng,
        boolean generatedFlow) {

        public RenderRequest {
            source = source != null ? source : "";
            theme = theme != null ? theme : Theme.LIGHT;
            backgroundColor = normalizeBackground(backgroundColor, theme);
        }

        public static RenderRequest generatedFlow(
            String source,
            Theme theme,
            String backgroundColor,
            boolean includePng) {

            return new RenderRequest(source, theme, backgroundColor, includePng, true);
        }

        public static RenderRequest chat(String source, Theme theme) {
            return new RenderRequest(source, theme, null, false, false);
        }
    }

    public record NodeBounds(
        String nodeId,
        String label,
        double x,
        double y,
        double width,
        double height) {
    }

    public record RenderResult(
        boolean success,
        String svg,
        byte[] png,
        double width,
        double height,
        List<NodeBounds> nodeBounds,
        String message) {

        public RenderResult {
            svg = svg != null ? svg : "";
            png = png != null ? png.clone() : null;
            nodeBounds = nodeBounds != null ? List.copyOf(nodeBounds) : List.of();
            message = message != null ? message : "";
        }

        public static RenderResult failure(String message) {
            return new RenderResult(false, "", null, 0, 0, List.of(), message);
        }

        @Override
        public byte[] png() {
            return png != null ? png.clone() : null;
        }
    }

    public record SyntaxCheckResult(boolean available, boolean valid, String diagramType, String message) {
        public SyntaxCheckResult {
            diagramType = diagramType != null ? diagramType : "";
            message = message != null ? message : "";
        }
    }

    private enum RequestKind {
        PARSE,
        RENDER
    }

    private enum LoadState {
        EMPTY,
        LOADING,
        READY,
        BROKEN
    }

    private static final class Pending<T> {
        private final String id;
        private final RequestKind kind;
        private final RenderRequest renderRequest;
        private final String parseSource;
        private final CompletableFuture<T> future;
        private final boolean hangForTest;
        private final long timeoutMillis;
        private volatile ScheduledFuture<?> timeout;

        private Pending(
            String id,
            RequestKind kind,
            RenderRequest renderRequest,
            String parseSource,
            CompletableFuture<T> future) {

            this.id = id;
            this.kind = kind;
            this.renderRequest = renderRequest;
            this.parseSource = parseSource;
            this.future = future;
            this.hangForTest = Boolean.getBoolean(TEST_HANG_PROPERTY);
            this.timeoutMillis = requestTimeoutMillis();
        }
    }

    private static final class Holder {
        private static final MermaidRenderService INSTANCE = new MermaidRenderService();
    }

    private final AtomicLong requestSequence = new AtomicLong();
    private final AtomicBoolean pumpScheduled = new AtomicBoolean();
    private final ConcurrentLinkedQueue<Pending<?>> queue = new ConcurrentLinkedQueue<>();
    private final ScheduledExecutorService scheduler;

    // FX-thread-confined state.
    private WebView webView;
    private WebEngine engine;
    private JSObject mermaidApi;
    private Bridge bridge;
    private Pending<?> active;
    private LoadState loadState = LoadState.EMPTY;
    private long engineGeneration;
    private ScheduledFuture<?> idleReset;

    private MermaidRenderService() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "mermaid-render-timeouts");
            thread.setDaemon(true);
            return thread;
        };
        scheduler = Executors.newSingleThreadScheduledExecutor(factory);
    }

    public static boolean isBundledAvailable() {
        return MermaidResourceBundle.isBundled();
    }

    public static CompletableFuture<RenderResult> render(RenderRequest request) {
        Objects.requireNonNull(request, "request");
        String validationFailure = request.generatedFlow()
            ? restrictedFlowValidationFailure(request.source())
            : validateSource(request.source());
        if (validationFailure != null) {
            return CompletableFuture.completedFuture(RenderResult.failure(validationFailure));
        }
        String normalizedSource = request.generatedFlow()
            ? SnippetDiagramSupport.normalizeMermaid(request.source())
            : normalizeSource(request.source());
        String source = request.generatedFlow()
            ? appendKorTTYThemeClasses(normalizedSource, request.theme())
            : normalizedSource;
        RenderRequest styled = new RenderRequest(
            source,
            request.theme(),
            request.backgroundColor(),
            request.includePng(),
            request.generatedFlow());
        return Holder.INSTANCE.enqueueRender(styled);
    }

    public static CompletableFuture<SyntaxCheckResult> checkSyntax(String source) {
        String validationFailure = validateSource(source);
        if (validationFailure != null) {
            return CompletableFuture.completedFuture(
                new SyntaxCheckResult(true, false, "", validationFailure));
        }
        return Holder.INSTANCE.enqueueParse(normalizeSource(source));
    }

    public static void dispose() {
        MermaidRenderService service = Holder.INSTANCE;
        try {
            Platform.runLater(service::disposeOnFx);
        } catch (IllegalStateException ignored) {
            service.failAllWithoutToolkit();
        }
    }

    static long testHangDispatchCount() {
        return TEST_HANG_DISPATCH_COUNT.get();
    }

    private CompletableFuture<RenderResult> enqueueRender(RenderRequest request) {
        CompletableFuture<RenderResult> future = new CompletableFuture<>();
        Pending<RenderResult> pending = new Pending<>(
            Long.toString(requestSequence.incrementAndGet()), RequestKind.RENDER, request, null, future);
        enqueue(pending);
        return future;
    }

    private CompletableFuture<SyntaxCheckResult> enqueueParse(String source) {
        CompletableFuture<SyntaxCheckResult> future = new CompletableFuture<>();
        Pending<SyntaxCheckResult> pending = new Pending<>(
            Long.toString(requestSequence.incrementAndGet()), RequestKind.PARSE, null, source, future);
        enqueue(pending);
        return future;
    }

    private void enqueue(Pending<?> pending) {
        queue.add(pending);
        pending.future.whenComplete((ignored, failure) -> {
            if (pending.future.isCancelled()) {
                scheduleOnFx(() -> cancelOnFx(pending));
            }
        });
        schedulePump();
    }

    private void schedulePump() {
        if (!pumpScheduled.compareAndSet(false, true)) {
            return;
        }
        scheduleOnFx(() -> {
            pumpScheduled.set(false);
            pumpOnFx();
        });
    }

    private void scheduleOnFx(Runnable runnable) {
        try {
            Platform.runLater(runnable);
        } catch (IllegalStateException e) {
            failAllWithoutToolkit();
        }
    }

    private void pumpOnFx() {
        if (active != null) {
            return;
        }
        cancelIdleReset();
        Pending<?> next;
        do {
            next = queue.poll();
        } while (next != null && next.future.isDone());
        if (next == null) {
            scheduleIdleReset();
            return;
        }
        active = next;
        startTimeout(next);
        if (loadState == LoadState.BROKEN) {
            resetEngineOnFx(engineGeneration);
        }
        if (loadState == LoadState.READY) {
            dispatchOnFx(next);
        } else if (loadState == LoadState.EMPTY) {
            loadEngineOnFx();
        }
    }

    private void loadEngineOnFx() {
        long generation = ++engineGeneration;
        loadState = LoadState.LOADING;
        CompletableFuture.supplyAsync(() -> {
            try {
                return MermaidResourceBundle.hostUrl();
            } catch (IOException e) {
                throw new java.util.concurrent.CompletionException(e);
            }
        }).whenComplete((hostUrl, failure) -> scheduleOnFx(
            () -> createEngineOnFx(generation, hostUrl, failure)));
    }

    private void createEngineOnFx(long generation, String hostUrl, Throwable preparationFailure) {
        if (generation != engineGeneration || loadState != LoadState.LOADING) {
            return;
        }
        if (preparationFailure != null || hostUrl == null || hostUrl.isBlank()) {
            logger.warn("Could not prepare Mermaid WebView resources", preparationFailure);
            failActiveOnFx("Mermaid render resources are unavailable.", generation);
            return;
        }
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
                    Platform.runLater(() -> finishLoadOnFx(generation));
                } else if (newState == Worker.State.FAILED || newState == Worker.State.CANCELLED) {
                    failActiveOnFx("Mermaid host page could not be loaded.", generation);
                }
            });
            engine.load(hostUrl);
        } catch (RuntimeException e) {
            logger.warn("Could not initialize Mermaid WebView", e);
            failActiveOnFx("Mermaid renderer could not be initialized.", generation);
        }
    }

    private void finishLoadOnFx(long generation) {
        if (generation != engineGeneration || engine == null || loadState != LoadState.LOADING) {
            return;
        }
        try {
            JSObject window = (JSObject) engine.executeScript("window");
            bridge = new Bridge(this, generation);
            window.setMember("javaBridge", bridge);
            Object api = window.getMember("korttyMermaid");
            if (!(api instanceof JSObject jsApi)) {
                throw new IllegalStateException("window.korttyMermaid is unavailable");
            }
            mermaidApi = jsApi;
            loadState = LoadState.READY;
            if (active != null && !active.future.isDone()) {
                dispatchOnFx(active);
            } else {
                Pending<?> completed = active;
                active = null;
                cancelTimeout(completed);
                resetEngineOnFx(generation);
                pumpOnFx();
            }
        } catch (RuntimeException e) {
            logger.warn("Mermaid host page did not initialize", e);
            failActiveOnFx("Mermaid renderer did not initialize.", generation);
        }
    }

    private void dispatchOnFx(Pending<?> pending) {
        if (mermaidApi == null || loadState != LoadState.READY) {
            failActiveOnFx("Mermaid renderer is not ready.", engineGeneration);
            return;
        }
        // A system-property-gated test hook exercises timeout/cancellation recovery without
        // reserving any Mermaid source text as a production sentinel.
        if (pending.hangForTest) {
            TEST_HANG_DISPATCH_COUNT.incrementAndGet();
            return;
        }
        try {
            if (pending.kind == RequestKind.PARSE) {
                mermaidApi.call("parse", pending.id, pending.parseSource);
            } else {
                RenderRequest request = pending.renderRequest;
                mermaidApi.call(
                    "render",
                    pending.id,
                    request.source(),
                    request.theme() == Theme.DARK,
                    request.backgroundColor(),
                    request.includePng());
            }
        } catch (RuntimeException e) {
            logger.warn("Could not dispatch Mermaid request", e);
            failActiveOnFx("Could not invoke Mermaid renderer.", engineGeneration);
        }
    }

    private void completeParseOnFx(String requestId, long generation, String diagramType) {
        if (!matchesActive(requestId, generation, RequestKind.PARSE)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Pending<SyntaxCheckResult> pending = (Pending<SyntaxCheckResult>) active;
        pending.future.complete(new SyntaxCheckResult(true, true, diagramType, ""));
        finishActiveOnFx();
    }

    private void completeRenderOnFx(
        String requestId,
        long generation,
        String svg,
        String pngBase64,
        double width,
        double height,
        String boundsJson) {

        if (!matchesActive(requestId, generation, RequestKind.RENDER)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Pending<RenderResult> pending = (Pending<RenderResult>) active;
        try {
            String sanitizedSvg = AiSvgContentSupport.sanitizeSvg(svg);
            byte[] png = pngBase64 != null && !pngBase64.isBlank()
                ? Base64.getDecoder().decode(pngBase64)
                : null;
            List<NodeBounds> bounds = parseNodeBounds(boundsJson);
            pending.future.complete(new RenderResult(
                true, sanitizedSvg, png, width, height, bounds, ""));
            finishActiveOnFx();
        } catch (RuntimeException e) {
            logger.warn("Could not accept Mermaid render result", e);
            pending.future.complete(RenderResult.failure("Mermaid returned an invalid render result."));
            markBrokenAndFinishOnFx(generation);
        }
    }

    private void completeFailureOnFx(String requestId, long generation, String message) {
        if (active == null || active.future.isDone()
            || generation != engineGeneration || !active.id.equals(requestId)) {
            return;
        }
        String useful = message != null && !message.isBlank() ? message : "Mermaid rendering failed.";
        if (active.kind == RequestKind.RENDER) {
            @SuppressWarnings("unchecked")
            Pending<RenderResult> pending = (Pending<RenderResult>) active;
            pending.future.complete(RenderResult.failure(useful));
        } else {
            @SuppressWarnings("unchecked")
            Pending<SyntaxCheckResult> pending = (Pending<SyntaxCheckResult>) active;
            pending.future.complete(new SyntaxCheckResult(true, false, "", useful));
        }
        finishActiveOnFx();
    }

    private boolean matchesActive(String requestId, long generation, RequestKind kind) {
        return active != null
            && generation == engineGeneration
            && active.kind == kind
            && !active.future.isDone()
            && active.id.equals(requestId);
    }

    private void timeoutOnFx(Pending<?> pending) {
        queue.remove(pending);
        cancelTimeout(pending);
        if (active == pending) {
            resetEngineOnFx(engineGeneration);
            active = null;
        }
        pumpOnFx();
    }

    private void cancelOnFx(Pending<?> pending) {
        queue.remove(pending);
        cancelTimeout(pending);
        if (active == pending) {
            resetEngineOnFx(engineGeneration);
            active = null;
        }
        pumpOnFx();
    }

    private void failActiveOnFx(String message, long generation) {
        if (generation != engineGeneration || active == null) {
            return;
        }
        if (active.kind == RequestKind.RENDER) {
            @SuppressWarnings("unchecked")
            Pending<RenderResult> pending = (Pending<RenderResult>) active;
            pending.future.complete(RenderResult.failure(message));
        } else {
            @SuppressWarnings("unchecked")
            Pending<SyntaxCheckResult> pending = (Pending<SyntaxCheckResult>) active;
            pending.future.complete(new SyntaxCheckResult(false, false, "", message));
        }
        markBrokenAndFinishOnFx(generation);
    }

    private void markBrokenAndFinishOnFx(long generation) {
        if (generation == engineGeneration) {
            loadState = LoadState.BROKEN;
        }
        finishActiveOnFx();
    }

    private void finishActiveOnFx() {
        Pending<?> finished = active;
        active = null;
        cancelTimeout(finished);
        pumpOnFx();
    }

    private void startTimeout(Pending<?> pending) {
        if (pending.timeout != null || pending.future.isDone()) {
            return;
        }
        pending.timeout = scheduler.schedule(() -> {
            String message = pending.kind == RequestKind.RENDER
                ? "Mermaid rendering timed out."
                : "Mermaid syntax check timed out.";
            if (completePendingFailure(pending, message, true)) {
                scheduleOnFx(() -> timeoutOnFx(pending));
            }
        }, pending.timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private static void cancelTimeout(Pending<?> pending) {
        if (pending != null && pending.timeout != null) {
            pending.timeout.cancel(false);
            pending.timeout = null;
        }
    }

    private void disposeOnFx() {
        cancelIdleReset();
        Pending<?> current = active;
        active = null;
        if (current != null) {
            cancelTimeout(current);
            completePendingFailure(current, "Mermaid renderer was disposed.", false);
        }
        Pending<?> queued;
        while ((queued = queue.poll()) != null) {
            cancelTimeout(queued);
            completePendingFailure(queued, "Mermaid renderer was disposed.", false);
        }
        resetEngineOnFx(engineGeneration);
    }

    private void resetEngineOnFx(long generation) {
        if (generation != engineGeneration) {
            return;
        }
        engineGeneration++;
        try {
            if (engine != null) {
                engine.getLoadWorker().cancel();
                engine.load("about:blank");
            }
        } catch (RuntimeException ignored) {
            // Best effort; all references are dropped below.
        }
        mermaidApi = null;
        bridge = null;
        engine = null;
        webView = null;
        loadState = LoadState.EMPTY;
    }

    private void scheduleIdleReset() {
        cancelIdleReset();
        long generation = engineGeneration;
        idleReset = scheduler.schedule(
            () -> scheduleOnFx(() -> {
                if (active == null && queue.isEmpty() && generation == engineGeneration) {
                    resetEngineOnFx(generation);
                }
            }),
            IDLE_TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS);
    }

    private void cancelIdleReset() {
        if (idleReset != null) {
            idleReset.cancel(false);
            idleReset = null;
        }
    }

    private void failAllWithoutToolkit() {
        List<Pending<?>> failures = new ArrayList<>();
        Pending<?> current = active;
        active = null;
        if (current != null) {
            failures.add(current);
        }
        Pending<?> pending;
        while ((pending = queue.poll()) != null) {
            failures.add(pending);
        }
        for (Pending<?> failure : failures) {
            cancelTimeout(failure);
            completePendingFailure(failure, "JavaFX toolkit is unavailable.", false);
        }
    }

    private static boolean completePendingFailure(Pending<?> pending, String message, boolean available) {
        if (pending.kind == RequestKind.RENDER) {
            @SuppressWarnings("unchecked")
            Pending<RenderResult> render = (Pending<RenderResult>) pending;
            return render.future.complete(RenderResult.failure(message));
        }
        @SuppressWarnings("unchecked")
        Pending<SyntaxCheckResult> parse = (Pending<SyntaxCheckResult>) pending;
        return parse.future.complete(new SyntaxCheckResult(available, false, "", message));
    }

    private static List<NodeBounds> parseNodeBounds(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        List<NodeBounds> parsed = GSON.fromJson(json, NODE_BOUNDS_TYPE);
        if (parsed == null) {
            return List.of();
        }
        return parsed.stream()
            .filter(Objects::nonNull)
            .filter(item -> item.nodeId() != null && !item.nodeId().isBlank())
            .filter(item -> Double.isFinite(item.x()) && Double.isFinite(item.y())
                && Double.isFinite(item.width()) && Double.isFinite(item.height())
                && item.width() > 0 && item.height() > 0)
            .toList();
    }

    private static long requestTimeoutMillis() {
        long configured = Long.getLong(TEST_TIMEOUT_PROPERTY, DEFAULT_REQUEST_TIMEOUT.toMillis());
        return Math.max(100L, Math.min(DEFAULT_REQUEST_TIMEOUT.toMillis(), configured));
    }

    static String validateSource(String source) {
        String original = source != null ? source : "";
        if (original.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            return "Mermaid source exceeds the 32 KiB limit.";
        }
        String value = normalizeSource(original);
        if (value.isBlank()) {
            return "Mermaid source is empty.";
        }
        if (value.startsWith("---")) {
            return "Mermaid frontmatter is not allowed.";
        }
        if (value.contains("%%{")) {
            return "Mermaid directives are not allowed.";
        }
        if (URL_PATTERN.matcher(value).find()) {
            return "Mermaid links and external resources are not allowed.";
        }
        if (CLICK_PATTERN.matcher(value).find()) {
            return "Mermaid click callbacks are not allowed.";
        }
        if (IMAGE_OR_ICON_PATTERN.matcher(value).find()) {
            return "Mermaid image and icon nodes are not allowed.";
        }
        if (countEdges(value) > MAX_EDGES) {
            return "Mermaid source exceeds the 300-edge limit.";
        }
        return null;
    }

    private static String normalizeSource(String source) {
        return source != null ? source.strip() : "";
    }

    private static int countEdges(String source) {
        Matcher explicitEdges = EDGE_PATTERN.matcher(source);
        int count = 0;
        while (explicitEdges.find()) {
            if (++count > MAX_EDGES) {
                return count;
            }
        }
        if (!isMindmap(source)) {
            return count;
        }
        int nodeLines = 0;
        boolean headerSeen = false;
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("%%")) {
                continue;
            }
            if (!headerSeen) {
                headerSeen = true;
                continue;
            }
            nodeLines++;
            if (nodeLines - 1 > MAX_EDGES) {
                return nodeLines - 1;
            }
        }
        return Math.max(count, Math.max(0, nodeLines - 1));
    }

    private static boolean isMindmap(String source) {
        for (String line : source.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("%%")) {
                return "mindmap".equalsIgnoreCase(trimmed);
            }
        }
        return false;
    }

    private static String restrictedFlowValidationFailure(String source) {
        SnippetDiagramSupport.MermaidValidation validation = SnippetDiagramSupport.validateMermaid(source);
        return validation.valid() ? null : validation.message();
    }

    private static String appendKorTTYThemeClasses(String source, Theme theme) {
        String setup = theme == Theme.DARK ? "#26382D" : "#EAF7EF";
        String work = theme == Theme.DARK ? "#23354A" : "#EAF4FF";
        String success = theme == Theme.DARK ? "#1F4A36" : "#DCFCE7";
        String failure = theme == Theme.DARK ? "#4A2628" : "#FDECEC";
        String stroke = theme == Theme.DARK ? "#CBD5E1" : "#475569";
        String text = theme == Theme.DARK ? "#F8FAFC" : "#0F172A";
        return source.stripTrailing()
            + "\nclassDef setup fill:" + setup + ",stroke:" + stroke + ",color:" + text + ";"
            + "\nclassDef work fill:" + work + ",stroke:" + stroke + ",color:" + text + ";"
            + "\nclassDef success fill:" + success + ",stroke:" + stroke + ",color:" + text + ";"
            + "\nclassDef failure fill:" + failure + ",stroke:" + stroke + ",color:" + text + ";";
    }

    private static String normalizeBackground(String color, Theme theme) {
        String fallback = theme == Theme.DARK ? "#111827" : "#FFFFFF";
        if (color == null || !color.matches("(?i)^#[0-9a-f]{6}$")) {
            return fallback;
        }
        return color.toUpperCase(Locale.ROOT);
    }

    /** Java bridge retained strongly for the lifetime of a WebEngine generation. */
    public static final class Bridge {
        private final MermaidRenderService owner;
        private final long generation;

        private Bridge(MermaidRenderService owner, long generation) {
            this.owner = owner;
            this.generation = generation;
        }

        public void parseSucceeded(String requestId, String diagramType) {
            owner.completeParseOnFx(requestId, generation, diagramType);
        }

        public void renderSucceeded(
            String requestId,
            String svg,
            String pngBase64,
            double width,
            double height,
            String boundsJson) {

            owner.completeRenderOnFx(requestId, generation, svg, pngBase64, width, height, boundsJson);
        }

        public void requestFailed(String requestId, String message) {
            owner.completeFailureOnFx(requestId, generation, message);
        }
    }
}
