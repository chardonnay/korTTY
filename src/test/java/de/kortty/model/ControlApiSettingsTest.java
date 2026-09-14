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
 * The control API's persisted switch: default off, and a faithful XML round trip in both positions.
 *
 * <p>The default is the whole security story of the feature — {@code PolicyClamp} early-returns when
 * no policy file is installed, so nothing but this field initialiser keeps the socket closed on a
 * machine without enterprise policy.
 */
class ControlApiSettingsTest {

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
    void theControlApiIsOffOutOfTheBox() {
        assertWithMessage("the only default-off toggle in this group: it hands a local program the "
            + "ability to read and type into every pane")
            .that(new GlobalSettings().isControlApiEnabled())
            .isFalse();
    }

    @Test
    void theSetterRoundTripsInMemory() {
        GlobalSettings settings = new GlobalSettings();
        settings.setControlApiEnabled(true);
        assertThat(settings.isControlApiEnabled()).isTrue();
        settings.setControlApiEnabled(false);
        assertThat(settings.isControlApiEnabled()).isFalse();
    }

    @Test
    void bothValuesSurviveAnXmlRoundTrip() throws Exception {
        GlobalSettings enabled = new GlobalSettings();
        enabled.setControlApiEnabled(true);
        assertThat(marshal(enabled)).contains("<controlApiEnabled>true</controlApiEnabled>");
        assertThat(unmarshal(marshal(enabled)).isControlApiEnabled()).isTrue();

        GlobalSettings disabled = new GlobalSettings();
        assertThat(marshal(disabled)).contains("<controlApiEnabled>false</controlApiEnabled>");
        assertThat(unmarshal(marshal(disabled)).isControlApiEnabled()).isFalse();
    }

    @Test
    void settingsWrittenBeforeTheFeatureExistedUnmarshalToOff() throws Exception {
        // The element is absent from every global-settings.xml written by an older korTTY; an upgrade
        // must not silently open the socket.
        GlobalSettings restored = unmarshal("<globalSettings></globalSettings>");
        assertWithMessage("an absent element must not be read as 'enabled'")
            .that(restored.isControlApiEnabled())
            .isFalse();
    }
}
