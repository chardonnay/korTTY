package de.kortty.ui;

import de.kortty.perf.PerfTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Loads the JavaFX WebKit native library on a background thread shortly after the main window is
 * up, so the first WebView of the session (AI Manager, snippet editor, guide, diagram and report
 * dialogs) no longer pays for it on the FX thread.
 *
 * <p>Measured with the dialog-open benchmark: the first {@code new WebView()} of a JVM spends
 * ~0.9 s inside {@code com.sun.webkit.WebPage.<clinit>}, which extracts {@code libjfxwebkit} from the
 * javafx-web jar into the user's cache and {@code System.load}s it — 75 % of a cold AI Manager
 * open. That static initializer only loads the library and installs the cookie handler; the
 * FX-thread-bound WebKit initialization happens later in the {@code WebPage} constructor. Class
 * initialization is thread-safe, so triggering it here is equivalent to the first WebView doing it,
 * minus the freeze. If a WebView is created while the preload is still running, its constructor
 * simply blocks on the class-init lock, exactly as long as it would have taken anyway.</p>
 *
 * <p>Opt-out for measurements: {@code -Dkortty.webkit.preload=false}.</p>
 */
public final class WebKitPreloader {

    private static final Logger logger = LoggerFactory.getLogger(WebKitPreloader.class);
    /** Internal JavaFX class whose static initializer loads the native library. */
    static final String WEBKIT_PAGE_CLASS = "com.sun.webkit.WebPage";
    private static final long START_DELAY_MILLIS = 1500;
    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private WebKitPreloader() {
    }

    /** Starts the preload once per JVM; later calls are no-ops. Never throws. */
    public static void start() {
        if (!Boolean.parseBoolean(System.getProperty("kortty.webkit.preload", "true"))) {
            return;
        }
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(WebKitPreloader::preload, "kortty-webkit-preload");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        thread.start();
    }

    private static void preload() {
        try {
            // Let the main window's first pulses and the startup I/O settle before competing for disk.
            Thread.sleep(START_DELAY_MILLIS);
            PerfTrace.Span perf = PerfTrace.begin("WebKitPreloader");
            Class.forName(WEBKIT_PAGE_CLASS, true, WebKitPreloader.class.getClassLoader());
            perf.end();
            logger.debug("WebKit native library preloaded");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Throwable t) {
            // Purely an optimisation: the first WebView will load the library itself.
            logger.debug("WebKit preload skipped: {}", t.toString());
        }
    }
}
