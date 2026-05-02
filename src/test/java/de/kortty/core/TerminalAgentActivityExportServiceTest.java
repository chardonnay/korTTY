package de.kortty.core;

import com.google.gson.JsonParser;
import de.kortty.model.TerminalAgentModels;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.annotations.Test;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilderFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;


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
            assertThat(exported.contains("local")).isTrue();
            assertThat(exported.contains("gpt-test")).isTrue();
            assertThat(exported.contains("High")).isTrue();
            assertThat(exported.contains("42")).isTrue();
            assertThat(exported.contains("Collected the current server state.")).isTrue();
            assertThat(exported.contains("Read 10 lines")).isTrue();
            assertThat(exported.contains("150")).isTrue();
        }
    }

    @Test
    void markdownAndTextExportsRemoveCommentMarkersByDefault() {
        TerminalAgentActivityExportService service = new TerminalAgentActivityExportService();
        TerminalAgentActivityExportService.ExportDocument document = sampleDocument();

        String markdown = service.buildMarkdownExport(document);
        String text = service.buildTextExport(document);

        assertThat(markdown.contains("\nFedora Linux 43\n")).isTrue();
        assertThat(text.contains("    Fedora Linux 43")).isTrue();
        assertThat(markdown.contains("> Fedora Linux 43")).isFalse();
        assertThat(text.contains("  > Fedora Linux 43")).isFalse();
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
        assertThat(JsonParser.parseString(json)
            .getAsJsonObject()
            .getAsJsonArray("runs")
            .get(0)
            .getAsJsonObject()
            .get("modelName")
            .getAsString()).isEqualTo("unknown");
        assertThat(JsonParser.parseString(json)
            .getAsJsonObject()
            .getAsJsonArray("runs")
            .get(0)
            .getAsJsonObject()
            .get("reasoningStatus")
            .getAsString()).isEqualTo("unknown");

        String xml = service.buildXmlExport(document);
        Document parsedXml = DocumentBuilderFactory.newInstance()
            .newDocumentBuilder()
            .parse(new java.io.ByteArrayInputStream(xml.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        assertThat(parsedXml.getDocumentElement().getNodeName()).isEqualTo("terminalAgentExport");
        assertThat(parsedXml.getElementsByTagName("modelName").item(0).getTextContent()).isEqualTo("unknown");
        assertThat(parsedXml.getElementsByTagName("reasoningStatus").item(0).getTextContent()).isEqualTo("unknown");
        assertThat(parsedXml.getElementsByTagName("reportedTokens").item(0).getTextContent()).isEqualTo("unknown");
    }

    @Test
    void exportWritesAllFormatsAndPdfContainsRunMetadata() throws Exception {
        TerminalAgentActivityExportService service = new TerminalAgentActivityExportService();
        TerminalAgentActivityExportService.ExportDocument document = sampleDocument();

        for (TerminalAgentActivityExportService.Format format : TerminalAgentActivityExportService.Format.values()) {
            Path exportFile = Files.createTempFile("terminal-agent-export-", format.extension());
            try {
                service.export(exportFile, format, document);
                assertThat(Files.size(exportFile) > 0).isTrue();
                if (format == TerminalAgentActivityExportService.Format.PDF) {
                    try (PDDocument pdf = Loader.loadPDF(exportFile.toFile())) {
                        assertThat(pdf.getDocumentInformation()).isNotNull();
                        String extracted = new PDFTextStripper().getText(pdf);
                        assertThat(extracted.contains("local")).isTrue();
                        assertThat(extracted.contains("gpt-test")).isTrue();
                        assertThat(extracted.contains("High")).isTrue();
                        assertThat(extracted.contains("Collected the current server state.")).isTrue();
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
            "High",
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
