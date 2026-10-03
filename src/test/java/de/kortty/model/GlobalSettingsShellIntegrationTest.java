package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.io.StringWriter;
import org.testng.annotations.Test;

/**
 * The shell integration switch: on for a fresh installation and for a settings file written before
 * it existed, and it survives the XML round trip.
 */
class GlobalSettingsShellIntegrationTest {

    private static String marshal(GlobalSettings settings) throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(GlobalSettings.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter writer = new StringWriter();
        marshaller.marshal(settings, writer);
        return writer.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        Unmarshaller unmarshaller = JAXBContext.newInstance(GlobalSettings.class).createUnmarshaller();
        return (GlobalSettings) unmarshaller.unmarshal(new StringReader(xml));
    }

    @Test
    void shellIntegrationIsOnByDefault() {
        assertWithMessage("it acts only on marks a shell sends, so it changes nothing without them")
            .that(new GlobalSettings().isShellIntegrationEnabled()).isTrue();
    }

    @Test
    void settingsWrittenBeforeTheFeatureExistedTurnItOn() throws Exception {
        assertThat(unmarshal("<globalSettings></globalSettings>").isShellIntegrationEnabled()).isTrue();
    }

    @Test
    void theChoiceSurvivesAnXmlRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setShellIntegrationEnabled(false);
        String xml = marshal(settings);
        assertThat(xml).contains("<shellIntegrationEnabled>false</shellIntegrationEnabled>");
        assertThat(unmarshal(xml).isShellIntegrationEnabled()).isFalse();

        settings.setShellIntegrationEnabled(true);
        assertThat(unmarshal(marshal(settings)).isShellIntegrationEnabled()).isTrue();
    }

    @Test
    void theOldAiPromptMarkerSettingIsStillRead() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><defaultPromptHookEnabled>false</defaultPromptHookEnabled></globalSettings>");
        assertThat(settings.isDefaultPromptHookEnabled()).isFalse();
        assertWithMessage("the old AI setting does not switch shell integration off")
            .that(settings.isShellIntegrationEnabled()).isTrue();
    }
}
