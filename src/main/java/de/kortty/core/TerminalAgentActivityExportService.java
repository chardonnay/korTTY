package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.kortty.model.TerminalAgentModels;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType0Font;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Exports terminal AI agent activity runs into human-readable and machine-readable formats.
 */
public class TerminalAgentActivityExportService {

    public enum Format {
        MARKDOWN(".md", "ai.agent.export.file.markdown"),
        TEXT(".txt", "ai.agent.export.file.text"),
        YAML(".yaml", "ai.agent.export.file.yaml"),
        XML(".xml", "ai.agent.export.file.xml"),
        JSON(".json", "ai.agent.export.file.json"),
        PDF(".pdf", "ai.agent.export.file.pdf"),
        ASCIIDOCTOR(".adoc", "ai.agent.export.file.asciidoctor");

        private final String extension;
        private final String filterKey;

        Format(String extension, String filterKey) {
            this.extension = extension;
            this.filterKey = filterKey;
        }

        public String extension() {
            return extension;
        }

        public String filterKey() {
            return filterKey;
        }

        @Override
        public String toString() {
            return switch (this) {
                case MARKDOWN -> "Markdown";
                case TEXT -> "Plain text";
                case YAML -> "YAML";
                case XML -> "XML";
                case JSON -> "JSON";
                case PDF -> "PDF";
                case ASCIIDOCTOR -> "Asciidoctor";
            };
        }
    }

    public record ExportDocument(String title, LocalDateTime exportedAt, List<Run> runs) {
        public ExportDocument {
            title = nonBlank(title, "Terminal AI Agent Export");
            exportedAt = exportedAt != null ? exportedAt : LocalDateTime.now();
            runs = runs != null ? List.copyOf(runs) : List.of();
        }
    }

    public record Run(
        String title,
        String prompt,
        String profileId,
        String profileName,
        String modelName,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        long elapsedSeconds,
        boolean hasReportedTokens,
        long reportedTokens,
        List<Activity> activities) {

        public Run {
            title = nonBlank(title, "AI Agent");
            prompt = normalize(prompt);
            profileId = normalize(profileId);
            profileName = nonBlank(profileName, UNKNOWN);
            modelName = nonBlank(modelName, UNKNOWN);
            elapsedSeconds = Math.max(0L, elapsedSeconds);
            reportedTokens = Math.max(0L, reportedTokens);
            activities = activities != null ? List.copyOf(activities) : List.of();
        }
    }

    public record Activity(
        String id,
        TerminalAgentModels.AgentActivityType type,
        TerminalAgentModels.AgentActivityStatus status,
        String title,
        String summary,
        String detail,
        TerminalAgentModels.AgentActivityTokenUsage tokenUsage,
        long elapsedSeconds) {

        public Activity {
            id = normalize(id);
            type = type != null ? type : TerminalAgentModels.AgentActivityType.MESSAGE;
            status = status != null ? status : TerminalAgentModels.AgentActivityStatus.COMPLETED;
            title = normalize(title);
            summary = normalize(summary);
            detail = normalize(detail);
            tokenUsage = tokenUsage != null ? tokenUsage : TerminalAgentModels.AgentActivityTokenUsage.unknown();
            elapsedSeconds = Math.max(0L, elapsedSeconds);
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String UNKNOWN = "unknown";
    private static final String SANS_FONT_RESOURCE = "/fonts/noto/NotoSans-Regular.ttf";
    private static final String SANS_BOLD_FONT_RESOURCE = "/fonts/noto/NotoSans-Bold.ttf";
    private static final float PAGE_MARGIN = 48f;
    private static final float PDF_FONT_SIZE = 10.5f;
    private static final float PDF_TITLE_SIZE = 16f;
    private static final float PDF_LEADING = 14f;
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public void export(Path targetFile, Format format, ExportDocument document) throws IOException {
        if (targetFile == null) {
            throw new IOException("Export target file is missing");
        }
        ExportDocument safeDocument = document != null
            ? document
            : new ExportDocument("Terminal AI Agent Export", LocalDateTime.now(), List.of());
        Format safeFormat = format != null ? format : Format.MARKDOWN;
        switch (safeFormat) {
            case MARKDOWN -> Files.writeString(targetFile, buildMarkdownExport(safeDocument), StandardCharsets.UTF_8);
            case TEXT -> Files.writeString(targetFile, buildTextExport(safeDocument), StandardCharsets.UTF_8);
            case YAML -> Files.writeString(targetFile, buildYamlExport(safeDocument), StandardCharsets.UTF_8);
            case XML -> Files.writeString(targetFile, buildXmlExport(safeDocument), StandardCharsets.UTF_8);
            case JSON -> Files.writeString(targetFile, buildJsonExport(safeDocument), StandardCharsets.UTF_8);
            case PDF -> exportPdf(targetFile, safeDocument);
            case ASCIIDOCTOR -> Files.writeString(targetFile, buildAsciidoctorExport(safeDocument), StandardCharsets.UTF_8);
        }
    }

    public String buildMarkdownExport(ExportDocument document) {
        StringBuilder builder = new StringBuilder();
        builder.append("# ").append(escapeMarkdown(document.title())).append("\n\n");
        builder.append("- Exported: ").append(formatTimestamp(document.exportedAt())).append("\n");
        builder.append("- Runs: ").append(document.runs().size()).append("\n\n");
        for (Run run : document.runs()) {
            appendMarkdownRun(builder, run);
        }
        return builder.toString();
    }

    public String buildTextExport(ExportDocument document) {
        StringBuilder builder = new StringBuilder();
        builder.append(document.title()).append("\n");
        builder.append("Exported: ").append(formatTimestamp(document.exportedAt())).append("\n");
        builder.append("Runs: ").append(document.runs().size()).append("\n");
        for (Run run : document.runs()) {
            builder.append("\n").append(repeat("=", 72)).append("\n");
            appendTextRun(builder, run);
        }
        return builder.toString();
    }

    public String buildYamlExport(ExportDocument document) {
        StringBuilder builder = new StringBuilder();
        builder.append("title: ").append(yamlScalar(document.title())).append("\n");
        builder.append("exportedAt: ").append(yamlScalar(formatTimestamp(document.exportedAt()))).append("\n");
        builder.append("runs:\n");
        for (Run run : document.runs()) {
            builder.append("  - title: ").append(yamlScalar(run.title())).append("\n");
            builder.append("    prompt: ").append(yamlScalar(run.prompt())).append("\n");
            builder.append("    profileId: ").append(yamlScalar(run.profileId())).append("\n");
            builder.append("    profileName: ").append(yamlScalar(run.profileName())).append("\n");
            builder.append("    modelName: ").append(yamlScalar(run.modelName())).append("\n");
            builder.append("    startedAt: ").append(yamlScalar(formatTimestamp(run.startedAt()))).append("\n");
            builder.append("    finishedAt: ").append(yamlScalar(formatTimestamp(run.finishedAt()))).append("\n");
            builder.append("    elapsedSeconds: ").append(run.elapsedSeconds()).append("\n");
            builder.append("    reportedTokens: ").append(run.hasReportedTokens() ? run.reportedTokens() : UNKNOWN).append("\n");
            builder.append("    activities:\n");
            for (Activity activity : run.activities()) {
                builder.append("      - id: ").append(yamlScalar(activity.id())).append("\n");
                builder.append("        type: ").append(yamlScalar(activity.type().name())).append("\n");
                builder.append("        status: ").append(yamlScalar(activity.status().name())).append("\n");
                builder.append("        title: ").append(yamlScalar(activity.title())).append("\n");
                builder.append("        summary: ").append(yamlScalar(activity.summary())).append("\n");
                builder.append("        elapsedSeconds: ").append(activity.elapsedSeconds()).append("\n");
                appendYamlTokenUsage(builder, activity.tokenUsage(), "        ");
                appendYamlBlock(builder, "        detail", activity.detail());
            }
        }
        return builder.toString();
    }

    public String buildXmlExport(ExportDocument document) {
        StringBuilder builder = new StringBuilder();
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        builder.append("<terminalAgentExport>\n");
        appendXmlElement(builder, 1, "title", document.title());
        appendXmlElement(builder, 1, "exportedAt", formatTimestamp(document.exportedAt()));
        builder.append("  <runs>\n");
        for (Run run : document.runs()) {
            builder.append("    <run>\n");
            appendXmlElement(builder, 3, "title", run.title());
            appendXmlElement(builder, 3, "prompt", run.prompt());
            appendXmlElement(builder, 3, "profileId", run.profileId());
            appendXmlElement(builder, 3, "profileName", run.profileName());
            appendXmlElement(builder, 3, "modelName", run.modelName());
            appendXmlElement(builder, 3, "startedAt", formatTimestamp(run.startedAt()));
            appendXmlElement(builder, 3, "finishedAt", formatTimestamp(run.finishedAt()));
            appendXmlElement(builder, 3, "elapsedSeconds", String.valueOf(run.elapsedSeconds()));
            appendXmlElement(builder, 3, "reportedTokens", run.hasReportedTokens() ? String.valueOf(run.reportedTokens()) : UNKNOWN);
            builder.append("      <activities>\n");
            for (Activity activity : run.activities()) {
                builder.append("        <activity>\n");
                appendXmlElement(builder, 5, "id", activity.id());
                appendXmlElement(builder, 5, "type", activity.type().name());
                appendXmlElement(builder, 5, "status", activity.status().name());
                appendXmlElement(builder, 5, "title", activity.title());
                appendXmlElement(builder, 5, "summary", activity.summary());
                appendXmlElement(builder, 5, "detail", activity.detail());
                appendXmlElement(builder, 5, "elapsedSeconds", String.valueOf(activity.elapsedSeconds()));
                appendXmlTokenUsage(builder, activity.tokenUsage(), 5);
                builder.append("        </activity>\n");
            }
            builder.append("      </activities>\n");
            builder.append("    </run>\n");
        }
        builder.append("  </runs>\n");
        builder.append("</terminalAgentExport>\n");
        return builder.toString();
    }

    public String buildJsonExport(ExportDocument document) {
        return GSON.toJson(toJsonMap(document));
    }

    public String buildAsciidoctorExport(ExportDocument document) {
        StringBuilder builder = new StringBuilder();
        builder.append("= ").append(document.title()).append("\n");
        builder.append(":toc:\n\n");
        builder.append("*Exported:* ").append(formatTimestamp(document.exportedAt())).append("\n\n");
        for (Run run : document.runs()) {
            appendAsciidoctorRun(builder, run);
        }
        return builder.toString();
    }

    private void exportPdf(Path targetFile, ExportDocument document) throws IOException {
        try (PDDocument pdf = new PDDocument()) {
            PDType0Font sans = loadFont(pdf, SANS_FONT_RESOURCE);
            PDType0Font bold = loadFont(pdf, SANS_BOLD_FONT_RESOURCE);
            PDDocumentInformation info = pdf.getDocumentInformation();
            info.setTitle(document.title());
            info.setProducer("KorTTY by Daniel Mengel");
            info.setSubject("Terminal AI agent export");

            PdfCursor cursor = openPdfPage(pdf);
            cursor = drawPdfLine(pdf, cursor, bold, PDF_TITLE_SIZE, document.title());
            cursor = drawPdfLine(pdf, cursor, sans, PDF_FONT_SIZE, "Exported: " + formatTimestamp(document.exportedAt()));
            cursor = drawPdfLine(pdf, cursor, sans, PDF_FONT_SIZE, "Runs: " + document.runs().size());
            cursor = cursor.withY(cursor.y() - 10f);

            for (Run run : document.runs()) {
                cursor = ensurePdfSpace(pdf, cursor, 80f);
                cursor = drawPdfLine(pdf, cursor, bold, 13f, run.title());
                for (String metaLine : runMetadataLines(run)) {
                    cursor = drawPdfLine(pdf, cursor, sans, PDF_FONT_SIZE, metaLine);
                }
                cursor = cursor.withY(cursor.y() - 4f);
                for (Activity activity : run.activities()) {
                    cursor = ensurePdfSpace(pdf, cursor, 45f);
                    cursor = drawPdfLine(pdf, cursor, bold, PDF_FONT_SIZE,
                        activity.type().name() + " / " + activity.status().name() + " - " + nonBlank(activity.summary(), activity.title()));
                    if (!blank(activity.detail())) {
                        for (String line : wrapPdfText("> " + activity.detail().replace("\n", " "), sans, PDF_FONT_SIZE)) {
                            cursor = drawPdfLine(pdf, cursor, sans, PDF_FONT_SIZE, line);
                        }
                    }
                }
                cursor = cursor.withY(cursor.y() - 12f);
            }
            cursor.stream().close();
            pdf.save(targetFile.toFile());
        }
    }

    private void appendMarkdownRun(StringBuilder builder, Run run) {
        builder.append("## ").append(escapeMarkdown(run.title())).append("\n\n");
        appendMarkdownMetadata(builder, run);
        builder.append("| Type | Status | Activity | Duration | Tokens |\n");
        builder.append("| --- | --- | --- | ---: | --- |\n");
        for (Activity activity : run.activities()) {
            builder.append("| ")
                .append(activity.type().name()).append(" | ")
                .append(activity.status().name()).append(" | ")
                .append(escapeMarkdownTableCell(nonBlank(activity.summary(), activity.title()))).append(" | ")
                .append(activity.elapsedSeconds()).append("s | ")
                .append(formatTokenUsage(activity.tokenUsage())).append(" |\n");
            if (!blank(activity.detail())) {
                builder.append("\n").append("> ")
                    .append(activity.detail().replace("\n", "\n> "))
                    .append("\n\n");
            }
        }
        builder.append("\n");
    }

    private void appendMarkdownMetadata(StringBuilder builder, Run run) {
        builder.append("- Prompt: ").append(escapeMarkdown(nullToUnknown(run.prompt()))).append("\n");
        builder.append("- AI profile: ").append(escapeMarkdown(run.profileName())).append("\n");
        builder.append("- Profile ID: ").append(escapeMarkdown(nullToUnknown(run.profileId()))).append("\n");
        builder.append("- LLM/model: ").append(escapeMarkdown(run.modelName())).append("\n");
        builder.append("- Started: ").append(formatTimestamp(run.startedAt())).append("\n");
        builder.append("- Finished: ").append(formatTimestamp(run.finishedAt())).append("\n");
        builder.append("- Runtime: ").append(run.elapsedSeconds()).append("s\n");
        builder.append("- Reported tokens: ").append(run.hasReportedTokens() ? run.reportedTokens() : UNKNOWN).append("\n\n");
    }

    private void appendTextRun(StringBuilder builder, Run run) {
        builder.append(run.title()).append("\n");
        for (String metaLine : runMetadataLines(run)) {
            builder.append(metaLine).append("\n");
        }
        for (Activity activity : run.activities()) {
            builder.append("\n- ")
                .append(activity.type().name()).append(" / ")
                .append(activity.status().name()).append(": ")
                .append(nonBlank(activity.summary(), activity.title()))
                .append(" [").append(activity.elapsedSeconds()).append("s, ")
                .append(formatTokenUsage(activity.tokenUsage())).append("]\n");
            if (!blank(activity.detail())) {
                builder.append(indent(activity.detail().trim(), "  > ")).append("\n");
            }
        }
    }

    private List<String> runMetadataLines(Run run) {
        return List.of(
            "Prompt: " + nullToUnknown(run.prompt()),
            "AI profile: " + run.profileName(),
            "Profile ID: " + nullToUnknown(run.profileId()),
            "LLM/model: " + run.modelName(),
            "Started: " + formatTimestamp(run.startedAt()),
            "Finished: " + formatTimestamp(run.finishedAt()),
            "Runtime: " + run.elapsedSeconds() + "s",
            "Reported tokens: " + (run.hasReportedTokens() ? run.reportedTokens() : UNKNOWN));
    }

    private void appendAsciidoctorRun(StringBuilder builder, Run run) {
        builder.append("== ").append(run.title()).append("\n\n");
        builder.append("[cols=\"1,3\"]\n|===\n");
        builder.append("|Prompt |").append(nullToUnknown(run.prompt())).append("\n");
        builder.append("|AI profile |").append(run.profileName()).append("\n");
        builder.append("|Profile ID |").append(nullToUnknown(run.profileId())).append("\n");
        builder.append("|LLM/model |").append(run.modelName()).append("\n");
        builder.append("|Started |").append(formatTimestamp(run.startedAt())).append("\n");
        builder.append("|Finished |").append(formatTimestamp(run.finishedAt())).append("\n");
        builder.append("|Runtime |").append(run.elapsedSeconds()).append("s\n");
        builder.append("|Reported tokens |").append(run.hasReportedTokens() ? run.reportedTokens() : UNKNOWN).append("\n");
        builder.append("|===\n\n");
        for (Activity activity : run.activities()) {
            builder.append("=== ")
                .append(activity.type().name()).append(" / ")
                .append(activity.status().name()).append("\n\n");
            builder.append(nonBlank(activity.summary(), activity.title())).append("\n\n");
            builder.append("*Runtime:* ").append(activity.elapsedSeconds()).append("s +\n");
            builder.append("*Tokens:* ").append(formatTokenUsage(activity.tokenUsage())).append("\n\n");
            if (!blank(activity.detail())) {
                builder.append("----\n").append(activity.detail().trim()).append("\n----\n\n");
            }
        }
    }

    private Map<String, Object> toJsonMap(ExportDocument document) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("title", document.title());
        root.put("exportedAt", formatTimestamp(document.exportedAt()));
        List<Map<String, Object>> runs = new ArrayList<>();
        for (Run run : document.runs()) {
            Map<String, Object> runMap = new LinkedHashMap<>();
            runMap.put("title", run.title());
            runMap.put("prompt", run.prompt());
            runMap.put("profileId", run.profileId());
            runMap.put("profileName", run.profileName());
            runMap.put("modelName", run.modelName());
            runMap.put("startedAt", formatTimestamp(run.startedAt()));
            runMap.put("finishedAt", formatTimestamp(run.finishedAt()));
            runMap.put("elapsedSeconds", run.elapsedSeconds());
            runMap.put("reportedTokens", run.hasReportedTokens() ? run.reportedTokens() : UNKNOWN);
            List<Map<String, Object>> activities = new ArrayList<>();
            for (Activity activity : run.activities()) {
                Map<String, Object> activityMap = new LinkedHashMap<>();
                activityMap.put("id", activity.id());
                activityMap.put("type", activity.type().name());
                activityMap.put("status", activity.status().name());
                activityMap.put("title", activity.title());
                activityMap.put("summary", activity.summary());
                activityMap.put("detail", activity.detail());
                activityMap.put("elapsedSeconds", activity.elapsedSeconds());
                activityMap.put("tokenUsage", tokenUsageMap(activity.tokenUsage()));
                activities.add(activityMap);
            }
            runMap.put("activities", activities);
            runs.add(runMap);
        }
        root.put("runs", runs);
        return root;
    }

    private Map<String, Object> tokenUsageMap(TerminalAgentModels.AgentActivityTokenUsage usage) {
        Map<String, Object> map = new LinkedHashMap<>();
        TerminalAgentModels.AgentActivityTokenUsage safeUsage = usage != null
            ? usage
            : TerminalAgentModels.AgentActivityTokenUsage.unknown();
        map.put("known", safeUsage.known());
        map.put("promptTokens", safeUsage.promptTokens());
        map.put("completionTokens", safeUsage.completionTokens());
        map.put("totalTokens", safeUsage.totalTokens());
        return map;
    }

    private void appendYamlTokenUsage(
        StringBuilder builder,
        TerminalAgentModels.AgentActivityTokenUsage usage,
        String indent) {

        TerminalAgentModels.AgentActivityTokenUsage safeUsage = usage != null
            ? usage
            : TerminalAgentModels.AgentActivityTokenUsage.unknown();
        builder.append(indent).append("tokenUsage:\n");
        builder.append(indent).append("  known: ").append(safeUsage.known()).append("\n");
        builder.append(indent).append("  promptTokens: ").append(safeUsage.promptTokens()).append("\n");
        builder.append(indent).append("  completionTokens: ").append(safeUsage.completionTokens()).append("\n");
        builder.append(indent).append("  totalTokens: ").append(safeUsage.totalTokens()).append("\n");
    }

    private void appendYamlBlock(StringBuilder builder, String key, String value) {
        builder.append(key).append(":");
        if (blank(value)) {
            builder.append(" ").append(yamlScalar(null)).append("\n");
            return;
        }
        builder.append(" |\n");
        for (String line : value.strip().split("\\R", -1)) {
            builder.append("          ").append(line).append("\n");
        }
    }

    private void appendXmlTokenUsage(
        StringBuilder builder,
        TerminalAgentModels.AgentActivityTokenUsage usage,
        int indentLevel) {

        TerminalAgentModels.AgentActivityTokenUsage safeUsage = usage != null
            ? usage
            : TerminalAgentModels.AgentActivityTokenUsage.unknown();
        builder.append(indent(indentLevel)).append("<tokenUsage>\n");
        appendXmlElement(builder, indentLevel + 1, "known", String.valueOf(safeUsage.known()));
        appendXmlElement(builder, indentLevel + 1, "promptTokens", String.valueOf(safeUsage.promptTokens()));
        appendXmlElement(builder, indentLevel + 1, "completionTokens", String.valueOf(safeUsage.completionTokens()));
        appendXmlElement(builder, indentLevel + 1, "totalTokens", String.valueOf(safeUsage.totalTokens()));
        builder.append(indent(indentLevel)).append("</tokenUsage>\n");
    }

    private void appendXmlElement(StringBuilder builder, int indentLevel, String name, String value) {
        builder.append(indent(indentLevel))
            .append("<").append(name).append(">")
            .append(escapeXml(nullToUnknown(value)))
            .append("</").append(name).append(">\n");
    }

    private String formatTokenUsage(TerminalAgentModels.AgentActivityTokenUsage usage) {
        if (usage == null || !usage.known()) {
            return UNKNOWN;
        }
        return String.valueOf(usage.totalTokens());
    }

    private PdfCursor ensurePdfSpace(PDDocument document, PdfCursor cursor, float minimumHeight) throws IOException {
        if (cursor.y() - minimumHeight >= PAGE_MARGIN) {
            return cursor;
        }
        cursor.stream().close();
        return openPdfPage(document);
    }

    private PdfCursor openPdfPage(PDDocument document) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        return new PdfCursor(page, new PDPageContentStream(document, page), page.getMediaBox().getHeight() - PAGE_MARGIN);
    }

    private PdfCursor drawPdfLine(
        PDDocument document,
        PdfCursor cursor,
        PDType0Font font,
        float fontSize,
        String text) throws IOException {

        PdfCursor current = cursor;
        for (String line : wrapPdfText(text, font, fontSize)) {
            current = ensurePdfSpace(document, current, PDF_LEADING);
            current.stream().beginText();
            current.stream().setFont(font, fontSize);
            current.stream().newLineAtOffset(PAGE_MARGIN, current.y());
            current.stream().showText(stripPdfControls(line));
            current.stream().endText();
            current = current.withY(current.y() - PDF_LEADING);
        }
        return current;
    }

    private List<String> wrapPdfText(String text, PDType0Font font, float fontSize) throws IOException {
        float maxWidth = PDRectangle.A4.getWidth() - (PAGE_MARGIN * 2f);
        List<String> result = new ArrayList<>();
        String safeText = nullToUnknown(text).replace("\r", "").replace("\n", " ");
        StringBuilder line = new StringBuilder();
        for (String word : safeText.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (font.getStringWidth(stripPdfControls(candidate)) / 1000f * fontSize <= maxWidth) {
                line.setLength(0);
                line.append(candidate);
            } else {
                if (!line.isEmpty()) {
                    result.add(line.toString());
                }
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) {
            result.add(line.toString());
        }
        return result.isEmpty() ? List.of("") : result;
    }

    private PDType0Font loadFont(PDDocument document, String resourcePath) throws IOException {
        try (InputStream inputStream = TerminalAgentActivityExportService.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing PDF font resource " + resourcePath);
            }
            return PDType0Font.load(document, inputStream, true);
        }
    }

    private String stripPdfControls(String value) {
        return value != null ? value.replaceAll("\\p{Cntrl}", " ") : UNKNOWN;
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        return timestamp != null ? timestamp.format(TIMESTAMP_FORMAT) : UNKNOWN;
    }

    private String nullToUnknown(String value) {
        return blank(value) ? UNKNOWN : value.trim();
    }

    private static String nonBlank(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private static String normalize(String value) {
        return blank(value) ? null : value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String yamlScalar(String value) {
        if (blank(value)) {
            return "\"" + UNKNOWN + "\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private String escapeXml(String value) {
        return value
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;");
    }

    private String escapeMarkdown(String value) {
        return nullToUnknown(value).replace("\\", "\\\\").replace("*", "\\*").replace("_", "\\_");
    }

    private String escapeMarkdownTableCell(String value) {
        return nullToUnknown(value).replace("|", "\\|").replace("\n", "<br>");
    }

    private String indent(String text, String prefix) {
        return nullToUnknown(text).lines()
            .map(line -> prefix + line)
            .reduce((left, right) -> left + "\n" + right)
            .orElse(prefix + UNKNOWN);
    }

    private String indent(int level) {
        return repeat("  ", level);
    }

    private String repeat(String value, int count) {
        return value.repeat(Math.max(0, count));
    }

    private record PdfCursor(PDPage page, PDPageContentStream stream, float y) {
        private PdfCursor withY(float nextY) {
            return new PdfCursor(page, stream, nextY);
        }
    }
}
