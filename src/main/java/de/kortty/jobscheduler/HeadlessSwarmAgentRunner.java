package de.kortty.jobscheduler;

import de.kortty.KorTTYApplication;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Headless runner for interactive swarm targets that have no open terminal: the SSH session is
 * created lazily on the first command (always on an agent worker thread, never on the FX thread).
 * The host key is probed and accepted on first contact (TOFU, with a warning log) — the same
 * trust model the terminal connector applies when opening a new connection.
 */
public final class HeadlessSwarmAgentRunner implements AgentCommandRunner, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(HeadlessSwarmAgentRunner.class);

    private final KorTTYApplication app;
    private final ServerConnection connection;
    private final char[] masterPassword;
    private final String initialWorkingDirectory;
    private volatile JobSwarmAgentRunner delegate;
    private volatile boolean closed;

    public HeadlessSwarmAgentRunner(
        KorTTYApplication app,
        ServerConnection connection,
        char[] masterPassword,
        String initialWorkingDirectory) {
        this.app = app;
        this.connection = connection;
        this.masterPassword = masterPassword;
        this.initialWorkingDirectory = initialWorkingDirectory;
    }

    private JobSwarmAgentRunner ensureDelegate() throws Exception {
        JobSwarmAgentRunner existing = delegate;
        if (existing != null) {
            if (closed) {
                throw new IllegalStateException("Headless swarm runner already closed.");
            }
            return existing;
        }
        if (closed) {
            throw new IllegalStateException("Headless swarm runner already closed.");
        }
        // Probe + session construction happen OUTSIDE the monitor: close() runs on the FX thread
        // and must never wait behind network I/O (SSH probe can take a full connect timeout).
        PinnedHostKey hostKey = JobSchedulerRemoteSession.probeHostKey(connection);
        logger.warn("Accepting server key for headless swarm target {} ({}:{})",
            connection.getDisplayName(), connection.getHost(), connection.getPort());
        JobSchedulerRemoteSession session = new JobSchedulerRemoteSession(
            app, connection, hostKey, masterPassword, false);
        JobSwarmAgentRunner created = new JobSwarmAgentRunner(session, initialWorkingDirectory);
        JobSwarmAgentRunner winner;
        boolean closeCreated = false;
        synchronized (this) {
            if (closed) {
                closeCreated = true;
                winner = null;
            } else if (delegate == null) {
                delegate = created;
                winner = created;
            } else {
                closeCreated = true;
                winner = delegate;
            }
        }
        if (closeCreated) {
            created.close();
        }
        if (winner == null) {
            throw new IllegalStateException("Headless swarm runner already closed.");
        }
        return winner;
    }

    @Override
    public ExecResult exec(
        String command,
        byte[] stdin,
        Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier,
        boolean useTrackedWorkingDirectory) throws Exception {
        return ensureDelegate().exec(command, stdin, outputConsumer, cancellationSupplier, useTrackedWorkingDirectory);
    }

    @Override
    public ExecResult runProbe(boolean useTrackedWorkingDirectory, BooleanSupplier cancellationSupplier)
        throws Exception {
        return ensureDelegate().runProbe(useTrackedWorkingDirectory, cancellationSupplier);
    }

    @Override
    public ShellKind shellKind() {
        return ShellKind.POSIX;
    }

    @Override
    public String currentWorkingDirectory() {
        JobSwarmAgentRunner current = delegate;
        return current != null ? current.currentWorkingDirectory() : initialWorkingDirectory;
    }

    @Override
    public void updateDirectoryHints(String homeDir, String currentDir) {
        JobSwarmAgentRunner current = delegate;
        if (current != null) {
            current.updateDirectoryHints(homeDir, currentDir);
        }
    }

    @Override
    public boolean indicatesMissingTrackedWorkingDirectory(String stderr) {
        JobSwarmAgentRunner current = delegate;
        return current != null && current.indicatesMissingTrackedWorkingDirectory(stderr);
    }

    /**
     * Lazy semantics: "connectable" counts as connected until a real attempt happened — a failed
     * connect surfaces through {@code exec} as a per-target failure instead of a silent skip.
     */
    @Override
    public boolean isConnected() {
        if (closed) {
            return false;
        }
        JobSwarmAgentRunner current = delegate;
        return current == null || current.isConnected();
    }

    /**
     * Non-blocking for the caller (the FX thread closes runners on tab close): the flag flips
     * immediately, the SSH teardown runs on a background daemon thread.
     */
    @Override
    public void close() {
        closed = true;
        Thread teardown = new Thread(() -> {
            JobSwarmAgentRunner current;
            synchronized (this) {
                current = delegate;
                delegate = null;
            }
            if (current != null) {
                current.close();
            }
        }, "headless-swarm-close");
        teardown.setDaemon(true);
        teardown.start();
    }
}
