package de.kortty.core.agent;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Backend used by the AI agent / planning flows to run one non-interactive command and gather an
 * environment probe. It abstracts over an SSH exec channel ({@link SshAgentCommandRunner}) and a
 * local process ({@link LocalAgentCommandRunner}), so the agent works for both remote SSH sessions
 * and local shells (Windows PowerShell/cmd, macOS/Linux {@code $SHELL}).
 */
public interface AgentCommandRunner {

    /** The command language the agent should generate for, and how commands are wrapped. */
    enum ShellKind { POSIX, WINDOWS_POWERSHELL, WINDOWS_CMD }

    /** Result of one command execution. */
    record ExecResult(String stdout, String stderr, int exitCode, boolean cancelled, boolean timedOut) {
    }

    /**
     * Runs a single non-interactive command, capturing stdout/stderr/exit code.
     *
     * @param useTrackedWorkingDirectory when true, run relative to the tracked working directory
     *                                   (SSH wraps with {@code cd}; local sets the process directory).
     */
    ExecResult exec(
        String command,
        byte[] stdin,
        Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier,
        boolean useTrackedWorkingDirectory) throws Exception;

    /** Runs the environment probe and returns its raw {@code key=value} stdout in an {@link ExecResult}. */
    ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier) throws Exception;

    /** The shell/command language the agent should target. */
    ShellKind shellKind();

    default boolean isPosix() {
        return shellKind() == ShellKind.POSIX;
    }

    /** Tracked working directory, or {@code null}. */
    String currentWorkingDirectory();

    /** Update the underlying connector's directory hints from a fresh probe. Default: no-op. */
    default void updateDirectoryHints(String homeDir, String currentDir) {
    }

    /** True when a failed probe/command stderr indicates the tracked working directory is missing. */
    default boolean indicatesMissingTrackedWorkingDirectory(String stderr) {
        return false;
    }

    /** Whether the backend is currently usable (SSH session open / local shell alive). */
    boolean isConnected();
}
