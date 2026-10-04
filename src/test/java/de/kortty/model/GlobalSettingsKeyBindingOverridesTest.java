package de.kortty.model;

import de.kortty.core.KeyChord;
import de.kortty.core.KeymapOverrides;
import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The user's shortcut overrides survive the round trip through {@code global-settings.xml}; a file
 * written before they existed keeps every default, and a hand-edited entry korTTY cannot use is
 * skipped instead of failing the load.
 */
class GlobalSettingsKeyBindingOverridesTest {

    @Test
    void noOverridesByDefault() {
        assertThat(new GlobalSettings().getKeyBindingOverrides()).isEmpty();
        GlobalSettings settings = new GlobalSettings();
        settings.setKeyBindingOverrides(null);
        assertThat(settings.getKeyBindingOverrides()).isEmpty();
    }

    @Test
    void theOverridesSurviveTheRoundTripUnderTheirDocumentedNames() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setKeyBindingOverrides(KeymapOverrides.empty()
            .with("menu.view.commandPalette", KeyChord.parse("Shortcut+Alt+P"))
            .with("menu.file.newTab", null)
            .toEntries());

        String xml = marshal(settings);
        assertThat(xml).contains("<keyBindingOverrides>");
        assertThat(xml).contains("<binding>menu.view.commandPalette=Shortcut+Alt+P</binding>");
        assertThat(xml).contains("<binding>menu.file.newTab=none</binding>");

        KeymapOverrides back = KeymapOverrides.parse(unmarshal(xml).getKeyBindingOverrides());
        assertThat(back.chord("menu.view.commandPalette")).isEqualTo(KeyChord.parse("Shortcut+Alt+P"));
        assertThat(back.isUnbound("menu.file.newTab")).isTrue();
    }

    @Test
    void aSettingsFileFromBeforeTheOverridesExistedKeepsEveryDefault() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><showMenuBar>true</showMenuBar></globalSettings>");

        assertThat(settings.getKeyBindingOverrides()).isEmpty();
        assertThat(KeymapOverrides.parse(settings.getKeyBindingOverrides()).isEmpty()).isTrue();
    }

    @Test
    void aHandEditedEntryKorttyCannotUseIsSkipped() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><keyBindingOverrides>"
            + "<binding>menu.view.menuBar=Shortcut+Banana</binding>"
            + "<binding>garbage</binding>"
            + "<binding>menu.fromANewerVersion=Shortcut+Alt+Q</binding>"
            + "</keyBindingOverrides></globalSettings>");

        assertThat(settings.getKeyBindingOverrides()).hasSize(3);
        assertThat(KeymapOverrides.parse(settings.getKeyBindingOverrides()).toEntries())
            .containsExactly("menu.fromANewerVersion=Shortcut+Alt+Q");
    }

    @Test
    void theSetterCopies() {
        List<String> entries = new java.util.ArrayList<>(List.of("menu.file.newTab=none"));
        GlobalSettings settings = new GlobalSettings();
        settings.setKeyBindingOverrides(entries);
        entries.clear();

        assertThat(settings.getKeyBindingOverrides()).containsExactly("menu.file.newTab=none");
    }

    private static String marshal(GlobalSettings settings) throws Exception {
        StringWriter writer = new StringWriter();
        JAXBContext.newInstance(GlobalSettings.class).createMarshaller().marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class)
            .createUnmarshaller().unmarshal(new StringReader(xml));
    }
}
