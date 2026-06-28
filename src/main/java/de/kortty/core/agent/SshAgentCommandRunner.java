package de.kortty.core.agent;

import de.kortty.core.SshTtyConnector;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * {@link AgentCommandRunner} backed by an SSH exec channel. This is the original agent execution
 * behavior, extracted from {@code TerminalAgentService} so a local backend can sit beside it.
 */
public final class SshAgentCommandRunner implements AgentCommandRunner {

    private static final Duration COMMAND_OPEN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_WAIT_TIMEOUT = Duration.ofMinutes(15);

    private final SshTtyConnector connector;

    public SshAgentCommandRunner(SshTtyConnector connector) {
        this.connector = connector;
    }

    public SshTtyConnector connector() {
        return connector;
    }

    @Override
    public ExecResult exec(
        String command,
        byte[] stdin,
        Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier,
        boolean useTrackedWorkingDirectory) throws Exception {

        if (connector == null || connector.getSession() == null) {
            throw new IllegalStateException("The selected SSH session is not connected.");
        }
        String commandToExecute = useTrackedWorkingDirectory
            ? TerminalAgentService.wrapCommandForWorkingDirectory(command, connector.getCurrentRemoteDirectory())
            : command;
        try (ChannelExec channel = connector.getSession().createExecChannel(commandToExecute)) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.setErr(stderr);
            if (stdin != null && stdin.length > 0) {
                channel.setIn(new ByteArrayInputStream(stdin));
            }
            channel.open().verify(COMMAND_OPEN_TIMEOUT);
            boolean timedOut = waitForCommand(channel, cancellationSupplier);
            String stdoutText = stdout.toString(StandardCharsets.UTF_8);
            String stderrText = stderr.toString(StandardCharsets.UTF_8);
            emit(outputConsumer, stdoutText);
            emit(outputConsumer, stderrText);
            Integer exitStatus = channel.getExitStatus();
            return new ExecResult(stdoutText, stderrText, exitStatus != null ? exitStatus : -1, false, timedOut);
        }
    }

    private static void emit(Consumer<String> outputConsumer, String text) {
        if (outputConsumer != null && text != null && !text.isBlank()) {
            outputConsumer.accept(text);
            if (!text.endsWith("\n")) {
                outputConsumer.accept("\n");
            }
        }
    }

    private boolean waitForCommand(ChannelExec channel, BooleanSupplier cancellationSupplier) throws Exception {
        long deadlineNanos = System.nanoTime() + COMMAND_WAIT_TIMEOUT.toNanos();
        while (true) {
            if ((cancellationSupplier != null && cancellationSupplier.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
                channel.close(false);
                throw new TerminalAgentService.AgentCancelledException("Terminal agent run cancelled");
            }
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0L) {
                channel.close(false);
                return true;
            }
            long waitMillis = Math.min(250L, Math.max(1L, TimeUnit.NANOSECONDS.toMillis(remainingNanos)));
            Set<ClientChannelEvent> events = channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), waitMillis);
            if (events.contains(ClientChannelEvent.CLOSED)) {
                return false;
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
        return connector != null ? connector.getCurrentRemoteDirectory() : null;
    }

    @Override
    public void updateDirectoryHints(String homeDir, String currentDir) {
        if (connector != null) {
            connector.updateHomeRemoteDirectoryHint(homeDir);
            connector.updateCurrentRemoteDirectoryHint(currentDir);
        }
    }

    @Override
    public boolean indicatesMissingTrackedWorkingDirectory(String stderr) {
        return connector != null
            && TerminalAgentService.isMissingTrackedWorkingDirectory(stderr, connector.getCurrentRemoteDirectory());
    }

    @Override
    public boolean isConnected() {
        return connector != null && connector.getSession() != null;
    }

    public ServerConnection getConnection() {
        return connector != null ? connector.getConnection() : null;
    }
}
