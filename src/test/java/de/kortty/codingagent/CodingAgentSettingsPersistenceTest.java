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

/** The coding-agent settings default as documented and survive the global-settings round trip. */
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

    @Test
    void stageTwoSettingsDefaultToOnHiddenAndDefaultWidth() {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.isCodingAgentNotificationsEnabled()).isTrue();
        assertThat(settings.isCodingAgentAppBadgeEnabled()).isTrue();
        assertThat(settings.getCodingAgentPanelPlacement()).isEqualTo("HIDDEN");
        assertThat(settings.getCodingAgentPanelWidth()).isEqualTo(CodingAgentPanelDefaults.DEFAULT_WIDTH);
        assertThat(GlobalSettings.CODING_AGENT_PANEL_MIN_WIDTH).isEqualTo(CodingAgentPanelDefaults.MIN_WIDTH);
        assertThat(GlobalSettings.CODING_AGENT_PANEL_MAX_WIDTH).isEqualTo(CodingAgentPanelDefaults.MAX_WIDTH);
        assertThat(GlobalSettings.CODING_AGENT_PANEL_DEFAULT_WIDTH).isEqualTo(CodingAgentPanelDefaults.DEFAULT_WIDTH);
    }

    @Test
    void stageTwoSettingsSurviveTheJaxbRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setCodingAgentNotificationsEnabled(false);
        settings.setCodingAgentAppBadgeEnabled(false);
        settings.setCodingAgentPanelPlacement("LEFT");
        settings.setCodingAgentPanelWidth(512.0);

        GlobalSettings restored = roundTrip(settings);
        assertThat(restored.isCodingAgentNotificationsEnabled()).isFalse();
        assertThat(restored.isCodingAgentAppBadgeEnabled()).isFalse();
        assertThat(restored.getCodingAgentPanelPlacement()).isEqualTo("LEFT");
        assertThat(restored.getCodingAgentPanelWidth()).isEqualTo(512.0);
    }

    @Test
    void panelWidthIsClampedOnWriteAndRead() {
        GlobalSettings settings = new GlobalSettings();
        settings.setCodingAgentPanelWidth(10.0);
        assertThat(settings.getCodingAgentPanelWidth()).isEqualTo(CodingAgentPanelDefaults.MIN_WIDTH);
        settings.setCodingAgentPanelWidth(99_999.0);
        assertThat(settings.getCodingAgentPanelWidth()).isEqualTo(CodingAgentPanelDefaults.MAX_WIDTH);
        settings.setCodingAgentPanelWidth(Double.NaN);
        assertThat(settings.getCodingAgentPanelWidth()).isEqualTo(CodingAgentPanelDefaults.DEFAULT_WIDTH);
        settings.setCodingAgentPanelPlacement(null);
        assertThat(settings.getCodingAgentPanelPlacement()).isEqualTo("HIDDEN");
    }

    private static GlobalSettings roundTrip(boolean enabled) throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setCodingAgentDetectionEnabled(enabled);
        return roundTrip(settings);
    }

    private static GlobalSettings roundTrip(GlobalSettings settings) throws Exception {
        JAXBContext context = JAXBContext.newInstance(GlobalSettings.class);
        StringWriter writer = new StringWriter();
        context.createMarshaller().marshal(settings, writer);
        return (GlobalSettings) context.createUnmarshaller().unmarshal(new StringReader(writer.toString()));
    }
}
