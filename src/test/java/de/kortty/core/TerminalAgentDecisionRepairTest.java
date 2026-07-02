package de.kortty.core;

import de.kortty.model.AiSkillTarget;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import static com.google.common.truth.Truth.assertThat;


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
            probe("/home/daniel/Dokumente"),
            List.of(),
            1,
            false,
            new RunUiTestDouble(),
            "run-1");

        assertThat(decision.status()).isEqualTo(TerminalAgentService.AgentDecisionStatus.run_commands);
        assertThat(decision.commands().size()).isEqualTo(1);
        assertThat(aiService.userPrompts().size()).isEqualTo(2);
        assertThat(aiService.userPrompts().get(1).contains("The AI agent returned too many commands.")).isTrue();
        assertThat(aiService.userPrompts().get(1).contains("Return at most 3 commands.")).isTrue();
        assertThat(aiService.userPrompts().get(1)).contains("Active terminal working directory: /home/daniel/Dokumente");
        assertThat(aiService.userPrompts().get(1)).contains("\"currentDir\":\"/home/daniel/Dokumente\"");
    }

    @Test
    void responseFormatFallbackUsesJsonFallbackPath() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        UnsupportedResponseFormatAiService aiService = new UnsupportedResponseFormatAiService("""
            {
              "status": "done",
              "summary": "Created script guidance",
              "userMessage": "Use find and sort to list the largest XML files.",
              "commands": [],
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

        assertThat(decision.status()).isEqualTo(TerminalAgentService.AgentDecisionStatus.done);
        assertThat(aiService.jsonFallbackCalls()).isEqualTo(1);
        assertThat(aiService.plainPromptCalls()).isEqualTo(0);
    }

    @Test
    void acceptsSingleTopLevelCommandFieldFromJsonStrictModels() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        AiServiceTestDouble aiService = new AiServiceTestDouble("""
            {
              "status": "needs_confirmation",
              "summary": "Install the package",
              "userMessage": "I will install gpg-pubkey with dnf.",
              "command": "sudo -n dnf install -y gpg-pubkey",
              "purpose": "Install the requested gpg-pubkey package",
              "risk": "requires confirmation",
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

        assertThat(decision.status()).isEqualTo(TerminalAgentService.AgentDecisionStatus.needs_confirmation);
        assertThat(decision.commands()).hasSize(1);
        assertThat(decision.commands().get(0).command()).isEqualTo("sudo -n dnf install -y gpg-pubkey");
        assertThat(decision.commands().get(0).purpose()).isEqualTo("Install the requested gpg-pubkey package");
        assertThat(decision.commands().get(0).risk()).isEqualTo("requires_confirmation");
    }

    @Test
    void acceptsCommandObjectInsteadOfCommandArray() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        AiServiceTestDouble aiService = new AiServiceTestDouble("""
            {
              "status": "run_commands",
              "summary": "Inspect package availability",
              "userMessage": "I will check the package name first.",
              "commands": {
                "cmd": "dnf info gpg-pubkey",
                "description": "Check whether the package is available",
                "risk": "low"
              },
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

        assertThat(decision.status()).isEqualTo(TerminalAgentService.AgentDecisionStatus.run_commands);
        assertThat(decision.commands()).hasSize(1);
        assertThat(decision.commands().get(0).command()).isEqualTo("dnf info gpg-pubkey");
        assertThat(decision.commands().get(0).purpose()).isEqualTo("Check whether the package is available");
        assertThat(decision.commands().get(0).risk()).isEqualTo("read_only");
    }

    @Test
    void publishesUsedAiSkillsInAgentActivityLog() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        SkillUsageAiService aiService = new SkillUsageAiService("""
            {
              "status": "done",
              "summary": "No commands needed",
              "userMessage": "Done.",
              "commands": [],
              "needsReprobe": false
            }
            """);
        RunUiTestDouble ui = new RunUiTestDouble();

        service.requestAgentDecision(
            aiService,
            request(),
            probe(),
            List.of(),
            1,
            false,
            ui,
            "run-1");

        List<String> skillSummaries = ui.activities().stream()
            .filter(activity -> "AI Skills".equals(activity.title()))
            .map(TerminalAgentModels.AgentActivity::summary)
            .toList();
        assertThat(skillSummaries).containsExactly("Using AI skill: bash-quality");
    }

    @Test
    void derivesUserMessageWhenModelOmitsItInsteadOfFailing() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        AiServiceTestDouble aiService = new AiServiceTestDouble("""
            {
              "status": "done",
              "summary": "Listed the top failures",
              "commands": [],
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

        assertThat(decision.status()).isEqualTo(TerminalAgentService.AgentDecisionStatus.done);
        // userMessage is derived from summary so the run is not failed for a missing field.
        assertThat(decision.userMessage()).isEqualTo("Listed the top failures");
        // No repair round-trip was needed.
        assertThat(aiService.userPrompts().size()).isEqualTo(1);
    }

    @Test
    void derivesSummaryWhenModelOmitsItInsteadOfFailing() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        AiServiceTestDouble aiService = new AiServiceTestDouble("""
            {
              "status": "done",
              "userMessage": "Top failures:\\n49 server-error-device-error\\n3 client-error-not-found",
              "commands": [],
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

        assertThat(decision.status()).isEqualTo(TerminalAgentService.AgentDecisionStatus.done);
        // summary is derived from the first line of userMessage.
        assertThat(decision.summary()).isEqualTo("Top failures:");
        assertThat(decision.userMessage()).contains("49 server-error-device-error");
        assertThat(aiService.userPrompts().size()).isEqualTo(1);
    }

    @Test
    void showsModelReasoningAsThinkingDetailWhenAvailable() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        ReasoningAiService aiService = new ReasoningAiService(
            """
            {"status":"done","summary":"Short summary","userMessage":"All done.","commands":[],"needsReprobe":false}
            """,
            "First I inspected the probe, then I decided nothing else was needed.");
        RunUiTestDouble ui = new RunUiTestDouble();

        service.requestAgentDecision(aiService, request(), probe(), List.of(), 1, false, ui, "run-1");

        TerminalAgentModels.AgentActivity thinking = lastCompletedThinking(ui);
        assertThat(thinking.detail())
            .isEqualTo("First I inspected the probe, then I decided nothing else was needed.");
        // The collapsed header still shows the user-facing message, not the reasoning.
        assertThat(thinking.summary()).isEqualTo("All done.");
    }

    @Test
    void fallsBackToDecisionSummaryWhenModelHasNoReasoning() throws Exception {
        TerminalAgentService service = new TerminalAgentService();
        AiServiceTestDouble aiService = new AiServiceTestDouble("""
            {"status":"done","summary":"Short summary","userMessage":"All done.","commands":[],"needsReprobe":false}
            """);
        RunUiTestDouble ui = new RunUiTestDouble();

        service.requestAgentDecision(aiService, request(), probe(), List.of(), 1, false, ui, "run-1");

        TerminalAgentModels.AgentActivity thinking = lastCompletedThinking(ui);
        assertThat(thinking.detail()).isEqualTo("Short summary");
    }

    private static TerminalAgentModels.AgentActivity lastCompletedThinking(RunUiTestDouble ui) {
        return ui.activities().stream()
            .filter(activity -> activity.type() == TerminalAgentModels.AgentActivityType.THINKING
                && activity.status() == TerminalAgentModels.AgentActivityStatus.COMPLETED)
            .reduce((first, second) -> second)
            .orElseThrow();
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
            false,
            false);
    }

    private TerminalAgentModels.ProbeSnapshot probe() {
        return probe("/home/daniel");
    }

    private TerminalAgentModels.ProbeSnapshot probe(String currentDirectory) {
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
            currentDirectory,
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

    /** Test double that returns a fixed decision plus model reasoning. */
    private static final class ReasoningAiService extends OpenAiCompatibleAiService {
        private final String response;
        private final String reasoning;

        private ReasoningAiService(String response, String reasoning) {
            super("http://localhost", "test-model", "");
            this.response = response;
            this.reasoning = reasoning;
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(response, null, reasoning);
        }
    }

    /** Test double for AI providers that reject the OpenAI response_format option. */
    private static final class UnsupportedResponseFormatAiService extends OpenAiCompatibleAiService {
        private final String fallbackResponse;
        private int jsonFallbackCalls;
        private int plainPromptCalls;

        private UnsupportedResponseFormatAiService(String fallbackResponse) {
            super("http://localhost", "test-model", "");
            this.fallbackResponse = fallbackResponse;
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws IOException {
            throw new IOException("response_format is not supported");
        }

        @Override
        public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) {
            jsonFallbackCalls++;
            return new AiExecutionResult(fallbackResponse, null);
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            plainPromptCalls++;
            return new AiExecutionResult(fallbackResponse, null);
        }

        private int jsonFallbackCalls() {
            return jsonFallbackCalls;
        }

        private int plainPromptCalls() {
            return plainPromptCalls;
        }
    }

    /** Test double that simulates an AI service reporting one used skill. */
    private static final class SkillUsageAiService implements AiPromptService, AiSkillUsageTracker {
        private final String response;
        private boolean usagesDrained;

        private SkillUsageAiService(String response) {
            this.response = response;
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            return new AiExecutionResult(response, null);
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(response, null);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return new AiExecutionResult(response, null);
        }

        @Override
        public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
            if (usagesDrained) {
                return List.of();
            }
            usagesDrained = true;
            return List.of(new AiSkillPromptSupport.SkillUsage("skill-1", "bash-quality", AiSkillTarget.AGENT));
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    /** Test double for the terminal-agent UI callbacks. */
    private static final class RunUiTestDouble implements TerminalAgentService.RunUi {
        private final List<TerminalAgentModels.AgentActivity> activities = new ArrayList<>();

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

        @Override
        public void publishActivity(TerminalAgentModels.AgentActivity activity) {
            activities.add(activity);
        }

        private List<TerminalAgentModels.AgentActivity> activities() {
            return activities;
        }
    }
}
