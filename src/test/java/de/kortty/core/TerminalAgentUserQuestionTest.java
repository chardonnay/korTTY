package de.kortty.core;

import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.memory.MemoryService;
import de.kortty.memory.MemoryStore;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * The agent's question during a run: it is asked on the solution card in its question form, the answer binds the
 * following turns, a UI without prompts lets the agent decide by itself, and stopping at the question ends the run.
 * Also the note that tells the planner when the same error came back from more than one command.
 */
class TerminalAgentUserQuestionTest {

    private static final String ASK = """
        {"status":"needs_input","summary":"Port 80 is taken","userMessage":"Apache httpd already listens on port 80.",
         "question":"Which port should nginx listen on?","answers":["8081 (free)","8888 (free)"],
         "commands":[],"needsReprobe":false}
        """;
    private static final String DONE = """
        {"status":"done","summary":"Done","userMessage":"Finished.","commands":[],"needsReprobe":false}
        """;
    private static final String SIMPLE_TRIAGE = """
        {"complexity": "simple", "reasons": [], "recommended": 1, "options": []}
        """;

    private Path root;

    @BeforeMethod
    void useTemporaryMemory() throws IOException {
        root = Files.createTempDirectory("kortty-agent-question-");
        MemoryService service = new MemoryService(
            new MemoryStore(root.resolve("memory"), Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC)),
            name -> null);
        service.setAiMemoryAllowedSupplier(() -> false);
        MemoryService.replaceInstanceForTesting(service);
    }

    @AfterMethod(alwaysRun = true)
    void restoreMemory() throws IOException {
        MemoryService.replaceInstanceForTesting(null);
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void theQuestionIsAskedOnTheCardAndTheAnswerBindsTheNextTurn() throws Exception {
        QuestionUi ui = new QuestionUi(true, TerminalAgentModels.SolutionChoice.option(1, null));
        ScriptedAi ai = new ScriptedAi(call -> call == 1 ? ASK : DONE);

        new TerminalAgentService().runAgent(null, runner(), cloud(), ai, request(), "run-ask", ui);

        assertThat(ui.questions).hasSize(1);
        TerminalAgentModels.TriageProposal card = ui.questions.get(0);
        assertThat(card.isQuestion()).isTrue();
        assertThat(card.question()).isEqualTo("Which port should nginx listen on?");
        assertThat(card.reasons()).containsExactly("Apache httpd already listens on port 80.");
        assertThat(card.options().stream().map(TerminalAgentModels.PlanOption::title).toList())
            .containsExactly("8081 (free)", "8888 (free)").inOrder();
        assertThat(ai.agentUserPrompts).hasSize(2);
        assertThat(ai.agentUserPrompts.get(1))
            .contains("The user answered your question \"Which port should nginx listen on?\": 8888 (free).");
        assertThat(ui.messages()).contains("Your answer: 8888 (free)");
        assertThat(ui.lastPhase()).isEqualTo(TerminalAgentModels.Phase.DONE);
    }

    @Test
    void aQuestionDoesNotUseUpAPlanningTurn() throws Exception {
        QuestionUi ui = new QuestionUi(true, TerminalAgentModels.SolutionChoice.ownIdea("9090", null));
        ScriptedAi ai = new ScriptedAi(call -> call == 1 ? ASK : DONE);

        new TerminalAgentService().runAgent(null, runner(), cloud(), ai, request(), "run-turn", ui);

        assertThat(ai.agentUserPrompts.get(0)).contains("Turn: 1/");
        assertThat(ai.agentUserPrompts.get(1)).contains("Turn: 1/");
        assertThat(ai.agentUserPrompts.get(1)).contains(": 9090.");
    }

    @Test
    void stoppingAtTheQuestionEndsTheRun() throws Exception {
        QuestionUi ui = new QuestionUi(true, TerminalAgentModels.SolutionChoice.cancel());
        ScriptedAi ai = new ScriptedAi(call -> ASK);

        new TerminalAgentService().runAgent(null, runner(), cloud(), ai, request(), "run-stop", ui);

        assertThat(ai.agentUserPrompts).hasSize(1);
        assertThat(ui.lastPhase()).isEqualTo(TerminalAgentModels.Phase.CANCELLED);
    }

    @Test
    void aUiWithoutPromptsLetsTheAgentDecideByItself() throws Exception {
        QuestionUi ui = new QuestionUi(false, null);
        ScriptedAi ai = new ScriptedAi(call -> call == 1 ? ASK : DONE);

        new TerminalAgentService().runAgent(null, runner(), cloud(), ai, request(), "run-headless", ui);

        assertThat(ui.questions).isEmpty();
        assertThat(ai.agentUserPrompts.get(1)).contains("Nobody could answer your question");
        assertThat(ai.agentUserPrompts.get(1)).contains("Decide by yourself");
        assertThat(ai.agentUserPrompts.get(1)).contains("Turn: 2/");
    }

    @Test
    void afterThreeQuestionsTheAgentDecidesByItself() throws Exception {
        QuestionUi ui = new QuestionUi(true, TerminalAgentModels.SolutionChoice.option(0, null));
        ScriptedAi ai = new ScriptedAi(call -> call <= 4 ? ASK : DONE);

        new TerminalAgentService().runAgent(null, runner(), cloud(), ai, request(), "run-limit", ui);

        assertThat(ui.questions).hasSize(TerminalAgentService.MAX_USER_QUESTIONS);
        assertThat(ai.agentUserPrompts.get(4)).contains("Nobody could answer your question");
    }

    @Test
    void theStatusAliasesAndAQuestionOnlyInUserMessageCount() throws Exception {
        ScriptedAi ai = new ScriptedAi(call -> call == 1
            ? "{\"status\":\"ask_user\",\"summary\":\"Need a port\",\"userMessage\":\"Which port should nginx use?\","
                + "\"commands\":[],\"needsReprobe\":false}"
            : DONE);
        QuestionUi ui = new QuestionUi(true, TerminalAgentModels.SolutionChoice.ownIdea("8081", null));

        new TerminalAgentService().runAgent(null, runner(), cloud(), ai, request(), "run-alias", ui);

        assertThat(ui.questions).hasSize(1);
        assertThat(ui.questions.get(0).question()).isEqualTo("Which port should nginx use?");
        assertThat(ui.questions.get(0).reasons()).isEmpty();
        assertThat(ui.questions.get(0).options()).isEmpty();
    }

    @Test
    void thePromptOffersTheQuestionAndTheConflictRules() throws Exception {
        ScriptedAi ai = new ScriptedAi(call -> DONE);

        new TerminalAgentService().runAgent(null, runner(), cloud(), ai, request(), "run-rules", new QuestionUi(true, null));

        String system = ai.agentSystemPrompts.get(0);
        assertThat(system).contains("`needs_input`");
        assertThat(system).contains("\"question\"");
        assertThat(system).contains("ss -ltnp");
        assertThat(system).contains("/etc/nginx/nginx.conf");
        assertThat(system).contains("Never repeat a command or approach that already failed");
        assertThat(system).contains("sudo -n tee");
    }

    @Test
    void answersCombineTheChoiceWithTypedInstructions() {
        TerminalAgentModels.TriageProposal card = TerminalAgentModels.TriageProposal.question(
            "Which port?", List.of(), List.of("8081", "8888"), null, null, null);

        assertThat(TerminalAgentService.answerOf(TerminalAgentModels.SolutionChoice.option(0, null), card))
            .isEqualTo("8081");
        assertThat(TerminalAgentService.answerOf(TerminalAgentModels.SolutionChoice.option(1, "and open the firewall"), card))
            .isEqualTo("8888 (and open the firewall)");
        assertThat(TerminalAgentService.answerOf(TerminalAgentModels.SolutionChoice.ownIdea(" 9090 ", null), card))
            .isEqualTo("9090");
        assertThat(TerminalAgentService.answerOf(TerminalAgentModels.SolutionChoice.asRequested(), card)).isNull();
        assertThat(TerminalAgentService.answerOf(TerminalAgentModels.SolutionChoice.option(5, null), card)).isNull();
    }

    @Test
    void theSameErrorFromTwoFailedCommandsIsNamedForThePlanner() {
        TerminalAgentModels.CommandResult first = failed("sudo -n systemctl enable --now nginx",
            "Job for nginx.service failed because the control process exited with error code.\nSee details.");
        TerminalAgentModels.CommandResult second = failed("sudo -n systemctl restart nginx",
            "Job for nginx.service failed because the control process exited with error code.");
        TerminalAgentModels.CommandResult other = failed("cat > /etc/nginx/conf.d/server.conf",
            "bash: line 1: /etc/nginx/conf.d/server.conf: Permission denied");

        String note = TerminalAgentService.repeatedFailureNote(List.of(first, other, second));

        assertThat(note).startsWith("Repeated failure:");
        assertThat(note).contains("Job for nginx.service failed because the control process exited with error code.");
        assertThat(note).doesNotContain("Permission denied");
        assertThat(note).contains("`needs_input`");
    }

    @Test
    void differentErrorsSucceededCommandsAndPlainOutputGiveNoNote() {
        TerminalAgentModels.CommandResult ok = new TerminalAgentModels.CommandResult("ss -ltn", "ports", null, 0, null,
            "Error-free output that mentions error twice: error", "", false, false, false, false);
        TerminalAgentModels.CommandResult okAgain = new TerminalAgentModels.CommandResult("ss -ltn", "ports", null, 0, null,
            "Error-free output that mentions error twice: error", "", false, false, false, false);
        TerminalAgentModels.CommandResult port = failed("nginx", "nginx: [emerg] bind() to 0.0.0.0:80 failed (98: Address in use)");
        TerminalAgentModels.CommandResult other = failed("dnf install nginx-mod-http2", "No match for argument: nginx-mod-http2");
        TerminalAgentModels.CommandResult header = failed("printf", "--- port 80 ---");
        TerminalAgentModels.CommandResult headerAgain = failed("printf", "--- port 80 ---");

        assertThat(TerminalAgentService.repeatedFailureNote(List.of(ok, okAgain, port, other, header, headerAgain)))
            .isEmpty();
    }

    @Test
    void numbersDoNotHideARepeatedError() {
        String note = TerminalAgentService.repeatedFailureNote(List.of(
            failed("nginx", "2026/11/02 15:28:12 [emerg] 8023#8023: bind() to 0.0.0.0:80 failed (98: Address in use)"),
            failed("nginx", "2026/11/02 15:29:40 [emerg] 8101#8101: bind() to 0.0.0.0:80 failed (98: Address in use)")));

        assertThat(note).contains("bind() to 0.0.0.0:80 failed");
    }

    private static TerminalAgentModels.CommandResult failed(String command, String stderr) {
        return new TerminalAgentModels.CommandResult(command, "test", null, 1, null, "", stderr,
            false, false, false, false);
    }

    private static AiProfile cloud() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.com/v1");
        return profile;
    }

    private static TerminalAgentModels.Request request() {
        return new TerminalAgentModels.Request(
            "session-1", "profile-1", "Install a web server", "test-host", null,
            TerminalAgentExecutionTarget.CHAT_WINDOW,
            false, false, false, false, false, false);
    }

    /** Answers the complexity check with "simple" and plays the script for the agent's own turns. */
    private static final class ScriptedAi implements AiPromptService {
        final List<String> agentSystemPrompts = new CopyOnWriteArrayList<>();
        final List<String> agentUserPrompts = new CopyOnWriteArrayList<>();
        private final AtomicInteger agentCalls = new AtomicInteger();
        private final IntFunction<String> script;

        ScriptedAi(IntFunction<String> script) {
            this.script = script;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return executeJsonPrompt(systemPrompt, userPrompt);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            if (systemPrompt.contains("complexity check for a terminal agent run")) {
                return new AiExecutionResult(SIMPLE_TRIAGE, null);
            }
            agentSystemPrompts.add(systemPrompt);
            agentUserPrompts.add(userPrompt);
            return new AiExecutionResult(script.apply(agentCalls.incrementAndGet()), null);
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    private static final class QuestionUi implements TerminalAgentService.RunUi {
        final List<TerminalAgentModels.TriageProposal> questions = new CopyOnWriteArrayList<>();
        private final List<TerminalAgentModels.RunState> states = new CopyOnWriteArrayList<>();
        private final List<TerminalAgentModels.AgentActivity> activities = new CopyOnWriteArrayList<>();
        private final boolean supportsChoices;
        private final TerminalAgentModels.SolutionChoice answer;

        QuestionUi(boolean supportsChoices, TerminalAgentModels.SolutionChoice answer) {
            this.supportsChoices = supportsChoices;
            this.answer = answer;
        }

        @Override
        public boolean supportsChoices() {
            return supportsChoices;
        }

        @Override
        public TerminalAgentModels.SolutionChoice requestSolutionChoice(TerminalAgentModels.TriageProposal proposal) {
            if (proposal.isQuestion()) {
                questions.add(proposal);
                return answer;
            }
            return TerminalAgentModels.SolutionChoice.asRequested();
        }

        @Override
        public void publishActivity(TerminalAgentModels.AgentActivity activity) {
            activities.add(activity);
        }

        List<String> messages() {
            return activities.stream().map(TerminalAgentModels.AgentActivity::title).toList();
        }

        TerminalAgentModels.Phase lastPhase() {
            return states.isEmpty() ? null : states.get(states.size() - 1).phase();
        }

        @Override
        public void updateState(TerminalAgentModels.RunState state) {
            states.add(state);
        }

        @Override
        public void appendTranscript(String text) {
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
            return TerminalAgentService.ApprovalDecision.APPROVE_ONCE;
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

    /** A connected POSIX runner whose commands all succeed. */
    private static AgentCommandRunner runner() {
        return new AgentCommandRunner() {
            @Override
            public ExecResult exec(String command, byte[] stdin, Consumer<String> outputConsumer,
                                   BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) {
                return new ExecResult("ok\n", "", 0, false, false);
            }

            @Override
            public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
                String stdout = String.join("\n",
                    "osRelease=Fedora 44",
                    "shell=/bin/bash",
                    "currentUser=daniel",
                    "homeDir=/home/daniel",
                    "currentDir=/home/daniel",
                    "sudoAvailable=false");
                return new ExecResult(stdout, "", 0, false, false);
            }

            @Override
            public ShellKind shellKind() {
                return ShellKind.POSIX;
            }

            @Override
            public String currentWorkingDirectory() {
                return "/home/daniel";
            }

            @Override
            public boolean isConnected() {
                return true;
            }
        };
    }
}
