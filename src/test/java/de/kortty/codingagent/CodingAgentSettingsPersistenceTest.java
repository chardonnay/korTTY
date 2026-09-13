package de.kortty.codingagent;

import de.kortty.core.GlobalSettingsManager;
import de.kortty.model.GlobalSettings;
import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;

/** The coding-agent detection toggle defaults to on and survives the global-settings round trip. */
class CodingAgentSettingsPersistenceTest {

    @Test
    void detectionIsEnabledByDefaultAndPersistsWhenSwitchedOff() throws Exception {
        Path dir = Files.createTempDirectory("kortty-coding-agent-settings");
        try {
            GlobalSettingsManager manager = new GlobalSettingsManager(dir);
            assertThat(manager.getSettings().isCodingAgentDetectionEnabled()).isTrue();

            manager.getSettings().setCodingAgentDetectionEnabled(false);
            manager.save();

            GlobalSettingsManager reloaded = new GlobalSettingsManager(dir);
            reloaded.load();
            assertThat(reloaded.getSettings().isCodingAgentDetectionEnabled()).isFalse();
        } finally {
            Files.deleteIfExists(dir.resolve("global-settings.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void jaxbRoundTripKeepsBothValues() throws Exception {
        assertThat(roundTrip(true).isCodingAgentDetectionEnabled()).isTrue();
        assertThat(roundTrip(false).isCodingAgentDetectionEnabled()).isFalse();
    }

    private static GlobalSettings roundTrip(boolean enabled) throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setCodingAgentDetectionEnabled(enabled);
        JAXBContext context = JAXBContext.newInstance(GlobalSettings.class);
        StringWriter writer = new StringWriter();
        context.createMarshaller().marshal(settings, writer);
        return (GlobalSettings) context.createUnmarshaller().unmarshal(new StringReader(writer.toString()));
    }
}
