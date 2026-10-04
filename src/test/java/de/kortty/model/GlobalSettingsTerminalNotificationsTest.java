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
 * The terminal notification settings (the bell, long commands, programs' notifications, the silence
 * threshold of watched tabs) and whether programs may write the clipboard (OSC 52): what a fresh
 * installation and an old settings file use, and that they survive the XML round trip.
 */
class GlobalSettingsTerminalNotificationsTest {

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
    void bellNotificationsAreOffOnAFreshInstallation() {
        assertWithMessage("shells ring on every failed Tab completion; the tab's mark is enough by default")
            .that(new GlobalSettings().isTerminalBellNotificationsEnabled()).isFalse();
    }

    @Test
    void settingsWrittenBeforeTheFeatureExistedKeepThemOff() throws Exception {
        assertThat(unmarshal("<globalSettings></globalSettings>").isTerminalBellNotificationsEnabled()).isFalse();
    }

    @Test
    void theChoiceSurvivesAnXmlRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalBellNotificationsEnabled(true);
        String xml = marshal(settings);
        assertThat(xml).contains("<terminalBellNotificationsEnabled>true</terminalBellNotificationsEnabled>");
        assertThat(unmarshal(xml).isTerminalBellNotificationsEnabled()).isTrue();

        settings.setTerminalBellNotificationsEnabled(false);
        assertThat(unmarshal(marshal(settings)).isTerminalBellNotificationsEnabled()).isFalse();
    }

    @Test
    void longCommandNotificationsAreOnAtThirtySecondsOnAFreshInstallation() {
        GlobalSettings settings = new GlobalSettings();
        assertWithMessage("decision D4 a: on, for commands of 30 s or more")
            .that(settings.isCommandFinishedNotificationsEnabled()).isTrue();
        assertThat(settings.getCommandFinishedNotificationSeconds()).isEqualTo(30);
        assertThat(GlobalSettings.forFreshInstall().isCommandFinishedNotificationsEnabled()).isTrue();
    }

    @Test
    void settingsWrittenBeforeLongCommandNotificationsExistedGetTheDefaults() throws Exception {
        GlobalSettings old = unmarshal("<globalSettings></globalSettings>");
        assertThat(old.isCommandFinishedNotificationsEnabled()).isTrue();
        assertThat(old.getCommandFinishedNotificationSeconds()).isEqualTo(30);
    }

    @Test
    void theLongCommandChoiceSurvivesAnXmlRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setCommandFinishedNotificationsEnabled(false);
        settings.setCommandFinishedNotificationSeconds(120);
        String xml = marshal(settings);
        assertThat(xml).contains("<commandFinishedNotificationsEnabled>false</commandFinishedNotificationsEnabled>");
        assertThat(xml).contains("<commandFinishedNotificationSeconds>120</commandFinishedNotificationSeconds>");
        GlobalSettings read = unmarshal(xml);
        assertThat(read.isCommandFinishedNotificationsEnabled()).isFalse();
        assertThat(read.getCommandFinishedNotificationSeconds()).isEqualTo(120);
    }

    @Test
    void theThresholdStaysBetweenOneSecondAndOneHour() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setCommandFinishedNotificationSeconds(0);
        assertThat(settings.getCommandFinishedNotificationSeconds()).isEqualTo(1);
        settings.setCommandFinishedNotificationSeconds(7200);
        assertThat(settings.getCommandFinishedNotificationSeconds()).isEqualTo(3600);
        assertWithMessage("a hand-edited file cannot get round the range")
            .that(unmarshal("<globalSettings><commandFinishedNotificationSeconds>-3</commandFinishedNotificationSeconds>"
                + "</globalSettings>").getCommandFinishedNotificationSeconds()).isEqualTo(1);
    }

    @Test
    void programsNotificationsAreOnOnAFreshInstallationAndForOldSettings() throws Exception {
        assertWithMessage("decision D4 a: remote coding agents rely on OSC 9/777")
            .that(new GlobalSettings().isRemoteTerminalNotificationsEnabled()).isTrue();
        assertThat(GlobalSettings.forFreshInstall().isRemoteTerminalNotificationsEnabled()).isTrue();
        assertThat(unmarshal("<globalSettings></globalSettings>").isRemoteTerminalNotificationsEnabled()).isTrue();
    }

    @Test
    void programsMayNotWriteTheClipboardOnAFreshInstallationOrWithOldSettings() throws Exception {
        assertWithMessage("decision D4 a: OSC 52 lets any program in the terminal replace what you copied")
            .that(new GlobalSettings().isOsc52ClipboardWriteEnabled()).isFalse();
        assertThat(GlobalSettings.forFreshInstall().isOsc52ClipboardWriteEnabled()).isFalse();
        assertThat(unmarshal("<globalSettings></globalSettings>").isOsc52ClipboardWriteEnabled()).isFalse();
    }

    @Test
    void theClipboardWriteChoiceSurvivesAnXmlRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setOsc52ClipboardWriteEnabled(true);
        String xml = marshal(settings);
        assertThat(xml).contains("<osc52ClipboardWriteEnabled>true</osc52ClipboardWriteEnabled>");
        assertThat(unmarshal(xml).isOsc52ClipboardWriteEnabled()).isTrue();

        settings.setOsc52ClipboardWriteEnabled(false);
        assertThat(unmarshal(marshal(settings)).isOsc52ClipboardWriteEnabled()).isFalse();
    }

    @Test
    void theProgramsNotificationChoiceSurvivesAnXmlRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setRemoteTerminalNotificationsEnabled(false);
        String xml = marshal(settings);
        assertThat(xml).contains("<remoteTerminalNotificationsEnabled>false</remoteTerminalNotificationsEnabled>");
        assertThat(unmarshal(xml).isRemoteTerminalNotificationsEnabled()).isFalse();

        settings.setRemoteTerminalNotificationsEnabled(true);
        assertThat(unmarshal(marshal(settings)).isRemoteTerminalNotificationsEnabled()).isTrue();
    }

    @Test
    void aiRunNotificationsAreOnOnAFreshInstallationAndForOldSettings() throws Exception {
        assertWithMessage("decision D5: an approval in a window behind another one must not block silently")
            .that(new GlobalSettings().isAiRunToastsEnabled()).isTrue();
        assertThat(GlobalSettings.forFreshInstall().isAiRunToastsEnabled()).isTrue();
        assertThat(unmarshal("<globalSettings></globalSettings>").isAiRunToastsEnabled()).isTrue();
    }

    @Test
    void theAiRunNotificationChoiceSurvivesAnXmlRoundTrip() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiRunToastsEnabled(false);
        String xml = marshal(settings);
        assertThat(xml).contains("<aiRunToastsEnabled>false</aiRunToastsEnabled>");
        assertThat(unmarshal(xml).isAiRunToastsEnabled()).isFalse();

        settings.setAiRunToastsEnabled(true);
        assertThat(unmarshal(marshal(settings)).isAiRunToastsEnabled()).isTrue();
    }

    @Test
    void theSilenceThresholdIsThirtySecondsOnAFreshInstallationAndForOldSettings() throws Exception {
        assertThat(new GlobalSettings().getTerminalSilenceSeconds()).isEqualTo(30);
        assertThat(GlobalSettings.forFreshInstall().getTerminalSilenceSeconds()).isEqualTo(30);
        assertThat(unmarshal("<globalSettings></globalSettings>").getTerminalSilenceSeconds()).isEqualTo(30);
    }

    @Test
    void theSilenceThresholdSurvivesAnXmlRoundTripAndStaysBetweenFiveSecondsAndOneHour() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalSilenceSeconds(120);
        String xml = marshal(settings);
        assertThat(xml).contains("<terminalSilenceSeconds>120</terminalSilenceSeconds>");
        assertThat(unmarshal(xml).getTerminalSilenceSeconds()).isEqualTo(120);

        settings.setTerminalSilenceSeconds(1);
        assertThat(settings.getTerminalSilenceSeconds()).isEqualTo(5);
        settings.setTerminalSilenceSeconds(7200);
        assertThat(settings.getTerminalSilenceSeconds()).isEqualTo(3600);
        assertWithMessage("a hand-edited file cannot get round the range")
            .that(unmarshal("<globalSettings><terminalSilenceSeconds>0</terminalSilenceSeconds></globalSettings>")
                .getTerminalSilenceSeconds()).isEqualTo(5);
    }
}
