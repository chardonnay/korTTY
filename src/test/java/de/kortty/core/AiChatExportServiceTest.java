package de.kortty.core;

import de.kortty.model.SavedAiChatMessage;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatExportServiceTest {

    @Test
    void plainTextExportContainsLocalizedRoleLabels() {
        AiChatExportService service = new AiChatExportService();

        String exported = service.buildPlainTextExport(List.of(
            message(SavedAiChatMessage.ROLE_USER, "Zeig mir die letzte Fehlermeldung", null),
            message(SavedAiChatMessage.ROLE_ASSISTANT, "Proxy ist nicht erreichbar.", "GPT Ops")));

        assertTrue(exported.contains("You:"));
        assertTrue(exported.contains("AI (GPT Ops):"));
        assertTrue(exported.contains("Proxy ist nicht erreichbar."));
    }

    @Test
    void markdownExportKeepsCodeBlocksAndTextSections() {
        AiChatExportService service = new AiChatExportService();

        String exported = service.buildMarkdownExport(List.of(
            message(SavedAiChatMessage.ROLE_ASSISTANT, "Analyse\n```bash\ncurl -I https://example.test\n```", "GPT Ops")));

        assertTrue(exported.contains("## AI (GPT Ops):"));
        assertTrue(exported.contains("### Text"));
        assertTrue(exported.contains("### Code (bash)"));
        assertTrue(exported.contains("curl -I https://example.test"));
    }

    private SavedAiChatMessage message(String role, String content, String profileName) {
        SavedAiChatMessage message = new SavedAiChatMessage();
        message.setRole(role);
        message.setContent(content);
        message.setAiProfileName(profileName);
        return message;
    }
}
