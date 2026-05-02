package de.kortty.core;

import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TerminalAgentDecisionRepairTest {

    @Test
    void repairsAgentDecisionThatContainsTooManyCommands() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        AiServiceTestDouble aiService = new AiServiceTestDouble(
            """
                {
                  "status": "run_commands",
                  "summary": "Need several steps",
                  "userMessage": "Running checks.",
                  "commands": [
                    {"command": "echo one", "purpose": "Step one", "risk": "read_only"},
                    {"command": "echo two", "purpose": "Step two", "risk": "read_only"},
                    {"command": "echo three", "purpose": "Step three", "risk": "read_only"},
                    {"command": "echo four", "purpose": "Step four", "risk": "read_only"}
                  ],
                  "needsReprobe": false
                }
                """,
            """
                {
                  "status": "run_commands",
                  "summary": "Use the next safe batch",
                  "userMessage": "Running the first step.",
                  "commands": [
                    {"command": "echo one", "purpose": "Step one", "risk": "read_only"}
                  ],
                  "needsReprobe": false
                }
                """);

        TerminalAgentService.AgentDecision decision = service.requestAgentDecision(
            aiService,
            request(),
            probe(),
            List.of(),
            1,
            false,
            new RunUiTestDouble(),
            "run-1");

        assertEquals(TerminalAgentService.AgentDecisionStatus.run_commands, decision.status());
        assertEquals(1, decision.commands().size());
        assertEquals(2, aiService.userPrompts().size());
        assertTrue(aiService.userPrompts().get(1).contains("The AI agent returned too many commands."));
        assertTrue(aiService.userPrompts().get(1).contains("Return at most 3 commands."));
    }

    private TerminalAgentModels.Request request() {
        return new TerminalAgentModels.Request(
            "session-1",
            "profile-1",
            "Create a small script.",
            "Fedora44",
            "",
            TerminalAgentExecutionTarget.CHAT_WINDOW,
            false,
            false,
            false,
            false,
            false);
    }

    private TerminalAgentModels.ProbeSnapshot probe() {
        return new TerminalAgentModels.ProbeSnapshot(
            "Fedora Linux 44",
            "kernel",
            "aarch64",
            "bash",
            "daniel",
            "1000",
            "1000",
            List.of("wheel"),
            "/home/daniel",
            "/home/daniel",
            null,
            "",
            List.of("dnf"),
            List.of("systemctl"),
            false,
            true,
            false,
            false,
            "",
            "sudo_password");
    }

    /** Test double for deterministic AI responses. */
    private static final class AiServiceTestDouble extends OpenAiCompatibleAiService {
        private final Queue<String> responses;
        private final List<String> userPrompts = new ArrayList<>();

        private AiServiceTestDouble(String... responses) {
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

    /** Test double for the terminal-agent UI callbacks. */
    private static final class RunUiTestDouble implements TerminalAgentService.RunUi {
        @Override
        public void updateState(TerminalAgentModels.RunState state) {
        }

        @Override
        public void appendTranscript(String text) {
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest request) {
            return null;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    }
}
