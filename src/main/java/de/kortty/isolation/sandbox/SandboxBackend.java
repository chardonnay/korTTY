package de.kortty.isolation.sandbox;

import java.util.List;

/** An operating-system sandbox that a command can be started in. */
public interface SandboxBackend {

    /** A short name for logs and the tab's tooltip, such as {@code sandbox-exec} or {@code bubblewrap}. */
    String id();

    /**
     * Whether this backend can run on this computer at all (the tool exists), before any self-test.
     * Must be cheap: no process is started.
     */
    boolean installed();

    /** Whether this backend applies {@link SandboxSpec#outboundPorts()}. */
    default boolean limitsNetwork() {
        return false;
    }

    /**
     * Whether this backend limits the network by giving the process none of its own: it then needs
     * korTTY to relay the connections it may make ({@code de.kortty.core.worker.NetworkRelay}).
     */
    default boolean needsNetworkRelay() {
        return false;
    }

    /** The command that starts {@code command} inside the sandbox {@code spec} describes. */
    List<String> wrap(List<String> command, SandboxSpec spec);
}
