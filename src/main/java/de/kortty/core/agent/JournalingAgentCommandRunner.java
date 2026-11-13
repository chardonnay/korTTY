package de.kortty.core.agent;

import de.kortty.core.AutomationJournalRecorder;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Wraps the command runner of an AI agent so every command it runs, and that command's output,
 * lands in an automation run's session journal. The agent's exec channels never reach a
 * terminal, so this is the only place the swarm's work can be captured.
 */
public final class JournalingAgentCommandRunner implements AgentCommandRunner {

    private final AgentCommandRunner delegate;
    private final AutomationJournalRecorder recorder;
    private volatile boolean probeNoted;

    public JournalingAgentCommandRunner(AgentCommandRunner delegate, AutomationJournalRecorder recorder) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.recorder = recorder != null ? recorder : AutomationJournalRecorder.NOOP;
    }

    /** Returns {@code runner} wrapped, or unchanged when the recorder does not record. */
    public static AgentCommandRunner wrap(AgentCommandRunner runner, AutomationJournalRecorder recorder) {
        if (runner == null || recorder == null || !recorder.isRecording()) {
            return runner;
        }
        return new JournalingAgentCommandRunner(runner, recorder);
    }

    public AgentCommandRunner delegate() {
        return delegate;
    }

    @Override
    public ExecResult exec(
            String command,
            byte[] stdin,
            Consumer<String> outputConsumer,
            BooleanSupplier cancellationSupplier,
            boolean useTrackedWorkingDirectory) throws Exception {
        recorder.appendCommand(command);
        ExecResult result = delegate.exec(command, stdin, outputConsumer, cancellationSupplier, useTrackedWorkingDirectory);
        recordResult(command, result);
        return result;
    }

    @Override
    public ExecResult execWithSudo(
            String command,
            AgentSudoAuthentication auth,
            Consumer<String> outputConsumer,
            BooleanSupplier cancellationSupplier,
            boolean useTrackedWorkingDirectory) throws Exception {
        recorder.appendCommand(auth.displayCommand());
        ExecResult result = delegate.execWithSudo(command, auth, outputConsumer, cancellationSupplier, useTrackedWorkingDirectory);
        if (result != null) {
            recordResult(auth.displayCommand(), new ExecResult(auth.redact(result.stdout()), auth.redact(result.stderr()),
                result.exitCode(), result.cancelled(), result.timedOut()));
        }
        return result;
    }

    @Override
    public boolean supportsSudoPty() {
        return delegate.supportsSudoPty();
    }

    @Override
    public AgentSudoPty openSudoPty(AgentSudoAuthentication auth, BooleanSupplier cancelled) throws Exception {
        AgentSudoPty terminal = delegate.openSudoPty(auth, cancelled);
        return new AgentSudoPty() {
            @Override
            public ExecResult exec(String command, Consumer<String> output, BooleanSupplier stop) throws Exception {
                recorder.appendCommand(command);
                ExecResult result = terminal.exec(command, output, stop);
                recordResult(command, new ExecResult(auth.redact(result.stdout()), auth.redact(result.stderr()),
                    result.exitCode(), result.cancelled(), result.timedOut()));
                return result;
            }

            @Override
            public void close() {
                terminal.close();
            }
        };
    }

    private void recordResult(String command, ExecResult result) {
        if (result != null) {
            recorder.appendOutput(result.stdout(), result.stderr());
            if (result.cancelled()) {
                recorder.appendLogNote("cancelled: " + command);
            } else if (result.timedOut()) {
                recorder.appendLogNote("timed out: " + command);
            } else if (result.exitCode() != 0) {
                recorder.appendLogNote("exit " + result.exitCode() + ": " + command);
            }
        }
    }

    @Override
    public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) throws Exception {
        ExecResult result = delegate.runProbe(useTrackedWorkingDirectory, cancellationSupplier);
        if (!probeNoted) {
            probeNoted = true;
            recorder.appendLogNote("environment probe (exit " + (result != null ? result.exitCode() : -1) + ")");
        }
        return result;
    }

    @Override
    public ShellKind shellKind() {
        return delegate.shellKind();
    }

    @Override
    public de.kortty.core.SessionJournalRedactor knownSecrets() {
        return delegate.knownSecrets();
    }

    @Override
    public String currentWorkingDirectory() {
        return delegate.currentWorkingDirectory();
    }

    @Override
    public void updateDirectoryHints(String homeDir, String currentDir) {
        delegate.updateDirectoryHints(homeDir, currentDir);
    }

    @Override
    public boolean indicatesMissingTrackedWorkingDirectory(String stderr) {
        return delegate.indicatesMissingTrackedWorkingDirectory(stderr);
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }
}
