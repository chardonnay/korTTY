package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.testng.annotations.Test;

/**
 * The wiring of the "Sent along" disclosure, checked in the sources: each chat request captures its own reply, the agent
 * records and publishes every turn, and only the chat, the classic agent surfaces and the design link show the disclosure.
 */
class SentContextWiringSourceScanTest {

    private static final Path MAIN = Path.of("src/main/java/de/kortty");

    private static String source(String relative) throws IOException {
        return Files.readString(MAIN.resolve(relative), StandardCharsets.UTF_8);
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int index = text.indexOf(needle);
        while (index >= 0) {
            count++;
            index = text.indexOf(needle, index + needle.length());
        }
        return count;
    }

    @Test
    void everyChatRequestCapturesItsOwnReply() throws IOException {
        assertThat(occurrences(source("ui/AiResultTab.java"), "AiSentContextCollector.capture(")).isEqualTo(1);
        assertThat(occurrences(source("ui/MainWindow.java"), "AiSentContextCollector.capture(")).isEqualTo(2);
    }

    @Test
    void theAgentRecordsEveryJsonCallInItsRunLedgerAndPublishesIt() throws IOException {
        String service = source("core/TerminalAgentService.java");

        assertThat(service).contains("AiSentContextCollector.open()");
        assertThat(service).contains("outbound.sentLedger().recordTurn(");
        assertThat(service).contains("publishSentContext(ui, runId, outbound);");
        assertThat(occurrences(service, "publishUsedSkillActivity(ui, runId, aiService, outbound);")).isEqualTo(5);
    }

    @Test
    void theRequestPathsReportTheMemoryAndTheSkillsTheySend() throws IOException {
        String support = source("core/AiSkillPromptSupport.java");

        assertThat(support).contains("AiSentContextCollector.reportMemory(block);");
        assertThat(support).contains("reportSentSkill(skill, block.substring(sectionStart), learnedRules);");
    }

    @Test
    void theRunTabsTheClassicPanelAndTheDesignLinkAreTheOnlySurfacesThatShowTheDisclosure() throws IOException {
        Set<String> shownBy = new TreeSet<>();
        try (Stream<Path> files = Files.walk(MAIN.resolve("ui"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains("SentContextView.create(")) {
                    shownBy.add(file.getFileName().toString());
                }
            }
        }

        assertThat(shownBy).containsExactly(
            "AgentDesignSentLink.java",
            "AiAgentActivityPanel.java",
            "AiAgentRunTab.java",
            "AiResultTab.java");
    }

    @Test
    void theDesignHostShowsTheDisclosureInTheClassicPanelAndTheLinkOfTheOtherDesigns() throws IOException {
        String host = source("ui/AgentDesignHost.java");

        assertThat(host).contains("public void showSentContext(String runId, AiSentContext context)");
        assertThat(host).contains("if (design.classic() && classicPanel != null) {\n                classicPanel.showSentContext(runId, context);"
            + "\n            } else if (!design.classic()) {\n                sentLink.publish(context);");
    }

    @Test
    void theLinkIsPlacedAboveTheApprovalStripAndResetByEveryNewRun() throws IOException {
        String host = source("ui/AgentDesignHost.java");

        // The line sits above the bottom displays and the approval row, so the approval buttons keep their place.
        assertThat(host).contains("getChildren().addAll(sentLink, approvalStrip);");
        assertThat(host).contains("runOnFx(() -> {\n            applyDesignSettings();\n            sentLink.reset();");
    }
}
