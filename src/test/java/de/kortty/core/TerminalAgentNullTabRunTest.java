package de.kortty.core;

import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import org.testng.annotations.Test;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Regression tests for the swarm enabler: {@link TerminalAgentService#runAgent} must accept a
 * {@code null} terminal tab as long as a connected {@link AgentCommandRunner} is supplied (swarm
 * runs drive a connector directly without an active tab), and must still reject the case where
 * neither is provided.
 */
class TerminalAgentNullTabRunTest {

    /** A connected runner whose probe throws a sentinel, proving runAgent reached the probe stage. */
    private static final class SentinelRunner implements AgentCommandRunner {
        static final String SENTINEL = "PROBE_REACHED";

        @Override
        public ExecResult exec(String command, byte[] stdin, Consumer<String> outputConsumer,
                               BooleanSupplier cancellationSupplier, boolean useTrackedWorkingDirectory) {
            throw new UnsupportedOperationException("exec should not be reached in this test");
        }

        @Override
        public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) {
            throw new RuntimeException(SENTINEL);
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
        public boolean isConnected() {
            return true;
        }
    }

    private static final class ThrowingAiService implements AiPromptService {
        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException("AI service should not be reached in this test");
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
            throw new UnsupportedOperationException("AI service should not be reached in this test");
        }

        @Override
        public AiExecutionResult execute(AiRequest request) {
            throw new UnsupportedOperationException("AI service should not be reached in this test");
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }

    private static final class NoopRunUi implements TerminalAgentService.RunUi {
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

    private static TerminalAgentModels.Request request() {
        return new TerminalAgentModels.Request(
            "sess-1", "profile-1", "check disk usage", "test-conn", null,
            TerminalAgentExecutionTarget.CHAT_WINDOW,
            false, false, false, false, true, false);
    }

    @Test
    void runAgentWithNullTerminalTabReachesProbeWhenRunnerSupplied() {
        TerminalAgentService service = new TerminalAgentService();
        RuntimeException error = expectThrows(RuntimeException.class, () ->
            service.runAgent(null, new SentinelRunner(), new AiProfile(), new ThrowingAiService(),
                request(), "run-x", new NoopRunUi()));
        // The probe was reached: not blocked by the null-tab guard nor by "No connected terminal".
        assertThat(error).hasMessageThat().contains(SentinelRunner.SENTINEL);
    }

    @Test
    void runAgentRejectsWhenBothTerminalTabAndRunnerMissing() {
        TerminalAgentService service = new TerminalAgentService();
        NullPointerException error = expectThrows(NullPointerException.class, () ->
            service.runAgent(null, null, new AiProfile(), new ThrowingAiService(),
                request(), "run-y", new NoopRunUi()));
        assertThat(error).hasMessageThat().contains("terminalTab or runner");
    }
}
