package de.kortty.ui;

import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.settings.SettingsProvider;
import de.kortty.model.ConnectionSettings;
import javafx.scene.paint.Color;
import org.testng.annotations.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import static com.google.common.truth.Truth.assertThat;

/**
 * Verifies the heart of the per-pane terminal-effects feature: {@code TerminalView.KorTTYSettingsProvider}
 * is now per pane, carrying a nullable appearance override while delegating font size to a single shared
 * source. This is what lets one split pane show an effect's colors/font while its siblings stay at the
 * connection baseline, and keeps global zoom tab-wide.
 *
 * <p>The provider and its override record are private nested types of {@link TerminalView}, so they are
 * reached by reflection. Only {@code getDefaultForeground()} / {@code getFontSize()} are exercised — both
 * are toolkit-free, so this runs in the normal {@code test} task (no JavaFX bootstrap needed).
 */
public class PerPaneSettingsProviderTest {

    private static final String BASELINE_FG = "#FFFFFF";
    private static final String MOTHER_FG = "#19FF4C";

    private static ConnectionSettings baselineSettings() {
        ConnectionSettings s = new ConnectionSettings();
        s.setForegroundColor(BASELINE_FG);
        s.setBackgroundColor("#000000");
        s.setFontFamily("Monospaced");
        s.setFontSize(14);
        s.setCursorStyle("BLOCK");
        return s;
    }

    private static Object newProvider(ConnectionSettings settings, DynamicFontSizeSettingsProvider shared) throws Exception {
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        Constructor<?> ctor = cls.getDeclaredConstructor(ConnectionSettings.class, DynamicFontSizeSettingsProvider.class);
        ctor.setAccessible(true);
        return ctor.newInstance(settings, shared);
    }

    /** Builds a PaneAppearanceOverride; nulls mean "inherit baseline". */
    private static Object newOverride(String fg, String bg, String cursorColor,
                                      String cursorStyle, String fontFamily, Float fontSize) throws Exception {
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$PaneAppearanceOverride");
        Constructor<?> ctor = cls.getDeclaredConstructor(
                String.class, String.class, String.class, String.class, String.class, Float.class);
        ctor.setAccessible(true);
        return ctor.newInstance(fg, bg, cursorColor, cursorStyle, fontFamily, fontSize);
    }

    private static void setOverride(Object provider, Object override) throws Exception {
        Class<?> overrideCls = Class.forName("de.kortty.ui.TerminalView$PaneAppearanceOverride");
        Method m = provider.getClass().getDeclaredMethod("setOverride", overrideCls);
        m.setAccessible(true);
        m.invoke(provider, override);
    }

    private static TerminalColor fg(Object provider) {
        return ((SettingsProvider) provider).getDefaultForeground();
    }

    private static float fontSize(Object provider) {
        return ((SettingsProvider) provider).getTerminalFontSize();
    }

    /** Builds the expected default foreground with the SAME truncation the provider uses. */
    private static TerminalColor expectedFg(String web) {
        Color c = Color.web(web);
        return TerminalColor.rgb((int) (c.getRed() * 255), (int) (c.getGreen() * 255), (int) (c.getBlue() * 255));
    }

    @Test
    void effectOverrideConfinesForegroundToItsOwnPane() throws Exception {
        DynamicFontSizeSettingsProvider shared = new DynamicFontSizeSettingsProvider(14f);
        Object paneA = newProvider(baselineSettings(), shared);
        Object paneB = newProvider(baselineSettings(), shared);

        // paneA gets a MU/TH/UR-like override; paneB stays untouched.
        setOverride(paneA, newOverride(MOTHER_FG, null, null, null, "Monospaced", null));

        assertThat(fg(paneA)).isEqualTo(expectedFg(MOTHER_FG));   // effect pane recolored
        assertThat(fg(paneB)).isEqualTo(expectedFg(BASELINE_FG)); // sibling stays baseline
    }

    @Test
    void clearingOverrideRestoresBaselineForeground() throws Exception {
        DynamicFontSizeSettingsProvider shared = new DynamicFontSizeSettingsProvider(14f);
        Object pane = newProvider(baselineSettings(), shared);

        setOverride(pane, newOverride(MOTHER_FG, null, null, null, null, null));
        assertThat(fg(pane)).isEqualTo(expectedFg(MOTHER_FG));

        setOverride(pane, null); // effect stopped
        assertThat(fg(pane)).isEqualTo(expectedFg(BASELINE_FG));
    }

    @Test
    void sharedZoomAffectsAllPanesWhenOverrideDoesNotPinFontSize() throws Exception {
        DynamicFontSizeSettingsProvider shared = new DynamicFontSizeSettingsProvider(14f);
        Object paneA = newProvider(baselineSettings(), shared);
        Object paneB = newProvider(baselineSettings(), shared);
        // Mother sets font family but leaves size null, so it must keep tracking the shared size.
        setOverride(paneA, newOverride(MOTHER_FG, null, null, null, "Monospaced", null));

        assertThat(fontSize(paneA)).isEqualTo(14f);
        assertThat(fontSize(paneB)).isEqualTo(14f);

        shared.setFontSize(22f); // global zoom

        assertThat(fontSize(paneA)).isEqualTo(22f);
        assertThat(fontSize(paneB)).isEqualTo(22f);
    }

    @Test
    void overrideCanPinFontSizeToItsOwnPaneRegardlessOfZoom() throws Exception {
        DynamicFontSizeSettingsProvider shared = new DynamicFontSizeSettingsProvider(14f);
        Object pinned = newProvider(baselineSettings(), shared);
        Object tracking = newProvider(baselineSettings(), shared);
        setOverride(pinned, newOverride(null, null, null, null, null, 30f)); // pins 30pt

        assertThat(fontSize(pinned)).isEqualTo(30f);
        assertThat(fontSize(tracking)).isEqualTo(14f);

        shared.setFontSize(9f);

        assertThat(fontSize(pinned)).isEqualTo(30f); // pinned pane ignores zoom
        assertThat(fontSize(tracking)).isEqualTo(9f); // tracking pane follows zoom
    }
}
