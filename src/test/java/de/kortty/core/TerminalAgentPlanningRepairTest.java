package de.kortty.core;

import com.google.gson.JsonSyntaxException;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


/**
 * Covers the planning JSON robustness path: when a (typically small) model returns a truncated or
 * malformed JSON object, {@link TerminalAgentService} retries once with a repair prompt and only
 * fails with a helpful message if the second attempt is also unusable.
 */
class TerminalAgentPlanningRepairTest {

    // Starts a valid final_plan object but is cut off mid-string with no closing brace.
    private static final String TRUNCATED_REPORT =
        "{\"status\":\"final_plan\",\"title\":\"Install puppet\",\"summary\":\"Es wird keine Installation";

    private static final String VALID_REPORT = """
        {
          "status": "final_plan",
          "title": "Install puppet",
          "summary": "Plan and validate the installation.",
          "userMessage": "Ready.",
          "prerequisites": ["Sudo access"],
          "steps": ["Check package source", "Install agent"],
          "risks": ["Package unavailable"],
          "successCriteria": ["Agent runs"]
        }
        """;

    private static final String TRUNCATED_OPTIONS =
        "{\"status\":\"options\",\"summary\":\"Two paths\",\"options\":[{\"title\":\"Plan only\",\"summary\":\"Do";

    private static final String VALID_OPTIONS = """
        {
          "status": "options",
          "summary": "Two paths",
          "userMessage": "Pick one.",
          "options": [
            {"title": "Plan only", "summary": "Produce a checklist", "feasibility": "High",
             "risks": [], "prerequisites": [], "steps": ["List needs"], "alternatives": []}
          ]
        }
        """;

    @Test
    void retriesOnceAndRecoversTruncatedPlanReport() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        QueueAiService aiService = new QueueAiService(TRUNCATED_REPORT, VALID_REPORT);

        TerminalAgentService.PlanningReport report = service.requestPlanningReport(
            null, aiService, request(), probe(), List.of(), "", null, null);

        assertThat(report.report().title()).isEqualTo("Install puppet");
        assertThat(report.report().steps()).contains("Install agent");
        assertThat(aiService.userPrompts()).hasSize(2);
        assertThat(aiService.userPrompts().get(1)).contains("EXACTLY ONE complete JSON object");
    }

    @Test
    void retriesOnceAndRecoversTruncatedPlanOptions() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        QueueAiService aiService = new QueueAiService(TRUNCATED_OPTIONS, VALID_OPTIONS);

        TerminalAgentService.PlanningOptions options = service.requestPlanningOptions(
            null, aiService, request(), probe(), List.of(), "", null);

        assertThat(options.options()).hasSize(1);
        assertThat(options.options().getFirst().title()).isEqualTo("Plan only");
        assertThat(aiService.userPrompts()).hasSize(2);
    }

    @Test
    void failsWithHelpfulMessageWhenBothAttemptsAreTruncated() {
        TerminalAgentService service = new TerminalAgentService();
        QueueAiService aiService = new QueueAiService(TRUNCATED_REPORT, TRUNCATED_REPORT);

        JsonSyntaxException failure = expectThrows(JsonSyntaxException.class, () ->
            service.requestPlanningReport(null, aiService, request(), probe(), List.of(), "", null, null));

        assertThat(failure.getMessage()).contains("truncated");
        assertThat(failure.getMessage()).contains("larger");
        assertThat(aiService.userPrompts()).hasSize(2);
    }

    @Test
    void doesNotRetryWhenFirstReplyIsValid() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        QueueAiService aiService = new QueueAiService(VALID_REPORT);

        TerminalAgentService.PlanningReport report = service.requestPlanningReport(
            null, aiService, request(), probe(), List.of(), "", null, null);

        assertThat(report.report().title()).isEqualTo("Install puppet");
        assertThat(aiService.userPrompts()).hasSize(1);
    }

    private TerminalAgentModels.PlanRequest request() {
        return new TerminalAgentModels.PlanRequest(
            "session-1", "profile-1", "Install puppet on Fedora 44.", "Fedora44");
    }

    private TerminalAgentModels.ProbeSnapshot probe() {
        return new TerminalAgentModels.ProbeSnapshot(
            "Fedora Linux 44", "kernel", "aarch64", "bash", "daniel", "1000", "1000",
            List.of("wheel"), "/home/daniel", "/home/daniel", null, "",
            List.of("dnf"), List.of("systemctl"), false, true, false, false, "", "sudo_password");
    }

    /** Deterministic AI double that returns queued responses and records the prompts it saw. */
    private static final class QueueAiService extends OpenAiCompatibleAiService {
        private final Queue<String> responses;
        private final List<String> userPrompts = new ArrayList<>();

        private QueueAiService(String... responses) {
            super("http://localhost", "test-model", "");
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            userPrompts.add(userPrompt);
            return new AiExecutionResult(responses.remove(), null);
        }

        private List<String> userPrompts() {
            return userPrompts;
        }
    }
}
