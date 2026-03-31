package de.kortty.model;

import java.util.List;

/**
 * Shared public DTOs for AI agent execution and planning.
 */
public final class TerminalAgentModels {

    private TerminalAgentModels() {
    }

    public enum Phase {
        STARTING,
        PROBING,
        PLANNING,
        AWAITING_APPROVAL,
        AWAITING_PASSWORD,
        RUNNING_COMMANDS,
        DONE,
        BLOCKED,
        CANCELLED,
        FAILED
    }

    public enum PlanPhase {
        STARTING,
        PROBING,
        QUESTIONING,
        AWAITING_ANSWERS,
        GENERATING_OPTIONS,
        AWAITING_SELECTION,
        READY_TO_EXECUTE,
        DONE,
        BLOCKED,
        CANCELLED,
        FAILED
    }

    public enum Risk {
        READ_ONLY,
        REQUIRES_CONFIRMATION
    }

    public record Request(
        String sessionId,
        String profileId,
        String userPrompt,
        String connectionDisplayName,
        String acceptedPlanContext,
        TerminalAgentExecutionTarget executionTarget,
        boolean showDebugMessages,
        boolean showRuntimeMessages,
        boolean askConfirmationBeforeEveryCommand,
        boolean autoApproveRootCommands,
        boolean queryOnly) {
    }

    public record PlanRequest(
        String sessionId,
        String profileId,
        String userPrompt,
        String connectionDisplayName) {
    }

    public record PlannedCommand(String command, String purpose, Risk risk) {
    }

    public record Approval(
        String runId,
        String sessionId,
        TerminalAgentExecutionTarget executionTarget,
        String summary,
        String userMessage,
        List<PlannedCommand> commands) {
    }

    public record PasswordRequest(
        String runId,
        String sessionId,
        TerminalAgentExecutionTarget executionTarget,
        String summary,
        String userMessage,
        String command) {
    }

    public record ProbeSnapshot(
        String osRelease,
        String kernel,
        String architecture,
        String shell,
        String currentUser,
        String uid,
        String gid,
        List<String> groups,
        String homeDir,
        String currentDir,
        Long availableDiskKb,
        String availableDiskPath,
        List<String> packageManagers,
        List<String> serviceManagers,
        boolean alreadyRoot,
        boolean sudoAvailable,
        boolean passwordlessSudo,
        boolean sudoNonInteractive,
        String sudoNListSummary,
        String rootEscalationMode) {
    }

    public record PlanQuestion(String id, String question) {
    }

    public record PlanOption(
        String id,
        String title,
        String summary,
        String feasibility,
        List<String> risks,
        List<String> prerequisites,
        List<String> steps,
        List<String> alternatives) {
    }

    public record CommandResult(
        String command,
        String purpose,
        Risk risk,
        Integer exitStatus,
        String exitSignal,
        String stdoutTail,
        String stderrTail,
        boolean stdoutTruncated,
        boolean stderrTruncated,
        boolean cancelled,
        boolean timedOut) {
    }

    public record RunState(
        String runId,
        String sessionId,
        TerminalAgentExecutionTarget executionTarget,
        Phase phase,
        String summary,
        String userMessage,
        Approval pendingApproval,
        PasswordRequest pendingPasswordRequest,
        String currentCommand,
        int turn) {
    }

    public record PlanRunState(
        String runId,
        String sessionId,
        PlanPhase phase,
        String summary,
        String userMessage,
        String probeSummary,
        List<PlanQuestion> questions,
        List<PlanOption> options,
        String acceptedOptionId,
        String executionStartedRunId) {
    }
}
