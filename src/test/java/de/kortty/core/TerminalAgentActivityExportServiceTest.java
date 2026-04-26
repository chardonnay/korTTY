package de.kortty.core;

import com.google.gson.JsonParser;
import de.kortty.model.TerminalAgentModels;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAgentActivityExportServiceTest {

    @Test
    void textFormatsContainProfileModelDurationsDetailsAndTokens() {
        TerminalAgentActivityExportService service = new TerminalAgentActivityExportService();
        TerminalAgentActivityExportService.ExportDocument document = sampleDocument();

        String markdown = service.buildMarkdownExport(document);
        String text = service.buildTextExport(document);
        String yaml = service.buildYamlExport(document);
        String asciidoctor = service.buildAsciidoctorExport(document);

        for (String exported : List.of(markdown, text, yaml, asciidoctor)) {
            assertTrue(exported.contains("local"));
            assertTrue(exported.contains("gpt-test"));
            assertTrue(exported.contains("42"));
            assertTrue(exported.contains("Collected the current server state."));
            assertTrue(exported.contains("Read 10 lines"));
            assertTrue(exported.contains("150"));
        }
    }

    @Test
    void jsonAndXmlExportsAreStructuredAndIncludeUnknownFallbacks() throws Exception {
        TerminalAgentActivityExportService service = new TerminalAgentActivityExportService();
        TerminalAgentActivityExportService.ExportDocument document = new TerminalAgentActivityExportService.ExportDocument(
            "Terminal Agent Export",
            LocalDateTime.of(2026, 4, 26, 15, 0),
            List.of(new TerminalAgentActivityExportService.Run(
                "unknown model run",
                "agent check",
                "profile-2",
                "Local profile",
                "",
                LocalDateTime.of(2026, 4, 26, 14, 59),
                LocalDateTime.of(2026, 4, 26, 15, 0),
                60,
                false,
                0,
                List.of(new TerminalAgentActivityExportService.Activity(
                    "a1",
                    TerminalAgentModels.AgentActivityType.MESSAGE,
                    TerminalAgentModels.AgentActivityStatus.COMPLETED,
                    "Done",
                    "Finished",
                    "",
                    TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                    3)))));

        String json = service.buildJsonExport(document);
        assertEquals("unknown", JsonParser.parseString(json)
            .getAsJsonObject()
            .getAsJsonArray("runs")
            .get(0)
            .getAsJsonObject()
            .get("modelName")
            .getAsString());

        String xml = service.buildXmlExport(document);
        Document parsedXml = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertEquals("terminalAgentExport", parsedXml.getDocumentElement().getNodeName());
        assertEquals("unknown", parsedXml.getElementsByTagName("modelName").item(0).getTextContent());
        assertEquals("unknown", parsedXml.getElementsByTagName("reportedTokens").item(0).getTextContent());
    }

    @Test
    void exportWritesAllFormatsAndPdfContainsRunMetadata() throws Exception {
        TerminalAgentActivityExportService service = new TerminalAgentActivityExportService();
        TerminalAgentActivityExportService.ExportDocument document = sampleDocument();

        for (TerminalAgentActivityExportService.Format format : TerminalAgentActivityExportService.Format.values()) {
            Path exportFile = Files.createTempFile("terminal-agent-export-", format.extension());
            try {
                service.export(exportFile, format, document);
                assertTrue(Files.size(exportFile) > 0);
                if (format == TerminalAgentActivityExportService.Format.PDF) {
                    try (PDDocument pdf = Loader.loadPDF(exportFile.toFile())) {
                        assertNotNull(pdf.getDocumentInformation());
                        String extracted = new PDFTextStripper().getText(pdf);
                        assertTrue(extracted.contains("local"));
                        assertTrue(extracted.contains("gpt-test"));
                        assertTrue(extracted.contains("Collected the current server state."));
                    }
                }
            } finally {
                Files.deleteIfExists(exportFile);
            }
        }
    }

    private TerminalAgentActivityExportService.ExportDocument sampleDocument() {
        return new TerminalAgentActivityExportService.ExportDocument(
            "Terminal Agent Export",
            LocalDateTime.of(2026, 4, 26, 15, 30),
            List.of(sampleRun()));
    }

    private TerminalAgentActivityExportService.Run sampleRun() {
        return new TerminalAgentActivityExportService.Run(
            "install tomcat",
            "agent install tomcat",
            "local",
            "local",
            "gpt-test",
            LocalDateTime.of(2026, 4, 26, 15, 28, 10),
            LocalDateTime.of(2026, 4, 26, 15, 28, 52),
            42,
            true,
            150,
            List.of(
                new TerminalAgentActivityExportService.Activity(
                    "probe",
                    TerminalAgentModels.AgentActivityType.ACTION,
                    TerminalAgentModels.AgentActivityStatus.COMPLETED,
                    "Inspect(SSH session)",
                    "Collected the current server state.",
                    "Fedora Linux 43",
                    new TerminalAgentModels.AgentActivityTokenUsage(true, 100, 50, 150),
                    2),
                new TerminalAgentActivityExportService.Activity(
                    "read",
                    TerminalAgentModels.AgentActivityType.ACTION,
                    TerminalAgentModels.AgentActivityStatus.COMPLETED,
                    "Read(README.md)",
                    "Exit 0 - 10 output lines",
                    "Read 10 lines",
                    TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                    4)));
    }
}
