package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The tab settings of the Window settings tab default as documented and survive the round trip
 * through {@code global-settings.xml}, including a file written before they existed.
 */
class GlobalSettingsTabSettingsTest {

    @Test
    void theConnectionColorFrameIsOnByDefault() {
        assertWithMessage("only connections with a tab color get a frame, so it can default to on")
                .that(new GlobalSettings().isConnectionColorBorderEnabled()).isTrue();
    }

    @Test
    void theConnectionColorFrameSurvivesTheRoundTripInBothStates() throws Exception {
        GlobalSettings off = new GlobalSettings();
        off.setConnectionColorBorderEnabled(false);
        assertThat(roundTrip(off).isConnectionColorBorderEnabled()).isFalse();

        GlobalSettings on = new GlobalSettings();
        on.setConnectionColorBorderEnabled(true);
        assertThat(roundTrip(on).isConnectionColorBorderEnabled()).isTrue();
    }

    @Test
    void theSettingIsWrittenUnderItsDocumentedName() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setConnectionColorBorderEnabled(false);

        assertThat(marshal(settings)).contains("<connectionColorBorderEnabled>false</connectionColorBorderEnabled>");
    }

    @Test
    void aSettingsFileFromBeforeTheFrameExistedKeepsItOn() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><showMenuBar>true</showMenuBar></globalSettings>");

        assertThat(settings.isConnectionColorBorderEnabled()).isTrue();
    }

    @Test
    void theShellTitleIsOnByDefaultAndSurvivesTheRoundTrip() throws Exception {
        assertWithMessage("tabs follow the title the shell sets unless the user switches it off")
                .that(new GlobalSettings().isTabTitleFromShellEnabled()).isTrue();

        GlobalSettings off = new GlobalSettings();
        off.setTabTitleFromShellEnabled(false);
        assertThat(marshal(off)).contains("<tabTitleFromShellEnabled>false</tabTitleFromShellEnabled>");
        assertThat(roundTrip(off).isTabTitleFromShellEnabled()).isFalse();

        GlobalSettings on = new GlobalSettings();
        on.setTabTitleFromShellEnabled(true);
        assertThat(roundTrip(on).isTabTitleFromShellEnabled()).isTrue();
    }

    @Test
    void aSettingsFileFromBeforeTheShellTitleExistedKeepsItOn() throws Exception {
        GlobalSettings settings = unmarshal("<globalSettings><showMenuBar>true</showMenuBar></globalSettings>");

        assertThat(settings.isTabTitleFromShellEnabled()).isTrue();
    }

    private static GlobalSettings roundTrip(GlobalSettings settings) throws Exception {
        return unmarshal(marshal(settings));
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
