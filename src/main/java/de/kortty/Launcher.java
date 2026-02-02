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
        // Starte die eigentliche JavaFX Application
        KorTTYApplication.main(args);
    }
}
