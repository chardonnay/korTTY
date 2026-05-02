package de.kortty.core;

import com.google.gson.JsonSyntaxException;
import de.kortty.model.TerminalAgentModels;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals("q1", question.id());
        assertEquals(List.of("Local", "Remote"), question.options());
        assertTrue(question.allowCustomAnswer());
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

        assertEquals("options", options.status());
        assertEquals("Incremental", options.options().getFirst().title());

        TerminalAgentService.AgentPlanOptionDecision blocked = service.parsePlanOptionDecision("""
            {
              "status": "blocked",
              "summary": "Missing input",
              "userMessage": "Need a target host.",
              "options": []
            }
            """);

        assertEquals("blocked", blocked.status());
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

        assertEquals("Install package", report.title());
        assertEquals(List.of("Install package", "Verify service"), report.steps());

        assertThrows(JsonSyntaxException.class, () -> service.parsePlanReportDecision("""
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

        assertTrue(context.contains("Accepted final plan: Install package"));
        assertTrue(context.contains("Summary: Install and validate the package."));
        assertTrue(context.contains("Prerequisites: Sudo access"));
        assertTrue(context.contains("Risks: Package unavailable"));
        assertTrue(context.contains("Success criteria: Service is active"));
        assertTrue(context.contains("- Install package"));
        assertTrue(context.contains("- Verify service"));
    }
}
