package de.kortty.ui;

import com.sun.glass.ui.Application;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Routes the NATIVE macOS quit into korTTY's own quit logic.
 *
 * <p>The packaged macOS app disables JavaFX implicit exit so korTTY keeps running
 * (JobScheduler) after the last window closes. JavaFX Glass owns the
 * {@code NSApplication} delegate; its {@code applicationShouldTerminate:} always
 * answers {@code NSTerminateCancel} and merely forwards the quit to
 * {@code Application.EventHandler.handleQuitAction}, whose Quantum implementation
 * only fires a close request on each <em>visible</em> window. With implicit exit
 * disabled that means: a native quit (Cmd+Q consumed by the apple menu, the app
 * menu's "Quit korTTY" clicked by mouse, the system Dock "Quit", logout) either
 * fell into the keep-alive branch (windows close, process lingers headless) or —
 * once headless — was a complete silent no-op. The process could only be killed.
 *
 * <p>This hook wraps Quantum's Glass event handler with a forwarder whose
 * {@code handleQuitAction} calls {@link MainWindow#requestApplicationQuit()}
 * instead: the same confirmations, JobScheduler drain gate and shutdown path as
 * File → Quit, ending in {@code Runtime.halt(0)}. All other events are forwarded
 * verbatim, so Glass/Quantum behavior (theme changes, open-files, activation) is
 * untouched. Plain window closes never traverse {@code handleQuitAction}, so the
 * keep-alive background mode is preserved.
 *
 * <p>{@code com.sun.glass.ui} is internal API (stable across JavaFX 21.x; the
 * shipped JavaFX version is pinned — see {@code javaFxVersion} in
 * build.gradle.kts). In the packaged app JavaFX runs on the class path (unnamed
 * module), so no {@code --add-exports} is needed at runtime; compile and dev-run
 * configure the export explicitly. If a future JavaFX/packaging change makes the
 * install fail, {@link #install()} returns {@code false} and the caller must
 * leave implicit exit ON so the app stays quittable (background mode is
 * sacrificed, quittability never).
 */
public final class MacGlassQuitHook {

    private static final Logger logger = LoggerFactory.getLogger(MacGlassQuitHook.class);

    private static volatile boolean installed = false;

    private MacGlassQuitHook() {
    }

    /**
     * Installs the quit hook. Must be called on the JavaFX Application Thread
     * (Glass {@code setEventHandler} enforces its event thread). Idempotent.
     *
     * @return {@code true} when the hook is active (also on repeat calls),
     *     {@code false} when installation failed — the caller must then keep
     *     JavaFX implicit exit enabled as the quit fallback.
     */
    public static synchronized boolean install() {
        if (installed) {
            return true;
        }
        try {
            Application glassApp = Application.GetApplication();
            Application.EventHandler previous = glassApp.getEventHandler();
            glassApp.setEventHandler(new ForwardingQuitHandler(previous));
            installed = true;
            logger.info("Installed macOS native-quit hook (Glass handleQuitAction -> korTTY quit)");
            return true;
        } catch (Throwable t) {
            // LinkageError/IllegalAccessError on a future JavaFX where the internals moved,
            // or IllegalStateException off the Glass event thread. Never break startup.
            logger.error("Could not install the macOS native-quit hook; keeping JavaFX implicit exit "
                + "enabled so the app stays quittable (background keep-alive disabled)", t);
            return false;
        }
    }

    /**
     * Forwards every Glass application event to the previous (Quantum) handler and
     * reroutes only {@code handleQuitAction}. The base class's methods are no-ops
     * (and {@code handleThemeChanged} returns {@code false}), so forwarding all 14
     * is behavior-preserving; Quantum's handler notably needs
     * {@code handleThemeChanged} (high-contrast) and {@code handleOpenFilesAction}.
     */
    private static final class ForwardingQuitHandler extends Application.EventHandler {

        private final Application.EventHandler delegate;

        private ForwardingQuitHandler(Application.EventHandler delegate) {
            this.delegate = delegate;
        }

        @Override
        public void handleQuitAction(Application app, long time) {
            // Deliberately NOT forwarded: Quantum would only fire per-window close
            // requests, which the keep-alive design swallows. Route into korTTY's
            // quit (confirmations + drain gate + shutdown + halt) instead.
            logger.info("Native macOS quit intercepted, routing to application quit");
            if (Platform.isFxApplicationThread()) {
                MainWindow.requestApplicationQuit();
            } else {
                Platform.runLater(MainWindow::requestApplicationQuit);
            }
        }

        @Override
        public void handleWillFinishLaunchingAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleWillFinishLaunchingAction(app, time);
            }
        }

        @Override
        public void handleDidFinishLaunchingAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleDidFinishLaunchingAction(app, time);
            }
        }

        @Override
        public void handleWillBecomeActiveAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleWillBecomeActiveAction(app, time);
            }
        }

        @Override
        public void handleDidBecomeActiveAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleDidBecomeActiveAction(app, time);
            }
        }

        @Override
        public void handleWillResignActiveAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleWillResignActiveAction(app, time);
            }
        }

        @Override
        public void handleDidResignActiveAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleDidResignActiveAction(app, time);
            }
        }

        @Override
        public void handleDidReceiveMemoryWarning(Application app, long time) {
            if (delegate != null) {
                delegate.handleDidReceiveMemoryWarning(app, time);
            }
        }

        @Override
        public void handleWillHideAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleWillHideAction(app, time);
            }
        }

        @Override
        public void handleDidHideAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleDidHideAction(app, time);
            }
        }

        @Override
        public void handleWillUnhideAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleWillUnhideAction(app, time);
            }
        }

        @Override
        public void handleDidUnhideAction(Application app, long time) {
            if (delegate != null) {
                delegate.handleDidUnhideAction(app, time);
            }
        }

        @Override
        public void handleOpenFilesAction(Application app, long time, String[] files) {
            if (delegate != null) {
                delegate.handleOpenFilesAction(app, time, files);
            }
        }

        @Override
        public boolean handleThemeChanged(String themeName) {
            return delegate != null && delegate.handleThemeChanged(themeName);
        }
    }
}
