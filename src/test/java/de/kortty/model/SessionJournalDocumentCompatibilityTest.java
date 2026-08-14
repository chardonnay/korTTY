package de.kortty.model;

import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import org.testng.annotations.Test;

import java.io.StringReader;
import java.io.StringWriter;

import static com.google.common.truth.Truth.assertThat;

/**
 * Guards both directions of journal.xml compatibility: a korTTY without custom markers must keep
 * reading a document that uses them, and this korTTY must keep reading documents written before
 * they existed.
 */
class SessionJournalDocumentCompatibilityTest {

    private static final String LEGACY_DOCUMENT = """
        <?xml version="1.0" encoding="UTF-8"?>
        <session-journal formatVersion="1" id="11111111-2222-3333-4444-555555555555">
            <meta><title>Legacy</title></meta>
            <entries>
                <entry>
                    <id>entry-1</id>
                    <kind>AI_SUMMARY</kind>
                    <marker>ERROR</marker>
                    <markerSource>AI</markerSource>
                    <state>SUMMARIZED</state>
                    <title>Disk full</title>
                    <inputExcerpt/>
                    <outputExcerpt/>
                </entry>
            </entries>
        </session-journal>
        """;

    private static JAXBContext context() throws Exception {
        return JAXBContext.newInstance(
            SessionJournalDocument.class,
            SessionJournalMeta.class,
            SessionJournalEntry.class,
            SessionJournalMarkerDefinition.class);
    }

    private static String marshal(SessionJournalDocument document) throws Exception {
        Marshaller marshaller = context().createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, Boolean.TRUE);
        StringWriter writer = new StringWriter();
        marshaller.marshal(document, writer);
        return writer.toString();
    }

    private static SessionJournalDocument unmarshal(String xml) throws Exception {
        Unmarshaller unmarshaller = context().createUnmarshaller();
        return (SessionJournalDocument) unmarshaller.unmarshal(new StringReader(xml));
    }

    @Test
    void readsALegacyDocumentThatHasNeitherMarkerIdNorMarkerDefinitions() throws Exception {
        SessionJournalDocument document = unmarshal(LEGACY_DOCUMENT);

        SessionJournalEntry entry = document.getEntries().get(0);
        assertThat(entry.getMarkerId()).isNull();
        assertThat(entry.getMarker()).isEqualTo(SessionJournalMarker.ERROR);
        assertThat(document.getMarkerDefinitions()).isEmpty();
    }

    @Test
    void reMarshallingALegacyDocumentAddsNoMarkerElements() throws Exception {
        String rewritten = marshal(unmarshal(LEGACY_DOCUMENT));

        assertThat(rewritten).doesNotContain("markerDefinitions");
        assertThat(rewritten).doesNotContain("markerId");
        assertThat(rewritten).contains("<marker>ERROR</marker>");
    }

    @Test
    void readsALegacyDocumentWithoutAiAnalysisFields() throws Exception {
        SessionJournalEntry entry = unmarshal(LEGACY_DOCUMENT).getEntries().get(0);

        assertThat(entry.getAiDescription()).isNull();
        assertThat(entry.getAiTags()).isEmpty();
        assertThat(entry.getAiAnalysisModel()).isNull();
        assertThat(entry.hasAiAnalysis()).isFalse();
    }

    @Test
    void reMarshallingALegacyDocumentAddsNoAiAnalysisElements() throws Exception {
        SessionJournalDocument document = unmarshal(LEGACY_DOCUMENT);
        // The lazy getter materializes an empty list; beforeMarshal must drop it again, or every
        // legacy entry would grow a stray <aiTags/> wrapper on the next save.
        assertThat(document.getEntries().get(0).getAiTags()).isEmpty();
        String rewritten = marshal(document);

        assertThat(rewritten).doesNotContain("aiTags");
        assertThat(rewritten).doesNotContain("aiDescription");
        assertThat(rewritten).doesNotContain("aiAnalysisModel");
    }

    @Test
    void roundTripsAiAnalysisFieldsOnAScreenshotEntry() throws Exception {
        SessionJournalDocument document = new SessionJournalDocument();
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setKind(SessionJournalEntryKind.SCREENSHOT);
        entry.setScreenshotFile("screenshots/shot-000001.png");
        entry.setAiDescription("Terminal zeigt den nginx-Status.");
        entry.setAiTags(java.util.List.of("nginx", "status"));
        entry.setAiAnalysisModel("qwen2.5-vl");
        document.getEntries().add(entry);

        SessionJournalEntry reread = unmarshal(marshal(document)).getEntries().get(0);

        assertThat(reread.getAiDescription()).isEqualTo("Terminal zeigt den nginx-Status.");
        assertThat(reread.getAiTags()).containsExactly("nginx", "status").inOrder();
        assertThat(reread.getAiAnalysisModel()).isEqualTo("qwen2.5-vl");
        assertThat(reread.hasAiAnalysis()).isTrue();
    }

    @Test
    void copyConstructorDeepCopiesTheAiTags() {
        SessionJournalEntry original = new SessionJournalEntry();
        original.setAiDescription("desc");
        original.setAiTags(java.util.List.of("one"));

        SessionJournalEntry copy = new SessionJournalEntry(original);
        copy.getAiTags().add("two");

        assertThat(original.getAiTags()).containsExactly("one");
        assertThat(copy.getAiDescription()).isEqualTo("desc");
    }

    @Test
    void aCustomMarkedEntryStillCarriesTheLegacyEnumValueForOlderReaders() throws Exception {
        SessionJournalDocument document = new SessionJournalDocument();
        SessionJournalMarkerDefinition deploy = new SessionJournalMarkerDefinition(
            "deploy", "Deployment", "#7c3aed", false, SessionJournalMarker.IMPORTANT);
        document.getMarkerDefinitions().add(deploy);
        SessionJournalEntry entry = new SessionJournalEntry();
        entry.setMarkerId("deploy");
        entry.setMarker(SessionJournalMarker.IMPORTANT);
        document.getEntries().add(entry);

        String xml = marshal(document);

        // An older korTTY knows only <marker>; it must find the degraded value there.
        assertThat(xml).contains("<marker>IMPORTANT</marker>");
        assertThat(xml).contains("<markerId>deploy</markerId>");
        assertThat(xml).contains("<name>Deployment</name>");
    }

    @Test
    void roundTripsCustomMarkerDefinitionsThroughTheDocument() throws Exception {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getMarkerDefinitions().add(new SessionJournalMarkerDefinition(
            "deploy", "Deployment", "#7c3aed", false, SessionJournalMarker.IMPORTANT));

        SessionJournalDocument reloaded = unmarshal(marshal(document));

        assertThat(reloaded.getMarkerDefinitions()).hasSize(1);
        SessionJournalMarkerDefinition definition = reloaded.getMarkerDefinitions().get(0);
        assertThat(definition.getId()).isEqualTo("deploy");
        assertThat(definition.getColor()).isEqualTo("#7c3aed");
        assertThat(definition.getLegacyMarker()).isEqualTo(SessionJournalMarker.IMPORTANT);
        assertThat(definition.isBuiltIn()).isFalse();
    }

    @Test
    void anUnknownMarkerSourceValueDegradesToAiInsteadOfFailingTheLoad() throws Exception {
        String futureValue = LEGACY_DOCUMENT.replace(
            "<markerSource>AI</markerSource>", "<markerSource>FROM_THE_FUTURE</markerSource>");

        SessionJournalDocument document = unmarshal(futureValue);

        assertThat(document.getEntries()).hasSize(1);
        assertThat(document.getEntries().get(0).getMarkerSource())
            .isEqualTo(SessionJournalEntry.MarkerSource.AI);
    }

    @Test
    void copyConstructorDeepCopiesTheMarkerSnapshot() {
        SessionJournalDocument document = new SessionJournalDocument();
        document.getMarkerDefinitions().add(new SessionJournalMarkerDefinition(
            "deploy", "Deployment", "#7c3aed", false, SessionJournalMarker.IMPORTANT));

        SessionJournalDocument copy = new SessionJournalDocument(document);
        copy.getMarkerDefinitions().get(0).setName("Changed");

        assertThat(document.getMarkerDefinitions().get(0).getName()).isEqualTo("Deployment");
    }
}
