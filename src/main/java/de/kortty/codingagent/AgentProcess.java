package de.kortty.codingagent;

import java.time.Instant;

/** A live OS process identified as a coding agent below the pane's shell. startedAt may be null. */
public record AgentProcess(long pid, CodingAgentKind kind, String command, Instant startedAt) {

    public boolean isAlive() {
        try {
            return ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
        } catch (SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }
}
