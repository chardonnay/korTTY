package de.kortty;

import de.kortty.core.InternalPlantUmlWorker;

/**
 * Launcher-Klasse für jpackage.
 * 
 * JavaFX erfordert entweder das Module-System oder diese Launcher-Klasse,
 * die KEINE JavaFX Application Subklasse ist. Dies umgeht die JavaFX
 * Runtime-Prüfung beim Start über jpackage.
 */
public class Launcher {
    
    public static void main(String[] args) {
        // A stripped jpackage runtime may omit bin/java. In that case PlantUML rendering reuses the
        // native launcher as a private, non-JavaFX worker process. Intercept it before the regular
        // JVM-profile relaunch and before any JavaFX application class is initialized.
        if (InternalPlantUmlWorker.isInvocation(args)) {
            System.exit(InternalPlantUmlWorker.run(args));
            return;
        }
        // Apply the opt-in JVM resource profile before anything else: this may re-exec the app with
        // a larger heap / different GC and terminate this process. Runs before KorTTYApplication is
        // referenced so no logging/JavaFX is initialized in the throwaway parent.
        JvmRelauncher.maybeRelaunch(args);
        // Starte die eigentliche JavaFX Application
        KorTTYApplication.main(args);
    }
}
