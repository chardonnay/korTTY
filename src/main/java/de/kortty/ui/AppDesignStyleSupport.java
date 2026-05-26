package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies app-level designs to JavaFX scenes and dialogs without changing
 * terminal session or editor content colors.
 */
final class AppDesignStyleSupport {

    static final String MATRIX_BACKGROUND = "#080c09";
    static final String MATRIX_TEXT = "#00ff88";
    static final String MATRIX_DIM = "#00c96e";
    static final String HOLOGRAPHIC_BACKGROUND = "#000000";
    static final String HOLOGRAPHIC_TEXT = "#00d4ff";
    static final String HOLOGRAPHIC_DIM = "rgba(0,212,255,0.5)";
    static final String TACTICAL_BACKGROUND = "#0d0906";
    static final String TACTICAL_TEXT = "rgba(255,180,180,0.85)";
    static final String TACTICAL_DIM = "rgba(204,68,85,0.6)";
    static final String ELEGANT_BACKGROUND = "#1a1c20";
    static final String ELEGANT_TEXT = "#e2e4e8";
    static final String ELEGANT_DIM = "#8b9099";

    private static final Logger logger = LoggerFactory.getLogger(AppDesignStyleSupport.class);
    private static final String MATRIX_STYLESHEET_RESOURCE = "/styles/matrix-terminal.css";
    private static final String HOLOGRAPHIC_STYLESHEET_RESOURCE = "/styles/holographic.css";
    private static final String TACTICAL_STYLESHEET_RESOURCE = "/styles/tactical.css";
    private static final String ELEGANT_STYLESHEET_RESOURCE = "/styles/elegant.css";
    private static final ListChangeListener<Window> WINDOW_LISTENER = change -> {
        while (change.next()) {
            if (change.wasAdded()) {
                for (Window window : change.getAddedSubList()) {
                    applyToWindow(window);
                }
            }
        }
    };

    private static boolean windowStylerInstalled;
    private static String matrixStylesheetUrl;
    private static String holographicStylesheetUrl;
    private static String tacticalStylesheetUrl;
    private static String elegantStylesheetUrl;

    private AppDesignStyleSupport() {
    }

    static void installGlobalWindowStyler() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(AppDesignStyleSupport::installGlobalWindowStyler);
            return;
        }
        if (!windowStylerInstalled) {
            Window.getWindows().addListener(WINDOW_LISTENER);
            windowStylerInstalled = true;
        }
        applyToOpenWindows();
    }

    static void applyToOpenWindows() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(AppDesignStyleSupport::applyToOpenWindows);
            return;
        }
        for (Window window : Window.getWindows()) {
            applyToWindow(window);
        }
    }

    static void applyToWindow(Window window) {
        if (window == null) {
            return;
        }
        Scene scene = window.getScene();
        if (scene == null) {
            return;
        }
        applyToScene(scene);
        if (scene.getRoot() instanceof DialogPane dialogPane) {
            applyToDialogPane(dialogPane);
        }
    }

    static void applyToScene(Scene scene) {
        if (scene == null) {
            return;
        }
        applyToStylesheets(scene.getStylesheets(), resolveActiveDesign());
    }

    static void applyToDialogPane(DialogPane dialogPane) {
        if (dialogPane == null) {
            return;
        }
        applyToStylesheets(dialogPane.getStylesheets(), resolveActiveDesign());
    }

    static void applyToParent(Parent parent) {
        if (parent == null) {
            return;
        }
        applyToStylesheets(parent.getStylesheets(), resolveActiveDesign());
    }

    static boolean isMatrixTerminalActive() {
        return resolveActiveDesign() == AppDesign.MATRIX_TERMINAL;
    }

    static boolean isHolographicInterfaceActive() {
        return resolveActiveDesign() == AppDesign.HOLOGRAPHIC_INTERFACE;
    }

    static boolean isKlingonTacticalActive() {
        return resolveActiveDesign() == AppDesign.KLINGON_TACTICAL;
    }

    static boolean isElegantDarkActive() {
        return resolveActiveDesign() == AppDesign.ELEGANT_DARK;
    }

    static boolean isCustomAppDesignActive() {
        return resolveActiveDesign() != AppDesign.NORMAL;
    }

    static String activeBackgroundColor() {
        AppDesign activeDesign = resolveActiveDesign();
        if (activeDesign == AppDesign.HOLOGRAPHIC_INTERFACE) {
            return HOLOGRAPHIC_BACKGROUND;
        }
        if (activeDesign == AppDesign.KLINGON_TACTICAL) {
            return TACTICAL_BACKGROUND;
        }
        if (activeDesign == AppDesign.ELEGANT_DARK) {
            return ELEGANT_BACKGROUND;
        }
        return MATRIX_BACKGROUND;
    }

    static String activeTextColor() {
        AppDesign activeDesign = resolveActiveDesign();
        if (activeDesign == AppDesign.HOLOGRAPHIC_INTERFACE) {
            return HOLOGRAPHIC_TEXT;
        }
        if (activeDesign == AppDesign.KLINGON_TACTICAL) {
            return TACTICAL_TEXT;
        }
        if (activeDesign == AppDesign.ELEGANT_DARK) {
            return ELEGANT_TEXT;
        }
        return MATRIX_TEXT;
    }

    static String activeDimColor() {
        AppDesign activeDesign = resolveActiveDesign();
        if (activeDesign == AppDesign.HOLOGRAPHIC_INTERFACE) {
            return HOLOGRAPHIC_DIM;
        }
        if (activeDesign == AppDesign.KLINGON_TACTICAL) {
            return TACTICAL_DIM;
        }
        if (activeDesign == AppDesign.ELEGANT_DARK) {
            return ELEGANT_DIM;
        }
        return MATRIX_DIM;
    }

    static void applyToStylesheets(ObservableList<String> stylesheets, AppDesign appDesign) {
        if (stylesheets == null) {
            return;
        }
        String matrixStylesheet = getMatrixStylesheetUrl();
        String holographicStylesheet = getHolographicStylesheetUrl();
        String tacticalStylesheet = getTacticalStylesheetUrl();
        String elegantStylesheet = getElegantStylesheetUrl();

        if (matrixStylesheet != null) {
            stylesheets.removeIf(matrixStylesheet::equals);
        }
        if (holographicStylesheet != null) {
            stylesheets.removeIf(holographicStylesheet::equals);
        }
        if (tacticalStylesheet != null) {
            stylesheets.removeIf(tacticalStylesheet::equals);
        }
        if (elegantStylesheet != null) {
            stylesheets.removeIf(elegantStylesheet::equals);
        }

        if (appDesign == AppDesign.MATRIX_TERMINAL && matrixStylesheet != null) {
            stylesheets.add(matrixStylesheet);
        } else if (appDesign == AppDesign.HOLOGRAPHIC_INTERFACE && holographicStylesheet != null) {
            stylesheets.add(holographicStylesheet);
        } else if (appDesign == AppDesign.KLINGON_TACTICAL && tacticalStylesheet != null) {
            stylesheets.add(tacticalStylesheet);
        } else if (appDesign == AppDesign.ELEGANT_DARK && elegantStylesheet != null) {
            stylesheets.add(elegantStylesheet);
        }
    }

    static String getMatrixStylesheetUrl() {
        if (matrixStylesheetUrl != null) {
            return matrixStylesheetUrl;
        }

        var cssUrl = AppDesignStyleSupport.class.getResource(MATRIX_STYLESHEET_RESOURCE);
        if (cssUrl == null) {
            logger.warn("Matrix Terminal stylesheet not found: {}", MATRIX_STYLESHEET_RESOURCE);
            return null;
        }
        matrixStylesheetUrl = cssUrl.toExternalForm();
        return matrixStylesheetUrl;
    }

    static String getHolographicStylesheetUrl() {
        if (holographicStylesheetUrl != null) {
            return holographicStylesheetUrl;
        }

        var cssUrl = AppDesignStyleSupport.class.getResource(HOLOGRAPHIC_STYLESHEET_RESOURCE);
        if (cssUrl == null) {
            logger.warn("Holographic stylesheet not found: {}", HOLOGRAPHIC_STYLESHEET_RESOURCE);
            return null;
        }
        holographicStylesheetUrl = cssUrl.toExternalForm();
        return holographicStylesheetUrl;
    }

    static String getTacticalStylesheetUrl() {
        if (tacticalStylesheetUrl != null) {
            return tacticalStylesheetUrl;
        }

        var cssUrl = AppDesignStyleSupport.class.getResource(TACTICAL_STYLESHEET_RESOURCE);
        if (cssUrl == null) {
            logger.warn("Tactical stylesheet not found: {}", TACTICAL_STYLESHEET_RESOURCE);
            return null;
        }
        tacticalStylesheetUrl = cssUrl.toExternalForm();
        return tacticalStylesheetUrl;
    }

    static String getElegantStylesheetUrl() {
        if (elegantStylesheetUrl != null) {
            return elegantStylesheetUrl;
        }

        var cssUrl = AppDesignStyleSupport.class.getResource(ELEGANT_STYLESHEET_RESOURCE);
        if (cssUrl == null) {
            logger.warn("Elegant Dark stylesheet not found: {}", ELEGANT_STYLESHEET_RESOURCE);
            return null;
        }
        elegantStylesheetUrl = cssUrl.toExternalForm();
        return elegantStylesheetUrl;
    }

    private static AppDesign resolveActiveDesign() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getGlobalSettingsManager() == null) {
            return AppDesign.NORMAL;
        }

        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        return settings != null ? settings.getAppDesign() : AppDesign.NORMAL;
    }
}
