package de.kortty.model;

import java.util.List;
import java.util.Objects;

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

    public enum AgentActivityType {
        MESSAGE,
        ACTION,
        THINKING,
        QUESTION,
        ERROR
    }

    public enum AgentActivityStatus {
        RUNNING,
        COMPLETED,
        CANCELLED,
        FAILED
    }

    public record AgentActivityTokenUsage(
        boolean known,
        long promptTokens,
        long completionTokens,
        long totalTokens) {

        public AgentActivityTokenUsage {
            promptTokens = Math.max(0L, promptTokens);
            completionTokens = Math.max(0L, completionTokens);
            totalTokens = Math.max(0L, totalTokens);
            if (known) {
                totalTokens = Math.max(totalTokens, promptTokens + completionTokens);
            }
        }

        public static AgentActivityTokenUsage unknown() {
            return new AgentActivityTokenUsage(false, 0L, 0L, 0L);
        }
    }

    public record AgentActivity(
        String id,
        AgentActivityType type,
        AgentActivityStatus status,
        String title,
        String summary,
        String detail,
        AgentActivityTokenUsage tokenUsage,
        long elapsedSeconds,
        boolean collapsible,
        boolean collapsed,
        AgentActionCategory actionCategory) {

        /** Backward-compatible constructor for activities without an explicit action category. */
        public AgentActivity(
            String id,
            AgentActivityType type,
            AgentActivityStatus status,
            String title,
            String summary,
            String detail,
            AgentActivityTokenUsage tokenUsage,
            long elapsedSeconds,
            boolean collapsible,
            boolean collapsed) {
            this(id, type, status, title, summary, detail, tokenUsage, elapsedSeconds,
                collapsible, collapsed, null);
        }
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
        boolean confirmMutatingCommandSets,
        boolean queryOnly,
        boolean mirrorFinalAnswerToTerminal) {

        /**
         * Backward-compatible constructor for callers that do not choose the terminal-mirror option;
         * it defaults to mirroring the final answer into the terminal (the historical behavior).
         */
        public Request(
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
            boolean confirmMutatingCommandSets,
            boolean queryOnly) {
            this(sessionId, profileId, userPrompt, connectionDisplayName, acceptedPlanContext,
                executionTarget, showDebugMessages, showRuntimeMessages, askConfirmationBeforeEveryCommand,
                autoApproveRootCommands, confirmMutatingCommandSets, queryOnly, true);
        }
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
        List<PlannedCommand> commands,
        boolean allowAlways) {
    }

    public record PasswordRequest(
        String runId,
        String sessionId,
        TerminalAgentExecutionTarget executionTarget,
        String summary,
        String userMessage,
        String command) {
    }

    public record PasswordResponse(
        String password,
        boolean cacheForSession) {
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

    public record PlanQuestion(
        String id,
        String question,
        List<String> options,
        boolean allowCustomAnswer) {
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

    public record PlanReport(
        String title,
        String summary,
        List<String> prerequisites,
        List<String> steps,
        List<String> risks,
        List<String> successCriteria) {
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
        PlanReport finalPlan,
        String acceptedOptionId,
        String executionStartedRunId) {
    }

    /**
     * The complexity check of one agent request: whether it looks complex, why, and the solution options to
     * choose from. The connection fields name the connection the run drives; {@code null} when there is none.
     *
     * @param complex        whether the request looks complex
     * @param reasons        short statements on why it looks complex, or why it does not
     * @param recommended    the zero-based index of the recommended option
     * @param options        two or three solution options
     * @param connectionId   the stable id of the connection, or {@code null}
     * @param connectionName the display name of the connection, or {@code null}
     * @param connectionHost the host of the connection, or {@code null}
     */
    public record TriageProposal(
        boolean complex,
        List<String> reasons,
        int recommended,
        List<PlanOption> options,
        String connectionId,
        String connectionName,
        String connectionHost) {

        public TriageProposal {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
            options = options == null ? List.of() : List.copyOf(options);
        }
    }

    /**
     * What the user answered to a {@link TriageProposal}. {@link Kind#OPTION} takes {@code optionIndex},
     * {@link Kind#OWN_IDEA} takes the idea in {@code text}; {@code instructions} is an optional addition to the
     * plan. {@link Kind#AS_REQUESTED} and {@link Kind#CANCEL} need neither.
     */
    public record SolutionChoice(Kind kind, int optionIndex, String text, String instructions) {

        public enum Kind {
            OPTION,
            OWN_IDEA,
            AS_REQUESTED,
            CANCEL
        }

        public SolutionChoice {
            kind = Objects.requireNonNull(kind, "kind");
            text = blankToNull(text);
            instructions = blankToNull(instructions);
        }

        public static SolutionChoice option(int optionIndex, String instructions) {
            return new SolutionChoice(Kind.OPTION, optionIndex, null, instructions);
        }

        public static SolutionChoice ownIdea(String text, String instructions) {
            return new SolutionChoice(Kind.OWN_IDEA, -1, text, instructions);
        }

        public static SolutionChoice asRequested() {
            return new SolutionChoice(Kind.AS_REQUESTED, -1, null, null);
        }

        public static SolutionChoice asRequested(String instructions) {
            return new SolutionChoice(Kind.AS_REQUESTED, -1, null, instructions);
        }

        public static SolutionChoice cancel() {
            return new SolutionChoice(Kind.CANCEL, -1, null, null);
        }

        private static String blankToNull(String value) {
            return value == null || value.isBlank() ? null : value.strip();
        }
    }
}
