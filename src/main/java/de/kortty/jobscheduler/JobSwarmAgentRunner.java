package de.kortty.jobscheduler;

import de.kortty.core.TerminalAgentService;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.core.agent.AgentProbeScripts;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Headless {@link AgentCommandRunner} for scheduled AI-swarm jobs: drives one agent over a
 * {@link JobSchedulerRemoteSession} (SSH exec channels, host-key pinning, no terminal tab).
 * Connects lazily on the first command so connection failures surface through the normal
 * per-agent error path instead of blocking the whole job.
 */
public final class JobSwarmAgentRunner implements AgentCommandRunner, AutoCloseable {

    private final JobSchedulerRemoteSession session;
    private String currentDirectory;
    private String homeDirectory;

    public JobSwarmAgentRunner(JobSchedulerRemoteSession session, String initialWorkingDirectory) {
        this.session = session;
        this.currentDirectory = initialWorkingDirectory != null && !initialWorkingDirectory.isBlank()
            ? initialWorkingDirectory.trim()
            : null;
    }

    @Override
    public ExecResult exec(
        String command,
        byte[] stdin,
        Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier,
        boolean useTrackedWorkingDirectory) throws Exception {

        ensureConnected();
        String commandToExecute = useTrackedWorkingDirectory
            ? TerminalAgentService.wrapCommandForWorkingDirectory(command, currentDirectory)
            : command;
        String stdinText = stdin != null && stdin.length > 0 ? new String(stdin, StandardCharsets.UTF_8) : null;
        JobSchedulerRemoteSession.CommandResult result;
        try {
            result = session.execute(commandToExecute, stdinText, cancellationSupplier);
        } catch (java.io.IOException e) {
            if ((cancellationSupplier != null && cancellationSupplier.getAsBoolean())
                || Thread.currentThread().isInterrupted()) {
                throw new TerminalAgentService.AgentCancelledException("Swarm job agent cancelled");
            }
            throw e;
        }
        emit(outputConsumer, result.stdout());
        emit(outputConsumer, result.stderr());
        return new ExecResult(result.stdout(), result.stderr(), result.exitCode(), false, false);
    }

    private static void emit(Consumer<String> outputConsumer, String text) {
        if (outputConsumer != null && text != null && !text.isBlank()) {
            outputConsumer.accept(text);
            if (!text.endsWith("\n")) {
                outputConsumer.accept("\n");
            }
        }
    }

    @Override
    public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) throws Exception {
        return exec(AgentProbeScripts.POSIX, null, null, cancellationSupplier, useTrackedWorkingDirectory);
    }

    @Override
    public ShellKind shellKind() {
        return ShellKind.POSIX;
    }

    @Override
    public String currentWorkingDirectory() {
        return currentDirectory;
    }

    @Override
    public void updateDirectoryHints(String homeDir, String currentDir) {
        if (homeDir != null && !homeDir.isBlank()) {
            this.homeDirectory = homeDir.trim();
        }
        if (currentDir != null && !currentDir.isBlank()) {
            this.currentDirectory = currentDir.trim();
        }
    }

    @Override
    public boolean indicatesMissingTrackedWorkingDirectory(String stderr) {
        return TerminalAgentService.isMissingTrackedWorkingDirectory(stderr, currentDirectory);
    }

    @Override
    public boolean isConnected() {
        return session != null && session.isConnected();
    }

    private void ensureConnected() throws Exception {
        if (session != null && !session.isConnected()) {
            session.connect();
        }
    }

    /** Session password once connected, for secret redaction of the journal output. */
    public java.util.Optional<String> sessionPassword() {
        return session != null ? session.getPassword() : java.util.Optional.empty();
    }

    @Override
    public void close() {
        if (session != null) {
            try {
                session.close();
            } catch (Exception e) {
                // best effort — the job worker is winding down
            }
        }
    }
}
