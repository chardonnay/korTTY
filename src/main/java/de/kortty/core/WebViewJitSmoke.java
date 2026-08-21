package de.kortty.core;

import javafx.application.Platform;
import javafx.concurrent.Worker;
import javafx.scene.web.WebEngine;
import javafx.scene.web.WebView;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Console routine behind {@code korTTY --webview-jit-smoke}: boots a JavaFX WebView and executes
 * JavaScript hot enough for JavaScriptCore to tier up into its JIT, then exits with 0 (passed) or
 * 1 (failed) without ever starting the UI.
 *
 * <p>This exists for the macOS signing trap documented at {@code javaFxVersion} in
 * build.gradle.kts. JavaFX ships its natives inside the jars, and re-signing libjfxwebkit.dylib
 * with {@code codesign --options runtime} kills JavaScriptCore's JIT: the WebView boots, then the
 * process is SIGKILLed the instant JavaScript executes — with no crash report. That failure only
 * reproduces in a signed bundle, so nothing on the Gradle test classpath can catch it; CI runs this
 * mode against the notarized, stapled .app instead (see the packagedMacWebViewJitSmoke task).</p>
 *
 * <p>Deliberately dependency-light and side-effect-free: it must not read settings, initialize
 * logging or touch {@code ~/.kortty}, so a release runner can execute it without leaving state
 * behind and without a JVM relaunch changing what is under test.</p>
 */
public final class WebViewJitSmoke {

    /** Command-line switch that selects this mode. */
    public static final String ARG = "--webview-jit-smoke";

    private static final int ITERATIONS = 3_000_000;
    private static final int FACTOR = 7;
    private static final int MODULUS = 1_000_003;
    private static final String PAGE_MARKER = "kortty-webview-jit-smoke";
    private static final long TIMEOUT_SECONDS = 120;

    private static final String PAGE = "<!DOCTYPE html><html><head><meta charset=\"utf-8\"></head>"
        + "<body><script>window.korttySmokeMarker = '" + PAGE_MARKER + "';</script></body></html>";

    /** Proves the page's own inline script ran, not just an injected evaluation. */
    private static final String READ_MARKER = "String(window.korttySmokeMarker)";

    /**
     * Long enough that JavaScriptCore leaves the interpreter and compiles the loop, which is what
     * needs executable memory — the exact thing a hardened-runtime re-signature takes away.
     */
    private static final String HOT_LOOP = "(function () {"
        + " var sum = 0;"
        + " for (var i = 0; i < " + ITERATIONS + "; i++) { sum = (sum + i * " + FACTOR + ") % " + MODULUS + "; }"
        + " return String(sum);"
        + "})()";

    /**
     * Static so the engine's native peer cannot be collected while the load is in flight; a
     * collected WebView would look exactly like the crash this smoke is here to detect.
     */
    private static WebView smokeView;

    private WebViewJitSmoke() {
    }

    /** Runs the smoke and returns the process exit code. */
    public static int run() {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicReference<String> checksum = new AtomicReference<>();

        System.out.println("korTTY WebView JIT smoke: starting JavaFX toolkit ...");
        try {
            Platform.startup(() -> loadSmokePage(done, failure, checksum));
        } catch (RuntimeException ex) {
            System.err.println("korTTY WebView JIT smoke FAILED: JavaFX toolkit did not start: " + ex);
            return 1;
        }

        boolean completed;
        try {
            completed = done.await(TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            completed = false;
        }
        try {
            Platform.exit();
        } catch (RuntimeException ignored) {
            // The toolkit is on its way out either way; the exit code below is what matters.
        }

        if (!completed) {
            System.err.println("korTTY WebView JIT smoke FAILED: no JavaScript result after "
                + TIMEOUT_SECONDS + "s.");
            return 1;
        }
        if (failure.get() != null) {
            System.err.println("korTTY WebView JIT smoke FAILED: " + failure.get());
            return 1;
        }
        String expected = Long.toString(expectedChecksum());
        if (!expected.equals(checksum.get())) {
            System.err.println("korTTY WebView JIT smoke FAILED: JavaScript returned "
                + checksum.get() + ", expected " + expected + ".");
            return 1;
        }
        System.out.println("korTTY WebView JIT smoke passed: " + ITERATIONS
            + " JIT-hot iterations, checksum " + expected + ".");
        return 0;
    }

    private static void loadSmokePage(
        CountDownLatch done,
        AtomicReference<String> failure,
        AtomicReference<String> checksum
    ) {
        smokeView = new WebView();
        WebEngine engine = smokeView.getEngine();
        engine.setOnError(event -> {
            failure.compareAndSet(null, "WebEngine reported an error: " + event.getMessage());
            done.countDown();
        });
        engine.getLoadWorker().stateProperty().addListener((observable, oldState, state) -> {
            if (state == Worker.State.FAILED) {
                Throwable cause = engine.getLoadWorker().getException();
                failure.compareAndSet(null, "the smoke page failed to load: " + cause);
                done.countDown();
                return;
            }
            if (state != Worker.State.SUCCEEDED) {
                return;
            }
            try {
                Object marker = engine.executeScript(READ_MARKER);
                if (!PAGE_MARKER.equals(marker)) {
                    failure.compareAndSet(null, "the in-page <script> did not run: marker is " + marker);
                    return;
                }
                checksum.set(String.valueOf(engine.executeScript(HOT_LOOP)));
            } catch (RuntimeException ex) {
                failure.compareAndSet(null, "executeScript threw: " + ex);
            } finally {
                done.countDown();
            }
        });
        engine.loadContent(PAGE);
    }

    /** The Java twin of {@link #HOT_LOOP}; every term stays well inside exact double range. */
    private static long expectedChecksum() {
        long sum = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            sum = (sum + (long) i * FACTOR) % MODULUS;
        }
        return sum;
    }
}
