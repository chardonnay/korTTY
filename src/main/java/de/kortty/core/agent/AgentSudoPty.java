package de.kortty.core.agent;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** A private execution terminal and its sudo ticket, owned by one agent run. */
public interface AgentSudoPty extends AutoCloseable {

    AgentCommandRunner.ExecResult exec(
        String command, Consumer<String> output, BooleanSupplier cancelled) throws Exception;

    @Override
    void close();
}
