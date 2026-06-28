package de.kortty.core.agent;

import de.kortty.core.LocalShellTtyConnector;
import de.kortty.core.ObservableTtyConnector;
import de.kortty.core.SshTtyConnector;

/** Builds the {@link AgentCommandRunner} appropriate for a connector. */
public final class AgentCommandRunners {

    private AgentCommandRunners() {
    }

    /** Returns an SSH or local runner for the connector, or {@code null} if unsupported (e.g. Mosh). */
    public static AgentCommandRunner forConnector(ObservableTtyConnector connector) {
        if (connector instanceof SshTtyConnector ssh) {
            return new SshAgentCommandRunner(ssh);
        }
        if (connector instanceof LocalShellTtyConnector local) {
            return new LocalAgentCommandRunner(local.getConnection());
        }
        return null;
    }
}
