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

import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;

/**
 * Applies app-level designs to JavaFX scenes and dialogs without changing
 * terminal session or editor content colors.
 *
 * <p>Each design is described once by a {@link DesignSpec} entry in {@link #SPECS};
 * adding a new design means adding an enum constant, a spec entry, a stylesheet and
 * a preview image. The stylesheet/colour wiring here is fully data-driven so it does
 * not need to grow per design.</p>
 */
final class AppDesignStyleSupport {

    // Palette constants for the four original designs. Kept as named constants because
    // other UI code (terminal/file-browser fallbacks) reads them via active*Color().
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

    // Named so the existing getters (referenced by tests) stay stable.
    private static final String MATRIX_STYLESHEET_RESOURCE = "/styles/matrix-terminal.css";
    private static final String HOLOGRAPHIC_STYLESHEET_RESOURCE = "/styles/holographic.css";
    private static final String TACTICAL_STYLESHEET_RESOURCE = "/styles/tactical.css";
    private static final String ELEGANT_STYLESHEET_RESOURCE = "/styles/elegant.css";

    /**
     * Everything that distinguishes one app design: its stylesheet, the colours used by
     * non-CSS code, the accent used for the optional status animation, and the Settings
     * preview image plus its border colour.
     */
    record DesignSpec(AppDesign design, String cssResource,
                      String background, String text, String dim, String accent,
                      String previewResource, String previewBorder) {
    }

    private static final Map<AppDesign, DesignSpec> SPECS = createSpecs();
    private static final Map<String, String> CSS_URL_CACHE = new HashMap<>();

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

    private AppDesignStyleSupport() {
    }

    private static Map<AppDesign, DesignSpec> createSpecs() {
        Map<AppDesign, DesignSpec> specs = new EnumMap<>(AppDesign.class);
        specs.put(AppDesign.MATRIX_TERMINAL, new DesignSpec(AppDesign.MATRIX_TERMINAL,
                MATRIX_STYLESHEET_RESOURCE, MATRIX_BACKGROUND, MATRIX_TEXT, MATRIX_DIM, "#00ff88",
                "/previews/matrix-terminal-preview.png", "#00ff88"));
        specs.put(AppDesign.HOLOGRAPHIC_INTERFACE, new DesignSpec(AppDesign.HOLOGRAPHIC_INTERFACE,
                HOLOGRAPHIC_STYLESHEET_RESOURCE, HOLOGRAPHIC_BACKGROUND, HOLOGRAPHIC_TEXT, HOLOGRAPHIC_DIM, "#00d4ff",
                "/previews/holographic-preview.png", "#00d4ff"));
        specs.put(AppDesign.KLINGON_TACTICAL, new DesignSpec(AppDesign.KLINGON_TACTICAL,
                TACTICAL_STYLESHEET_RESOURCE, TACTICAL_BACKGROUND, TACTICAL_TEXT, TACTICAL_DIM, "#ff3c5a",
                "/previews/klingon-tactical-preview.png", "#ff3c5a"));
        specs.put(AppDesign.ELEGANT_DARK, new DesignSpec(AppDesign.ELEGANT_DARK,
                ELEGANT_STYLESHEET_RESOURCE, ELEGANT_BACKGROUND, ELEGANT_TEXT, ELEGANT_DIM, "#c8a96e",
                "/previews/elegant-dark-preview.png", "rgba(255,255,255,0.12)"));
        specs.put(AppDesign.AMBER_CRT, new DesignSpec(AppDesign.AMBER_CRT,
                "/styles/amber-crt.css", "#1a0f02", "#ffb000", "#9c6a12", "#ffcc44",
                "/previews/amber-crt-preview.png", "#ffb000"));
        specs.put(AppDesign.SYNTHWAVE_84, new DesignSpec(AppDesign.SYNTHWAVE_84,
                "/styles/synthwave.css", "#1a0b2e", "#f5e1ff", "#9d8fc7", "#ff2e88",
                "/previews/synthwave-84-preview.png", "#ff2e88"));
        specs.put(AppDesign.GRUVBOX_RETRO, new DesignSpec(AppDesign.GRUVBOX_RETRO,
                "/styles/gruvbox.css", "#282828", "#ebdbb2", "#a89984", "#fe8019",
                "/previews/gruvbox-retro-preview.png", "#fe8019"));
        specs.put(AppDesign.NORD_ARCTIC, new DesignSpec(AppDesign.NORD_ARCTIC,
                "/styles/nord.css", "#2e3440", "#eceff4", "#7b88a1", "#88c0d0",
                "/previews/nord-arctic-preview.png", "#88c0d0"));
        specs.put(AppDesign.DRACULA, new DesignSpec(AppDesign.DRACULA,
                "/styles/dracula.css", "#282a36", "#f8f8f2", "#6272a4", "#bd93f9",
                "/previews/dracula-preview.png", "#bd93f9"));
        return specs;
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
        AppDesign active = resolveActiveDesign();
        applyToStylesheets(scene.getStylesheets(), active);
        forceRestyle(active, scene.getRoot());
    }

    static void applyToDialogPane(DialogPane dialogPane) {
        if (dialogPane == null) {
            return;
        }
        AppDesign active = resolveActiveDesign();
        applyToStylesheets(dialogPane.getStylesheets(), active);
        forceRestyle(active, dialogPane);
    }

    static void applyToParent(Parent parent) {
        if (parent == null) {
            return;
        }
        AppDesign active = resolveActiveDesign();
        applyToStylesheets(parent.getStylesheets(), active);
        forceRestyle(active, parent);
    }

    /**
     * Swapping the design stylesheet (remove + re-add) makes some already-skinned controls — notably
     * MenuBar buttons — fall back to the JavaFX default colour and only restyle on the next user
     * interaction (hover). For a custom design, force a CSS re-application so the design colours apply
     * deterministically right away. Skipped for NORMAL to avoid needless restyles in the common case.
     */
    private static void forceRestyle(AppDesign active, Parent root) {
        if (active != AppDesign.NORMAL && root != null) {
            root.applyCss();
        }
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
        DesignSpec spec = SPECS.get(resolveActiveDesign());
        return spec != null ? spec.background() : MATRIX_BACKGROUND;
    }

    static String activeTextColor() {
        DesignSpec spec = SPECS.get(resolveActiveDesign());
        return spec != null ? spec.text() : MATRIX_TEXT;
    }

    static String activeDimColor() {
        DesignSpec spec = SPECS.get(resolveActiveDesign());
        return spec != null ? spec.dim() : MATRIX_DIM;
    }

    /** @return the design currently selected in global settings (never null). */
    static AppDesign activeDesign() {
        return resolveActiveDesign();
    }

    /** @return the Settings preview image resource for a design, or {@code null} (e.g. for NORMAL). */
    static String previewResource(AppDesign design) {
        DesignSpec spec = SPECS.get(design);
        return spec != null ? spec.previewResource() : null;
    }

    /** @return the external-form stylesheet URL for a design, or {@code null} (e.g. for NORMAL). */
    static String stylesheetUrl(AppDesign design) {
        DesignSpec spec = SPECS.get(design);
        return spec != null ? resolveCssUrl(spec.cssResource()) : null;
    }

    /** @return the background colour for a specific design's preview box. */
    static String backgroundColor(AppDesign design) {
        DesignSpec spec = SPECS.get(design);
        return spec != null ? spec.background() : MATRIX_BACKGROUND;
    }

    /** @return the border colour for a specific design's preview box. */
    static String previewBorderColor(AppDesign design) {
        DesignSpec spec = SPECS.get(design);
        return spec != null ? spec.previewBorder() : "rgba(255,255,255,0.12)";
    }

    /** @return the accent colour used by the optional status animation, or {@code null}. */
    static String accentColor(AppDesign design) {
        DesignSpec spec = SPECS.get(design);
        return spec != null ? spec.accent() : null;
    }

    /** @return whether the user has design animations enabled (defaults to true). */
    static boolean appDesignAnimationsEnabled() {
        GlobalSettings settings = currentSettings();
        return settings == null || settings.isAppDesignAnimationsEnabled();
    }

    static void applyToStylesheets(ObservableList<String> stylesheets, AppDesign appDesign) {
        if (stylesheets == null) {
            return;
        }
        for (DesignSpec spec : SPECS.values()) {
            String url = resolveCssUrl(spec.cssResource());
            if (url != null) {
                stylesheets.removeIf(url::equals);
            }
        }
        DesignSpec active = SPECS.get(appDesign);
        if (active != null) {
            String url = resolveCssUrl(active.cssResource());
            if (url != null) {
                stylesheets.add(url);
            }
        }
    }

    static String getMatrixStylesheetUrl() {
        return resolveCssUrl(MATRIX_STYLESHEET_RESOURCE);
    }

    static String getHolographicStylesheetUrl() {
        return resolveCssUrl(HOLOGRAPHIC_STYLESHEET_RESOURCE);
    }

    static String getTacticalStylesheetUrl() {
        return resolveCssUrl(TACTICAL_STYLESHEET_RESOURCE);
    }

    static String getElegantStylesheetUrl() {
        return resolveCssUrl(ELEGANT_STYLESHEET_RESOURCE);
    }

    private static String resolveCssUrl(String resource) {
        if (CSS_URL_CACHE.containsKey(resource)) {
            return CSS_URL_CACHE.get(resource);
        }
        var cssUrl = AppDesignStyleSupport.class.getResource(resource);
        String external = cssUrl != null ? cssUrl.toExternalForm() : null;
        if (cssUrl == null) {
            logger.warn("App design stylesheet not found: {}", resource);
        }
        CSS_URL_CACHE.put(resource, external);
        return external;
    }

    private static AppDesign resolveActiveDesign() {
        GlobalSettings settings = currentSettings();
        return settings != null ? settings.getAppDesign() : AppDesign.NORMAL;
    }

    private static GlobalSettings currentSettings() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getGlobalSettingsManager() == null) {
            return null;
        }
        return app.getGlobalSettingsManager().getSettings();
    }
}
