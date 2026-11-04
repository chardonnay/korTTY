package de.kortty.core;

import de.kortty.model.SavedAgentStep;
import de.kortty.model.SavedAiChat;
import de.kortty.model.SavedAiChatMessage;
import de.kortty.ui.I18n;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** Exports of a chat that came from a KI-Agent run: origin line and steps. Ordinary chats stay as they were. */
class AiChatAgentRunExportTest {

    private static SavedAiChat agentRunChat() {
        SavedAiChat chat = new SavedAiChat();
        chat.setTitle("Restart nginx");
        chat.setOrigin(SavedAiChat.ORIGIN_AGENT_RUN);
        chat.setSourceRunId("run-1");
        chat.setConnectionDisplayName("Web01");
        chat.setCreatedAt(java.time.ZonedDateTime.of(LocalDateTime.of(2026, 8, 3, 15, 2), java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli());
        return chat;
    }

    private static List<SavedAiChatMessage> agentMessages() {
        SavedAiChatMessage user = new SavedAiChatMessage();
        user.setRole(SavedAiChatMessage.ROLE_USER);
        user.setContent("Restart nginx and check it.");
        SavedAgentStep step = new SavedAgentStep();
        step.setType("ACTION");
        step.setStatus("DONE");
        step.setTitle("Restart");
        step.setCommand("systemctl restart nginx");
        step.setExitCode(0);
        step.setOutput("restarted ok");
        SavedAiChatMessage assistant = new SavedAiChatMessage();
        assistant.setRole(SavedAiChatMessage.ROLE_ASSISTANT);
        assistant.setAiProfileName("Claude");
        assistant.setContent("nginx is running again.");
        assistant.setAgentSteps(List.of(step));
        return List.of(user, assistant);
    }

    @Test
    void markdownOfAnAgentRunChatHasTheOriginLineAndTheSteps() {
        AiChatExportService service = new AiChatExportService();

        String markdown = service.buildMarkdownExport(agentMessages(), agentRunChat());

        assertThat(markdown).startsWith(AiChatExportService.originLine(agentRunChat()));
        assertThat(markdown).contains("$ systemctl restart nginx");
        assertThat(markdown).contains("restarted ok");
        assertThat(markdown).contains(I18n.get("ai.result.agentRun.steps", 1));
        assertThat(markdown).contains("[DONE] Restart");
        // The role headings appear once per message, the steps add none of their own.
        assertThat(markdown.split("\n## ", -1).length - 1).isEqualTo(2);
    }

    /** The chat exports write their steps with the formatter's blocks, so Markdown and plain text agree with the journal. */
    @Test
    void theChatExportsWriteTheStepsWithTheFormatterBlocks() {
        AiChatExportService service = new AiChatExportService();
        List<SavedAgentStep> steps = agentMessages().stream()
            .filter(message -> message.getAgentSteps() != null && !message.getAgentSteps().isEmpty())
            .findFirst().orElseThrow().getAgentSteps();

        assertThat(service.buildMarkdownExport(agentMessages(), agentRunChat()))
            .contains(AgentChatTranscriptFormatter.markdownSteps(steps));
        assertThat(service.buildPlainTextExport(agentMessages(), agentRunChat()))
            .contains(AgentChatTranscriptFormatter.plainSteps(steps));
    }

    @Test
    void plainTextOfAnAgentRunChatHasTheOriginLineAndIndentedSteps() {
        AiChatExportService service = new AiChatExportService();

        String text = service.buildPlainTextExport(agentMessages(), agentRunChat());

        assertThat(text).startsWith(AiChatExportService.originLine(agentRunChat()));
        assertThat(text).contains("  - [DONE] Restart");
        assertThat(text).contains("      $ systemctl restart nginx");
        assertThat(text).contains("      restarted ok");
    }

    @Test
    void anOrdinaryChatExportsExactlyAsBefore() {
        AiChatExportService service = new AiChatExportService();
        SavedAiChatMessage user = new SavedAiChatMessage();
        user.setRole(SavedAiChatMessage.ROLE_USER);
        user.setContent("Hello");
        SavedAiChatMessage assistant = new SavedAiChatMessage();
        assistant.setRole(SavedAiChatMessage.ROLE_ASSISTANT);
        assistant.setContent("Hi there.");
        List<SavedAiChatMessage> messages = List.of(user, assistant);

        assertThat(new SavedAiChat().isAgentRunChat()).isFalse();
        assertThat(service.buildMarkdownExport(messages, null)).isEqualTo(service.buildMarkdownExport(messages));
        assertThat(service.buildPlainTextExport(messages, null)).isEqualTo(service.buildPlainTextExport(messages));
        assertThat(service.buildMarkdownExport(messages)).doesNotContain("ai.result.agentRun");
        assertThat(service.buildPlainTextExport(messages)).doesNotContain("ai.result.agentRun");
        assertThat(AiChatExportService.originLine(new SavedAiChat())).isNull();
    }

    @Test
    void theOriginLineNamesTheConnectionAndTheRunStart() {
        String line = AiChatExportService.originLine(agentRunChat());

        assertThat(line).isEqualTo(I18n.get("ai.result.agentRun.origin", "03.08.2026 15:02", "Web01"));
    }

    @Test
    void pdfOfAnAgentRunChatCarriesTheStepsAndTheOrigin() throws Exception {
        AiChatExportService service = new AiChatExportService();
        Path target = Files.createTempFile("kortty-agent-chat", ".pdf");
        try {
            AiChatExportContext context = new AiChatExportContext("Restart nginx", LocalDateTime.now(), "Claude",
                2);
            service.exportChat(target, AiChatExportService.Format.PDF, agentMessages(), 13, context,
                AiPdfExportOptions.defaults(context.title()), agentRunChat());
            try (PDDocument pdf = Loader.loadPDF(target.toFile())) {
                String text = new PDFTextStripper().getText(pdf);
                assertThat(text).contains("systemctl restart nginx");
                assertThat(text).contains("restarted ok");
                assertThat(text).contains(AiChatExportService.originLine(agentRunChat()).substring(0, 20));
            }
        } finally {
            Files.deleteIfExists(target);
        }
    }
}
