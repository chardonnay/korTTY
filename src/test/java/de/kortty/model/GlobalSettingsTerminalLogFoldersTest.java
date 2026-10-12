package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The Logfile Manager's remembered log folders and the viewer's highlighting choices survive a settings round trip. */
class GlobalSettingsTerminalLogFoldersTest {

    @Test
    void knownFoldersRoundTripWithRetentionAndManualFlag() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalLogKnownFolders(List.of(
            new TerminalLogKnownFolder("/var/log/kortty", 30, "2026-10-12T00:00:00Z", false),
            new TerminalLogKnownFolder("/Users/me/logs", 0, null, true)));

        GlobalSettings back = unmarshal(marshal(settings));

        assertThat(back.getTerminalLogKnownFolders()).hasSize(2);
        TerminalLogKnownFolder first = back.getTerminalLogKnownFolders().get(0);
        assertThat(first.getPath()).isEqualTo("/var/log/kortty");
        assertThat(first.getRetentionDays()).isEqualTo(30);
        assertThat(first.getLastUsed()).isEqualTo("2026-10-12T00:00:00Z");
        assertThat(first.isManual()).isFalse();
        TerminalLogKnownFolder second = back.getTerminalLogKnownFolders().get(1);
        assertThat(second.getPath()).isEqualTo("/Users/me/logs");
        assertThat(second.getRetentionDays()).isEqualTo(0);
        assertThat(second.isManual()).isTrue();
    }

    @Test
    void listIsNeverNullAndTheSetterCopies() {
        GlobalSettings settings = new GlobalSettings();
        settings.setTerminalLogKnownFolders(null);
        assertThat(settings.getTerminalLogKnownFolders()).isEmpty();

        List<TerminalLogKnownFolder> source = new java.util.ArrayList<>();
        source.add(new TerminalLogKnownFolder("/a", 7, null, false));
        settings.setTerminalLogKnownFolders(source);
        source.clear();
        assertThat(settings.getTerminalLogKnownFolders()).hasSize(1);

        TerminalLogKnownFolder clamped = new TerminalLogKnownFolder();
        clamped.setRetentionDays(-5);
        assertThat(clamped.getRetentionDays()).isEqualTo(0);
    }

    @Test
    void viewerHighlightingDefaultsOnWithTheBuiltInErrorSetAndRoundTrips() throws Exception {
        GlobalSettings settings = new GlobalSettings();
        assertThat(settings.isLogfileViewerHighlightLinks()).isTrue();
        assertThat(settings.isLogfileViewerHighlightKeywords()).isTrue();
        assertThat(settings.getLogfileViewerKeywordSetId()).isEqualTo("builtin.errors");

        settings.setLogfileViewerHighlightLinks(false);
        settings.setLogfileViewerKeywordSetId("user-ops");
        GlobalSettings back = unmarshal(marshal(settings));
        assertThat(back.isLogfileViewerHighlightLinks()).isFalse();
        assertThat(back.isLogfileViewerHighlightKeywords()).isTrue();
        assertThat(back.getLogfileViewerKeywordSetId()).isEqualTo("user-ops");

        // A blank id (an edited settings file) falls back to the built-in set rather than to nothing.
        settings.setLogfileViewerKeywordSetId("  ");
        assertThat(settings.getLogfileViewerKeywordSetId()).isEqualTo("builtin.errors");
    }

    @Test
    void oldSettingsWithoutTheElementsLoadWithDefaults() throws Exception {
        GlobalSettings back = unmarshal("<globalSettings/>");
        assertThat(back.getTerminalLogKnownFolders()).isEmpty();
        assertThat(back.isLogfileViewerHighlightLinks()).isTrue();
        assertThat(back.getLogfileViewerKeywordSetId()).isEqualTo("builtin.errors");
    }

    private static String marshal(GlobalSettings settings) throws Exception {
        Marshaller marshaller = JAXBContext.newInstance(GlobalSettings.class).createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter out = new StringWriter();
        marshaller.marshal(settings, out);
        return out.toString();
    }

    private static GlobalSettings unmarshal(String xml) throws Exception {
        return (GlobalSettings) JAXBContext.newInstance(GlobalSettings.class).createUnmarshaller()
            .unmarshal(new StringReader(xml));
    }
}
