package de.kortty.core.swarm;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiOutboundRedaction;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import de.kortty.core.AiTokenUsage;
import de.kortty.core.SessionJournalRedactor;
import de.kortty.core.TerminalAgentService;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentModels;
import de.kortty.ui.TerminalTab;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.google.common.truth.Truth.assertThat;

/**
 * The swarm's aggregation prompt carries every server's answer and transcript excerpt, so it is
 * masked for a profile that may forward it, while an integrated model gets the raw text and the
 * local fallback table, which never leaves the computer, stays raw.
 */
class SwarmAggregatorRedactionTest {

    private static final String PASSWORD = "Swarm-Passw0rd-4711";
    // Split so secret scanners do not flag the fixtures.
    private static final String GITHUB_TOKEN = "ghp" + "_" + "abcdefghijklmnopqrstuvwxyz0123456789";
    private static final String VALID_TABLE = "| Server | Fehler |\n|---|---|\n| srv-1 | - |";

    private static AiProfile profile(AiConnectionMode mode, String apiUrl) {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(mode);
        profile.setApiUrl(apiUrl);
        return profile;
    }

    private static AiProfile cloud() {
        return profile(AiConnectionMode.HTTP_API, "https://api.example.com/v1");
    }

    private static SessionJournalRedactor secrets(String... values) {
        SessionJournalRedactor redactor = AiOutboundRedaction.newPolicyRedactor();
        for (String value : values) {
            redactor.addSecret(value);
        }
        return redactor;
    }

    private static SwarmModels.SwarmAgentStatus answered(String name, String answer) {
        return new SwarmModels.SwarmAgentStatus(
            name, name, null, SwarmModels.SwarmAgentState.DONE, "", 1L,
            SwarmModels.TokenTotals.zero(), answer, null, null);
    }

    private static SwarmModels.SwarmAgentStatus transcriptOnly(String name, String transcript) {
        return new SwarmModels.SwarmAgentStatus(
            name, name, null, SwarmModels.SwarmAgentState.FAILED, "", 1L,
            SwarmModels.TokenTotals.zero(), null, transcript, null);
    }

    private static SwarmModels.SwarmAggregationRequest request(SwarmModels.SwarmAgentStatus... statuses) {
        return new SwarmModels.SwarmAggregationRequest("which tokens?", List.of(statuses));
    }

    @Test
    void tokenAndPasswordInAnAnswerOrTranscriptAreMaskedForACloudProfile() {
        RecordingAiService ai = new RecordingAiService(VALID_TABLE);

        new SwarmAggregator().aggregate(
            request(
                answered("srv-1", "token " + GITHUB_TOKEN + " and password " + PASSWORD),
                transcriptOnly("srv-2", "$ env\nGH=" + GITHUB_TOKEN + "\n")),
            ai, cloud(), secrets(PASSWORD));

        assertThat(ai.userPrompt).doesNotContain(GITHUB_TOKEN);
        assertThat(ai.userPrompt).doesNotContain(PASSWORD);
        assertThat(ai.userPrompt).contains(SessionJournalRedactor.REPLACEMENT);
        assertThat(ai.userPrompt).contains("### srv-1");
        assertThat(ai.userPrompt).contains("### srv-2");
    }

    @Test
    void anIntegratedModelGetsTheRawAnswers() {
        for (AiConnectionMode mode : List.of(AiConnectionMode.EMBEDDED_LLAMA_CPP, AiConnectionMode.EMBEDDED_MLX)) {
            RecordingAiService ai = new RecordingAiService(VALID_TABLE);

            new SwarmAggregator().aggregate(
                request(answered("srv-1", "token " + GITHUB_TOKEN + " and password " + PASSWORD)),
                ai, profile(mode, null), secrets(PASSWORD));

            assertThat(ai.userPrompt).contains(GITHUB_TOKEN);
            assertThat(ai.userPrompt).contains(PASSWORD);
        }
    }

    @Test
    void theCompatibilityOverloadFailsClosed() {
        RecordingAiService ai = new RecordingAiService(VALID_TABLE);

        new SwarmAggregator().aggregate(request(answered("srv-1", "token " + GITHUB_TOKEN)), ai);

        assertThat(ai.userPrompt).doesNotContain(GITHUB_TOKEN);
    }

    @Test
    void theLocalFallbackTableStaysRaw() {
        String answer = "token " + GITHUB_TOKEN + " and password " + PASSWORD;

        SwarmModels.SwarmAggregationResult withoutService = new SwarmAggregator().aggregate(
            request(answered("srv-1", answer)), null, cloud(), secrets(PASSWORD));
        SwarmModels.SwarmAggregationResult afterFailure = new SwarmAggregator().aggregate(
            request(answered("srv-1", answer)), new FailingAiService(), cloud(), secrets(PASSWORD));

        assertThat(withoutService.markdown()).contains(GITHUB_TOKEN);
        assertThat(withoutService.markdown()).contains(PASSWORD);
        assertThat(afterFailure.markdown()).contains(GITHUB_TOKEN);
        assertThat(afterFailure.markdown()).contains(PASSWORD);
    }

    @Test
    void theOrchestratorMasksWithTheSecretsEveryTargetsRunnerKnowsAfterTheRun() {
        RecordingAiService ai = new RecordingAiService(VALID_TABLE);
        SecretRunner runner = new SecretRunner();
        SwarmOrchestrator orchestrator = new SwarmOrchestrator(new ConnectingAgentService());
        List<SwarmTarget> targets = List.of(new SwarmTarget(
            "run-1", new ServerConnection("A", "hostA", 22, "root"), runner, null, "sess-1", "hostA"));

        orchestrator.run(
            new SwarmModels.SwarmRequest("which password?", "prof", SwarmModels.SwarmSource.CONNECTION_SELECTION,
                false, true, 1, SwarmModels.BatchApprovalPolicy.READ_ONLY),
            targets, cloud(), () -> ai, new NoOpCallback());

        assertThat(ai.userPrompt).isNotNull();
        assertThat(ai.userPrompt).contains("### hostA");
        assertThat(ai.userPrompt).doesNotContain(PASSWORD);
    }

    // ---------------------------------------------------------------- fakes

    /** Connects its runner during the run (the runner learns its password then) and answers with it. */
    private static final class ConnectingAgentService extends TerminalAgentService {
        @Override
        public void runAgent(
            TerminalTab terminalTab,
            AgentCommandRunner runner,
            AiProfile profile,
            AiPromptService aiService,
            TerminalAgentModels.Request request,
            String requestedRunId,
            TerminalAgentService.RunUi ui) throws Exception {
            runner.exec("whoami", null, null, () -> false, false);
            ui.updateState(new TerminalAgentModels.RunState(
                requestedRunId, request.sessionId(), request.executionTarget(),
                TerminalAgentModels.Phase.DONE, "ok",
                "the login password is " + PASSWORD,
                null, null, null, 1));
        }
    }

    /** A runner that knows its session password only once it ran a command. */
    private static final class SecretRunner implements AgentCommandRunner {
        private volatile boolean connected;

        @Override
        public ExecResult exec(
            String command, byte[] stdin, Consumer<String> outputConsumer,
            BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) {
            connected = true;
            return new ExecResult("root\n", "", 0, false, false);
        }

        @Override
        public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
            return new ExecResult("", "", 0, false, false);
        }

        @Override
        public ShellKind shellKind() {
            return ShellKind.POSIX;
        }

        @Override
        public String currentWorkingDirectory() {
            return null;
        }

        @Override
        public void updateDirectoryHints(String homeDir, String currentDir) {
        }

        @Override
        public boolean indicatesMissingTrackedWorkingDirectory(String stderr) {
            return false;
        }

        @Override
        public boolean isConnected() {
            return connected;
        }

        @Override
        public SessionJournalRedactor knownSecrets() {
            return connected ? secrets(PASSWORD) : null;
        }
    }

    private static final class RecordingAiService implements AiPromptService {
        private final String content;
        private volatile String userPrompt;

        RecordingAiService(String content) {
            this.content = content;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            this.userPrompt = userPrompt;
            return new AiExecutionResult(content, new AiTokenUsage(1, 2, 3));
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            return executePrompt(systemPrompt, userPrompt);
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

    private static final class FailingAiService implements AiPromptService {
        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws IOException {
            throw new IOException("offline");
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws IOException {
            throw new IOException("offline");
        }

        @Override
        public AiExecutionResult execute(AiRequest request) throws IOException {
            throw new IOException("offline");
        }

        @Override
        public boolean testConnection() {
            return false;
        }
    }

    private static final class NoOpCallback implements SwarmCallback {
        @Override
        public void onSwarmState(SwarmModels.SwarmRunState state) {
        }

        @Override
        public void onAgentStatus(SwarmModels.SwarmAgentStatus status) {
        }

        @Override
        public void onAgentTranscript(String agentId, String chunk) {
        }

        @Override
        public void onAggregationResult(SwarmModels.SwarmAggregationResult result) {
        }

        @Override
        public TerminalAgentService.ApprovalDecision requestBatchApproval(
            TerminalAgentModels.Approval approval, String agentId) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }

        @Override
        public TerminalAgentModels.PasswordResponse requestPassword(
            TerminalAgentModels.PasswordRequest request, String agentId) {
            return null;
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isAgentCancelled(String agentId) {
            return false;
        }
    }
}
