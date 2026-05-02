package de.kortty.core;

import com.google.gson.JsonSyntaxException;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


class TerminalAgentPlanningServiceTest {

    private final TerminalAgentService service = new TerminalAgentService();

    @Test
    void parsesPlanQuestionsWithOptionsAndCustomAnswerFlag() {
        TerminalAgentService.AgentPlanQuestionDecision decision = service.parsePlanQuestionDecision("""
            {
              "status": "questions",
              "summary": "Need scope",
              "userMessage": "Choose the deployment target.",
              "questions": [
                {
                  "id": "q1",
                  "question": "Which target should be used?",
                  "options": ["Local", "Remote"],
                  "allowCustomAnswer": true
                }
              ]
            }
            """);

        TerminalAgentService.AgentPlanQuestionDecisionItem question = decision.questions().getFirst();
        assertThat(question.id()).isEqualTo("q1");
        assertThat(question.options()).isEqualTo(List.of("Local", "Remote"));
        assertThat(question.allowCustomAnswer()).isTrue();
    }

    @Test
    void parsesPlanningOptionsAndBlockedState() {
        TerminalAgentService.AgentPlanOptionDecision options = service.parsePlanOptionDecision("""
            {
              "status": "options",
              "summary": "Two paths",
              "userMessage": "Pick one.",
              "options": [
                {
                  "title": "Incremental",
                  "summary": "Small scoped change",
                  "feasibility": "High",
                  "risks": ["Regression"],
                  "prerequisites": ["Tests"],
                  "steps": ["Patch", "Test"],
                  "alternatives": ["Full rewrite"]
                }
              ]
            }
            """);

        assertThat(options.status()).isEqualTo("options");
        assertThat(options.options().getFirst().title()).isEqualTo("Incremental");

        TerminalAgentService.AgentPlanOptionDecision blocked = service.parsePlanOptionDecision("""
            {
              "status": "blocked",
              "summary": "Missing input",
              "userMessage": "Need a target host.",
              "options": []
            }
            """);

        assertThat(blocked.status()).isEqualTo("blocked");
    }

    @Test
    void parsesFinalPlanReportAndRejectsNonFinalStatus() {
        TerminalAgentService.AgentPlanReportDecision report = service.parsePlanReportDecision("""
            {
              "status": "final_plan",
              "title": "Install package",
              "summary": "Install and validate the package.",
              "userMessage": "Ready.",
              "prerequisites": ["Sudo access"],
              "steps": ["Install package", "Verify service"],
              "risks": ["Package unavailable"],
              "successCriteria": ["Service is active"]
            }
            """);

        assertThat(report.title()).isEqualTo("Install package");
        assertThat(report.steps()).isEqualTo(List.of("Install package", "Verify service"));

        expectThrows(JsonSyntaxException.class, () -> service.parsePlanReportDecision("""
            {
              "status": "options",
              "title": "Wrong state",
              "summary": "Not final.",
              "steps": ["Do work"]
            }
            """));
    }

    @Test
    void acceptedPlanContextIncludesCompleteFinalReport() {
        String context = service.buildAcceptedPlanContext(new TerminalAgentModels.PlanReport(
            "Install package",
            "Install and validate the package.",
            List.of("Sudo access"),
            List.of("Install package", "Verify service"),
            List.of("Package unavailable"),
            List.of("Service is active")));

        assertThat(context.contains("Accepted final plan: Install package")).isTrue();
        assertThat(context.contains("Summary: Install and validate the package.")).isTrue();
        assertThat(context.contains("Prerequisites: Sudo access")).isTrue();
        assertThat(context.contains("Risks: Package unavailable")).isTrue();
        assertThat(context.contains("Success criteria: Service is active")).isTrue();
        assertThat(context.contains("- Install package")).isTrue();
        assertThat(context.contains("- Verify service")).isTrue();
    }
}
