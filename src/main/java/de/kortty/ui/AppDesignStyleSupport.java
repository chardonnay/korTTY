package de.kortty.ui;

import atlantafx.base.theme.PrimerDark;
import de.kortty.KorTTYApplication;
import de.kortty.model.AppDesign;
import de.kortty.model.GlobalSettings;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Window;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Applies app-level designs to JavaFX scenes and dialogs without changing
 * terminal session or editor content colors.
 *
 * <p>Each design is described once by a {@link DesignSpec} entry in {@link #SPECS};
 * adding a new design means adding an enum constant, a spec entry, a stylesheet and
 * a preview image. The stylesheet/colour wiring here is fully data-driven so it does
 * not need to grow per design.</p>
 */
public final class AppDesignStyleSupport {

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
    private static final String APPLICATION_BASE_STYLESHEET_RESOURCE = "/styles/terminal.css";
    private static final String ATLANTAFX_COMPONENTS_STYLESHEET_RESOURCE =
            "/styles/atlantafx-kortty-components.css";
    private static final String APPLICATION_BASE_STYLES_MARKER =
            AppDesignStyleSupport.class.getName() + ".applicationBaseStyles";
    private static final String ATLANTAFX_PRIMER_DARK_USER_AGENT_STYLESHEET =
            new PrimerDark().getUserAgentStylesheet();

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
    // DialogPanes embedded in tabs and raw Popup content own author stylesheets directly rather
    // than through a Window Scene. Keep weak references so a live design switch reaches them
    // without extending the lifetime of a closed dialog or popup.
    private static final Set<Parent> REGISTERED_PARENT_SURFACES = Collections.synchronizedSet(
            Collections.newSetFromMap(new WeakHashMap<>()));

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
        specs.put(AppDesign.ATLANTAFX_PRIMER_DARK, new DesignSpec(AppDesign.ATLANTAFX_PRIMER_DARK,
                "/styles/atlantafx-primer-dark.css", "#0d1117", "#c9d1d9", "#8b949e", "#58a6ff",
                "/previews/atlantafx-primer-dark-preview.png", "#30363d"));
        return specs;
    }

    /**
     * Selects the global JavaFX base theme before any window is created and installs the listener
     * that styles dialogs and popup windows created later. AtlantaFX is deliberately opt-in; every
     * other korTTY design explicitly returns to Modena before its author stylesheet is applied.
     */
    public static void initializeGlobalStyling(AppDesign design) {
        applyUserAgentStylesheet(design);
        installGlobalWindowStyler();
    }

    /** Applies Primer Dark only for the AtlantaFX design and Modena for every other design. */
    public static void applyUserAgentStylesheet(AppDesign design) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> applyUserAgentStylesheet(design));
            return;
        }
        updateUserAgentStylesheet(
                design,
                Application::getUserAgentStylesheet,
                Application::setUserAgentStylesheet);
    }

    static String desiredUserAgentStylesheet(AppDesign design) {
        return design == AppDesign.ATLANTAFX_PRIMER_DARK
                ? ATLANTAFX_PRIMER_DARK_USER_AGENT_STYLESHEET
                : Application.STYLESHEET_MODENA;
    }

    static boolean updateUserAgentStylesheet(
            AppDesign design, Supplier<String> currentStylesheet, Consumer<String> stylesheetSetter) {
        String desired = desiredUserAgentStylesheet(design);
        if (Objects.equals(currentStylesheet.get(), desired)) {
            return false;
        }
        stylesheetSetter.accept(desired);
        return true;
    }

    public static void installGlobalWindowStyler() {
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
        applyToOpenWindows(resolveActiveDesign());
    }

    static void applyToOpenWindows(AppDesign design) {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(() -> applyToOpenWindows(design));
            return;
        }
        for (Window window : Window.getWindows()) {
            applyToWindow(window, design);
        }
        applyToRegisteredParentSurfaces(design);
    }

    static void applyToWindow(Window window) {
        applyToWindow(window, resolveActiveDesign());
    }

    static void applyToWindow(Window window, AppDesign design) {
        if (window == null) {
            return;
        }
        Scene scene = window.getScene();
        if (scene == null) {
            return;
        }
        applyToScene(scene, design);
        if (scene.getRoot() instanceof DialogPane dialogPane) {
            applyToDialogPane(dialogPane, design);
        }
    }

    static void applyToRegisteredParentSurfaces(AppDesign design) {
        List<Parent> surfaces;
        synchronized (REGISTERED_PARENT_SURFACES) {
            surfaces = new ArrayList<>(REGISTERED_PARENT_SURFACES);
        }
        for (Parent surface : surfaces) {
            applyToParent(surface, design);
        }
    }

    static void applyToScene(Scene scene) {
        applyToScene(scene, resolveActiveDesign());
    }

    static void applyToScene(Scene scene, AppDesign active) {
        if (scene == null) {
            return;
        }
        active = active != null ? active : AppDesign.NORMAL;
        if (hasApplicationBaseStyles(scene.getRoot())) {
            syncApplicationBaseStylesheets(scene.getStylesheets(), active);
            ThemeCssSupport.reconcileDynamicStylesheets(scene.getStylesheets(), active);
        }
        applyToStylesheets(scene.getStylesheets(), active);
        UiFontScaleSupport.applyToScene(scene);
        forceRestyle(active, scene.getRoot());
    }

    static void applyToDialogPane(DialogPane dialogPane) {
        applyToDialogPane(dialogPane, resolveActiveDesign());
    }

    static void applyToDialogPane(DialogPane dialogPane, AppDesign active) {
        if (dialogPane == null) {
            return;
        }
        active = active != null ? active : AppDesign.NORMAL;
        if (hasApplicationBaseStyles(dialogPane)) {
            syncApplicationBaseStylesheets(dialogPane.getStylesheets(), active);
            ThemeCssSupport.reconcileDynamicStylesheets(dialogPane.getStylesheets(), active);
        }
        applyToStylesheets(dialogPane.getStylesheets(), active);
        UiFontScaleSupport.applyToDialogPane(dialogPane);
        forceRestyle(active, dialogPane);
    }

    static void applyToParent(Parent parent) {
        applyToParent(parent, resolveActiveDesign());
    }

    static void applyToParent(Parent parent, AppDesign active) {
        if (parent == null) {
            return;
        }
        active = active != null ? active : AppDesign.NORMAL;
        if (hasApplicationBaseStyles(parent)) {
            syncApplicationBaseStylesheets(parent.getStylesheets(), active);
            ThemeCssSupport.reconcileDynamicStylesheets(parent.getStylesheets(), active);
        }
        applyToStylesheets(parent.getStylesheets(), active);
        UiFontScaleSupport.applyToParent(parent);
        forceRestyle(active, parent);
    }

    /**
     * Swapping the design stylesheet (remove + re-add) makes some already-skinned controls — notably
     * MenuBar buttons — fall back to the JavaFX default colour and only restyle on the next user
     * interaction (hover). For a custom design, force a CSS re-application so the design colours apply
     * deterministically right away. Skipped for NORMAL to avoid needless restyles in the common case.
     *
     * <p>Swapping the UI font scale stylesheet triggers the same stale rendering, and unlike a design
     * it also affects NORMAL — which is what most users are on. So a non-default scale forces the
     * restyle too.</p>
     */
    private static void forceRestyle(AppDesign active, Parent root) {
        if (root == null) {
            return;
        }
        if (active != AppDesign.NORMAL
            || UiFontScaleSupport.effectivePercent() != GlobalSettings.UI_FONT_SCALE_DEFAULT_PERCENT) {
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

    /**
     * Marks a Scene as one of korTTY's base-themed application surfaces. On these surfaces the
     * legacy author-level Modena stylesheet is swapped for component-only CSS while AtlantaFX is
     * active, then restored for Normal and all existing korTTY designs.
     */
    static void registerApplicationBaseStyles(Scene scene) {
        if (scene == null || scene.getRoot() == null) {
            return;
        }
        markApplicationBaseStyles(scene.getRoot());
        syncApplicationBaseStylesheets(scene.getStylesheets(), resolveActiveDesign());
    }

    /** Same as {@link #registerApplicationBaseStyles(Scene)} for DialogPane and raw Popup roots. */
    static void registerApplicationBaseStyles(Parent parent) {
        if (parent == null) {
            return;
        }
        REGISTERED_PARENT_SURFACES.add(parent);
        markApplicationBaseStyles(parent);
        syncApplicationBaseStylesheets(parent.getStylesheets(), resolveActiveDesign());
    }

    static void syncApplicationBaseStylesheets(
            ObservableList<String> stylesheets, AppDesign design) {
        if (stylesheets == null) {
            return;
        }
        String base = resolveCssUrl(APPLICATION_BASE_STYLESHEET_RESOURCE);
        String components = resolveCssUrl(ATLANTAFX_COMPONENTS_STYLESHEET_RESOURCE);
        int insertionIndex = stylesheets.size();
        if (base != null) {
            int index = stylesheets.indexOf(base);
            if (index >= 0) {
                insertionIndex = Math.min(insertionIndex, index);
            }
            stylesheets.removeIf(base::equals);
        }
        if (components != null) {
            int index = stylesheets.indexOf(components);
            if (index >= 0) {
                insertionIndex = Math.min(insertionIndex, index);
            }
            stylesheets.removeIf(components::equals);
        }

        String required = design == AppDesign.ATLANTAFX_PRIMER_DARK ? components : base;
        if (required != null) {
            stylesheets.add(Math.min(insertionIndex, stylesheets.size()), required);
        }
    }

    static String applicationBaseStylesheetUrl() {
        return resolveCssUrl(APPLICATION_BASE_STYLESHEET_RESOURCE);
    }

    static String atlantaFxComponentsStylesheetUrl() {
        return resolveCssUrl(ATLANTAFX_COMPONENTS_STYLESHEET_RESOURCE);
    }

    private static void markApplicationBaseStyles(Parent root) {
        root.getProperties().put(APPLICATION_BASE_STYLES_MARKER, Boolean.TRUE);
    }

    private static boolean hasApplicationBaseStyles(Parent root) {
        return root != null && Boolean.TRUE.equals(root.getProperties().get(APPLICATION_BASE_STYLES_MARKER));
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
