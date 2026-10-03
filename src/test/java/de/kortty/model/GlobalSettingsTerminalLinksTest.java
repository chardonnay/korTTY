package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Plain-text link detection is on out of the box: it only lets a Cmd/Ctrl+click open a web or
 * e-mail address and costs nothing while output arrives. Settings files from before the setting
 * existed must load with it on as well, and switching it off must survive a save.
 */
public class GlobalSettingsTerminalLinksTest {

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
    public void linkDetectionIsOnByDefault() {
        assertThat(new GlobalSettings().isTerminalLinkDetectionEnabled()).isTrue();
    }

    @Test
    public void theSetterRoundTripsInMemory() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalLinkDetectionEnabled(false);
        assertThat(settings.isTerminalLinkDetectionEnabled()).isFalse();
        settings.setTerminalLinkDetectionEnabled(true);
        assertThat(settings.isTerminalLinkDetectionEnabled()).isTrue();
    }

    @Test
    public void bothValuesSurviveAnXmlRoundTrip() throws Exception {
        GlobalSettings off = new GlobalSettings();
        off.setTerminalLinkDetectionEnabled(false);
        assertThat(marshal(off)).contains("<terminalLinkDetectionEnabled>false</terminalLinkDetectionEnabled>");
        assertThat(unmarshal(marshal(off)).isTerminalLinkDetectionEnabled()).isFalse();

        GlobalSettings on = new GlobalSettings();
        assertThat(marshal(on)).contains("<terminalLinkDetectionEnabled>true</terminalLinkDetectionEnabled>");
        assertThat(unmarshal(marshal(on)).isTerminalLinkDetectionEnabled()).isTrue();
    }

    @Test
    public void settingsWrittenBeforeTheSettingExistedLoadWithDetectionOn() throws Exception {
        GlobalSettings restored = unmarshal("<globalSettings><terminalCopyOnSelectEnabled>false"
            + "</terminalCopyOnSelectEnabled></globalSettings>");
        assertWithMessage("an absent element keeps the default").that(restored.isTerminalLinkDetectionEnabled())
            .isTrue();
        assertThat(restored.isTerminalCopyOnSelectEnabled()).isFalse();
    }
}
