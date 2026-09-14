package de.kortty.codingagent;

import java.time.Instant;
import java.util.Optional;

/**
 * A live OS process identified as a coding agent below the pane's shell. startedAt may be null when
 * the platform does not report a start time.
 */
public record AgentProcess(long pid, CodingAgentKind kind, String command, Instant startedAt) {

    /**
     * True while the process this record was taken from is alive. A pid can be reused by an unrelated
     * process once the agent has exited, so a present {@link #startedAt} must also equal the start
     * instant of the pid's current occupant; without a recorded start time only liveness is checked.
     */
    public boolean isAlive() {
        try {
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (handle.isEmpty() || !handle.get().isAlive()) {
                return false;
            }
            if (startedAt == null) {
                return true;
            }
            Optional<Instant> currentStart = handle.get().info().startInstant();
            return currentStart.isEmpty() || startedAt.equals(currentStart.get());
        } catch (SecurityException | UnsupportedOperationException e) {
            return false;
        }
    }
}
