package de.kortty.core;

import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.core.agent.AgentOutboundContext;
import de.kortty.core.agent.JournalingAgentCommandRunner;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import de.kortty.ui.I18n;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntFunction;

import static com.google.common.truth.Truth.assertThat;

/**
 * The terminal agent masks every prompt it sends to a cloud AI profile at its single AI choke
 * point — the system prompt with the probe snapshot, the history, the repair and turn-limit
 * prompts and the planning prompts — while integrated models and a trusted loopback endpoint get
 * the raw text and the run itself keeps it.
 */
class TerminalAgentServiceOutboundRedactionTest {

    private static final String PASSWORD = "Conn-Passw0rd-4711";
    // Split so secret scanners do not flag the fixtures.
    private static final String AWS_KEY_ID = "AKIA" + "QWERTYUIOPASDFGH";
    private static final String GITHUB_TOKEN = "ghp" + "_" + "abcdefghijklmnopqrstuvwxyz0123456789";

    private static final String RUN_COMMAND = """
        {"status":"run_commands","summary":"Read the config","userMessage":"Reading.",
         "commands":[{"command":"cat /etc/app.conf","purpose":"Read the config","risk":"read_only"}],
         "needsReprobe":false}
        """;
    private static final String DONE = """
        {"status":"done","summary":"Done","userMessage":"Finished.","commands":[],"needsReprobe":false}
        """;

    // ---------------------------------------------------------------- fixtures

    private static AiProfile profile(AiConnectionMode mode, String apiUrl) {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(mode);
        profile.setApiUrl(apiUrl);
        return profile;
    }

    private static AiProfile cloud() {
        return profile(AiConnectionMode.HTTP_API, "https://api.example.com/v1");
    }

    private static AiProfile trustedLoopback() {
        AiProfile profile = profile(AiConnectionMode.HTTP_API, "http://127.0.0.1:1234/v1");
        profile.setTrustedLocalEndpoint(true);
        return profile;
    }

    private static TerminalAgentModels.Request request() {
        return new TerminalAgentModels.Request(
            "session-1", "profile-1", "Show the app config", "test-host", null,
            TerminalAgentExecutionTarget.CHAT_WINDOW,
            false, false, false, false, false, false);
    }

    private static SessionJournalRedactor secrets(String password) {
        SessionJournalRedactor redactor = new SessionJournalRedactor();
        redactor.addSecret(password);
        return redactor;
    }

    /** Records every prompt pair it receives and answers from a script indexed by call number. */
    private static final class CapturingAiService implements AiPromptService {
        final List<String> systemPrompts = new CopyOnWriteArrayList<>();
        final List<String> userPrompts = new CopyOnWriteArrayList<>();
        private final AtomicInteger calls = new AtomicInteger();
        private final IntFunction<String> script;
        private final Runnable onCall;

        CapturingAiService(IntFunction<String> script) {
            this(script, null);
        }

        CapturingAiService(IntFunction<String> script, Runnable onCall) {
            this.script = script;
            this.onCall = onCall;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            return executeJsonPrompt(systemPrompt, userPrompt);
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            systemPrompts.add(systemPrompt);
            userPrompts.add(userPrompt);
            if (onCall != null) {
                onCall.run();
            }
            return new AiExecutionResult(script.apply(calls.incrementAndGet()), null);
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean testConnection() {
            return true;
        }

        List<String> allPrompts() {
            List<String> all = new ArrayList<>(systemPrompts);
            all.addAll(userPrompts);
            return all;
        }
    }

    /** A connected POSIX runner whose probe and command output carry the fixture secrets. */
    private static final class SecretRunner implements AgentCommandRunner {
        final List<String> executed = new CopyOnWriteArrayList<>();
        private final String password;
        private final String probeExtra;
        private final String output;

        SecretRunner(String password, String probeExtra, String output) {
            this.password = password;
            this.probeExtra = probeExtra;
            this.output = output;
        }

        @Override
        public ExecResult exec(String command, byte[] stdin, Consumer<String> outputConsumer,
                               BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) {
            executed.add(command);
            if (outputConsumer != null) {
                outputConsumer.accept(output);
            }
            return new ExecResult(output, "", 0, false, false);
        }

        @Override
        public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
            String stdout = String.join("\n",
                "osRelease=Ubuntu 24.04 " + probeExtra,
                "shell=/bin/bash",
                "currentUser=deploy",
                "homeDir=/home/deploy",
                "currentDir=/home/deploy",
                "sudoAvailable=false");
            return new ExecResult(stdout, "", 0, false, false);
        }

        @Override
        public ShellKind shellKind() {
            return ShellKind.POSIX;
        }

        @Override
        public String currentWorkingDirectory() {
            return "/home/deploy";
        }

        @Override
        public boolean isConnected() {
            return true;
        }

        @Override
        public SessionJournalRedactor knownSecrets() {
            return password != null ? secrets(password) : null;
        }
    }

    private static final class RecordingUi implements TerminalAgentService.RunUi {
        final StringBuilder transcript = new StringBuilder();
        final List<TerminalAgentModels.AgentActivity> activities = new CopyOnWriteArrayList<>();
        final List<TerminalAgentModels.RunState> states = new CopyOnWriteArrayList<>();

        @Override
        public void updateState(TerminalAgentModels.RunState state) {
            states.add(state);
        }

        @Override
        public synchronized void appendTranscript(String text) {
            transcript.append(text);
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

        @Override
        public void publishActivity(TerminalAgentModels.AgentActivity activity) {
            activities.add(activity);
        }

        List<TerminalAgentModels.AgentActivity> maskedActivities() {
            return activities.stream().filter(activity -> activity.id().endsWith(":outbound-masked")).toList();
        }
    }

    private static String commandOutput() {
        // The AWS key starts a line: in the JSON history it follows an escaped "\n", which the
        // token pattern's word-boundary check would miss if the JSON were masked as a whole.
        return "db_password " + PASSWORD + " token " + GITHUB_TOKEN + "\n" + AWS_KEY_ID + "\n";
    }

    private static void assertNoSecretIn(List<String> prompts) {
        for (String prompt : prompts) {
            assertThat(prompt).doesNotContain(PASSWORD);
            assertThat(prompt).doesNotContain(AWS_KEY_ID);
            assertThat(prompt).doesNotContain(GITHUB_TOKEN);
        }
    }

    // ---------------------------------------------------------------- tests

    @Test
    void cloudProfileMasksProbeHistoryRepairAndTurnLimitPrompts() throws Exception {
        // Call 1 is invalid (repair), calls 2..9 plan a command each of the 8 turns, call 10 is
        // the turn-limit final prompt.
        CapturingAiService ai = new CapturingAiService(call -> switch (call) {
            case 1 -> "not json at all";
            case 10 -> DONE;
            default -> RUN_COMMAND;
        });
        SecretRunner runner = new SecretRunner(PASSWORD, AWS_KEY_ID + " " + PASSWORD, commandOutput());
        RecordingUi ui = new RecordingUi();

        new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "run-cloud", ui);

        assertThat(ai.userPrompts).hasSize(10);
        assertThat(ai.userPrompts.get(1)).contains("Your previous reply was invalid");
        assertThat(ai.userPrompts.get(9)).contains("Turn limit reached");
        assertNoSecretIn(ai.allPrompts());
        assertThat(ai.userPrompts.get(9)).contains(SessionJournalRedactor.REPLACEMENT);
        // The placeholder is explained to the model.
        assertThat(ai.systemPrompts.get(0)).contains(TerminalAgentService.MASKED_SECRET_NOTE);

        // The run itself keeps the raw text.
        assertThat(ui.transcript.toString()).contains(PASSWORD);
        assertThat(ui.transcript.toString()).contains(AWS_KEY_ID);
        assertThat(runner.executed).contains("cat /etc/app.conf");

        // Password, GitHub token and AWS key: three distinct values, however often they repeat.
        List<TerminalAgentModels.AgentActivity> masked = ui.maskedActivities();
        assertThat(masked).isNotEmpty();
        assertThat(masked.get(masked.size() - 1).summary())
            .isEqualTo(I18n.get("ai.agent.outbound.masked.summary", 3));
    }

    @Test
    void distinctCountStaysOneWhenTheSameTokenRepeatsAcrossTurns() throws Exception {
        CapturingAiService ai = new CapturingAiService(call -> call <= 3 ? RUN_COMMAND : DONE);
        SecretRunner runner = new SecretRunner(null, "", "key " + AWS_KEY_ID + "\n");
        RecordingUi ui = new RecordingUi();

        new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "run-repeat", ui);

        assertThat(runner.executed).hasSize(3);
        assertNoSecretIn(ai.allPrompts());
        assertThat(ui.maskedActivities()).isNotEmpty();
        for (TerminalAgentModels.AgentActivity activity : ui.maskedActivities()) {
            assertThat(activity.summary()).isEqualTo(I18n.get("ai.agent.outbound.masked.summary", 1));
        }
    }

    @Test
    void integratedModelsAndTrustedLoopbackGetTheRawText() throws Exception {
        for (AiProfile profile : List.of(
            profile(AiConnectionMode.EMBEDDED_LLAMA_CPP, null),
            profile(AiConnectionMode.EMBEDDED_MLX, null),
            trustedLoopback())) {
            CapturingAiService ai = new CapturingAiService(call -> call == 1 ? RUN_COMMAND : DONE);
            SecretRunner runner = new SecretRunner(PASSWORD, PASSWORD, commandOutput());
            RecordingUi ui = new RecordingUi();

            new TerminalAgentService().runAgent(null, runner, profile, ai, request(), "run-local", ui);

            assertThat(ai.userPrompts.get(0)).contains(PASSWORD);
            assertThat(ai.userPrompts.get(1)).contains(PASSWORD);
            assertThat(ai.userPrompts.get(1)).contains(GITHUB_TOKEN);
            assertThat(ui.maskedActivities()).isEmpty();
        }
    }

    @Test
    void journalingRunnerForwardsTheKnownSecrets() throws Exception {
        CapturingAiService ai = new CapturingAiService(call -> call == 1 ? RUN_COMMAND : DONE);
        SecretRunner inner = new SecretRunner(PASSWORD, "", "plain " + PASSWORD + "\n");
        AgentCommandRunner journaling = new JournalingAgentCommandRunner(inner, AutomationJournalRecorder.NOOP);

        new TerminalAgentService().runAgent(null, journaling, cloud(), ai, request(), "run-journal", new RecordingUi());

        assertNoSecretIn(ai.allPrompts());
        assertThat(ai.userPrompts.get(1)).contains("plain " + SessionJournalRedactor.REPLACEMENT);
    }

    @Test
    void concurrentRunsOnOneServiceDoNotCrossMask() throws Exception {
        String alpha = "alpha-Pass-1111";
        String bravo = "bravo-Pass-2222";
        String output = "values " + alpha + " and " + bravo + "\n";
        TerminalAgentService service = new TerminalAgentService();
        // Both runs wait for each other inside their first AI call, so they really overlap.
        CountDownLatch bothInside = new CountDownLatch(2);
        Runnable rendezvous = () -> {
            bothInside.countDown();
            try {
                bothInside.await(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        CapturingAiService aiAlpha = new CapturingAiService(call -> call == 1 ? RUN_COMMAND : DONE, rendezvous);
        CapturingAiService aiBravo = new CapturingAiService(call -> call == 1 ? RUN_COMMAND : DONE, rendezvous);
        SecretRunner runnerAlpha = new SecretRunner(alpha, "", output);
        SecretRunner runnerBravo = new SecretRunner(bravo, "", output);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> a = pool.submit(() -> {
                service.runAgent(null, runnerAlpha, cloud(), aiAlpha, request(), "run-alpha", new RecordingUi());
                return null;
            });
            Future<?> b = pool.submit(() -> {
                service.runAgent(null, runnerBravo, cloud(), aiBravo, request(), "run-bravo", new RecordingUi());
                return null;
            });
            a.get(30, TimeUnit.SECONDS);
            b.get(30, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }

        String alphaHistory = aiAlpha.userPrompts.get(1);
        String bravoHistory = aiBravo.userPrompts.get(1);
        assertThat(alphaHistory).doesNotContain(alpha);
        assertThat(alphaHistory).contains(bravo);
        assertThat(bravoHistory).doesNotContain(bravo);
        assertThat(bravoHistory).contains(alpha);
    }

    @Test
    void plannedCommandWithThePlaceholderIsRefusedNotExecuted() throws Exception {
        String placeholderCommand = """
            {"status":"run_commands","summary":"Log in","userMessage":"Logging in.",
             "commands":[{"command":"curl -s -u admin:*** https://example.com/health","purpose":"Query","risk":"read_only"}],
             "needsReprobe":false}
            """;
        CapturingAiService ai = new CapturingAiService(call -> call == 1 ? placeholderCommand : DONE);
        SecretRunner runner = new SecretRunner(PASSWORD, "", commandOutput());
        RecordingUi ui = new RecordingUi();

        new TerminalAgentService().runAgent(null, runner, cloud(), ai, request(), "run-placeholder", ui);

        assertThat(runner.executed).isEmpty();
        assertThat(ui.transcript.toString())
            .contains(I18n.get("ai.agent.outbound.placeholderRefused", SessionJournalRedactor.REPLACEMENT));
        // The next turn learns why, so it can plan without the masked value.
        assertThat(ai.userPrompts).hasSize(2);
        assertThat(ai.userPrompts.get(1)).contains("Not run: the command contains the masked-secret placeholder");
    }

    @Test
    void placeholderCheckMatchesOnlyTheMarker() {
        assertThat(TerminalAgentService.containsMaskPlaceholder("mysql -p*** -e 'x'")).isTrue();
        assertThat(TerminalAgentService.containsMaskPlaceholder("ls -la /tmp")).isFalse();
        assertThat(TerminalAgentService.containsMaskPlaceholder(null)).isFalse();
    }

    @Test
    void planningPromptsAndTheirRepairAreMasked() throws Exception {
        String validOptions = """
            {"status":"options","summary":"One path","userMessage":"Pick one.",
             "options":[{"title":"Plan","summary":"Do it","feasibility":"High",
               "risks":[],"prerequisites":[],"steps":["Step"],"alternatives":[]}]}
            """;
        CapturingAiService ai = new CapturingAiService(call -> call == 1 ? "{\"status\":\"options\"," : validOptions);
        TerminalAgentModels.ProbeSnapshot probe = new TerminalAgentModels.ProbeSnapshot(
            "Ubuntu " + AWS_KEY_ID, "", "", "bash", "deploy", "", "", List.of(), "/home/deploy",
            "/srv/" + PASSWORD, null, "", List.of(), List.of(), false, false, false, false, "", "");

        new TerminalAgentService().requestPlanningOptions(
            cloud(), ai,
            new TerminalAgentModels.PlanRequest("session-1", "profile-1", "Rotate " + GITHUB_TOKEN, "host"),
            probe, List.of(), "", null, secrets(PASSWORD));

        assertThat(ai.userPrompts).hasSize(2);
        assertNoSecretIn(ai.allPrompts());
        // The probe the caller holds is unchanged.
        assertThat(probe.currentDir()).isEqualTo("/srv/" + PASSWORD);
    }

    @Test
    void maskStructuredLeavesTheOriginalUntouchedAndCountsDistinctValues() {
        List<Integer> reported = Collections.synchronizedList(new ArrayList<>());
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), reported::add, secrets(PASSWORD));
        List<TerminalAgentModels.CommandResult> history = List.of(
            new TerminalAgentModels.CommandResult("cat a", "read", TerminalAgentModels.Risk.READ_ONLY, 0, null,
                PASSWORD + "\n" + AWS_KEY_ID, "", false, false, false, false));

        for (int i = 0; i < 3; i++) {
            List<TerminalAgentModels.CommandResult> masked = context.maskStructured(
                history, new com.google.gson.reflect.TypeToken<List<TerminalAgentModels.CommandResult>>() { }.getType());
            assertThat(masked.get(0).stdoutTail()).isEqualTo("***\nAKIA***");
            assertThat(masked.get(0).exitStatus()).isEqualTo(0);
        }

        assertThat(history.get(0).stdoutTail()).contains(PASSWORD);
        assertThat(context.distinctMaskedCount()).isEqualTo(2);
        assertThat(reported).containsExactly(2);
    }

    @Test
    void sudoPasswordAddedDuringTheRunIsMaskedFromThenOn() {
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), null);
        assertThat(context.mask("echo SudoPw-98765")).isEqualTo("echo SudoPw-98765");
        context.addSecret("SudoPw-98765".toCharArray());
        assertThat(context.mask("echo SudoPw-98765")).isEqualTo("echo ***");
    }
}
