package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentModels;
import de.kortty.ui.TerminalTab;
import de.kortty.ui.TerminalView;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Shared runtime for AI Agent execution and planning flows.
 */
public class TerminalAgentService {

    private static final Logger logger = LoggerFactory.getLogger(TerminalAgentService.class);
    private static final Gson GSON = new Gson();
    private static final int MAX_AGENT_TURNS = 8;
    private static final int MAX_COMMANDS_PER_TURN = 3;
    public static final int MAX_SUDO_PASSWORD_RETRIES = 3;
    private static final int COMMAND_TITLE_PREVIEW_CHARS = 96;
    private static final int COMMAND_OUTPUT_TAIL_CHARS = 4_000;
    private static final Duration COMMAND_OPEN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_WAIT_TIMEOUT = Duration.ofMinutes(15);
    private static final List<String> INTERACTIVE_COMMAND_TOKENS = List.of(
        "vi", "vim", "nano", "less", "more", "man", "top", "htop", "su");
    private static final Pattern SUDO_INVOCATION_PATTERN = Pattern.compile("(?i)(^|[;&|()]\\s*)sudo\\s+");
    private static final Pattern SUDO_NON_INTERACTIVE_INVOCATION_PATTERN = Pattern.compile("(?i)(^|[;&|()]\\s*)sudo\\s+-n\\s+");
    private static final Pattern SUDO_STDIN_OPTION_AFTER_NON_INTERACTIVE_PATTERN =
        Pattern.compile("(^|[;&|()]\\s*)sudo\\s+-n\\s+(?:(?:-S|--stdin)\\s+)+");
    private static final Pattern FILE_TYPE_COUNT_PATH_PATTERN =
        Pattern.compile("(?i)\\b(?:directory|dir|folder)\\s+(['\"]?)(/[^\\s?'\";:,]+)\\1");
    private static final Pattern COUNT_OUTPUT_PATTERN = Pattern.compile("(?m)^(total|plain_text|binary_or_non_text)=(\\d+)\\s*$");
    private static final Pattern HERE_DOCUMENT_OPERATOR_PATTERN =
        Pattern.compile("(?<!<)<<-?(?!<)\\s*(?:'([^']+)'|\"([^\"]+)\"|([^\\s;|&<>]+))");
    private static final Pattern MUTATING_COMMAND_PATTERN =
        Pattern.compile("(?i)(^|[;&|()]\\s*)(?:chmod|chown|chgrp|rm|rmdir|mv|cp|mkdir|touch|ln|tee|dd|truncate|install)\\b");

    private final Map<String, CachedSudoPassword> cachedSudoPasswordBySessionId = new ConcurrentHashMap<>();

    public interface RunUi {
        void updateState(TerminalAgentModels.RunState state);
        void appendTranscript(String text);
        ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) throws Exception;
        TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest request) throws Exception;
        boolean isCancelled();

        default void publishActivity(TerminalAgentModels.AgentActivity activity) {
        }

        default void recordTokenUsage(AiTokenUsage usage) {
        }
    }

    public interface PlanProgressUi {
        void updateState(TerminalAgentModels.PlanRunState state);
    }

    public enum ApprovalDecision {
        APPROVE_ONCE,
        APPROVE_ALWAYS,
        CANCEL
    }

    public static final class AgentCancelledException extends RuntimeException {
        public AgentCancelledException(String message) {
            super(message);
        }
    }

    public static boolean isCancellation(Throwable error) {
        return error instanceof AgentCancelledException;
    }

    public record PlanningQuestions(
        List<TerminalAgentModels.PlanQuestion> questions,
        String summary,
        String userMessage) {
    }

    public record PlanningOptions(
        List<TerminalAgentModels.PlanOption> options,
        String summary,
        String userMessage) {
    }

    public record PlanningReport(
        TerminalAgentModels.PlanReport report,
        String summary,
        String userMessage) {
    }

    public TerminalAgentModels.ProbeSnapshot probeTerminalSession(TerminalTab terminalTab) throws Exception {
        return probeTerminalSession(terminalTab, null, null);
    }

    public TerminalAgentModels.ProbeSnapshot probeTerminalSession(
        TerminalTab terminalTab,
        SshTtyConnector connector) throws Exception {
        return probeTerminalSession(terminalTab, connector, null);
    }

    private TerminalAgentModels.ProbeSnapshot probeTerminalSession(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        BooleanSupplier cancellationSupplier) throws Exception {
        SshTtyConnector resolvedConnector = requireConnector(terminalTab, connector);
        ExecResult result = execInternal(terminalTab, resolvedConnector, buildProbeCommand(), null, null, cancellationSupplier, true);
        if (result.exitCode() != 0 && isMissingTrackedWorkingDirectory(result.stderr(), resolvedConnector.getCurrentRemoteDirectory())) {
            logger.warn(
                "Tracked terminal working directory '{}' is not available for the probe; retrying from the SSH default directory.",
                resolvedConnector.getCurrentRemoteDirectory());
            result = execInternal(terminalTab, resolvedConnector, buildProbeCommand(), null, null, cancellationSupplier, false);
        }
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Terminal probe failed: " + trimToSingleLine(result.stderr()));
        }
        TerminalAgentModels.ProbeSnapshot probe = parseProbeOutput(result.stdout());
        resolvedConnector.updateHomeRemoteDirectoryHint(probe.homeDir());
        resolvedConnector.updateCurrentRemoteDirectoryHint(probe.currentDir());
        return probe;
    }

    public String summarizeProbe(TerminalAgentModels.ProbeSnapshot probe) {
        if (probe == null) {
            return "Unknown server state.";
        }
        String osPart = nonBlank(probe.osRelease(), "unknown OS");
        String userPart = nonBlank(probe.currentUser(), "unknown user");
        String dirPart = nonBlank(probe.currentDir(), "~");
        String sudoPart;
        if (probe.alreadyRoot()) {
            sudoPart = "already root";
        } else if (!probe.sudoAvailable()) {
            sudoPart = "no sudo";
        } else if (probe.passwordlessSudo()) {
            sudoPart = "passwordless sudo";
        } else {
            sudoPart = "sudo requires password";
        }
        return osPart + " | user " + userPart + " | cwd " + dirPart + " | " + sudoPart;
    }

    public PlanningQuestions requestPlanningQuestions(
        AiProfile profile,
        AiPromptService aiService,
        TerminalAgentModels.PlanRequest request,
        TerminalAgentModels.ProbeSnapshot probe) throws Exception {
        String systemPrompt = buildPlanQuestionSystemPrompt();
        String userPrompt = buildPlanQuestionUserPrompt(request, probe);
        AiExecutionResult result = executeAgentJsonPrompt(aiService, systemPrompt, userPrompt);
        AgentPlanQuestionDecision decision = parsePlanQuestionDecision(result.content());
        List<TerminalAgentModels.PlanQuestion> questions = decision.questions().stream()
            .map(item -> new TerminalAgentModels.PlanQuestion(
                item.id(),
                item.question(),
                safeList(item.options()),
                item.allowCustomAnswer()))
            .toList();
        return new PlanningQuestions(questions, decision.summary(), decision.userMessage());
    }

    public PlanningOptions requestPlanningOptions(
        AiProfile profile,
        AiPromptService aiService,
        TerminalAgentModels.PlanRequest request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.PlanQuestion> questions,
        String answers,
        String customApproach) throws Exception {
        String systemPrompt = buildPlanOptionSystemPrompt();
        String userPrompt = buildPlanOptionUserPrompt(request, probe, questions, answers, customApproach);
        AiExecutionResult result = executeAgentJsonPrompt(aiService, systemPrompt, userPrompt);
        AgentPlanOptionDecision decision = parsePlanOptionDecision(result.content());
        List<TerminalAgentModels.PlanOption> options = new ArrayList<>();
        for (AgentPlanOptionDecisionItem item : safeList(decision.options())) {
            options.add(new TerminalAgentModels.PlanOption(
                UUID.randomUUID().toString(),
                item.title(),
                item.summary(),
                item.feasibility(),
                safeList(item.risks()),
                safeList(item.prerequisites()),
                safeList(item.steps()),
                safeList(item.alternatives())));
        }
        return new PlanningOptions(options, decision.summary(), decision.userMessage());
    }

    public PlanningReport requestPlanningReport(
        AiProfile profile,
        AiPromptService aiService,
        TerminalAgentModels.PlanRequest request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.PlanQuestion> questions,
        String answers,
        TerminalAgentModels.PlanOption selectedOption,
        String customApproach) throws Exception {
        String systemPrompt = buildPlanReportSystemPrompt();
        String userPrompt = buildPlanReportUserPrompt(request, probe, questions, answers, selectedOption, customApproach);
        AiExecutionResult result = executeAgentJsonPrompt(aiService, systemPrompt, userPrompt);
        AgentPlanReportDecision decision = parsePlanReportDecision(result.content());
        TerminalAgentModels.PlanReport report = new TerminalAgentModels.PlanReport(
            decision.title(),
            decision.summary(),
            safeList(decision.prerequisites()),
            safeList(decision.steps()),
            safeList(decision.risks()),
            safeList(decision.successCriteria()));
        return new PlanningReport(report, decision.summary(), decision.userMessage());
    }

    public String buildAcceptedPlanContext(TerminalAgentModels.PlanOption option) {
        if (option == null) {
            return "";
        }
        return "Accepted plan option: " + option.title() + "\n"
            + "Summary: " + option.summary() + "\n"
            + "Feasibility: " + option.feasibility() + "\n"
            + "Prerequisites: " + joinPlanItems(option.prerequisites()) + "\n"
            + "Risks: " + joinPlanItems(option.risks()) + "\n"
            + "Steps:\n" + joinSteps(option.steps()) + "\n"
            + "Alternatives: " + joinPlanItems(option.alternatives());
    }

    public String buildAcceptedPlanContext(TerminalAgentModels.PlanReport report) {
        if (report == null) {
            return "";
        }
        return "Accepted final plan: " + nonBlank(report.title(), "Untitled plan") + "\n"
            + "Summary: " + nonBlank(report.summary(), "") + "\n"
            + "Prerequisites: " + joinPlanItems(report.prerequisites()) + "\n"
            + "Risks: " + joinPlanItems(report.risks()) + "\n"
            + "Success criteria: " + joinPlanItems(report.successCriteria()) + "\n"
            + "Steps:\n" + joinSteps(report.steps());
    }

    public void runAgent(
        TerminalTab terminalTab,
        AiProfile profile,
        AiPromptService aiService,
        TerminalAgentModels.Request request,
        RunUi ui) throws Exception {
        runAgent(terminalTab, null, profile, aiService, request, ui);
    }

    public void runAgent(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        AiProfile profile,
        AiPromptService aiService,
        TerminalAgentModels.Request request,
        RunUi ui) throws Exception {
        Objects.requireNonNull(terminalTab, "terminalTab");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(aiService, "aiService");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ui, "ui");

        String runId = UUID.randomUUID().toString();
        String sessionId = request.sessionId();
        CachedSudoPassword cachedPassword = null;
        try {
            TerminalAgentModels.ProbeSnapshot probe = updateAndProbe(ui, runId, request, terminalTab, connector);
            List<TerminalAgentModels.CommandResult> history = new ArrayList<>();
            boolean confirmMutatingCommandSets = request.confirmMutatingCommandSets();
            boolean approvalBypass = !confirmMutatingCommandSets && request.autoApproveRootCommands();
            cachedPassword = cachedSudoPasswordBySessionId.get(sessionId);

            if (tryRunFileTypeCountRequest(terminalTab, connector, request, probe, ui, runId)) {
                return;
            }

            for (int turn = 1; turn <= MAX_AGENT_TURNS; turn++) {
                ensureNotCancelled(ui);
                ui.updateState(new TerminalAgentModels.RunState(
                    runId,
                    request.sessionId(),
                    request.executionTarget(),
                    TerminalAgentModels.Phase.PLANNING,
                    "Waiting for the AI planner response.",
                    "The AI agent is deciding on the next safe step.",
                    null,
                    null,
                    null,
                    turn));

                AgentDecision decision = requestAgentDecision(
                    aiService,
                    request,
                    probe,
                    history,
                    turn,
                    cachedPassword != null && !cachedPassword.isBlank(),
                    ui,
                    runId);
                if (decision.status() == AgentDecisionStatus.done) {
                    ui.updateState(new TerminalAgentModels.RunState(
                        runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.DONE,
                        decision.summary(), decision.userMessage(), null, null, null, turn));
                    publishMessage(ui, runId, "done-" + turn, decision.userMessage(), decision.summary());
                    return;
                }
                if (decision.status() == AgentDecisionStatus.blocked) {
                    if (shouldPromptForSudoPasswordAfterBlockedDecision(
                        decision.summary(),
                        decision.userMessage(),
                        probe,
                        cachedPassword)) {
                        cachedPassword = requestSudoPassword(
                            ui,
                            runId,
                            request,
                            turn,
                            nonBlank(decision.summary(), "Sudo password required."),
                            "Enter the sudo password to continue this SSH session.",
                            null);
                        if (cachedPassword == null || cachedPassword.isBlank()) {
                            return;
                        }
                        continue;
                    }
                    ui.updateState(new TerminalAgentModels.RunState(
                        runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.BLOCKED,
                        decision.summary(), decision.userMessage(), null, null, null, turn));
                    publishMessage(ui, runId, "blocked-" + turn, decision.userMessage(), decision.summary());
                    return;
                }

                List<TerminalAgentModels.PlannedCommand> commands = validateCommands(decision.commands(), probe, request.queryOnly());
                if (!approvalBypass && shouldRequestApproval(
                    decision.status(),
                    commands,
                    request.askConfirmationBeforeEveryCommand())) {
                    TerminalAgentModels.Approval approval = new TerminalAgentModels.Approval(
                        runId,
                        request.sessionId(),
                        request.executionTarget(),
                        decision.summary(),
                        decision.userMessage(),
                        commands,
                        !confirmMutatingCommandSets);
                    ui.updateState(new TerminalAgentModels.RunState(
                        runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.AWAITING_APPROVAL,
                        decision.summary(), decision.userMessage(), approval, null, null, turn));
                    publishQuestion(ui, runId, "approval-" + turn, decision.summary(), decision.userMessage());
                    ApprovalDecision approvalDecision = ui.requestApproval(approval);
                    if (approvalDecision == ApprovalDecision.CANCEL) {
                        ui.updateState(new TerminalAgentModels.RunState(
                            runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.CANCELLED,
                            "The terminal agent run was cancelled.", "The run was cancelled before the command set started.", null, null, null, turn));
                        publishCancelled(ui, runId, "cancelled-approval-" + turn, "The run was cancelled before the command set started.");
                        return;
                    }
                    approvalBypass = approvalBypass
                        || (approval.allowAlways() && approvalDecision == ApprovalDecision.APPROVE_ALWAYS);
                }

                for (TerminalAgentModels.PlannedCommand planned : commands) {
                    ensureNotCancelled(ui);
                    boolean sudoPasswordRequired = requiresSudoPassword(probe, planned.command());
                    int sudoPasswordFailures = 0;
                    String commandActivityId = runId + ":command:" + turn + ":" + history.size();
                    long commandStartedAtNanos = 0L;
                    boolean commandActivityStarted = false;
                    while (true) {
                        String commandToRun = planned.command();
                        byte[] stdin = null;
                        ExecResult execResult;
                        try {
                            if (sudoPasswordRequired) {
                                if (cachedPassword == null || cachedPassword.isBlank()) {
                                    cachedPassword = requestSudoPassword(
                                        ui,
                                        runId,
                                        request,
                                        turn,
                                        planned.purpose(),
                                        buildSudoPasswordPromptMessage(sudoPasswordFailures),
                                        planned.command());
                                    if (cachedPassword == null || cachedPassword.isBlank()) {
                                        if (commandActivityStarted) {
                                            publishCommandActivity(
                                                ui,
                                                commandActivityId,
                                                planned,
                                                TerminalAgentModels.AgentActivityStatus.CANCELLED,
                                                null,
                                                elapsedSecondsSince(commandStartedAtNanos));
                                        }
                                        return;
                                    }
                                }
                                commandToRun = rewriteSudoCommandForPassword(commandToRun);
                                stdin = cachedPassword.toUtf8Line();
                            }

                            if (!commandActivityStarted) {
                                ui.updateState(new TerminalAgentModels.RunState(
                                    runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.RUNNING_COMMANDS,
                                    planned.purpose(), planned.command(), null, null, planned.command(), turn));
                                commandStartedAtNanos = System.nanoTime();
                                publishCommandActivity(ui, commandActivityId, planned, TerminalAgentModels.AgentActivityStatus.RUNNING, null, 0L);
                                ui.appendTranscript("\n$ " + planned.command() + "\n");
                                commandActivityStarted = true;
                            }

                            execResult = exec(terminalTab, connector, commandToRun, stdin, chunk -> {
                                if (chunk == null || chunk.isEmpty()) {
                                    return;
                                }
                                ui.appendTranscript(chunk);
                            }, ui::isCancelled);
                        } finally {
                            if (stdin != null) {
                                Arrays.fill(stdin, (byte) 0);
                            }
                        }

                        boolean sudoPasswordRejected = sudoPasswordRequired && shouldClearCachedSudoPassword(execResult);
                        if (sudoPasswordRejected) {
                            sudoPasswordFailures++;
                            if (cachedPassword != null && !cachedPassword.isSessionScoped()) {
                                cachedPassword.clear();
                            }
                            clearCachedSudoPassword(sessionId);
                            cachedPassword = null;
                            if (sudoPasswordFailures <= MAX_SUDO_PASSWORD_RETRIES) {
                                continue;
                            }
                        }

                        TerminalAgentModels.CommandResult commandResult = toCommandResult(planned, execResult);
                        history.add(commandResult);
                        publishCommandActivity(
                            ui,
                            commandActivityId,
                            planned,
                            TerminalAgentModels.AgentActivityStatus.COMPLETED,
                            execResult,
                            elapsedSecondsSince(commandStartedAtNanos));
                        if (!sudoPasswordRejected && cachedPassword != null && !cachedPassword.isSessionScoped()) {
                            cachedPassword.clear();
                            cachedPassword = null;
                        }
                        break;
                    }
                    if (decision.needsReprobe()) {
                        String reprobeId = runId + ":reprobe:" + turn;
                        publishAction(ui, reprobeId, TerminalAgentModels.AgentActivityStatus.RUNNING, "Inspect(SSH session)", "Refreshing server state.", null, 0L);
                        probe = probeTerminalSession(terminalTab, connector, ui::isCancelled);
                        publishAction(ui, reprobeId, TerminalAgentModels.AgentActivityStatus.COMPLETED, "Inspect(SSH session)", "Server state refreshed.", summarizeProbe(probe), 0L);
                    }
                }
            }

            if (!history.isEmpty() && tryFinalizeAtTurnLimit(aiService, request, probe, history, ui, runId)) {
                return;
            }

            ui.updateState(new TerminalAgentModels.RunState(
                runId,
                request.sessionId(),
                request.executionTarget(),
                TerminalAgentModels.Phase.BLOCKED,
                "The AI agent reached its turn limit.",
                "The task needs more manual guidance before the next step is safe.",
                null,
                null,
                null,
                MAX_AGENT_TURNS));
            publishMessage(ui, runId, "turn-limit", "The task needs more manual guidance before the next step is safe.", "The AI agent reached its turn limit.");
        } finally {
            if (cachedPassword != null && !cachedPassword.isSessionScoped()) {
                cachedPassword.clear();
            }
        }
    }

    private boolean tryFinalizeAtTurnLimit(
        AiPromptService aiService,
        TerminalAgentModels.Request request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.CommandResult> history,
        RunUi ui,
        String runId) {
        try {
            AgentDecision finalDecision = requestTurnLimitFinalDecision(aiService, request, probe, history, ui, runId);
            TerminalAgentModels.Phase phase = finalDecision.status() == AgentDecisionStatus.done
                ? TerminalAgentModels.Phase.DONE
                : TerminalAgentModels.Phase.BLOCKED;
            ui.updateState(new TerminalAgentModels.RunState(
                runId,
                request.sessionId(),
                request.executionTarget(),
                phase,
                finalDecision.summary(),
                finalDecision.userMessage(),
                null,
                null,
                null,
                MAX_AGENT_TURNS));
            publishMessage(
                ui,
                runId,
                finalDecision.status() == AgentDecisionStatus.done ? "turn-limit-final" : "turn-limit-blocked",
                finalDecision.userMessage(),
                finalDecision.summary());
            return true;
        } catch (Exception e) {
            publishThinking(
                ui,
                runId + ":thinking-turn-limit-final",
                TerminalAgentModels.AgentActivityStatus.FAILED,
                "Thinking",
                "Could not finalize the agent response before the turn limit.",
                e.getMessage(),
                TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                0L,
                true);
            return false;
        }
    }

    private boolean tryRunFileTypeCountRequest(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        TerminalAgentModels.Request request,
        TerminalAgentModels.ProbeSnapshot probe,
        RunUi ui,
        String runId) throws Exception {
        if (request.queryOnly()) {
            return false;
        }
        FileTypeCountRequest countRequest = detectFileTypeCountRequest(request.userPrompt());
        if (countRequest == null) {
            return false;
        }

        ensureNotCancelled(ui);
        boolean usePasswordlessSudo = probe != null && !probe.alreadyRoot() && probe.passwordlessSudo();
        String command = buildFileTypeCountCommand(countRequest.directory(), usePasswordlessSudo);
        TerminalAgentModels.PlannedCommand planned = new TerminalAgentModels.PlannedCommand(
            command,
            "Count files and MIME text/non-text distribution under " + countRequest.directory() + ".",
            TerminalAgentModels.Risk.READ_ONLY);
        ui.updateState(new TerminalAgentModels.RunState(
            runId,
            request.sessionId(),
            request.executionTarget(),
            TerminalAgentModels.Phase.RUNNING_COMMANDS,
            planned.purpose(),
            planned.command(),
            null,
            null,
            planned.command(),
            1));
        String activityId = runId + ":file-type-count";
        long startedAtNanos = System.nanoTime();
        publishCommandActivity(ui, activityId, planned, TerminalAgentModels.AgentActivityStatus.RUNNING, null, 0L);
        ui.appendTranscript("\n$ " + planned.command() + "\n");

        ExecResult execResult = exec(terminalTab, connector, command, null, chunk -> {
            if (chunk == null || chunk.isEmpty()) {
                return;
            }
            ui.appendTranscript(chunk);
        }, ui::isCancelled);
        publishCommandActivity(
            ui,
            activityId,
            planned,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            execResult,
            elapsedSecondsSince(startedAtNanos));
        ensureNotCancelled(ui);

        FileTypeCounts counts = parseFileTypeCountOutput(execResult.stdout());
        if (execResult.exitCode() != 0 || counts == null) {
            String summary = "Could not count file types under " + countRequest.directory() + ".";
            String userMessage = buildFileTypeCountFailureMessage(countRequest.directory(), execResult);
            ui.updateState(new TerminalAgentModels.RunState(
                runId,
                request.sessionId(),
                request.executionTarget(),
                TerminalAgentModels.Phase.BLOCKED,
                summary,
                userMessage,
                null,
                null,
                null,
                1));
            publishMessage(ui, runId, "file-type-count-blocked", userMessage, summary);
            return true;
        }

        String userMessage = formatFileTypeCountTable(countRequest.directory(), counts);
        String summary = "Counted files and MIME text/non-text distribution under " + countRequest.directory() + ".";
        ui.updateState(new TerminalAgentModels.RunState(
            runId,
            request.sessionId(),
            request.executionTarget(),
            TerminalAgentModels.Phase.DONE,
            summary,
            userMessage,
            null,
            null,
            null,
            1));
        publishMessage(ui, runId, "file-type-count-done", userMessage, summary);
        return true;
    }

    static FileTypeCountRequest detectFileTypeCountRequest(String userPrompt) {
        String prompt = userPrompt != null ? userPrompt.trim() : "";
        if (prompt.isEmpty()) {
            return null;
        }
        String lower = prompt.toLowerCase(Locale.ROOT);
        boolean asksForCount = lower.contains("how many") || lower.contains("count");
        if (!asksForCount || !lower.contains("file") || !lower.contains("plain text") || !lower.contains("binar")) {
            return null;
        }
        Matcher matcher = FILE_TYPE_COUNT_PATH_PATTERN.matcher(prompt);
        if (!matcher.find()) {
            return null;
        }
        String directory = stripTrailingPathPunctuation(matcher.group(2));
        if (directory.isBlank() || !directory.startsWith("/")) {
            return null;
        }
        return new FileTypeCountRequest(directory);
    }

    static String buildFileTypeCountCommand(String directory, boolean usePasswordlessSudo) {
        String script = """
            if [ ! -d "$1" ]; then
              printf 'error=directory_not_found\\n'
              exit 2
            fi
            if ! command -v file >/dev/null 2>&1; then
              printf 'error=file_command_not_found\\n'
              exit 127
            fi
            total=$(find "$1" -type f 2>/dev/null | wc -l | tr -d '[:space:]')
            text=$(find "$1" -type f -exec file --mime-type -b -- {} + 2>/dev/null | awk 'BEGIN { count=0 } /^text\\// { count++ } END { print count }')
            case "$total" in ''|*[!0-9]*) total=0 ;; esac
            case "$text" in ''|*[!0-9]*) text=0 ;; esac
            binary=$((total - text))
            printf 'total=%s\\nplain_text=%s\\nbinary_or_non_text=%s\\n' "$total" "$text" "$binary"
            """;
        String prefix = usePasswordlessSudo ? "sudo -n " : "";
        return prefix + "sh -lc " + shellSingleQuote(script) + " sh " + shellSingleQuote(directory);
    }

    static FileTypeCounts parseFileTypeCountOutput(String stdout) {
        if (stdout == null || stdout.isBlank()) {
            return null;
        }
        Long total = null;
        Long plainText = null;
        Long binaryOrNonText = null;
        Matcher matcher = COUNT_OUTPUT_PATTERN.matcher(stdout);
        while (matcher.find()) {
            long value = Long.parseLong(matcher.group(2));
            switch (matcher.group(1)) {
                case "total" -> total = value;
                case "plain_text" -> plainText = value;
                case "binary_or_non_text" -> binaryOrNonText = value;
                default -> {
                }
            }
        }
        if (total == null || plainText == null || binaryOrNonText == null) {
            return null;
        }
        return new FileTypeCounts(total, plainText, binaryOrNonText);
    }

    static String formatFileTypeCountTable(String directory, FileTypeCounts counts) {
        return "File type count for `" + directory + "`:\n\n"
            + "| Category | Count |\n"
            + "| --- | ---: |\n"
            + "| Total files | " + counts.total() + " |\n"
            + "| Plain text files (`text/*`) | " + counts.plainText() + " |\n"
            + "| Binary/non-text files | " + counts.binaryOrNonText() + " |";
    }

    private String buildFileTypeCountFailureMessage(String directory, ExecResult execResult) {
        StringBuilder message = new StringBuilder("Could not count file types under `")
            .append(directory)
            .append("`.");
        String stdout = nonBlank(execResult != null ? execResult.stdout() : "", "");
        String stderr = nonBlank(execResult != null ? execResult.stderr() : "", "");
        if (!stdout.isBlank()) {
            message.append("\n\nstdout:\n").append(trimTail(stdout).trim());
        }
        if (!stderr.isBlank()) {
            message.append("\n\nstderr:\n").append(trimTail(stderr).trim());
        }
        return message.toString();
    }

    private static String shellSingleQuote(String value) {
        return "'" + (value != null ? value : "").replace("'", "'\"'\"'") + "'";
    }

    private static String stripTrailingPathPunctuation(String path) {
        String normalized = path != null ? path.trim() : "";
        while (!normalized.isEmpty()) {
            char last = normalized.charAt(normalized.length() - 1);
            if (last != '?' && last != '.' && last != ',' && last != ':' && last != ';') {
                break;
            }
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private CachedSudoPassword requestSudoPassword(
        RunUi ui,
        String runId,
        TerminalAgentModels.Request request,
        int turn,
        String summary,
        String userMessage,
        String command) throws Exception {
        TerminalAgentModels.PasswordRequest passwordRequest = new TerminalAgentModels.PasswordRequest(
            runId,
            request.sessionId(),
            request.executionTarget(),
            nonBlank(summary, "Sudo password required."),
            nonBlank(userMessage, "Enter the sudo password to continue this SSH session."),
            command);
        ui.updateState(new TerminalAgentModels.RunState(
            runId,
            request.sessionId(),
            request.executionTarget(),
            TerminalAgentModels.Phase.AWAITING_PASSWORD,
            passwordRequest.summary(),
            passwordRequest.userMessage(),
            null,
            passwordRequest,
            command,
            turn));
        publishQuestion(ui, runId, "password-" + turn, passwordRequest.summary(), passwordRequest.userMessage());
        TerminalAgentModels.PasswordResponse passwordResponse = ui.requestPassword(passwordRequest);
        String password = passwordResponse != null ? passwordResponse.password() : null;
        if (password == null || password.isBlank()) {
            ui.updateState(new TerminalAgentModels.RunState(
                runId,
                request.sessionId(),
                request.executionTarget(),
                TerminalAgentModels.Phase.CANCELLED,
                "The terminal agent run was cancelled.",
                "No sudo password was provided.",
                null,
                null,
                command,
                turn));
            publishCancelled(ui, runId, "cancelled-password-" + turn, "No sudo password was provided.");
            return null;
        }
        char[] passwordChars = password.toCharArray();
        CachedSudoPassword cachedPassword;
        try {
            cachedPassword = new CachedSudoPassword(passwordChars, passwordResponse != null && passwordResponse.cacheForSession());
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
        if (cachedPassword.isSessionScoped()) {
            CachedSudoPassword previous = cachedSudoPasswordBySessionId.put(request.sessionId(), cachedPassword);
            if (previous != null) {
                previous.clear();
            }
        }
        return cachedPassword;
    }

    private String buildSudoPasswordPromptMessage(int failedAttempts) {
        if (failedAttempts <= 0) {
            return "Waiting for the sudo password to continue this SSH session.";
        }
        return "The sudo password was not accepted. Enter it again to continue this SSH session. Retry "
            + failedAttempts + " of " + MAX_SUDO_PASSWORD_RETRIES + ".";
    }

    public void clearCachedSudoPassword(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            return;
        }
        CachedSudoPassword cachedPassword = cachedSudoPasswordBySessionId.remove(sessionId);
        if (cachedPassword != null) {
            cachedPassword.clear();
        }
    }

    public static boolean needsSudoPasswordPreflight(TerminalAgentModels.ProbeSnapshot probe) {
        return probe != null
            && !probe.alreadyRoot()
            && probe.sudoAvailable()
            && !probe.passwordlessSudo();
    }

    public boolean verifyAndCacheSudoPassword(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        String sessionId,
        TerminalAgentModels.PasswordResponse passwordResponse,
        BooleanSupplier cancellationSupplier) throws Exception {
        if (sessionId == null || sessionId.isBlank()
            || passwordResponse == null
            || passwordResponse.password() == null
            || passwordResponse.password().isBlank()) {
            return false;
        }
        char[] passwordChars = passwordResponse.password().toCharArray();
        CachedSudoPassword cachedPassword;
        try {
            cachedPassword = new CachedSudoPassword(passwordChars, true);
        } finally {
            Arrays.fill(passwordChars, '\0');
        }
        byte[] stdin = cachedPassword.toUtf8Line();
        boolean stored = false;
        try {
            ExecResult result = exec(terminalTab, connector, "sudo -S -p '' -v", stdin, null, cancellationSupplier);
            if (result.exitCode() == 0) {
                CachedSudoPassword previous = cachedSudoPasswordBySessionId.put(sessionId, cachedPassword);
                stored = true;
                if (previous != null) {
                    previous.clear();
                }
                return true;
            }
            clearCachedSudoPassword(sessionId);
            return false;
        } finally {
            Arrays.fill(stdin, (byte) 0);
            if (!stored) {
                cachedPassword.clear();
            }
        }
    }

    static boolean shouldClearCachedSudoPassword(String stdout, String stderr) {
        String combined = ((stdout != null ? stdout : "") + "\n" + (stderr != null ? stderr : ""))
            .toLowerCase(Locale.ROOT);
        return combined.contains("sorry, try again")
            || combined.contains("incorrect password")
            || combined.contains("authentication failure")
            || combined.contains("a password is required")
            || combined.contains("no password was provided");
    }

    private static boolean shouldClearCachedSudoPassword(ExecResult execResult) {
        return execResult != null
            && execResult.exitCode() != 0
            && shouldClearCachedSudoPassword(execResult.stdout(), execResult.stderr());
    }

    private TerminalAgentModels.ProbeSnapshot updateAndProbe(
        RunUi ui,
        String runId,
        TerminalAgentModels.Request request,
        TerminalTab terminalTab,
        SshTtyConnector connector) throws Exception {
        ui.updateState(new TerminalAgentModels.RunState(
            runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.STARTING,
            "Starting terminal agent run.", request.userPrompt(), null, null, null, 0));
        publishMessage(ui, runId, "start", request.userPrompt(), "Starting terminal agent run.");
        ui.updateState(new TerminalAgentModels.RunState(
            runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.PROBING,
            "Inspecting the connected server.", "Collecting the current server state.", null, null, null, 0));
        String probeId = runId + ":probe";
        publishAction(ui, probeId, TerminalAgentModels.AgentActivityStatus.RUNNING, "Inspect(SSH session)", "Collecting the current server state.", null, 0L);
        TerminalAgentModels.ProbeSnapshot probe = probeTerminalSession(terminalTab, connector, ui::isCancelled);
        publishAction(ui, probeId, TerminalAgentModels.AgentActivityStatus.COMPLETED, "Inspect(SSH session)", "Collected the current server state.", summarizeProbe(probe), 0L);
        return probe;
    }

    AgentDecision requestAgentDecision(
        AiPromptService aiService,
        TerminalAgentModels.Request request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.CommandResult> history,
        int turn,
        boolean sudoPasswordCached,
        RunUi ui,
        String runId) throws Exception {
        String systemPrompt = buildAgentSystemPrompt(request.queryOnly());
        String userPrompt = buildAgentUserPrompt(request, probe, history, turn, sudoPasswordCached);
        String thinkingId = runId + ":thinking:" + turn;
        long startedAtNanos = System.nanoTime();
        publishThinking(ui, thinkingId, TerminalAgentModels.AgentActivityStatus.RUNNING,
            "Thinking",
            "The AI agent is deciding on the next safe step.",
            "Turn " + turn + "/" + MAX_AGENT_TURNS + ". Using the probe snapshot and command history to choose a safe next step.",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            0L,
            false);
        AiExecutionResult result = executeAgentJsonPrompt(aiService, systemPrompt, userPrompt);
        publishUsedSkillActivity(ui, runId, aiService);
        recordTokenUsage(ui, result);
        try {
            AgentDecision decision = parseAndValidateAgentDecision(result.content(), probe, request.queryOnly());
            publishThinking(ui, thinkingId, TerminalAgentModels.AgentActivityStatus.COMPLETED,
                "Thinking",
                decision.userMessage(),
                decision.summary(),
                tokenUsageOf(result),
                elapsedSecondsSince(startedAtNanos),
                true);
            return decision;
        } catch (Exception firstFailure) {
            publishThinking(ui, thinkingId, TerminalAgentModels.AgentActivityStatus.COMPLETED,
                "Thinking",
                "The AI response needed repair.",
                firstFailure.getMessage(),
                tokenUsageOf(result),
                elapsedSecondsSince(startedAtNanos),
                true);
            String repairThinkingId = runId + ":thinking-repair:" + turn;
            long repairStartedAtNanos = System.nanoTime();
            publishThinking(ui, repairThinkingId, TerminalAgentModels.AgentActivityStatus.RUNNING,
                "Thinking",
                "Repairing the AI response format.",
                "The previous response did not match the required JSON schema or command constraints.",
                TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                0L,
                false);
            AiExecutionResult repaired = executeAgentJsonPrompt(
                aiService,
                systemPrompt,
                buildAgentDecisionRepairPrompt(userPrompt, result.content(), firstFailure.getMessage()));
            publishUsedSkillActivity(ui, runId, aiService);
            recordTokenUsage(ui, repaired);
            try {
                AgentDecision decision = parseAndValidateAgentDecision(repaired.content(), probe, request.queryOnly());
                publishThinking(ui, repairThinkingId, TerminalAgentModels.AgentActivityStatus.COMPLETED,
                    "Thinking",
                    decision.userMessage(),
                    decision.summary(),
                    tokenUsageOf(repaired),
                    elapsedSecondsSince(repairStartedAtNanos),
                    true);
                return decision;
            } catch (Exception repairFailure) {
                String message = "The AI response did not match the required agent JSON schema or command constraints.";
                publishThinking(ui, repairThinkingId, TerminalAgentModels.AgentActivityStatus.FAILED,
                    "Thinking",
                    message,
                    repairFailure.getMessage(),
                    tokenUsageOf(repaired),
                    elapsedSecondsSince(repairStartedAtNanos),
                    true);
                return AgentDecision.blocked(message, "Please retry or use an AI profile/model that follows JSON instructions.");
            }
        }
    }

    private AgentDecision requestTurnLimitFinalDecision(
        AiPromptService aiService,
        TerminalAgentModels.Request request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.CommandResult> history,
        RunUi ui,
        String runId) throws Exception {
        String systemPrompt = buildAgentTurnLimitFinalSystemPrompt();
        String userPrompt = buildAgentTurnLimitFinalUserPrompt(request, probe, history);
        String thinkingId = runId + ":thinking-turn-limit";
        long startedAtNanos = System.nanoTime();
        publishThinking(
            ui,
            thinkingId,
            TerminalAgentModels.AgentActivityStatus.RUNNING,
            "Thinking",
            "The AI agent is preparing a final response from existing command results.",
            "No more commands will be planned because the turn limit was reached.",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            0L,
            false);
        AiExecutionResult result = executeAgentJsonPrompt(aiService, systemPrompt, userPrompt);
        publishUsedSkillActivity(ui, runId, aiService);
        recordTokenUsage(ui, result);
        try {
            AgentDecision decision = parseFinalAgentDecision(result.content());
            publishThinking(
                ui,
                thinkingId,
                TerminalAgentModels.AgentActivityStatus.COMPLETED,
                "Thinking",
                decision.userMessage(),
                decision.summary(),
                tokenUsageOf(result),
                elapsedSecondsSince(startedAtNanos),
                true);
            return decision;
        } catch (Exception firstFailure) {
            publishThinking(
                ui,
                thinkingId,
                TerminalAgentModels.AgentActivityStatus.COMPLETED,
                "Thinking",
                "The final AI response needed schema repair.",
                firstFailure.getMessage(),
                tokenUsageOf(result),
                elapsedSecondsSince(startedAtNanos),
                true);
            String repairThinkingId = runId + ":thinking-turn-limit-repair";
            long repairStartedAtNanos = System.nanoTime();
            publishThinking(
                ui,
                repairThinkingId,
                TerminalAgentModels.AgentActivityStatus.RUNNING,
                "Thinking",
                "Repairing the final AI response format.",
                "The previous final response did not match the required JSON schema.",
                TerminalAgentModels.AgentActivityTokenUsage.unknown(),
                0L,
                false);
            AiExecutionResult repaired = executeAgentJsonPrompt(aiService, systemPrompt, buildAgentRepairPrompt(userPrompt, result.content()));
            publishUsedSkillActivity(ui, runId, aiService);
            recordTokenUsage(ui, repaired);
            AgentDecision decision;
            try {
                decision = parseFinalAgentDecision(repaired.content());
            } catch (Exception repairFailure) {
                publishThinking(
                    ui,
                    repairThinkingId,
                    TerminalAgentModels.AgentActivityStatus.FAILED,
                    "Thinking",
                    "Repair failed",
                    repairFailure.getMessage() != null
                        ? repairFailure.getMessage()
                        : repairFailure.getClass().getSimpleName(),
                    tokenUsageOf(repaired),
                    elapsedSecondsSince(repairStartedAtNanos),
                    true);
                throw repairFailure;
            }
            publishThinking(
                ui,
                repairThinkingId,
                TerminalAgentModels.AgentActivityStatus.COMPLETED,
                "Thinking",
                decision.userMessage(),
                decision.summary(),
                tokenUsageOf(repaired),
                elapsedSecondsSince(repairStartedAtNanos),
                true);
            return decision;
        }
    }

    private AiExecutionResult executeAgentJsonPrompt(
        AiPromptService aiService,
        String systemPrompt,
        String userPrompt) throws Exception {
        try {
            return aiService.executeJsonPrompt(systemPrompt, userPrompt);
        } catch (IOException e) {
            if (!looksLikeUnsupportedJsonResponseFormat(e.getMessage())) {
                throw e;
            }
            return aiService.executeJsonPromptWithoutResponseFormat(systemPrompt, userPrompt);
        }
    }

    private void publishUsedSkillActivity(RunUi ui, String runId, AiPromptService aiService) {
        if (!(aiService instanceof AiSkillUsageTracker tracker)) {
            return;
        }
        List<AiSkillPromptSupport.SkillUsage> usages = uniqueSkillUsages(tracker.drainSkillUsages());
        if (usages.isEmpty()) {
            return;
        }
        String names = usages.stream()
            .map(AiSkillPromptSupport.SkillUsage::name)
            .filter(name -> name != null && !name.isBlank())
            .reduce((left, right) -> left + ", " + right)
            .orElse("AI Skill");
        String summary = usages.size() == 1
            ? "Using AI skill: " + names
            : "Using AI skills: " + names;
        StringBuilder detail = new StringBuilder();
        for (AiSkillPromptSupport.SkillUsage usage : usages) {
            if (detail.length() > 0) {
                detail.append('\n');
            }
            detail.append("- ")
                .append(nonBlank(usage.name(), "AI Skill"));
            if (usage.target() != null) {
                detail.append(" (").append(usage.target().name()).append(")");
            }
        }
        ui.publishActivity(new TerminalAgentModels.AgentActivity(
            runId + ":skills",
            TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            "AI Skills",
            summary,
            detail.toString(),
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            0L,
            true,
            true));
    }

    private List<AiSkillPromptSupport.SkillUsage> uniqueSkillUsages(List<AiSkillPromptSupport.SkillUsage> usages) {
        if (usages == null || usages.isEmpty()) {
            return List.of();
        }
        List<AiSkillPromptSupport.SkillUsage> unique = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (AiSkillPromptSupport.SkillUsage usage : usages) {
            if (usage == null) {
                continue;
            }
            String key = !blank(usage.id())
                ? usage.id()
                : nonBlank(usage.name(), "AI Skill") + ":" + (usage.target() != null ? usage.target().name() : "");
            if (seen.add(key)) {
                unique.add(usage);
            }
        }
        return unique;
    }

    private boolean looksLikeUnsupportedJsonResponseFormat(String message) {
        String normalized = message != null ? message.toLowerCase(Locale.ROOT) : "";
        return normalized.contains("response_format")
            || normalized.contains("json_object")
            || normalized.contains("json mode");
    }

    private AgentDecision parseAgentDecision(String rawContent) {
        JsonObject object = JsonParser.parseString(extractJsonObjectContent(rawContent)).getAsJsonObject();
        AgentDecision decision = GSON.fromJson(normalizeAgentDecisionObject(object), AgentDecision.class);
        if (decision == null || decision.status == null) {
            throw new JsonSyntaxException("Missing decision status");
        }
        if (blank(decision.summary) || blank(decision.userMessage)) {
            throw new JsonSyntaxException("Missing summary or userMessage");
        }
        if ((decision.status == AgentDecisionStatus.run_commands || decision.status == AgentDecisionStatus.needs_confirmation)
            && (decision.commands == null || decision.commands.isEmpty())) {
            throw new JsonSyntaxException("Command decisions must include commands");
        }
        return decision;
    }

    private JsonObject normalizeAgentDecisionObject(JsonObject object) {
        JsonObject normalized = object != null ? object.deepCopy() : new JsonObject();
        normalizeAgentDecisionStatus(normalized);
        normalizeAgentDecisionCommands(normalized);
        return normalized;
    }

    private void normalizeAgentDecisionStatus(JsonObject object) {
        JsonElement statusElement = object.get("status");
        if (statusElement == null || !statusElement.isJsonPrimitive()) {
            return;
        }
        String status = statusElement.getAsString();
        if (status == null || status.isBlank()) {
            return;
        }
        String normalized = status.trim()
            .toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        switch (normalized) {
            case "run", "commands", "run_command", "execute", "execute_commands" -> object.addProperty("status", "run_commands");
            case "confirm", "confirmation", "requires_confirmation", "need_confirmation" -> object.addProperty("status", "needs_confirmation");
            case "complete", "completed", "success" -> object.addProperty("status", "done");
            case "cannot", "failed", "failure" -> object.addProperty("status", "blocked");
            default -> object.addProperty("status", normalized);
        }
    }

    private void normalizeAgentDecisionCommands(JsonObject object) {
        JsonElement commandsElement = object.get("commands");
        if (commandsElement == null || commandsElement.isJsonNull()) {
            commandsElement = firstExistingElement(object, "command", "cmd", "shellCommand", "terminalCommand");
        }
        if (commandsElement == null || commandsElement.isJsonNull()) {
            return;
        }
        JsonArray commands = new JsonArray();
        if (commandsElement.isJsonArray()) {
            for (JsonElement item : commandsElement.getAsJsonArray()) {
                JsonObject command = normalizeAgentCommandObject(item, object);
                if (command != null) {
                    commands.add(command);
                }
            }
        } else {
            JsonObject command = normalizeAgentCommandObject(commandsElement, object);
            if (command != null) {
                commands.add(command);
            }
        }
        object.add("commands", commands);
    }

    private JsonObject normalizeAgentCommandObject(JsonElement element, JsonObject decisionObject) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        JsonObject commandObject = element.isJsonObject()
            ? element.getAsJsonObject().deepCopy()
            : new JsonObject();
        if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
            commandObject.addProperty("command", element.getAsString());
        }
        copyFirstString(commandObject, "command", "cmd", "shellCommand", "terminalCommand");
        copyFirstString(commandObject, "purpose", "reason", "description", "summary");
        if (!hasNonBlankString(commandObject, "purpose")) {
            copyFirstStringFromDecision(commandObject, decisionObject, "purpose", "purpose", "reason", "description", "summary", "userMessage");
        }
        copyFirstString(commandObject, "risk", "riskLevel", "confirmation", "safety");
        if (!hasNonBlankString(commandObject, "risk")) {
            copyFirstStringFromDecision(commandObject, decisionObject, "risk", "risk", "riskLevel", "confirmation", "safety");
        }
        normalizeAgentCommandRisk(commandObject);
        return hasNonBlankString(commandObject, "command") ? commandObject : null;
    }

    private void normalizeAgentCommandRisk(JsonObject commandObject) {
        JsonElement riskElement = commandObject.get("risk");
        if (riskElement == null || !riskElement.isJsonPrimitive()) {
            return;
        }
        String risk = riskElement.getAsString();
        if (risk == null || risk.isBlank()) {
            return;
        }
        String normalized = risk.trim()
            .toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_');
        if (normalized.equals("read_only")
            || normalized.equals("readonly")
            || normalized.equals("read")
            || normalized.equals("safe")
            || normalized.equals("low")) {
            commandObject.addProperty("risk", "read_only");
        } else {
            commandObject.addProperty("risk", "requires_confirmation");
        }
    }

    private JsonElement firstExistingElement(JsonObject object, String... names) {
        for (String name : names) {
            if (object.has(name)) {
                return object.get(name);
            }
        }
        return null;
    }

    private void copyFirstString(JsonObject object, String targetName, String... sourceNames) {
        if (hasNonBlankString(object, targetName)) {
            return;
        }
        for (String sourceName : sourceNames) {
            JsonElement value = object.get(sourceName);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (text != null && !text.isBlank()) {
                    object.addProperty(targetName, text.trim());
                    return;
                }
            }
        }
    }

    private void copyFirstStringFromDecision(JsonObject commandObject, JsonObject decisionObject, String targetName, String... sourceNames) {
        if (decisionObject == null) {
            return;
        }
        for (String sourceName : sourceNames) {
            JsonElement value = decisionObject.get(sourceName);
            if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                String text = value.getAsString();
                if (text != null && !text.isBlank()) {
                    commandObject.addProperty(targetName, text.trim());
                    return;
                }
            }
        }
    }

    private boolean hasNonBlankString(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null
            && value.isJsonPrimitive()
            && value.getAsJsonPrimitive().isString()
            && value.getAsString() != null
            && !value.getAsString().isBlank();
    }

    private AgentDecision parseAndValidateAgentDecision(
        String rawContent,
        TerminalAgentModels.ProbeSnapshot probe,
        boolean queryOnly) {
        AgentDecision decision = parseAgentDecision(rawContent);
        if (decision.status() == AgentDecisionStatus.run_commands || decision.status() == AgentDecisionStatus.needs_confirmation) {
            validateCommands(decision.commands(), probe, queryOnly);
        }
        return decision;
    }

    private AgentDecision parseFinalAgentDecision(String rawContent) {
        AgentDecision decision = parseAgentDecision(rawContent);
        if (decision.status() != AgentDecisionStatus.done && decision.status() != AgentDecisionStatus.blocked) {
            throw new JsonSyntaxException("Final decision must be done or blocked");
        }
        return decision;
    }

    AgentPlanQuestionDecision parsePlanQuestionDecision(String rawContent) {
        AgentPlanQuestionDecision decision = GSON.fromJson(extractJsonObjectContent(rawContent), AgentPlanQuestionDecision.class);
        if (decision == null || !"questions".equals(decision.status())) {
            throw new JsonSyntaxException("Planning question status missing");
        }
        if (decision.questions == null || decision.questions.isEmpty()) {
            throw new JsonSyntaxException("Planning questions missing");
        }
        for (AgentPlanQuestionDecisionItem item : decision.questions()) {
            if (item == null || blank(item.id()) || blank(item.question())) {
                throw new JsonSyntaxException("Planning question entry incomplete");
            }
        }
        return decision;
    }

    AgentPlanOptionDecision parsePlanOptionDecision(String rawContent) {
        AgentPlanOptionDecision decision = GSON.fromJson(extractJsonObjectContent(rawContent), AgentPlanOptionDecision.class);
        if (decision == null || blank(decision.status())) {
            throw new JsonSyntaxException("Planning option status missing");
        }
        if (!"options".equals(decision.status()) && !"blocked".equals(decision.status())) {
            throw new JsonSyntaxException("Unsupported planning option status");
        }
        if ("options".equals(decision.status()) && (decision.options == null || decision.options.isEmpty())) {
            throw new JsonSyntaxException("Planning options missing");
        }
        for (AgentPlanOptionDecisionItem item : safeList(decision.options())) {
            if (item == null || blank(item.title()) || blank(item.summary())) {
                throw new JsonSyntaxException("Planning option entry incomplete");
            }
        }
        return decision;
    }

    AgentPlanReportDecision parsePlanReportDecision(String rawContent) {
        AgentPlanReportDecision decision = GSON.fromJson(extractJsonObjectContent(rawContent), AgentPlanReportDecision.class);
        if (decision == null || !"final_plan".equals(decision.status())) {
            throw new JsonSyntaxException("Planning report status missing");
        }
        if (blank(decision.title()) || blank(decision.summary())) {
            throw new JsonSyntaxException("Planning report missing title or summary");
        }
        if (decision.steps() == null || decision.steps().isEmpty()) {
            throw new JsonSyntaxException("Planning report missing steps");
        }
        return decision;
    }

    static String extractJsonObjectContent(String rawContent) {
        String candidate = rawContent != null ? rawContent.trim() : "";
        if (candidate.isEmpty()) {
            throw new JsonSyntaxException("AI response was empty.");
        }
        for (int attempt = 0; attempt < 3; attempt++) {
            candidate = stripMarkdownFence(candidate).trim();
            JsonElement element = parseJsonElementWithStringRepairs(candidate);
            if (element != null && element.isJsonObject()) {
                JsonObject object = element.getAsJsonObject();
                return object.toString();
            }
            if (element != null && element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                candidate = element.getAsString().trim();
                continue;
            }

            String embeddedObject = extractFirstBalancedJsonObject(candidate);
            if (embeddedObject != null) {
                candidate = embeddedObject;
                continue;
            }
            break;
        }
        throw new JsonSyntaxException("AI response did not contain the required JSON object. Received: "
            + trimForError(candidate));
    }

    private static JsonElement parseJsonElementWithStringRepairs(String candidate) {
        try {
            return JsonParser.parseString(candidate);
        } catch (Exception ignored) {
            // Fall through to a narrow repair for model-generated JSON strings.
        }
        String repaired = repairJsonStringCharacters(candidate);
        if (repaired.equals(candidate)) {
            return null;
        }
        try {
            return JsonParser.parseString(repaired);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static String repairJsonStringCharacters(String value) {
        if (value == null || value.isEmpty()) {
            return value != null ? value : "";
        }
        StringBuilder repaired = new StringBuilder(value.length());
        boolean inString = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (!inString) {
                repaired.append(c);
                if (c == '"') {
                    inString = true;
                }
                continue;
            }
            if (c == '"') {
                repaired.append(c);
                inString = false;
                continue;
            }
            if (c == '\\') {
                if (i + 1 >= value.length()) {
                    repaired.append("\\\\");
                    continue;
                }
                char next = value.charAt(i + 1);
                if (isValidJsonEscape(value, i + 1)) {
                    repaired.append('\\').append(next);
                    i++;
                    if (next == 'u') {
                        repaired.append(value, i + 1, i + 5);
                        i += 4;
                    }
                } else {
                    repaired.append("\\\\");
                }
                continue;
            }
            if (c == '\n') {
                repaired.append("\\n");
            } else if (c == '\r') {
                if (i + 1 < value.length() && value.charAt(i + 1) == '\n') {
                    i++;
                }
                repaired.append("\\n");
            } else if (c == '\t') {
                repaired.append("\\t");
            } else if (c < 0x20) {
                repaired.append(String.format("\\u%04x", (int) c));
            } else {
                repaired.append(c);
            }
        }
        return repaired.toString();
    }

    private static boolean isValidJsonEscape(String value, int escapeCharIndex) {
        char escaped = value.charAt(escapeCharIndex);
        if (escaped == '"' || escaped == '\\' || escaped == '/' || escaped == 'b'
            || escaped == 'f' || escaped == 'n' || escaped == 'r' || escaped == 't') {
            return true;
        }
        if (escaped != 'u' || escapeCharIndex + 4 >= value.length()) {
            return false;
        }
        for (int i = escapeCharIndex + 1; i <= escapeCharIndex + 4; i++) {
            if (!isHexDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9')
            || (c >= 'a' && c <= 'f')
            || (c >= 'A' && c <= 'F');
    }

    private static String stripMarkdownFence(String value) {
        String trimmed = value != null ? value.trim() : "";
        if (!trimmed.startsWith("```")) {
            return trimmed;
        }
        int firstLineEnd = trimmed.indexOf('\n');
        int closingFence = trimmed.lastIndexOf("```");
        if (firstLineEnd < 0 || closingFence <= firstLineEnd) {
            return trimmed;
        }
        return trimmed.substring(firstLineEnd + 1, closingFence).trim();
    }

    private static String extractFirstBalancedJsonObject(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int start = -1;
        boolean inString = false;
        boolean escaped = false;
        int depth = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (start < 0) {
                if (c == '{') {
                    start = i;
                    depth = 1;
                }
                continue;
            }
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    return value.substring(start, i + 1);
                }
            }
        }
        return null;
    }

    private static String trimForError(String text) {
        String normalized = text != null ? text.replace('\n', ' ').replace('\r', ' ').trim() : "";
        if (normalized.length() <= 180) {
            return normalized;
        }
        return normalized.substring(0, 177) + "...";
    }

    private List<TerminalAgentModels.PlannedCommand> validateCommands(
        List<AgentCommandDecision> commands,
        TerminalAgentModels.ProbeSnapshot probe,
        boolean queryOnly) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        if (commands.size() > MAX_COMMANDS_PER_TURN) {
            throw new IllegalArgumentException("The AI agent returned too many commands.");
        }
        List<TerminalAgentModels.PlannedCommand> validated = new ArrayList<>();
        for (AgentCommandDecision command : commands) {
            String trimmed = nonBlank(command.command, null);
            String purpose = nonBlank(command.purpose, null);
            if (trimmed == null || purpose == null) {
                throw new IllegalArgumentException("The AI planner returned an incomplete command entry.");
            }
            if (queryOnly) {
                throw new IllegalArgumentException("Query-only agent mode must not execute commands.");
            }
            if (isInteractiveCommand(trimmed)) {
                throw new IllegalArgumentException("Interactive commands are not supported: " + trimmed);
            }
            trimmed = normalizeSudoForAgentExecution(trimmed);
            if (containsSudoWithoutNonInteractiveFlag(trimmed)) {
                throw new IllegalArgumentException("Use sudo -n ... only: " + trimmed);
            }
            if (usesUnknownPackageManager(trimmed, probe.packageManagers())) {
                throw new IllegalArgumentException("Command uses a package manager that is not present on the server: " + trimmed);
            }
            if (usesUnknownServiceManager(trimmed, probe.serviceManagers())) {
                throw new IllegalArgumentException("Command uses a service manager that is not present on the server: " + trimmed);
            }
            TerminalAgentModels.Risk risk = "read_only".equalsIgnoreCase(nonBlank(command.risk, "requires_confirmation"))
                ? TerminalAgentModels.Risk.READ_ONLY
                : TerminalAgentModels.Risk.REQUIRES_CONFIRMATION;
            if (risk == TerminalAgentModels.Risk.READ_ONLY && requiresConfirmationByCommandShape(trimmed)) {
                risk = TerminalAgentModels.Risk.REQUIRES_CONFIRMATION;
            }
            validated.add(new TerminalAgentModels.PlannedCommand(
                trimmed,
                purpose,
                risk));
        }
        return validated;
    }

    static boolean shouldRequestApproval(
        AgentDecisionStatus status,
        List<TerminalAgentModels.PlannedCommand> commands,
        boolean askConfirmationBeforeEveryCommand) {

        return status == AgentDecisionStatus.needs_confirmation
            || askConfirmationBeforeEveryCommand
            || safeList(commands).stream()
                .anyMatch(command -> command.risk() == TerminalAgentModels.Risk.REQUIRES_CONFIRMATION);
    }

    public static boolean requiresConfirmationByCommandShape(String command) {
        String checked = stripHereDocumentBodiesForCommandCheck(command);
        return MUTATING_COMMAND_PATTERN.matcher(checked).find() || containsWriteRedirection(checked);
    }

    private static boolean containsWriteRedirection(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaped = false;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\' && !inSingleQuote) {
                escaped = true;
                continue;
            }
            if (c == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (c == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (c != '>' || inSingleQuote || inDoubleQuote) {
                continue;
            }
            char previous = i > 0 ? command.charAt(i - 1) : '\0';
            char next = i + 1 < command.length() ? command.charAt(i + 1) : '\0';
            if (previous == '<' || previous == '=' || previous == '-' || next == '=' || next == '&') {
                continue;
            }
            return true;
        }
        return false;
    }

    public static boolean isInteractiveCommand(String command) {
        String normalized = " " + stripHereDocumentBodiesForCommandCheck(command).toLowerCase(Locale.ROOT) + " ";
        for (String token : INTERACTIVE_COMMAND_TOKENS) {
            if (normalized.contains(" " + token + " ")) {
                return true;
            }
        }
        return false;
    }

    static String stripHereDocumentBodiesForCommandCheck(String command) {
        if (command == null || command.isBlank() || !command.contains("<<")) {
            return command != null ? command : "";
        }

        StringBuilder sanitized = new StringBuilder(command.length());
        List<String> pendingDelimiters = new ArrayList<>();
        String[] lines = command.split("\\R", -1);
        for (String line : lines) {
            if (!pendingDelimiters.isEmpty()) {
                if (matchesHereDocumentDelimiter(line, pendingDelimiters.getFirst())) {
                    pendingDelimiters.removeFirst();
                }
                continue;
            }
            sanitized.append(line).append('\n');
            Matcher matcher = HERE_DOCUMENT_OPERATOR_PATTERN.matcher(line);
            while (matcher.find()) {
                String delimiter = firstNonNull(matcher.group(1), matcher.group(2), matcher.group(3));
                if (delimiter != null && !delimiter.isBlank()) {
                    pendingDelimiters.add(delimiter);
                }
            }
        }
        return sanitized.toString();
    }

    private static boolean matchesHereDocumentDelimiter(String line, String delimiter) {
        if (line == null || delimiter == null) {
            return false;
        }
        return line.equals(delimiter) || line.replaceFirst("^\\t+", "").equals(delimiter);
    }

    private static String firstNonNull(String first, String second, String third) {
        if (first != null) {
            return first;
        }
        if (second != null) {
            return second;
        }
        return third;
    }

    public static String normalizeSudoForAgentExecution(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        if (containsForbiddenSudoMode(command)) {
            throw new IllegalArgumentException("Unsupported sudo mode: " + command);
        }

        Matcher matcher = SUDO_INVOCATION_PATTERN.matcher(command);
        StringBuffer normalized = new StringBuffer();
        while (matcher.find()) {
            String rest = command.substring(matcher.end());
            if (startsWithNonInteractiveSudoFlag(rest)) {
                matcher.appendReplacement(normalized, Matcher.quoteReplacement(matcher.group()));
            } else {
                matcher.appendReplacement(normalized, Matcher.quoteReplacement(matcher.group(1) + "sudo -n "));
            }
        }
        matcher.appendTail(normalized);
        return stripPlannerSudoStdinOptions(normalized.toString());
    }

    private static String stripPlannerSudoStdinOptions(String command) {
        Matcher matcher = SUDO_STDIN_OPTION_AFTER_NON_INTERACTIVE_PATTERN.matcher(command);
        return matcher.replaceAll("$1sudo -n ");
    }

    private static boolean containsSudoWithoutNonInteractiveFlag(String command) {
        if (command == null || command.isBlank()) {
            return false;
        }
        Matcher matcher = SUDO_INVOCATION_PATTERN.matcher(command);
        while (matcher.find()) {
            if (!startsWithNonInteractiveSudoFlag(command.substring(matcher.end()))) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsForbiddenSudoMode(String command) {
        Matcher matcher = SUDO_INVOCATION_PATTERN.matcher(command);
        while (matcher.find()) {
            String rest = command.substring(matcher.end()).stripLeading();
            if (rest.isBlank()) {
                continue;
            }
            String[] tokens = rest.split("\\s+", 8);
            for (String token : tokens) {
                if (token.isBlank()) {
                    continue;
                }
                if ("--".equals(token)) {
                    break;
                }
                if (token.startsWith("-")) {
                    if (isForbiddenSudoOption(token)) {
                        return true;
                    }
                    continue;
                }
                return "su".equalsIgnoreCase(token);
            }
        }
        return false;
    }

    private static boolean isForbiddenSudoOption(String token) {
        String lowerToken = token.toLowerCase(Locale.ROOT);
        if ("--shell".equals(lowerToken) || "--login".equals(lowerToken)) {
            return true;
        }
        if (lowerToken.startsWith("--")) {
            return false;
        }
        return token.indexOf('s') >= 0 || token.indexOf('i') >= 0;
    }

    private static boolean startsWithNonInteractiveSudoFlag(String rest) {
        if (rest == null) {
            return false;
        }
        String stripped = rest.stripLeading();
        return stripped.startsWith("-n")
            && (stripped.length() == 2 || Character.isWhitespace(stripped.charAt(2)));
    }

    static boolean shouldPromptForSudoPasswordAfterBlockedDecision(
        String summary,
        String userMessage,
        TerminalAgentModels.ProbeSnapshot probe,
        String cachedPassword) {
        return shouldPromptForSudoPasswordAfterBlockedDecision(
            summary,
            userMessage,
            probe,
            cachedPassword != null && !cachedPassword.isBlank());
    }

    private static boolean shouldPromptForSudoPasswordAfterBlockedDecision(
        String summary,
        String userMessage,
        TerminalAgentModels.ProbeSnapshot probe,
        CachedSudoPassword cachedPassword) {
        return shouldPromptForSudoPasswordAfterBlockedDecision(
            summary,
            userMessage,
            probe,
            cachedPassword != null && !cachedPassword.isBlank());
    }

    private static boolean shouldPromptForSudoPasswordAfterBlockedDecision(
        String summary,
        String userMessage,
        TerminalAgentModels.ProbeSnapshot probe,
        boolean cachedPasswordPresent) {
        if (probe == null
            || probe.alreadyRoot()
            || !probe.sudoAvailable()
            || probe.passwordlessSudo()
            || cachedPasswordPresent) {
            return false;
        }
        String text = ((summary != null ? summary : "") + " " + (userMessage != null ? userMessage : ""))
            .toLowerCase(Locale.ROOT);
        return text.contains("sudo") && (text.contains("password") || text.contains("passwort"));
    }

    private boolean usesUnknownPackageManager(String command, List<String> availablePackageManagers) {
        Set<String> managers = Set.of("apt", "apt-get", "dnf", "yum", "zypper", "pacman", "apk", "brew");
        String firstToken = firstToken(command);
        return managers.contains(firstToken) && !safeList(availablePackageManagers).contains(firstToken);
    }

    private boolean usesUnknownServiceManager(String command, List<String> availableServiceManagers) {
        Set<String> managers = Set.of("systemctl", "service", "rc-service", "launchctl");
        String firstToken = firstToken(command);
        return managers.contains(firstToken) && !safeList(availableServiceManagers).contains(firstToken);
    }

    private String rewriteSudoCommandForPassword(String command) {
        if (command == null || command.isBlank()) {
            return command;
        }
        Matcher matcher = SUDO_NON_INTERACTIVE_INVOCATION_PATTERN.matcher(command);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1) + "sudo -S -p '' "));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private boolean requiresSudoPassword(TerminalAgentModels.ProbeSnapshot probe, String command) {
        return probe != null
            && !probe.alreadyRoot()
            && probe.sudoAvailable()
            && !probe.passwordlessSudo()
            && command != null
            && SUDO_NON_INTERACTIVE_INVOCATION_PATTERN.matcher(command).find();
    }

    private void ensureNotCancelled(RunUi ui) {
        if (ui.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new AgentCancelledException("Terminal agent run cancelled");
        }
    }

    private TerminalAgentModels.CommandResult toCommandResult(TerminalAgentModels.PlannedCommand planned, ExecResult execResult) {
        return new TerminalAgentModels.CommandResult(
            planned.command(),
            planned.purpose(),
            planned.risk(),
            execResult.exitCode(),
            null,
            trimTail(execResult.stdout()),
            trimTail(execResult.stderr()),
            execResult.stdout().length() > COMMAND_OUTPUT_TAIL_CHARS,
            execResult.stderr().length() > COMMAND_OUTPUT_TAIL_CHARS,
            execResult.cancelled(),
            execResult.timedOut());
    }

    private ExecResult exec(TerminalTab terminalTab, String command, byte[] stdin) throws Exception {
        return exec(terminalTab, command, stdin, null, null);
    }

    private ExecResult exec(TerminalTab terminalTab, String command, byte[] stdin, java.util.function.Consumer<String> outputConsumer) throws Exception {
        return exec(terminalTab, command, stdin, outputConsumer, null);
    }

    private ExecResult exec(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        String command,
        byte[] stdin,
        java.util.function.Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier) throws Exception {
        return execInternal(terminalTab, connector, command, stdin, outputConsumer, cancellationSupplier);
    }

    private ExecResult exec(
        TerminalTab terminalTab,
        String command,
        byte[] stdin,
        java.util.function.Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier) throws Exception {
        return execInternal(terminalTab, null, command, stdin, outputConsumer, cancellationSupplier);
    }

    private ExecResult execInternal(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        String command,
        byte[] stdin,
        java.util.function.Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier) throws Exception {
        return execInternal(terminalTab, connector, command, stdin, outputConsumer, cancellationSupplier, true);
    }

    private ExecResult execInternal(
        TerminalTab terminalTab,
        SshTtyConnector connector,
        String command,
        byte[] stdin,
        java.util.function.Consumer<String> outputConsumer,
        BooleanSupplier cancellationSupplier,
        boolean useTrackedWorkingDirectory) throws Exception {
        connector = requireConnector(terminalTab, connector);
        String commandToExecute = useTrackedWorkingDirectory
            ? wrapCommandForWorkingDirectory(command, connector.getCurrentRemoteDirectory())
            : command;
        try (ChannelExec channel = connector.getSession().createExecChannel(commandToExecute)) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.setErr(stderr);
            if (stdin != null && stdin.length > 0) {
                channel.setIn(new ByteArrayInputStream(stdin));
                channel.open().verify(COMMAND_OPEN_TIMEOUT);
            } else {
                channel.open().verify(COMMAND_OPEN_TIMEOUT);
            }
            boolean timedOut = waitForCommand(channel, cancellationSupplier);
            String stdoutText = stdout.toString(StandardCharsets.UTF_8);
            String stderrText = stderr.toString(StandardCharsets.UTF_8);
            if (outputConsumer != null) {
                if (!stdoutText.isBlank()) {
                    outputConsumer.accept(stdoutText);
                    if (!stdoutText.endsWith("\n")) {
                        outputConsumer.accept("\n");
                    }
                }
                if (!stderrText.isBlank()) {
                    outputConsumer.accept(stderrText);
                    if (!stderrText.endsWith("\n")) {
                        outputConsumer.accept("\n");
                    }
                }
            }
            Integer exitStatus = channel.getExitStatus();
            return new ExecResult(stdoutText, stderrText, exitStatus != null ? exitStatus : -1, false, timedOut);
        }
    }

    private boolean waitForCommand(ChannelExec channel, BooleanSupplier cancellationSupplier) throws Exception {
        long deadlineNanos = System.nanoTime() + COMMAND_WAIT_TIMEOUT.toNanos();
        while (true) {
            if ((cancellationSupplier != null && cancellationSupplier.getAsBoolean()) || Thread.currentThread().isInterrupted()) {
                channel.close(false);
                throw new AgentCancelledException("Terminal agent run cancelled");
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

    private SshTtyConnector requireConnector(TerminalTab terminalTab) {
        return requireConnector(terminalTab, null);
    }

    private SshTtyConnector requireConnector(TerminalTab terminalTab, SshTtyConnector connector) {
        TerminalView terminalView = terminalTab.getTerminalView();
        if (terminalView == null) {
            throw new IllegalStateException("The selected SSH session is not connected.");
        }
        if (connector == null) {
            connector = terminalView.getActiveSshConnector();
        }
        if (connector == null || connector.getSession() == null) {
            throw new IllegalStateException("The selected SSH session is not connected.");
        }
        return connector;
    }

    static String wrapCommandForWorkingDirectory(String command, String workingDirectory) {
        if (command == null || command.isBlank()) {
            return command;
        }
        String normalizedDirectory = workingDirectory != null ? workingDirectory : "";
        if (normalizedDirectory.isBlank()) {
            return command;
        }
        if ("~".equals(normalizedDirectory)) {
            return "cd ~ && " + command;
        }
        if (normalizedDirectory.startsWith("~/")) {
            return "cd ~/" + shellSingleQuote(normalizedDirectory.substring(2)) + " && " + command;
        }
        if (!normalizedDirectory.startsWith("/")) {
            return command;
        }
        return "cd " + shellSingleQuote(normalizedDirectory) + " && " + command;
    }

    static boolean isMissingTrackedWorkingDirectory(String stderr, String workingDirectory) {
        if (stderr == null || stderr.isBlank() || workingDirectory == null || workingDirectory.isBlank()) {
            return false;
        }
        String normalizedError = stderr.toLowerCase(Locale.ROOT);
        return normalizedError.contains("cd:")
            && normalizedError.contains(workingDirectory.toLowerCase(Locale.ROOT))
            && (normalizedError.contains("no such file or directory")
                || normalizedError.contains("datei oder verzeichnis nicht gefunden")
                || normalizedError.contains("not a directory"));
    }

    private void publishMessage(RunUi ui, String runId, String suffix, String summary, String detail) {
        ui.publishActivity(new TerminalAgentModels.AgentActivity(
            runId + ":message:" + suffix,
            TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.COMPLETED,
            nonBlank(summary, "AI agent update"),
            nonBlank(summary, ""),
            nonBlank(detail, ""),
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            0L,
            !blank(detail),
            true));
    }

    private void publishQuestion(RunUi ui, String runId, String suffix, String summary, String detail) {
        ui.publishActivity(new TerminalAgentModels.AgentActivity(
            runId + ":question:" + suffix,
            TerminalAgentModels.AgentActivityType.QUESTION,
            TerminalAgentModels.AgentActivityStatus.RUNNING,
            nonBlank(summary, "Input required"),
            nonBlank(summary, ""),
            nonBlank(detail, ""),
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            0L,
            !blank(detail),
            false));
    }

    private void publishCancelled(RunUi ui, String runId, String suffix, String summary) {
        ui.publishActivity(new TerminalAgentModels.AgentActivity(
            runId + ":cancelled:" + suffix,
            TerminalAgentModels.AgentActivityType.MESSAGE,
            TerminalAgentModels.AgentActivityStatus.CANCELLED,
            "Cancelled",
            nonBlank(summary, "The terminal agent run was cancelled."),
            "",
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            0L,
            false,
            true));
    }

    private void publishAction(
        RunUi ui,
        String id,
        TerminalAgentModels.AgentActivityStatus status,
        String title,
        String summary,
        String detail,
        long elapsedSeconds) {
        ui.publishActivity(new TerminalAgentModels.AgentActivity(
            id,
            TerminalAgentModels.AgentActivityType.ACTION,
            status,
            nonBlank(title, "Action"),
            nonBlank(summary, ""),
            nonBlank(detail, ""),
            TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            elapsedSeconds,
            !blank(detail),
            status != TerminalAgentModels.AgentActivityStatus.RUNNING));
    }

    private void publishCommandActivity(
        RunUi ui,
        String id,
        TerminalAgentModels.PlannedCommand planned,
        TerminalAgentModels.AgentActivityStatus status,
        ExecResult execResult,
        long elapsedSeconds) {
        String verb = planned.risk() == TerminalAgentModels.Risk.READ_ONLY ? "Read" : "Run";
        String title = buildCommandActivityTitle(verb, planned.command());
        String summary = status == TerminalAgentModels.AgentActivityStatus.RUNNING
            ? planned.purpose()
            : buildCommandSummary(execResult);
        String detail = status == TerminalAgentModels.AgentActivityStatus.RUNNING
            ? planned.purpose()
            : buildCommandDetail(planned, execResult);
        publishAction(ui, id, status, title, summary, detail, elapsedSeconds);
    }

    static String buildCommandActivityTitle(String verb, String command) {
        String normalizedVerb = (verb == null || verb.isBlank()) ? "Run" : verb.trim();
        String preview = commandPreview(command);
        return normalizedVerb + "(" + preview + ")";
    }

    private static String commandPreview(String command) {
        if (command == null || command.isBlank()) {
            return "";
        }
        String firstLine = command.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse(command.trim());
        boolean hasMoreLines = command.lines().skip(1).anyMatch(line -> !line.isBlank());
        String suffix = hasMoreLines ? " ..." : "";
        int maxTextLength = Math.max(0, COMMAND_TITLE_PREVIEW_CHARS - suffix.length());
        if (firstLine.length() > maxTextLength) {
            return firstLine.substring(0, maxTextLength).trim() + suffix;
        }
        return firstLine + suffix;
    }

    private String buildCommandSummary(ExecResult execResult) {
        if (execResult == null) {
            return "Running command.";
        }
        if (execResult.timedOut()) {
            return "Command timed out.";
        }
        int outputLines = countLines(execResult.stdout()) + countLines(execResult.stderr());
        return "Exit " + execResult.exitCode() + " - " + outputLines + " output lines";
    }

    private String buildCommandDetail(TerminalAgentModels.PlannedCommand planned, ExecResult execResult) {
        if (execResult == null) {
            return planned.purpose();
        }
        StringBuilder detail = new StringBuilder();
        detail.append(planned.purpose()).append("\n");
        detail.append("Command: ").append(planned.command()).append("\n");
        detail.append("Exit code: ").append(execResult.exitCode());
        if (execResult.timedOut()) {
            detail.append(" (timed out)");
        }
        String stdout = trimTail(execResult.stdout());
        if (!stdout.isBlank()) {
            detail.append("\nstdout:\n").append(stdout.trim());
        }
        String stderr = trimTail(execResult.stderr());
        if (!stderr.isBlank()) {
            detail.append("\nstderr:\n").append(stderr.trim());
        }
        return detail.toString();
    }

    private int countLines(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return (int) text.lines().count();
    }

    private void publishThinking(
        RunUi ui,
        String id,
        TerminalAgentModels.AgentActivityStatus status,
        String title,
        String summary,
        String detail,
        TerminalAgentModels.AgentActivityTokenUsage tokenUsage,
        long elapsedSeconds,
        boolean collapsed) {
        ui.publishActivity(new TerminalAgentModels.AgentActivity(
            id,
            TerminalAgentModels.AgentActivityType.THINKING,
            status,
            nonBlank(title, "Thinking"),
            nonBlank(summary, ""),
            nonBlank(detail, ""),
            tokenUsage != null ? tokenUsage : TerminalAgentModels.AgentActivityTokenUsage.unknown(),
            elapsedSeconds,
            !blank(detail),
            collapsed));
    }

    private void recordTokenUsage(RunUi ui, AiExecutionResult result) {
        if (result != null && result.usage() != null) {
            ui.recordTokenUsage(result.usage());
        }
    }

    private TerminalAgentModels.AgentActivityTokenUsage tokenUsageOf(AiExecutionResult result) {
        AiTokenUsage usage = result != null ? result.usage() : null;
        if (usage == null) {
            return TerminalAgentModels.AgentActivityTokenUsage.unknown();
        }
        return new TerminalAgentModels.AgentActivityTokenUsage(
            true,
            usage.promptTokens(),
            usage.completionTokens(),
            usage.totalTokens());
    }

    private long elapsedSecondsSince(long startedAtNanos) {
        if (startedAtNanos <= 0L) {
            return 0L;
        }
        long elapsedNanos = System.nanoTime() - startedAtNanos;
        return Math.max(0L, TimeUnit.NANOSECONDS.toSeconds(elapsedNanos));
    }

    private TerminalAgentModels.ProbeSnapshot parseProbeOutput(String stdout) {
        Map<String, String> values = Arrays.stream((stdout != null ? stdout : "").split("\\R"))
            .map(String::trim)
            .filter(line -> !line.isEmpty() && line.contains("="))
            .map(line -> line.split("=", 2))
            .collect(java.util.stream.Collectors.toMap(parts -> parts[0], parts -> parts.length > 1 ? parts[1] : "", (left, right) -> right));
        return new TerminalAgentModels.ProbeSnapshot(
            values.getOrDefault("osRelease", ""),
            values.getOrDefault("kernel", ""),
            values.getOrDefault("architecture", ""),
            values.getOrDefault("shell", ""),
            values.getOrDefault("currentUser", ""),
            values.getOrDefault("uid", ""),
            values.getOrDefault("gid", ""),
            splitCsv(values.get("groups")),
            values.getOrDefault("homeDir", ""),
            values.getOrDefault("currentDir", ""),
            parseLong(values.get("availableDiskKb")),
            values.getOrDefault("availableDiskPath", values.getOrDefault("currentDir", "")),
            splitCsv(values.get("packageManagers")),
            splitCsv(values.get("serviceManagers")),
            Boolean.parseBoolean(values.getOrDefault("alreadyRoot", "false")),
            Boolean.parseBoolean(values.getOrDefault("sudoAvailable", "false")),
            Boolean.parseBoolean(values.getOrDefault("passwordlessSudo", "false")),
            Boolean.parseBoolean(values.getOrDefault("sudoNonInteractive", "false")),
            values.getOrDefault("sudoNListSummary", ""),
            values.getOrDefault("rootEscalationMode", ""));
    }

    private String buildProbeCommand() {
        return """
            sh -lc '
            sanitize() {
              printf "%s" "$1" | tr "\\r\\n\\t" "   " | sed "s/[[:space:]]\\+/ /g"
            }
            os_release=""
            if [ -f /etc/os-release ]; then
              . /etc/os-release >/dev/null 2>&1
              os_release="${PRETTY_NAME:-${NAME:-}}"
            fi
            [ -n "$os_release" ] || os_release="$(uname -s 2>/dev/null || printf unknown)"
            kernel="$(uname -sr 2>/dev/null || printf unknown)"
            architecture="$(uname -m 2>/dev/null || printf unknown)"
            shell_value="${SHELL:-}"
            current_user="$(id -un 2>/dev/null || whoami 2>/dev/null || printf unknown)"
            uid_value="$(id -u 2>/dev/null || printf unknown)"
            gid_value="$(id -g 2>/dev/null || printf unknown)"
            groups_value="$(id -nG 2>/dev/null || printf)"
            home_dir="${HOME:-}"
            current_dir="$(pwd 2>/dev/null || printf)"
            available_disk_kb="$(df -Pk . 2>/dev/null | awk "NR==2 {print \\$4}")"
            available_disk_path="$(df -Pk . 2>/dev/null | awk "NR==2 {print \\$6}")"
            package_managers=""
            for candidate in apt apt-get dnf yum zypper pacman apk brew; do
              if command -v "$candidate" >/dev/null 2>&1; then
                package_managers="${package_managers},${candidate}"
              fi
            done
            service_managers=""
            for candidate in systemctl service rc-service launchctl; do
              if command -v "$candidate" >/dev/null 2>&1; then
                service_managers="${service_managers},${candidate}"
              fi
            done
            already_root=false
            [ "$uid_value" = "0" ] && already_root=true
            sudo_available=false
            passwordless_sudo=false
            sudo_non_interactive=false
            sudo_n_list_summary=""
            if command -v sudo >/dev/null 2>&1; then
              sudo_available=true
              if sudo -n true >/dev/null 2>&1; then
                passwordless_sudo=true
                sudo_non_interactive=true
              fi
              sudo_n_list_summary="$(sudo -n -l 2>&1 | tail -c 2000)"
            fi
            if [ "$already_root" = "true" ]; then
              root_mode="already_root"
            elif [ "$passwordless_sudo" = "true" ]; then
              root_mode="passwordless_sudo"
            elif [ "$sudo_available" = "true" ]; then
              root_mode="sudo_password"
            else
              root_mode="none"
            fi
            printf "osRelease=%s\\n" "$(sanitize "$os_release")"
            printf "kernel=%s\\n" "$(sanitize "$kernel")"
            printf "architecture=%s\\n" "$(sanitize "$architecture")"
            printf "shell=%s\\n" "$(sanitize "$shell_value")"
            printf "currentUser=%s\\n" "$(sanitize "$current_user")"
            printf "uid=%s\\n" "$(sanitize "$uid_value")"
            printf "gid=%s\\n" "$(sanitize "$gid_value")"
            printf "groups=%s\\n" "$(sanitize "$groups_value" | tr " " ",")"
            printf "homeDir=%s\\n" "$(sanitize "$home_dir")"
            printf "currentDir=%s\\n" "$(sanitize "$current_dir")"
            printf "availableDiskKb=%s\\n" "$(sanitize "$available_disk_kb")"
            printf "availableDiskPath=%s\\n" "$(sanitize "$available_disk_path")"
            printf "packageManagers=%s\\n" "$(printf "%s" "$package_managers" | sed "s/^,//")"
            printf "serviceManagers=%s\\n" "$(printf "%s" "$service_managers" | sed "s/^,//")"
            printf "alreadyRoot=%s\\n" "$already_root"
            printf "sudoAvailable=%s\\n" "$sudo_available"
            printf "passwordlessSudo=%s\\n" "$passwordless_sudo"
            printf "sudoNonInteractive=%s\\n" "$sudo_non_interactive"
            printf "sudoNListSummary=%s\\n" "$(sanitize "$sudo_n_list_summary")"
            printf "rootEscalationMode=%s\\n" "$root_mode"
            '
            """;
    }

    private String buildAgentSystemPrompt(boolean queryOnly) {
        if (queryOnly) {
            return String.join(" ",
                "You are KorTTY's non-executing SSH helper.",
                "Reply with exactly one JSON object and nothing else.",
                "Do not use Markdown, code fences, comments, or explanations outside the JSON object.",
                "Never invent facts. Only use the provided probe snapshot and previous command results.",
                "Do not return commands in query-only mode.",
                "Allowed status values: `done`, `blocked`.",
                "JSON schema: {\"status\":\"done|blocked\",\"summary\":\"short summary\",\"userMessage\":\"short text for the user\",\"commands\":[],\"needsReprobe\":false}");
        }
        return String.join(" ",
            "You are the planner for a remote SSH terminal automation helper.",
            "Reply with exactly one JSON object and nothing else.",
            "Do not use Markdown, code fences, comments, or explanations outside the JSON object.",
            "Never invent facts. Only use the provided probe snapshot and command results.",
            "You may suggest at most 3 commands.",
            "All commands must be non-interactive and safe to run over SSH without user input.",
            "Each command runs in its own non-interactive SSH exec channel from the active terminal working directory in `probe.currentDir`; do not rely on `cd` persisting to later commands.",
            "When the user asks to create or save a file and does not specify an absolute path, create it in `probe.currentDir`.",
            "If sudo is needed, use `sudo -n ...` only. Never use `su`, `sudo su`, `sudo -S`, or commands that wait for a password.",
            "If the probe says `sudoAvailable` is true but `passwordlessSudo` is false, you may still plan `sudo -n ...` commands.",
            "If the runtime state says `sudoPasswordCached` is true, do not ask for the sudo password again.",
            "If the task is complete, set `status` to `done`.",
            "If previous command results already answer the user task, set `status` to `done` instead of planning more commands.",
            "For read-only inventory, counting, or classification tasks, prefer one aggregate shell command and finish once its output contains the requested counts.",
            "If the task cannot be completed with the known facts or there is no root access and no sudo available for privileged work, set `status` to `blocked`.",
            "If commands would change the system or need privilege, use `needs_confirmation`.",
            "Allowed `status` values: `run_commands`, `needs_confirmation`, `done`, `blocked`.",
            "Allowed `risk` values for each command: `read_only`, `requires_confirmation`.",
            "JSON schema: {\"status\":\"run_commands|needs_confirmation|done|blocked\",\"summary\":\"short summary\",\"userMessage\":\"short text for the user\",\"commands\":[{\"command\":\"shell command\",\"purpose\":\"why this command is needed\",\"risk\":\"read_only|requires_confirmation\"}],\"needsReprobe\":false}");
    }

    private String buildAgentUserPrompt(
        TerminalAgentModels.Request request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.CommandResult> history,
        int turn,
        boolean sudoPasswordCached) {
        String probeJson = GSON.toJson(probe);
        String historyJson = GSON.toJson(history);
        String runtimeStateJson = GSON.toJson(Map.of("sudoPasswordCached", sudoPasswordCached));
        String acceptedPlanContext = request.acceptedPlanContext() != null && !request.acceptedPlanContext().isBlank()
            ? "\nAccepted plan context:\n" + request.acceptedPlanContext().trim() + "\n"
            : "";
        String acceptedPlanInstruction = request.acceptedPlanContext() != null && !request.acceptedPlanContext().isBlank()
            ? "Use the accepted plan context as a binding execution plan unless the probe or command results prove that it is impossible on this server.\n\n"
            : "";

        return "User task: " + request.userPrompt().trim() + "\n"
            + "Connection: " + nonBlank(request.connectionDisplayName(), "unknown connection") + "\n"
            + buildPromptSessionContext(probe)
            + "Turn: " + turn + "/" + MAX_AGENT_TURNS + "\n\n"
            + (turn >= MAX_AGENT_TURNS
                ? "This is the final planning turn. If previous command results contain enough evidence to answer the task, return `done` now. If they do not, return `blocked` with the exact missing information.\n\n"
                : "")
            + "Runtime state:\n```json\n" + runtimeStateJson + "\n```\n\n"
            + "Remote probe snapshot:\n```json\n" + probeJson + "\n```\n\n"
            + "Previous command results:\n```json\n" + historyJson + "\n```\n\n"
            + acceptedPlanInstruction
            + acceptedPlanContext
            + "Plan the next step now.";
    }

    String buildAgentTurnLimitFinalSystemPrompt() {
        return String.join(" ",
            "You are KorTTY's final response writer for a remote SSH terminal automation helper.",
            "Reply with exactly one JSON object and nothing else.",
            "No more commands may be run.",
            "Never invent facts. Only use the provided probe snapshot and previous command results.",
            "If the previous command results answer the user task, set `status` to `done` and put the final answer in `userMessage`.",
            "If the previous command results do not contain enough evidence, set `status` to `blocked` and name the exact missing information.",
            "For tables, use a compact Markdown table inside the JSON string value.",
            "Allowed `status` values: `done`, `blocked`.",
            "Always return `commands`: [] and `needsReprobe`: false.",
            "JSON schema: {\"status\":\"done|blocked\",\"summary\":\"short summary\",\"userMessage\":\"short text for the user\",\"commands\":[],\"needsReprobe\":false}");
    }

    String buildAgentTurnLimitFinalUserPrompt(
        TerminalAgentModels.Request request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.CommandResult> history) {
        return "User task: " + request.userPrompt().trim() + "\n"
            + "Connection: " + nonBlank(request.connectionDisplayName(), "unknown connection") + "\n"
            + buildPromptSessionContext(probe)
            + "Turn limit reached: " + MAX_AGENT_TURNS + "/" + MAX_AGENT_TURNS + "\n\n"
            + "Remote probe snapshot:\n```json\n" + GSON.toJson(probe) + "\n```\n\n"
            + "Previous command results:\n```json\n" + GSON.toJson(history) + "\n```\n\n"
            + "Write the final response now without planning more commands.";
    }

    private String buildAgentRepairPrompt(String originalUserPrompt, String invalidResponse) {
        return "Your previous reply was invalid. Reply again with exactly one JSON object that matches the required schema. "
            + "Keep using this original request context, including `probe.currentDir` and the active terminal working directory:\n"
            + "<original_request_context>\n" + nonBlank(originalUserPrompt, "") + "\n</original_request_context>\n\n"
            + "Do not add Markdown. Previous reply:\n```text\n" + nonBlank(invalidResponse, "") + "\n```";
    }

    private String buildAgentDecisionRepairPrompt(String originalUserPrompt, String invalidResponse, String validationError) {
        return "Your previous reply was invalid. Reply again with exactly one JSON object that matches the required schema and constraints. "
            + "Validation error: " + nonBlank(validationError, "unknown validation error") + "\n"
            + "Keep using this original request context, including `probe.currentDir` and the active terminal working directory:\n"
            + "<original_request_context>\n" + nonBlank(originalUserPrompt, "") + "\n</original_request_context>\n\n"
            + "Return at most " + MAX_COMMANDS_PER_TURN + " commands. If the task needs more commands, return only the next safe batch; later turns can continue. "
            + "If status is `run_commands` or `needs_confirmation`, `commands` must be a non-empty array of objects with `command`, `purpose`, and `risk`. "
            + "Never return `run_commands` or `needs_confirmation` with an empty or missing `commands` array. "
            + "If no safe command can be returned, use status `blocked` and `commands`: []. Do not add Markdown. Previous reply:\n```text\n"
            + nonBlank(invalidResponse, "") + "\n```";
    }

    private String buildPlanQuestionSystemPrompt() {
        return String.join(" ",
            "You are KorTTY's planning agent.",
            "You are in planning mode and must never output shell commands.",
            "Ask clarifying questions first, even if the task seems clear.",
            "Return exactly one JSON object and no Markdown.",
            "Allowed status value: `questions`.",
            "JSON schema: {\"status\":\"questions\",\"summary\":\"short summary\",\"userMessage\":\"short text for the user\",\"questions\":[{\"id\":\"q1\",\"question\":\"question text\",\"options\":[\"short option\"],\"allowCustomAnswer\":true}]}",
            "For `questions`, return between 1 and 3 concrete questions.",
            "For every question, include 2 to 4 short answer options and set `allowCustomAnswer` true unless a custom answer would be unsafe.");
    }

    private String buildPlanOptionSystemPrompt() {
        return String.join(" ",
            "You are KorTTY's planning agent.",
            "You are still in planning mode and must never output shell commands.",
            "Return exactly one JSON object and no Markdown.",
            "Allowed status values: `options`, `blocked`.",
            "JSON schema: {\"status\":\"options|blocked\",\"summary\":\"short summary\",\"userMessage\":\"short text for the user\",\"options\":[{\"title\":\"option title\",\"summary\":\"short summary\",\"feasibility\":\"feasibility note\",\"risks\":[\"risk\"],\"prerequisites\":[\"prerequisite\"],\"steps\":[\"step\"],\"alternatives\":[\"alternative\"]}]}",
            "For `options`, return between 1 and 3 concrete implementation options.");
    }

    private String buildPlanReportSystemPrompt() {
        return String.join(" ",
            "You are KorTTY's planning agent preparing the final implementation plan.",
            "You are still in planning mode and must never output shell commands.",
            "Return exactly one JSON object and no Markdown.",
            "Allowed status value: `final_plan`.",
            "JSON schema: {\"status\":\"final_plan\",\"title\":\"plan title\",\"summary\":\"short report\",\"userMessage\":\"short text for the user\",\"prerequisites\":[\"item\"],\"steps\":[\"step\"],\"risks\":[\"risk\"],\"successCriteria\":[\"criterion\"]}",
            "Make the plan concise, executable, and specific enough for the agent to implement without further product decisions.");
    }

    private String buildPlanQuestionUserPrompt(TerminalAgentModels.PlanRequest request, TerminalAgentModels.ProbeSnapshot probe) {
        return "User task: " + request.userPrompt().trim() + "\n"
            + "Connection: " + nonBlank(request.connectionDisplayName(), "unknown connection") + "\n"
            + buildPromptSessionContext(probe)
            + "Remote probe snapshot:\n```json\n" + GSON.toJson(probe) + "\n```\n\n"
            + "Ask the user clarifying questions now.";
    }

    private String buildPlanOptionUserPrompt(
        TerminalAgentModels.PlanRequest request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.PlanQuestion> questions,
        String answers,
        String customApproach) {
        String customBlock = customApproach != null && !customApproach.isBlank()
            ? "\nUser custom approach:\n" + customApproach.trim() + "\n"
            : "";
        return "User task: " + request.userPrompt().trim() + "\n"
            + "Connection: " + nonBlank(request.connectionDisplayName(), "unknown connection") + "\n"
            + buildPromptSessionContext(probe)
            + "Remote probe snapshot:\n```json\n" + GSON.toJson(probe) + "\n```\n\n"
            + "Clarifying questions:\n```json\n" + GSON.toJson(questions) + "\n```\n\n"
            + "User answers:\n" + nonBlank(answers, "No explicit answers were provided.") + "\n"
            + (customApproach != null && !customApproach.isBlank()
                ? "Incorporate the user's own approach into the new options."
                : "Use the answers to refine the options.")
            + customBlock + "\nCreate implementation options now.";
    }

    private String buildPlanReportUserPrompt(
        TerminalAgentModels.PlanRequest request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.PlanQuestion> questions,
        String answers,
        TerminalAgentModels.PlanOption selectedOption,
        String customApproach) {
        String customBlock = customApproach != null && !customApproach.isBlank()
            ? "\nLatest user refinement:\n" + customApproach.trim() + "\n"
            : "";
        return "User task: " + request.userPrompt().trim() + "\n"
            + "Connection: " + nonBlank(request.connectionDisplayName(), "unknown connection") + "\n"
            + buildPromptSessionContext(probe)
            + "Remote probe snapshot:\n```json\n" + GSON.toJson(probe) + "\n```\n\n"
            + "Clarifying questions:\n```json\n" + GSON.toJson(questions) + "\n```\n\n"
            + "User answers:\n" + nonBlank(answers, "No explicit answers were provided.") + "\n\n"
            + "Selected implementation option:\n```json\n" + GSON.toJson(selectedOption) + "\n```\n"
            + customBlock
            + "\nCreate the final plan report now.";
    }

    private String buildPromptSessionContext(TerminalAgentModels.ProbeSnapshot probe) {
        return "Remote user: " + nonBlank(probe != null ? probe.currentUser() : "", "unknown") + "\n"
            + "Remote home directory: " + nonBlank(probe != null ? probe.homeDir() : "", "unknown") + "\n"
            + "Active terminal working directory: " + nonBlank(probe != null ? probe.currentDir() : "", "unknown") + "\n";
    }

    private String joinPlanItems(List<String> items) {
        List<String> safe = safeList(items);
        return safe.isEmpty() ? "none" : String.join(", ", safe);
    }

    private String joinSteps(List<String> steps) {
        List<String> safe = safeList(steps);
        if (safe.isEmpty()) {
            return "- No explicit steps";
        }
        return safe.stream().map(step -> "- " + step).collect(java.util.stream.Collectors.joining("\n"));
    }

    private String firstToken(String command) {
        String normalized = command != null ? command.trim() : "";
        if (normalized.isEmpty()) {
            return "";
        }
        String[] parts = normalized.split("\\s+", 2);
        return parts[0];
    }

    private String trimToSingleLine(String text) {
        return nonBlank(text, "").replace('\n', ' ').replace('\r', ' ').trim();
    }

    private String trimTail(String text) {
        if (text == null) {
            return "";
        }
        if (text.length() <= COMMAND_OUTPUT_TAIL_CHARS) {
            return text;
        }
        return text.substring(text.length() - COMMAND_OUTPUT_TAIL_CHARS);
    }

    private List<String> splitCsv(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        Set<String> values = new LinkedHashSet<>();
        for (String token : raw.split(",")) {
            String normalized = token != null ? token.trim() : "";
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return List.copyOf(values);
    }

    private Long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String nonBlank(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }

    private static <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    record FileTypeCountRequest(String directory) {
    }

    record FileTypeCounts(long total, long plainText, long binaryOrNonText) {
    }

    private record ExecResult(String stdout, String stderr, int exitCode, boolean cancelled, boolean timedOut) {
    }

    private static final class CachedSudoPassword {
        private char[] value;
        private final boolean sessionScoped;

        private CachedSudoPassword(char[] value, boolean sessionScoped) {
            this.value = value != null ? Arrays.copyOf(value, value.length) : new char[0];
            this.sessionScoped = sessionScoped;
        }

        private boolean isSessionScoped() {
            return sessionScoped;
        }

        private boolean isBlank() {
            char[] current = value;
            if (current == null || current.length == 0) {
                return true;
            }
            for (char ch : current) {
                if (!Character.isWhitespace(ch)) {
                    return false;
                }
            }
            return true;
        }

        private byte[] toUtf8Line() {
            char[] current = value;
            if (current == null || current.length == 0) {
                return new byte[0];
            }
            char[] charsWithNewline = Arrays.copyOf(current, current.length + 1);
            charsWithNewline[charsWithNewline.length - 1] = '\n';
            try {
                ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(charsWithNewline));
                byte[] bytes = new byte[encoded.remaining()];
                encoded.get(bytes);
                if (encoded.hasArray()) {
                    Arrays.fill(encoded.array(), (byte) 0);
                }
                encoded.clear();
                return bytes;
            } finally {
                Arrays.fill(charsWithNewline, '\0');
            }
        }

        private void clear() {
            if (value != null) {
                Arrays.fill(value, '\0');
                value = null;
            }
        }
    }

    static class AgentDecision {
        private AgentDecisionStatus status;
        private String summary;
        private String userMessage;
        private List<AgentCommandDecision> commands = Collections.emptyList();
        private boolean needsReprobe;

        public AgentDecisionStatus status() {
            return status;
        }

        public String summary() {
            return summary;
        }

        public String userMessage() {
            return userMessage;
        }

        public List<AgentCommandDecision> commands() {
            return commands != null ? commands : List.of();
        }

        public boolean needsReprobe() {
            return needsReprobe;
        }

        static AgentDecision blocked(String summary, String userMessage) {
            AgentDecision decision = new AgentDecision();
            decision.status = AgentDecisionStatus.blocked;
            decision.summary = summary;
            decision.userMessage = userMessage;
            decision.commands = List.of();
            decision.needsReprobe = false;
            return decision;
        }
    }

    enum AgentDecisionStatus {
        run_commands,
        needs_confirmation,
        done,
        blocked;

        AgentDecisionStatus normalized() {
            return this;
        }
    }

    static class AgentCommandDecision {
        private String command;
        private String purpose;
        private String risk;

        String command() {
            return command;
        }

        String purpose() {
            return purpose;
        }

        String risk() {
            return risk;
        }
    }

    record AgentPlanQuestionDecision(String status, String summary, String userMessage, List<AgentPlanQuestionDecisionItem> questions) {
    }

    record AgentPlanQuestionDecisionItem(String id, String question, List<String> options, boolean allowCustomAnswer) {
    }

    record AgentPlanOptionDecision(String status, String summary, String userMessage, List<AgentPlanOptionDecisionItem> options) {
    }

    record AgentPlanOptionDecisionItem(
        String title,
        String summary,
        String feasibility,
        List<String> risks,
        List<String> prerequisites,
        List<String> steps,
        List<String> alternatives) {
    }

    record AgentPlanReportDecision(
        String status,
        String title,
        String summary,
        String userMessage,
        List<String> prerequisites,
        List<String> steps,
        List<String> risks,
        List<String> successCriteria) {
    }
}
