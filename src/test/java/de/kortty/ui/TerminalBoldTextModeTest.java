package de.kortty.ui;

import com.sithtermfx.ui.settings.BoldTextMode;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.settings.UserSettingsProvider;
import de.kortty.core.TerminalPaletteSupport;
import de.kortty.core.TerminalScreenRenderer;
import de.kortty.model.ConnectionSettings;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.Properties;

import static com.google.common.truth.Truth.assertThat;

/**
 * Settings → Terminal → Bold text: the stored mode, how old settings files load, what the pane
 * provider hands SithTermFX (read live, so a saved change applies on the next repaint), the
 * recording palette and the dropdown labels. Toolkit-free; the provider is reached by reflection.
 */
public class TerminalBoldTextModeTest {

    private static final String[] LOCALES = {"", "_de", "_it", "_es", "_pt", "_fr", "_hr", "_nl"};

    private static UserSettingsProvider newProvider(ConnectionSettings settings) throws Exception {
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        Constructor<?> ctor = cls.getDeclaredConstructor(
                ConnectionSettings.class, DynamicFontSizeSettingsProvider.class, java.util.function.IntSupplier.class);
        ctor.setAccessible(true);
        return (UserSettingsProvider) ctor.newInstance(settings, new DynamicFontSizeSettingsProvider(14f),
                (java.util.function.IntSupplier) () -> 0);
    }

    private static ConnectionSettings unmarshal(String xml) throws Exception {
        return (ConnectionSettings) JAXBContext.newInstance(ConnectionSettings.class)
                .createUnmarshaller().unmarshal(new StringReader(xml));
    }

    @Test
    public void aFreshSettingsObjectDrawsBoldTextInABoldFontAsBefore() {
        ConnectionSettings settings = new ConnectionSettings();
        assertThat(settings.getBoldTextMode()).isEqualTo("BOLD_FONT");
        assertThat(TerminalPaletteSupport.boldTextMode(settings)).isEqualTo(BoldTextMode.BOLD_FONT);
        assertThat(TerminalPaletteSupport.boldTextMode(null)).isEqualTo(BoldTextMode.BOLD_FONT);
    }

    @Test
    public void unknownOrEmptyStoredValuesFallBackToTheBoldFont() {
        ConnectionSettings settings = new ConnectionSettings();
        for (String stored : new String[] {null, "", "  ", "BRIGHT", "true"}) {
            settings.setBoldTextMode(stored);
            assertThat(TerminalPaletteSupport.boldTextMode(settings)).isEqualTo(BoldTextMode.BOLD_FONT);
        }
        settings.setBoldTextMode(" bright_color ");
        assertThat(TerminalPaletteSupport.boldTextMode(settings)).isEqualTo(BoldTextMode.BRIGHT_COLOR);
    }

    @Test
    public void anOldSettingsFileLoadsAsBoldFontEvenWithTheOldBoldAsBrightFlag() throws Exception {
        ConnectionSettings old = unmarshal("<settings><fontSize>15</fontSize>"
                + "<boldAsBright>true</boldAsBright></settings>");
        assertThat(old.getFontSize()).isEqualTo(15);
        assertThat(TerminalPaletteSupport.boldTextMode(old)).isEqualTo(BoldTextMode.BOLD_FONT);
    }

    @Test
    public void theModeSurvivesASaveAndReloadByItsEnumName() throws Exception {
        ConnectionSettings settings = new ConnectionSettings();
        settings.setBoldTextMode(BoldTextMode.BOLD_FONT_AND_BRIGHT_COLOR.name());
        Marshaller marshaller = JAXBContext.newInstance(ConnectionSettings.class).createMarshaller();
        StringWriter xml = new StringWriter();
        marshaller.marshal(settings, xml);

        assertThat(xml.toString()).contains("<boldTextMode>BOLD_FONT_AND_BRIGHT_COLOR</boldTextMode>");
        assertThat(TerminalPaletteSupport.boldTextMode(unmarshal(xml.toString())))
                .isEqualTo(BoldTextMode.BOLD_FONT_AND_BRIGHT_COLOR);
    }

    @Test
    public void theCopiesUsedByOpenTerminalsCarryTheMode() {
        ConnectionSettings source = new ConnectionSettings();
        source.setBoldTextMode(BoldTextMode.BRIGHT_COLOR.name());

        assertThat(new ConnectionSettings(source).getBoldTextMode()).isEqualTo("BRIGHT_COLOR");
        ConnectionSettings pane = new ConnectionSettings();
        pane.copyTerminalPaletteFrom(source);
        assertThat(pane.getBoldTextMode()).isEqualTo("BRIGHT_COLOR");
    }

    @Test
    public void theProviderReadsTheModeOnEveryCallSoASavedChangeAppliesLive() throws Exception {
        ConnectionSettings settings = new ConnectionSettings();
        UserSettingsProvider provider = newProvider(settings);
        assertThat(provider.getBoldTextMode()).isEqualTo(BoldTextMode.BOLD_FONT);

        // TerminalView.applySettings copies the saved mode into the settings object every pane shares.
        ConnectionSettings saved = new ConnectionSettings();
        saved.setBoldTextMode(BoldTextMode.BRIGHT_COLOR.name());
        settings.copyTerminalPaletteFrom(saved);
        assertThat(provider.getBoldTextMode()).isEqualTo(BoldTextMode.BRIGHT_COLOR);

        settings.setBoldTextMode("garbage");
        assertThat(provider.getBoldTextMode()).isEqualTo(BoldTextMode.BOLD_FONT);
    }

    @Test
    public void recordingsFollowTheSameMode() {
        ConnectionSettings settings = new ConnectionSettings();
        TerminalScreenRenderer.Palette palette = TerminalPaletteSupport.recordingPalette(settings);
        assertThat(palette.boldAsBright()).isFalse();
        assertThat(palette.boldFont()).isTrue();

        settings.setBoldTextMode(BoldTextMode.BRIGHT_COLOR.name());
        palette = TerminalPaletteSupport.recordingPalette(settings);
        assertThat(palette.boldAsBright()).isTrue();
        assertThat(palette.boldFont()).isFalse();

        settings.setBoldTextMode(BoldTextMode.BOLD_FONT_AND_BRIGHT_COLOR.name());
        palette = TerminalPaletteSupport.recordingPalette(settings);
        assertThat(palette.boldAsBright()).isTrue();
        assertThat(palette.boldFont()).isTrue();

        assertThat(TerminalPaletteSupport.recordingPalette(null)).isSameInstanceAs(TerminalScreenRenderer.Palette.DEFAULT);
    }

    @Test
    public void everyModeHasALabelInEveryBundle() throws Exception {
        for (String locale : LOCALES) {
            Properties bundle = new Properties();
            try (InputStream in = TerminalBoldTextModeTest.class.getResourceAsStream(
                    "/i18n/messages" + locale + ".properties")) {
                assertThat(in).isNotNull();
                bundle.load(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
            }
            assertThat(bundle.getProperty("settings.terminal.boldText")).isNotEmpty();
            assertThat(bundle.getProperty("settings.terminal.boldText.tooltip")).isNotEmpty();
            for (BoldTextMode mode : BoldTextMode.values()) {
                assertThat(bundle.getProperty(SettingsDialog.boldTextModeKey(mode))).isNotEmpty();
            }
            assertThat(bundle.getProperty("settings.terminal.boldAsBright")).isNull();
        }
    }
}
