package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The folder (group) tab colors survive the round trip through {@code global-settings.xml} under their
 * documented names; a file written before they existed, and a fresh installation, have no colors at
 * all (there are no default colors), and a hand-edited entry korTTY cannot use is skipped.
 */
class GlobalSettingsConnectionGroupColorsTest {

    @Test
    void noFolderHasAColorByDefault() {
        assertThat(new GlobalSettings().getConnectionGroupColors()).isEmpty();
        assertThat(GlobalSettings.forFreshInstall().getConnectionGroupColors()).isEmpty();
        assertThat(new GlobalSettings().getConnectionGroupColor("Production")).isNull();
    }

    @Test
    void theColorsSurviveTheRoundTripUnderTheirDocumentedNames() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setConnectionGroupColor("Work/Production", "#d32f2f");
        settings.setConnectionGroupColor("Lab", "#7B1FA2");

        String xml = marshal(settings);
        assertThat(xml).contains("<connectionGroupColors>");
        assertThat(xml).contains("<group path=\"Work/Production\" color=\"#D32F2F\"/>");
        assertThat(xml).contains("<group path=\"Lab\" color=\"#7B1FA2\"/>");

        GlobalSettings back = unmarshal(xml);
        assertThat(back.getConnectionGroupColors()).containsExactly("Work/Production", "#D32F2F", "Lab", "#7B1FA2");
    }

    @Test
    void settingsWithoutColorsKeepTheirOldForm() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setConnectionGroupColor("Production", "#D32F2F");
        settings.setConnectionGroupColor("Production", null);

        assertThat(marshal(settings)).doesNotContain("connectionGroupColors");
        assertThat(marshal(new GlobalSettings())).doesNotContain("connectionGroupColors");
    }

    @Test
    void aSettingsFileFromBeforeTheColorsExistedLoadsWithoutAny() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><showMenuBar>true</showMenuBar></globalSettings>");

        assertThat(settings.getConnectionGroupColors()).isEmpty();
    }

    @Test
    void theSettingsManagerReadsAndWritesThem() throws Exception {
        Path dir = Files.createTempDirectory("kortty-group-colors");
        try {
            de.kortty.core.GlobalSettingsManager manager = new de.kortty.core.GlobalSettingsManager(dir);
            manager.getSettings().setConnectionGroupColor("Production", "#D32F2F");
            manager.save();

            de.kortty.core.GlobalSettingsManager reloaded = new de.kortty.core.GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().getConnectionGroupColors()).containsExactly("Production", "#D32F2F");
        } finally {
            try (var files = Files.walk(dir)) {
                files.sorted(java.util.Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
            }
        }
    }

    @Test
    void aHandEditedEntryKorttyCannotUseIsSkipped() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><connectionGroupColors>"
                + "<group path=\" Work / Production \" color=\"#d32f2f\"/>"
                + "<group path=\"Spoofed\" color=\"red; -fx-background-color: #8B0000\"/>"
                + "<group path=\" / \" color=\"#388E3C\"/>"
                + "<group color=\"#388E3C\"/>"
                + "<group path=\"NoColor\"/>"
                + "</connectionGroupColors></globalSettings>");

        assertThat(settings.getConnectionGroupColors()).containsExactly("Work/Production", "#D32F2F");
        assertThat(settings.getConnectionGroupColor("Work/Production")).isEqualTo("#D32F2F");
        assertThat(settings.getConnectionGroupColor("Spoofed")).isNull();
    }

    @Test
    void settingAndRemovingAColorAffectsOnlyThatFolder() {
        GlobalSettings settings = new GlobalSettings();
        settings.setConnectionGroupColor("Production", "#D32F2F");
        settings.setConnectionGroupColor("Production/DB", "#7B1FA2");

        assertWithMessage("the getter returns the folder's own color, not one from a folder above")
                .that(settings.getConnectionGroupColor("Production/Web")).isNull();
        assertThat(settings.getConnectionGroupColor(" Production / DB ")).isEqualTo("#7B1FA2");

        settings.setConnectionGroupColor("Production/DB", "not a color");
        assertThat(settings.getConnectionGroupColors()).containsExactly("Production", "#D32F2F");

        settings.setConnectionGroupColor("  ", "#388E3C");
        assertWithMessage("a blank path is no folder")
                .that(settings.getConnectionGroupColors()).containsExactly("Production", "#D32F2F");
    }

    @Test
    void theGetterAndTheSetterCopy() {
        GlobalSettings settings = new GlobalSettings();
        Map<String, String> colors = new LinkedHashMap<>(Map.of("Production", "#D32F2F"));
        settings.setConnectionGroupColors(colors);
        colors.clear();
        settings.getConnectionGroupColors().clear();

        assertThat(settings.getConnectionGroupColors()).containsExactly("Production", "#D32F2F");

        settings.setConnectionGroupColors(null);
        assertThat(settings.getConnectionGroupColors()).isEmpty();
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
