package de.kortty;

import de.kortty.core.WebViewJitSmoke;

/**
 * Launcher-Klasse für jpackage.
 * 
 * JavaFX erfordert entweder das Module-System oder diese Launcher-Klasse,
 * die KEINE JavaFX Application Subklasse ist. Dies umgeht die JavaFX
 * Runtime-Prüfung beim Start über jpackage.
 */
public class Launcher {
    
    public static void main(String[] args) {
        JavaFxPlatformSupport.configureRenderer();
        // Release-verification mode: boot a WebView, run JIT-hot JavaScript and exit. Must be the
        // very first thing here — it runs against the signed bundle in CI, so it must not relaunch
        // the JVM, read ~/.kortty or start the UI. See WebViewJitSmoke for the signing trap.
        if (args.length == 1 && WebViewJitSmoke.ARG.equals(args[0])) {
            System.exit(WebViewJitSmoke.run());
        }
        // Apply the opt-in JVM resource profile before anything else: this may re-exec the app with
        // a larger heap / different GC and terminate this process. Runs before KorTTYApplication is
        // referenced so no logging/JavaFX is initialized in the throwaway parent.
        JvmRelauncher.maybeRelaunch(args);
        // Starte die eigentliche JavaFX Application
        KorTTYApplication.main(args);
    }
}
