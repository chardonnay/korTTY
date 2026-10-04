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
        return result;
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
