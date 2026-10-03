package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.paste.PasteProtectionSettings;
import de.kortty.paste.PasteWarningMode;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import java.io.StringReader;
import java.io.StringWriter;
import org.testng.annotations.Test;

/**
 * The two paste protection settings: what a fresh installation and an old settings file use, that
 * both survive the XML round trip, and that a damaged value never switches protection off.
 */
class GlobalSettingsPasteProtectionTest {

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
    void aFreshInstallationWarnsUnlessBracketedAndAbove5KiB() {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.getPasteWarningMode()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(settings.getPasteLargeWarningKiB()).isEqualTo(5);
        assertThat(PasteProtectionSettings.from(settings)).isEqualTo(PasteProtectionSettings.DEFAULTS);
    }

    @Test
    void settingsWrittenBeforeTheFeatureExistedKeepTheDefaults() throws Exception {
        GlobalSettings restored = unmarshal("<globalSettings></globalSettings>");
        assertWithMessage("an upgrade must not leave paste protection off")
            .that(restored.getPasteWarningMode()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(restored.getPasteLargeWarningKiB()).isEqualTo(5);
    }

    @Test
    void everyModeAndTheThresholdSurviveAnXmlRoundTrip() throws Exception {
        for (PasteWarningMode mode : PasteWarningMode.values()) {
            GlobalSettings settings = new GlobalSettings();
            settings.setPasteWarningMode(mode);
            settings.setPasteLargeWarningKiB(64);
            String xml = marshal(settings);
            assertThat(xml).contains("<pasteWarningMode>" + mode.id() + "</pasteWarningMode>");
            assertThat(xml).contains("<pasteLargeWarningKiB>64</pasteLargeWarningKiB>");

            GlobalSettings restored = unmarshal(xml);
            assertThat(restored.getPasteWarningMode()).isEqualTo(mode);
            assertThat(restored.getPasteLargeWarningKiB()).isEqualTo(64);
        }
    }

    @Test
    void zeroTurnsTheSizeCheckOffAndSurvivesTheRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteLargeWarningKiB(0);
        assertThat(unmarshal(marshal(settings)).getPasteLargeWarningKiB()).isEqualTo(0);
        assertThat(PasteProtectionSettings.from(settings).largeWarningEnabled()).isFalse();
    }

    @Test
    void anUnknownOrBlankModeMeansUnlessBracketed() throws Exception {
        for (String stored : new String[] {"sometimes", "", "  ", "OFFF"}) {
            GlobalSettings restored = unmarshal(
                "<globalSettings><pasteWarningMode>" + stored + "</pasteWarningMode></globalSettings>");
            assertWithMessage("stored value \"%s\"", stored)
                .that(restored.getPasteWarningMode()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        }
        GlobalSettings upperCase = unmarshal("<globalSettings><pasteWarningMode> ALWAYS </pasteWarningMode></globalSettings>");
        assertThat(upperCase.getPasteWarningMode()).isEqualTo(PasteWarningMode.ALWAYS);
    }

    @Test
    void settingNoModeStoresTheDefault() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteWarningMode(PasteWarningMode.OFF);
        settings.setPasteWarningMode(null);
        assertThat(settings.getPasteWarningMode()).isEqualTo(PasteWarningMode.UNLESS_BRACKETED);
        assertThat(marshal(settings)).contains("<pasteWarningMode>unless-bracketed</pasteWarningMode>");
    }

    @Test
    void theThresholdIsClampedTo0Through10240() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setPasteLargeWarningKiB(-3);
        assertThat(settings.getPasteLargeWarningKiB()).isEqualTo(0);
        settings.setPasteLargeWarningKiB(10_241);
        assertThat(settings.getPasteLargeWarningKiB()).isEqualTo(10_240);

        GlobalSettings tooLarge = unmarshal(
            "<globalSettings><pasteLargeWarningKiB>999999</pasteLargeWarningKiB></globalSettings>");
        assertThat(tooLarge.getPasteLargeWarningKiB()).isEqualTo(10_240);
        GlobalSettings negative = unmarshal(
            "<globalSettings><pasteLargeWarningKiB>-1</pasteLargeWarningKiB></globalSettings>");
        assertThat(negative.getPasteLargeWarningKiB()).isEqualTo(0);
    }
}
