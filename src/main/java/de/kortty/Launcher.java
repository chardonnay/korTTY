package de.kortty;

/**
 * Launcher-Klasse für jpackage.
 * 
 * JavaFX erfordert entweder das Module-System oder diese Launcher-Klasse,
 * die KEINE JavaFX Application Subklasse ist. Dies umgeht die JavaFX
 * Runtime-Prüfung beim Start über jpackage.
 */
public class Launcher {
    
    public static void main(String[] args) {
        // Apply the opt-in JVM resource profile before anything else: this may re-exec the app with
        // a larger heap / different GC and terminate this process. Runs before KorTTYApplication is
        // referenced so no logging/JavaFX is initialized in the throwaway parent.
        JvmRelauncher.maybeRelaunch(args);
        // Starte die eigentliche JavaFX Application
        KorTTYApplication.main(args);
    }
}
