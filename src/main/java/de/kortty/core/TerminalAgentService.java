package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import de.kortty.model.AiProfile;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.TerminalAgentModels;
import de.kortty.ui.TerminalTab;
import de.kortty.ui.TerminalView;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
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

/**
 * Shared runtime for AI Agent execution and planning flows.
 */
public class TerminalAgentService {

    private static final Gson GSON = new Gson();
    private static final int MAX_AGENT_TURNS = 8;
    private static final int MAX_COMMANDS_PER_TURN = 3;
    private static final int COMMAND_OUTPUT_TAIL_CHARS = 4_000;
    private static final Duration COMMAND_OPEN_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration COMMAND_WAIT_TIMEOUT = Duration.ofMinutes(15);
    private static final List<String> INTERACTIVE_COMMAND_TOKENS = List.of(
        "vi", "vim", "nano", "less", "more", "man", "top", "htop");

    private final Map<String, String> cachedSudoPasswordBySessionId = new ConcurrentHashMap<>();

    public interface RunUi {
        void updateState(TerminalAgentModels.RunState state);
        void appendTranscript(String text);
        ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) throws Exception;
        String requestPassword(TerminalAgentModels.PasswordRequest request) throws Exception;
        boolean isCancelled();
    }

    public interface PlanProgressUi {
        void updateState(TerminalAgentModels.PlanRunState state);
    }

    public enum ApprovalDecision {
        APPROVE_ONCE,
        APPROVE_ALWAYS,
        CANCEL
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

    public TerminalAgentModels.ProbeSnapshot probeTerminalSession(TerminalTab terminalTab) throws Exception {
        ExecResult result = exec(terminalTab, buildProbeCommand(), null);
        if (result.exitCode() != 0) {
            throw new IllegalStateException("Terminal probe failed: " + trimToSingleLine(result.stderr()));
        }
        return parseProbeOutput(result.stdout());
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
        OpenAiCompatibleAiService aiService,
        TerminalAgentModels.PlanRequest request,
        TerminalAgentModels.ProbeSnapshot probe) throws Exception {
        String systemPrompt = buildPlanQuestionSystemPrompt();
        String userPrompt = buildPlanQuestionUserPrompt(request, probe);
        AiExecutionResult result = aiService.executePrompt(systemPrompt, userPrompt);
        AgentPlanQuestionDecision decision = parsePlanQuestionDecision(result.content());
        List<TerminalAgentModels.PlanQuestion> questions = decision.questions().stream()
            .map(item -> new TerminalAgentModels.PlanQuestion(item.id(), item.question()))
            .toList();
        return new PlanningQuestions(questions, decision.summary(), decision.userMessage());
    }

    public PlanningOptions requestPlanningOptions(
        AiProfile profile,
        OpenAiCompatibleAiService aiService,
        TerminalAgentModels.PlanRequest request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.PlanQuestion> questions,
        String answers,
        String customApproach) throws Exception {
        String systemPrompt = buildPlanOptionSystemPrompt();
        String userPrompt = buildPlanOptionUserPrompt(request, probe, questions, answers, customApproach);
        AiExecutionResult result = aiService.executePrompt(systemPrompt, userPrompt);
        AgentPlanOptionDecision decision = parsePlanOptionDecision(result.content());
        List<TerminalAgentModels.PlanOption> options = new ArrayList<>();
        for (AgentPlanOptionDecisionItem item : decision.options()) {
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

    public void runAgent(
        TerminalTab terminalTab,
        AiProfile profile,
        OpenAiCompatibleAiService aiService,
        TerminalAgentModels.Request request,
        RunUi ui) throws Exception {
        Objects.requireNonNull(terminalTab, "terminalTab");
        Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(aiService, "aiService");
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(ui, "ui");

        String runId = UUID.randomUUID().toString();
        TerminalAgentModels.ProbeSnapshot probe = updateAndProbe(ui, runId, request, terminalTab);
        List<TerminalAgentModels.CommandResult> history = new ArrayList<>();
        boolean approvalBypass = request.autoApproveRootCommands();
        String cachedPassword = cachedSudoPasswordBySessionId.get(request.sessionId());

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

            AgentDecision decision = requestAgentDecision(aiService, request, probe, history, turn, cachedPassword != null && !cachedPassword.isBlank());
            if (decision.status() == AgentDecisionStatus.done) {
                ui.updateState(new TerminalAgentModels.RunState(
                    runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.DONE,
                    decision.summary(), decision.userMessage(), null, null, null, turn));
                return;
            }
            if (decision.status() == AgentDecisionStatus.blocked) {
                ui.updateState(new TerminalAgentModels.RunState(
                    runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.BLOCKED,
                    decision.summary(), decision.userMessage(), null, null, null, turn));
                return;
            }

            List<TerminalAgentModels.PlannedCommand> commands = validateCommands(decision.commands(), probe, request.queryOnly());
            if (!approvalBypass && (decision.status() == AgentDecisionStatus.needs_confirmation || request.askConfirmationBeforeEveryCommand())) {
                TerminalAgentModels.Approval approval = new TerminalAgentModels.Approval(
                    runId,
                    request.sessionId(),
                    request.executionTarget(),
                    decision.summary(),
                    decision.userMessage(),
                    commands);
                ui.updateState(new TerminalAgentModels.RunState(
                    runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.AWAITING_APPROVAL,
                    decision.summary(), decision.userMessage(), approval, null, null, turn));
                ApprovalDecision approvalDecision = ui.requestApproval(approval);
                if (approvalDecision == ApprovalDecision.CANCEL) {
                    ui.updateState(new TerminalAgentModels.RunState(
                        runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.CANCELLED,
                        "The terminal agent run was cancelled.", "The run was cancelled before the command set started.", null, null, null, turn));
                    return;
                }
                approvalBypass = approvalBypass || approvalDecision == ApprovalDecision.APPROVE_ALWAYS;
            }

            for (TerminalAgentModels.PlannedCommand planned : commands) {
                ensureNotCancelled(ui);
                String commandToRun = planned.command();
                byte[] stdin = null;
                if (requiresSudoPassword(probe, commandToRun)) {
                    if (cachedPassword == null || cachedPassword.isBlank()) {
                        TerminalAgentModels.PasswordRequest passwordRequest = new TerminalAgentModels.PasswordRequest(
                            runId,
                            request.sessionId(),
                            request.executionTarget(),
                            planned.purpose(),
                            "Waiting for the sudo password to continue this SSH session.",
                            planned.command());
                        ui.updateState(new TerminalAgentModels.RunState(
                            runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.AWAITING_PASSWORD,
                            planned.purpose(), passwordRequest.userMessage(), null, passwordRequest, planned.command(), turn));
                        cachedPassword = ui.requestPassword(passwordRequest);
                        if (cachedPassword == null || cachedPassword.isBlank()) {
                            ui.updateState(new TerminalAgentModels.RunState(
                                runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.CANCELLED,
                                "The terminal agent run was cancelled.", "No sudo password was provided.", null, null, planned.command(), turn));
                            return;
                        }
                        cachedSudoPasswordBySessionId.put(request.sessionId(), cachedPassword);
                    }
                    commandToRun = rewriteSudoCommandForPassword(commandToRun);
                    stdin = (cachedPassword + "\n").getBytes(StandardCharsets.UTF_8);
                }

                ui.updateState(new TerminalAgentModels.RunState(
                    runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.RUNNING_COMMANDS,
                    planned.purpose(), planned.command(), null, null, planned.command(), turn));
                ui.appendTranscript("\n$ " + planned.command() + "\n");

                ExecResult execResult = exec(terminalTab, commandToRun, stdin, chunk -> {
                    if (chunk == null || chunk.isEmpty()) {
                        return;
                    }
                    ui.appendTranscript(chunk);
                    if (request.executionTarget() == TerminalAgentExecutionTarget.TERMINAL_WINDOW && request.showRuntimeMessages()) {
                        terminalTab.getTerminalView().showMessage(chunk.endsWith("\n") ? chunk.trim() : chunk);
                    }
                });
                TerminalAgentModels.CommandResult commandResult = toCommandResult(planned, execResult);
                history.add(commandResult);
                if (execResult.exitCode() != 0 && requiresSudoPassword(probe, planned.command())) {
                    cachedSudoPasswordBySessionId.remove(request.sessionId());
                    cachedPassword = null;
                }
                if (decision.needsReprobe()) {
                    probe = probeTerminalSession(terminalTab);
                }
            }
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
    }

    private TerminalAgentModels.ProbeSnapshot updateAndProbe(
        RunUi ui,
        String runId,
        TerminalAgentModels.Request request,
        TerminalTab terminalTab) throws Exception {
        ui.updateState(new TerminalAgentModels.RunState(
            runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.STARTING,
            "Starting terminal agent run.", request.userPrompt(), null, null, null, 0));
        ui.updateState(new TerminalAgentModels.RunState(
            runId, request.sessionId(), request.executionTarget(), TerminalAgentModels.Phase.PROBING,
            "Inspecting the connected server.", "Collecting the current server state.", null, null, null, 0));
        return probeTerminalSession(terminalTab);
    }

    private AgentDecision requestAgentDecision(
        OpenAiCompatibleAiService aiService,
        TerminalAgentModels.Request request,
        TerminalAgentModels.ProbeSnapshot probe,
        List<TerminalAgentModels.CommandResult> history,
        int turn,
        boolean sudoPasswordCached) throws Exception {
        String systemPrompt = buildAgentSystemPrompt(request.queryOnly());
        String userPrompt = buildAgentUserPrompt(request, probe, history, turn, sudoPasswordCached);
        AiExecutionResult result = aiService.executePrompt(systemPrompt, userPrompt);
        try {
            return parseAgentDecision(result.content());
        } catch (Exception firstFailure) {
            AiExecutionResult repaired = aiService.executePrompt(systemPrompt, buildAgentRepairPrompt(result.content()));
            return parseAgentDecision(repaired.content());
        }
    }

    private AgentDecision parseAgentDecision(String rawContent) {
        AgentDecision decision = GSON.fromJson(rawContent != null ? rawContent.trim() : "", AgentDecision.class);
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

    private AgentPlanQuestionDecision parsePlanQuestionDecision(String rawContent) {
        AgentPlanQuestionDecision decision = GSON.fromJson(rawContent != null ? rawContent.trim() : "", AgentPlanQuestionDecision.class);
        if (decision == null || decision.questions == null || decision.questions.isEmpty()) {
            throw new JsonSyntaxException("Planning questions missing");
        }
        return decision;
    }

    private AgentPlanOptionDecision parsePlanOptionDecision(String rawContent) {
        AgentPlanOptionDecision decision = GSON.fromJson(rawContent != null ? rawContent.trim() : "", AgentPlanOptionDecision.class);
        if (decision == null || decision.options == null || decision.options.isEmpty()) {
            throw new JsonSyntaxException("Planning options missing");
        }
        return decision;
    }

    private List<TerminalAgentModels.PlannedCommand> validateCommands(
        List<AgentCommandDecision> commands,
        TerminalAgentModels.ProbeSnapshot probe,
        boolean queryOnly) {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        if (commands.size() > MAX_COMMANDS_PER_TURN) {
            throw new IllegalArgumentException("The AI planner returned too many commands.");
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
            if (containsSudoWithoutNonInteractiveFlag(trimmed)) {
                throw new IllegalArgumentException("Use sudo -n ... only: " + trimmed);
            }
            if (usesUnknownPackageManager(trimmed, probe.packageManagers())) {
                throw new IllegalArgumentException("Command uses a package manager that is not present on the server: " + trimmed);
            }
            if (usesUnknownServiceManager(trimmed, probe.serviceManagers())) {
                throw new IllegalArgumentException("Command uses a service manager that is not present on the server: " + trimmed);
            }
            validated.add(new TerminalAgentModels.PlannedCommand(
                trimmed,
                purpose,
                "read_only".equalsIgnoreCase(nonBlank(command.risk, "requires_confirmation"))
                    ? TerminalAgentModels.Risk.READ_ONLY
                    : TerminalAgentModels.Risk.REQUIRES_CONFIRMATION));
        }
        return validated;
    }

    private boolean isInteractiveCommand(String command) {
        String normalized = " " + command.toLowerCase(Locale.ROOT) + " ";
        for (String token : INTERACTIVE_COMMAND_TOKENS) {
            if (normalized.contains(" " + token + " ")) {
                return true;
            }
        }
        return false;
    }

    private boolean containsSudoWithoutNonInteractiveFlag(String command) {
        String normalized = command.toLowerCase(Locale.ROOT);
        return normalized.contains("sudo ")
            && !normalized.contains("sudo -n ")
            && !normalized.startsWith("sudo -n ");
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
        int index = command.indexOf("sudo -n ");
        if (index < 0) {
            return command;
        }
        return command.substring(0, index) + "sudo -S -p '' " + command.substring(index + "sudo -n ".length());
    }

    private boolean requiresSudoPassword(TerminalAgentModels.ProbeSnapshot probe, String command) {
        return probe != null
            && !probe.alreadyRoot()
            && probe.sudoAvailable()
            && !probe.passwordlessSudo()
            && command != null
            && command.toLowerCase(Locale.ROOT).contains("sudo -n ");
    }

    private void ensureNotCancelled(RunUi ui) {
        if (ui.isCancelled() || Thread.currentThread().isInterrupted()) {
            throw new IllegalStateException("Terminal agent run cancelled");
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
            false,
            false);
    }

    private ExecResult exec(TerminalTab terminalTab, String command, byte[] stdin) throws Exception {
        return exec(terminalTab, command, stdin, null);
    }

    private ExecResult exec(TerminalTab terminalTab, String command, byte[] stdin, java.util.function.Consumer<String> outputConsumer) throws Exception {
        ClientSession session = requireSession(terminalTab);
        try (ChannelExec channel = session.createExecChannel(command)) {
            ByteArrayOutputStream stdout = new ByteArrayOutputStream();
            ByteArrayOutputStream stderr = new ByteArrayOutputStream();
            channel.setOut(stdout);
            channel.setErr(stderr);
            if (stdin != null && stdin.length > 0) {
                OutputStream pipedIn = channel.getInvertedIn();
                channel.open().verify(COMMAND_OPEN_TIMEOUT);
                pipedIn.write(stdin);
                pipedIn.flush();
                pipedIn.close();
            } else {
                channel.open().verify(COMMAND_OPEN_TIMEOUT);
            }
            channel.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), COMMAND_WAIT_TIMEOUT.toMillis());
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
            return new ExecResult(stdoutText, stderrText, exitStatus != null ? exitStatus : -1);
        }
    }

    private ClientSession requireSession(TerminalTab terminalTab) {
        TerminalView terminalView = terminalTab.getTerminalView();
        if (terminalView == null || terminalView.getActiveSshConnector() == null || terminalView.getActiveSshConnector().getSession() == null) {
            throw new IllegalStateException("The selected SSH session is not connected.");
        }
        return terminalView.getActiveSshConnector().getSession();
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
            "If sudo is needed, use `sudo -n ...` only. Never use `su`, `sudo su`, `sudo -S`, or commands that wait for a password.",
            "If the probe says `sudoAvailable` is true but `passwordlessSudo` is false, you may still plan `sudo -n ...` commands.",
            "If the runtime state says `sudoPasswordCached` is true, do not ask for the sudo password again.",
            "If the task is complete, set `status` to `done`.",
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
            + "Turn: " + turn + "/" + MAX_AGENT_TURNS + "\n\n"
            + "Runtime state:\n```json\n" + runtimeStateJson + "\n```\n\n"
            + "Remote probe snapshot:\n```json\n" + probeJson + "\n```\n\n"
            + "Previous command results:\n```json\n" + historyJson + "\n```\n\n"
            + acceptedPlanInstruction
            + acceptedPlanContext
            + "Plan the next step now.";
    }

    private String buildAgentRepairPrompt(String invalidResponse) {
        return "Your previous reply was invalid. Reply again with exactly one JSON object that matches the required schema. "
            + "Do not add Markdown. Previous reply:\n```text\n" + nonBlank(invalidResponse, "") + "\n```";
    }

    private String buildPlanQuestionSystemPrompt() {
        return String.join(" ",
            "You are KorTTY's planning agent.",
            "You are in planning mode and must never output shell commands.",
            "Ask clarifying questions first, even if the task seems clear.",
            "Return exactly one JSON object and no Markdown.",
            "Allowed status value: `questions`.",
            "JSON schema: {\"status\":\"questions\",\"summary\":\"short summary\",\"userMessage\":\"short text for the user\",\"questions\":[{\"id\":\"q1\",\"question\":\"question text\"}]}",
            "For `questions`, return between 1 and 3 concrete questions.");
    }

    private String buildPlanOptionSystemPrompt() {
        return String.join(" ",
            "You are KorTTY's planning agent.",
            "You are still in planning mode and must never output shell commands.",
            "Return exactly one JSON object and no Markdown.",
            "Allowed status values: `options`, `blocked`, `done`.",
            "JSON schema: {\"status\":\"options|blocked|done\",\"summary\":\"short summary\",\"userMessage\":\"short text for the user\",\"options\":[{\"title\":\"option title\",\"summary\":\"short summary\",\"feasibility\":\"feasibility note\",\"risks\":[\"risk\"],\"prerequisites\":[\"prerequisite\"],\"steps\":[\"step\"],\"alternatives\":[\"alternative\"]}]}",
            "For `options`, return between 1 and 3 concrete implementation options.");
    }

    private String buildPlanQuestionUserPrompt(TerminalAgentModels.PlanRequest request, TerminalAgentModels.ProbeSnapshot probe) {
        return "User task: " + request.userPrompt().trim() + "\n"
            + "Connection: " + nonBlank(request.connectionDisplayName(), "unknown connection") + "\n"
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
            + "Remote probe snapshot:\n```json\n" + GSON.toJson(probe) + "\n```\n\n"
            + "Clarifying questions:\n```json\n" + GSON.toJson(questions) + "\n```\n\n"
            + "User answers:\n" + nonBlank(answers, "No explicit answers were provided.") + "\n"
            + (customApproach != null && !customApproach.isBlank()
                ? "Incorporate the user's own approach into the new options."
                : "Use the answers to refine the options.")
            + customBlock + "\nCreate implementation options now.";
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

    private <T> List<T> safeList(List<T> values) {
        return values == null ? List.of() : values.stream().filter(Objects::nonNull).toList();
    }

    private record ExecResult(String stdout, String stderr, int exitCode) {
    }

    private static class AgentDecision {
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
    }

    private enum AgentDecisionStatus {
        run_commands,
        needs_confirmation,
        done,
        blocked;

        AgentDecisionStatus normalized() {
            return this;
        }
    }

    private static class AgentCommandDecision {
        private String command;
        private String purpose;
        private String risk;
    }

    private record AgentPlanQuestionDecision(String status, String summary, String userMessage, List<AgentPlanQuestionDecisionItem> questions) {
    }

    private record AgentPlanQuestionDecisionItem(String id, String question) {
    }

    private record AgentPlanOptionDecision(String status, String summary, String userMessage, List<AgentPlanOptionDecisionItem> options) {
    }

    private record AgentPlanOptionDecisionItem(
        String title,
        String summary,
        String feasibility,
        List<String> risks,
        List<String> prerequisites,
        List<String> steps,
        List<String> alternatives) {
    }
}
