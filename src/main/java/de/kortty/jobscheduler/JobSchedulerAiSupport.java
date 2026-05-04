package de.kortty.jobscheduler;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import de.kortty.KorTTYApplication;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;
import de.kortty.model.GlobalSettings;
import de.kortty.security.EncryptionService;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class JobSchedulerAiSupport {

    private static final Gson GSON = new Gson();
    private static final Pattern SUDO_NON_INTERACTIVE_PATTERN = Pattern.compile("(?i)(^|[;&|()]\\s*)sudo\\s+-n\\s+");

    private final KorTTYApplication app;

    public JobSchedulerAiSupport(KorTTYApplication app) {
        this.app = app;
    }

    public JobExecutionOutcome runAiAgent(
        ScheduledJob job,
        ServerConnectionContext connectionContext,
        JobSchedulerRemoteSession remoteSession,
        String sudoPassword,
        JobSchedulerSecretRedactor redactor) throws Exception {

        JobAction action = job.getAction();
        AiProfile profile = findAiProfile(action.getAiProfileId());
        if (profile == null) {
            return JobExecutionOutcome.blocked("AI profile is not available.", "AI profile id: " + action.getAiProfileId());
        }
        AiPromptService aiService = createAiService(profile);
        String systemPrompt = """
            You are KorTTY JobScheduler's unattended SSH agent.
            Return JSON only with this shape:
            {"status":"done|run_commands|blocked","summary":"short","commands":[{"command":"non-interactive shell command","purpose":"why","risk":"LOW|REQUIRES_CONFIRMATION|ROOT"}]}
            Use only non-interactive commands. Do not ask questions. Do not invent server facts.
            Use sudo only when clearly needed. Prefer read-only inspection unless the job prompt requests changes.
            """;
        String userPrompt = "Server: " + connectionContext.displayName() + "\n"
            + "Working directory: " + (job.getWorkingDirectory() != null ? job.getWorkingDirectory() : "~") + "\n"
            + "Job prompt:\n" + action.getAiPrompt();
        AiExecutionResult result = executeAgentJsonPrompt(aiService, systemPrompt, userPrompt);
        if (result.usage() != null) {
            redactor.addSecret(String.valueOf(result.usage().totalTokens()));
        }
        AgentDecision decision = parseDecision(result.content());
        if ("blocked".equalsIgnoreCase(decision.status())) {
            return JobExecutionOutcome.blocked(nonBlank(decision.summary(), "AI agent blocked the job."), result.content());
        }
        if (!"run_commands".equalsIgnoreCase(decision.status())) {
            return JobExecutionOutcome.success(nonBlank(decision.summary(), "AI agent completed without commands."), null, null, result.content());
        }
        if (!action.isAiAutoApproveCommands()) {
            return JobExecutionOutcome.blocked(
                "AI agent command execution needs explicit job auto-approval.",
                result.content());
        }
        StringBuilder stdout = new StringBuilder();
        StringBuilder stderr = new StringBuilder();
        StringBuilder detail = new StringBuilder(result.content()).append("\n\n");
        int lastExit = 0;
        for (AgentCommand command : decision.commands()) {
            if (command.command() == null || command.command().isBlank()) {
                continue;
            }
            if (TerminalAgentService.isInteractiveCommand(command.command())) {
                return JobExecutionOutcome.blocked(
                    "AI agent planned an interactive command.",
                    command.command());
            }
            String normalizedCommand = TerminalAgentService.normalizeSudoForAgentExecution(command.command());
            String stdin = null;
            if (sudoPassword != null && !sudoPassword.isBlank()) {
                normalizedCommand = rewriteSudoForStoredPassword(normalizedCommand);
                stdin = sudoPassword + "\n";
            }
            String shellCommand = "sh -lc " + ShellEscaper.quote(normalizedCommand);
            JobSchedulerRemoteSession.CommandResult commandResult = remoteSession.execute(shellCommand, stdin);
            lastExit = commandResult.exitCode();
            detail.append("$ ").append(normalizedCommand).append("\n")
                .append("exit=").append(commandResult.exitCode()).append("\n");
            stdout.append(commandResult.stdout());
            stderr.append(commandResult.stderr());
            if (commandResult.exitCode() != 0) {
                return JobExecutionOutcome.failed(
                    "AI agent command failed.",
                    commandResult.exitCode(),
                    stdout.toString(),
                    stderr.toString(),
                    detail.toString());
            }
        }
        return new JobExecutionOutcome(
            JobRunStatus.SUCCESS,
            nonBlank(decision.summary(), "AI agent commands completed."),
            lastExit,
            stdout.toString(),
            stderr.toString(),
            detail.toString());
    }

    private AiProfile findAiProfile(String profileId) {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings == null || settings.getAiProfiles() == null) {
            return null;
        }
        String effectiveId = profileId != null && !profileId.isBlank() ? profileId : settings.getDefaultAiProfileId();
        if (effectiveId != null) {
            for (AiProfile profile : settings.getAiProfiles()) {
                if (profile != null && effectiveId.equals(profile.getId())) {
                    return profile;
                }
            }
        }
        return settings.getAiProfiles().stream().filter(profile -> profile != null).findFirst().orElse(null);
    }

    private AiPromptService createAiService(AiProfile profile) {
        String apiKey = decryptSecret(profile.getEncryptedApiKey(), "AI API key");
        var service = AiServiceFactory.create(
            profile,
            apiKey,
            buildInternetAccessConfiguration(profile),
            AiSkillPromptSupport.fromSettings(app.getGlobalSettingsManager().getSettings()));
        if (!(service instanceof AiPromptService promptService)) {
            throw new IllegalStateException("AI profile is not configured for prompt execution.");
        }
        return promptService;
    }

    private AiInternetAccessConfiguration buildInternetAccessConfiguration(AiProfile profile) {
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        AiInternetAccessMode mode = profile != null ? profile.getInternetAccessMode() : null;
        if (settings == null || mode == null || !mode.isEnabled()) {
            return AiInternetAccessConfiguration.disabled();
        }
        String tavilyApiKey = null;
        String brightDataApiToken = null;
        String braveSearchApiKey = null;
        String searxngUrl = null;
        String tavilyMcpServerLabel = null;
        String brightDataMcpServerLabel = null;
        String braveSearchMcpPluginId = null;
        String searxngMcpPluginId = null;
        String lmStudioToolpackMcpPluginId = null;
        switch (mode) {
            case KORTTY_TAVILY_TOOL -> tavilyApiKey = decryptSecret(settings.getEncryptedAiTavilyApiKey(), "Tavily API key");
            case LM_STUDIO_TAVILY_MCP -> {
                tavilyApiKey = decryptSecret(settings.getEncryptedAiTavilyApiKey(), "Tavily API key");
                tavilyMcpServerLabel = settings.getAiTavilyMcpServerLabel();
            }
            case BRIGHT_DATA_WEB_MCP -> {
                brightDataApiToken = decryptSecret(settings.getEncryptedAiBrightDataApiToken(), "Bright Data API token");
                brightDataMcpServerLabel = settings.getAiBrightDataMcpServerLabel();
            }
            case BRAVE_SEARCH_MCP -> {
                braveSearchApiKey = decryptSecret(settings.getEncryptedAiBraveSearchApiKey(), "Brave Search API key");
                braveSearchMcpPluginId = settings.getAiBraveSearchMcpPluginId();
            }
            case SEARXNG_MCP -> {
                searxngUrl = settings.getAiSearxngUrl();
                searxngMcpPluginId = settings.getAiSearxngMcpPluginId();
            }
            case LM_STUDIO_TOOLPACK -> lmStudioToolpackMcpPluginId = settings.getAiLmStudioToolpackMcpPluginId();
            case DISABLED -> {
            }
        }
        return new AiInternetAccessConfiguration(
            mode,
            tavilyApiKey,
            brightDataApiToken,
            braveSearchApiKey,
            searxngUrl,
            tavilyMcpServerLabel,
            brightDataMcpServerLabel,
            braveSearchMcpPluginId,
            searxngMcpPluginId,
            lmStudioToolpackMcpPluginId);
    }

    private String decryptSecret(String encryptedValue, String label) {
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        char[] masterPassword = app.getMasterPasswordManager().getMasterPassword();
        if (masterPassword == null) {
            throw new IllegalStateException(label + " cannot be decrypted because the master password is locked.");
        }
        try {
            String decrypted = new EncryptionService().decryptPassword(encryptedValue, masterPassword);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (Exception e) {
            throw new IllegalStateException(label + " could not be decrypted.", e);
        }
    }

    private AgentDecision parseDecision(String content) {
        try {
            AgentDecision decision = GSON.fromJson(content, AgentDecision.class);
            return decision != null ? decision.normalized() : AgentDecision.blocked("AI agent returned no decision.");
        } catch (JsonSyntaxException e) {
            return AgentDecision.blocked("AI agent returned invalid JSON.");
        }
    }

    static AiExecutionResult executeAgentJsonPrompt(
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

    private static boolean looksLikeUnsupportedJsonResponseFormat(String message) {
        String normalized = message != null ? message.toLowerCase(Locale.ROOT) : "";
        return normalized.contains("response_format")
            || normalized.contains("json_object")
            || normalized.contains("json mode");
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value : fallback;
    }

    private String rewriteSudoForStoredPassword(String command) {
        Matcher matcher = SUDO_NON_INTERACTIVE_PATTERN.matcher(command);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            matcher.appendReplacement(rewritten, Matcher.quoteReplacement(matcher.group(1) + "sudo -S -p '' "));
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    public record ServerConnectionContext(String displayName) {
    }

    record AgentDecision(String status, String summary, List<AgentCommand> commands) {
        static AgentDecision blocked(String summary) {
            return new AgentDecision("blocked", summary, List.of());
        }

        AgentDecision normalized() {
            return new AgentDecision(
                status != null ? status : "blocked",
                summary,
                commands != null ? commands : new ArrayList<>());
        }
    }

    record AgentCommand(String command, String purpose, String risk) {
    }
}
