package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.model.GlobalSettings;
import javafx.collections.ObservableList;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.DialogPane;
import javafx.stage.Screen;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scales korTTY's UI chrome — menus, dialogs, labels — to a user-chosen percentage.
 *
 * <p>The scale is delivered as a tiny generated stylesheet that pins the base font size on the
 * three JavaFX subtree roots. Every other font size in the app's stylesheets and inline styles is
 * expressed in {@code em}, so it follows that base automatically. This is the mechanism JavaFX's
 * own {@code modena.css} uses, and it is why a scale change needs no tree walk: the CSS engine
 * recomputes the whole cascade from one number.</p>
 *
 * <p>Deliberately <em>not</em> scaled: the terminal canvas, the editor, and the AI chat/plan/
 * activity surfaces. Each of those has its own persisted font size and its own zoom control, and
 * each sets that size inline on the node — inline styles beat every stylesheet, so they are
 * immune to the base without needing an exclusion list here.</p>
 */
final class UiFontScaleSupport {

    /**
     * The base every {@code em} in korTTY's stylesheets is relative to.
     *
     * <p>JavaFX derives its default base from {@link javafx.scene.text.Font#getDefault()}, which is
     * 13 on macOS/Linux but 12 on Windows. Pinning it here means a converted rule reproduces its
     * original pixel value on every platform instead of silently shrinking by 8% on Windows.</p>
     */
    static final double BASE_FONT_PX = 13.0;

    /** Auto mode never shrinks the UI — it exists to fix "too small". */
    static final int AUTO_MIN_PERCENT = 100;
    /**
     * One step below the manual ceiling on purpose: an automatic decision should never land the
     * user on the most layout-stressful setting without them choosing it.
     */
    static final int AUTO_MAX_PERCENT = 140;

    /**
     * Marks a Parent that is not a Scene root but must still carry the scaled base — a raw
     * {@link javafx.stage.Popup}'s content container, which {@code .root} never matches.
     */
    static final String SCALE_ROOT_STYLE_CLASS = "kortty-ui-scale-root";

    /**
     * Opts a Scene out of scaling and pins it at 100%. Used by the master-password window, whose
     * scene layout is dispatched on an exact size comparison and whose shell is hard-clipped, so
     * growing its text would crop it.
     */
    static final String FIXED_SCALE_STYLE_CLASS = "kortty-fixed-font-scale";

    private static final Logger logger = LoggerFactory.getLogger(UiFontScaleSupport.class);

    private static final Map<Integer, String> STYLESHEET_CACHE = new ConcurrentHashMap<>();

    /** Memoised auto result, so the hundreds of per-window applications do not each hit the Screen API. */
    private static volatile Integer cachedAutoPercent;

    private UiFontScaleSupport() {
    }

    /**
     * Derives a UI font scale from a screen's <em>logical</em> height.
     *
     * <p>Logical, not physical: {@link Screen#getBounds()} is already divided by the OS scale
     * factor, so a Retina MacBook reports ~1117 and correctly stays at 100%. Feeding
     * {@code getOutputScaleX()} back in would double-scale exactly the displays the OS already
     * handled. The case this exists for is a 4K panel running at 100% OS scale, which reports the
     * full 2160.</p>
     *
     * <p>Steps rather than a continuous ratio: {@code height / 1080} maps a 27" QHD (109 PPI) to
     * 133%, which is too much for that panel.</p>
     */
    static int autoPercentForLogicalHeight(double logicalHeight) {
        if (logicalHeight < 1400) {
            return 100;
        }
        if (logicalHeight < 1800) {
            return 110;
        }
        if (logicalHeight < 2300) {
            return 125;
        }
        return AUTO_MAX_PERCENT;
    }

    /** @return the base font size in pixels for a scale percentage. */
    static double basePx(int percent) {
        return BASE_FONT_PX * GlobalSettings.clampUiFontScalePercent(percent) / 100.0;
    }

    /** @return the scale that is actually in effect, honouring the auto setting. Never throws. */
    static int effectivePercent() {
        GlobalSettings settings = currentSettings();
        if (settings == null) {
            return GlobalSettings.UI_FONT_SCALE_DEFAULT_PERCENT;
        }
        if (!settings.isUiFontScaleAuto()) {
            return settings.getUiFontScalePercent();
        }
        Integer cached = cachedAutoPercent;
        if (cached == null) {
            cached = autoPercentForPrimaryScreen();
            cachedAutoPercent = cached;
        }
        return cached;
    }

    /** Drops the memoised auto result so the next application re-reads the screen. */
    static void invalidateAutoCache() {
        cachedAutoPercent = null;
    }

    /** @return the scale the primary screen's logical height suggests, clamped to the auto range. */
    static int autoPercentForPrimaryScreen() {
        try {
            Screen primary = Screen.getPrimary();
            if (primary == null) {
                return AUTO_MIN_PERCENT;
            }
            int percent = autoPercentForLogicalHeight(primary.getBounds().getHeight());
            return Math.max(AUTO_MIN_PERCENT, Math.min(AUTO_MAX_PERCENT, percent));
        } catch (RuntimeException e) {
            // No toolkit (headless tests) or no display — fall back to the unscaled default.
            logger.debug("Could not derive the UI font scale from the primary screen: {}", e.getMessage());
            return AUTO_MIN_PERCENT;
        }
    }

    /**
     * Builds the scale stylesheet. The three selectors are JavaFX's subtree roots:
     * <ul>
     *   <li>{@code .root} — every Scene, since {@code Scene.setRoot} adds the class itself.</li>
     *   <li>{@code .dialog-pane} — a DialogPane hosted as a tab, where it is not the Scene root.</li>
     *   <li>{@code .context-menu} — popup content does not inherit the font of the MenuBar that
     *       opened it, so without this the menu titles would grow while the menu items stayed put.</li>
     * </ul>
     *
     * <p>{@code .tooltip} is deliberately absent: modena declares {@code .tooltip { -fx-font-size:
     * 0.85em }} and an author rule in px would beat it and destroy the intended relative shrink.</p>
     */
    static String buildCss(int percent) {
        String size = String.format(Locale.ROOT, "%.2fpx", basePx(percent));
        return String.join("\n",
            "/* Generated by korTTY — UI font scale " + GlobalSettings.clampUiFontScalePercent(percent) + "%. */",
            ".root, ." + SCALE_ROOT_STYLE_CLASS + " { -fx-font-size: " + size + "; }",
            ".dialog-pane { -fx-font-size: " + size + "; }",
            ".context-menu { -fx-font-size: " + size + "; }",
            "");
    }

    /**
     * Grows a hard-coded window or dialog dimension along with the UI font scale.
     *
     * <p>Several surfaces size themselves from pixel constants rather than from their content. Left
     * alone they would keep their original size while their text grew, and crowd or clip it. The
     * result is capped to the primary screen's visual bounds and never falls below {@code base}:
     * scaling up must not produce a window the display cannot show, and must not shrink anything.</p>
     *
     * @param horizontal {@code true} to cap against the screen width, {@code false} for the height
     */
    static double scaleDimension(double base, boolean horizontal) {
        double scaled = base * effectivePercent() / 100.0;
        try {
            Rectangle2D visual = Screen.getPrimary().getVisualBounds();
            double available = horizontal ? visual.getWidth() : visual.getHeight();
            return Math.max(base, Math.min(scaled, available));
        } catch (RuntimeException e) {
            // No display (headless tests) — the unscaled value is always safe.
            return base;
        }
    }

    /** @return a cached {@code file:} URL for the scale stylesheet, or {@code null} if it cannot be written. */
    static String stylesheetUrl(int percent) {
        return STYLESHEET_CACHE.computeIfAbsent(GlobalSettings.clampUiFontScalePercent(percent), resolved -> {
            try {
                Path tempCss = Files.createTempFile("kortty-uifont-", ".css");
                tempCss.toFile().deleteOnExit();
                Files.writeString(tempCss, buildCss(resolved));
                return tempCss.toUri().toString();
            } catch (Exception e) {
                logger.debug("Could not create the UI font scale stylesheet: {}", e.getMessage());
                return null;
            }
        });
    }

    /**
     * Replaces any previously applied scale stylesheet with the one for {@code percent}.
     * Removing first matters: without it, stepping 130% → 110% would leave both entries behind.
     */
    static void applyToStylesheets(ObservableList<String> stylesheets, int percent) {
        if (stylesheets == null) {
            return;
        }
        String active = stylesheetUrl(percent);
        for (String cached : STYLESHEET_CACHE.values()) {
            if (cached != null && !cached.equals(active)) {
                stylesheets.remove(cached);
            }
        }
        if (active != null && !stylesheets.contains(active)) {
            stylesheets.add(active);
        }
    }

    static void applyToScene(Scene scene) {
        if (scene == null) {
            return;
        }
        applyToStylesheets(scene.getStylesheets(), percentFor(scene.getRoot()));
    }

    static void applyToDialogPane(DialogPane dialogPane) {
        if (dialogPane == null) {
            return;
        }
        applyToStylesheets(dialogPane.getStylesheets(), percentFor(dialogPane));
    }

    /**
     * Applies the scale to a Parent that is not a Scene root — a raw Popup's content container.
     * Adds {@link #SCALE_ROOT_STYLE_CLASS} so the generated rule has something to match.
     */
    static void applyToParent(Parent parent) {
        if (parent == null) {
            return;
        }
        if (!parent.getStyleClass().contains(SCALE_ROOT_STYLE_CLASS)) {
            parent.getStyleClass().add(SCALE_ROOT_STYLE_CLASS);
        }
        applyToStylesheets(parent.getStylesheets(), percentFor(parent));
    }

    /** @return 100 for a surface that opted out via {@link #FIXED_SCALE_STYLE_CLASS}, else the active scale. */
    private static int percentFor(Parent root) {
        if (root != null && root.getStyleClass().contains(FIXED_SCALE_STYLE_CLASS)) {
            return GlobalSettings.UI_FONT_SCALE_DEFAULT_PERCENT;
        }
        return effectivePercent();
    }

    private static GlobalSettings currentSettings() {
        KorTTYApplication app = KorTTYApplication.getInstance();
        if (app == null || app.getGlobalSettingsManager() == null) {
            return null;
        }
        return app.getGlobalSettingsManager().getSettings();
    }
}
