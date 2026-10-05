package de.kortty.policy;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.BinaryOperator;
import java.util.function.Function;

/**
 * The policy resolved for one {@link PolicyIdentity}: what this user may do. Immutable; built once
 * at startup. Pure and JavaFX-free (see {@link de.kortty.core.HostKeyCheckPolicy} for the pattern).
 *
 * <p>Resolution precedence per setting key: the most specific tier that sets the key wins —
 * <b>user &gt; group &gt; all</b> (GPO-style, so an admin can lock everything globally and relax it
 * for a group). When several rules of the winning tier set the same key, the most restrictive value
 * applies. Server restrictions combine by intersection within the winning tier: a connection must
 * pass every applicable restriction.
 */
public final class EffectivePolicy {

    private static final PolicyRule.LoggingRule EMPTY_LOGGING =
        new PolicyRule.LoggingRule(null, null, null, null, null, null);

    private static final PolicyRule.SessionJournalRule EMPTY_SESSION_JOURNAL =
        new PolicyRule.SessionJournalRule(
            null, null, null, null, null, null, null, null, null, null, null, List.of());

    /** No policy file: everything allowed, nothing managed. */
    private static final EffectivePolicy UNRESTRICTED = new EffectivePolicy(false, false, null,
        new EnumMap<>(PolicyFeature.class), AgentExecutionMode.ALLOW, false, false,
        ClipboardMode.SYSTEM, true, true, true,
        true, true, true, true, true, true, true, true, true, null, LoadIntoEditorMode.ALLOW,
        EMPTY_LOGGING, EMPTY_SESSION_JOURNAL, null, TerminalPolicy.NONE,
        List.of(), EnumSet.noneOf(ManagedSetting.class), List.of(), List.of(), List.of(), List.of(),
        SftpPolicy.NONE, WebhookHostAllowlist.NONE);

    private final boolean fromPolicyFile;
    private final boolean lockdown;
    private final String organization;
    private final Map<PolicyFeature, PolicyDecision> features;
    private final AgentExecutionMode agentExecution;
    private final boolean requireMasterPassword;
    private final boolean enforceHostKeyCheck;
    private final ClipboardMode clipboardMode;
    private final boolean allowTelemetry;
    private final boolean allowTerminalRecording;
    private final boolean allowPortForwarding;
    private final boolean allowCustomTeamworkSources;
    private final boolean allowCustomScriptHeaders;
    private final boolean aiProfileCreateAllowed;
    private final boolean aiProfileEditAllowed;
    private final boolean aiInternetAllowed;
    private final boolean allowRuntimeDownloads;
    private final boolean allowModelDownloads;
    private final boolean allowUserModels;
    private final boolean updatesEnabled;
    private final String updateFeedUrl;
    private final LoadIntoEditorMode loadIntoSnippetEditor;
    private final PolicyRule.LoggingRule logging;
    private final PolicyRule.SessionJournalRule sessionJournal;
    private final Long snippetAnalysisMaxStoredContentBytes;
    private final TerminalPolicy terminal;
    private final List<ServerRestriction> serverRestrictions;
    private final Set<ManagedSetting> managedSettings;
    private final List<PolicyFile.ScriptHeader> scriptHeaders;
    private final List<PolicyFile.AiProfileDef> aiProfiles;
    private final List<PolicyFile.RuntimeModel> runtimeModels;
    private final List<PolicyFile.TeamworkSourceDef> teamworkSources;
    private final SftpPolicy sftp;
    private final WebhookHostAllowlist webhookHostAllowlist;

    private EffectivePolicy(boolean fromPolicyFile, boolean lockdown, String organization,
                            Map<PolicyFeature, PolicyDecision> features, AgentExecutionMode agentExecution,
                            boolean requireMasterPassword, boolean enforceHostKeyCheck,
                            ClipboardMode clipboardMode,
                            boolean allowTelemetry, boolean allowTerminalRecording,
                            boolean allowPortForwarding,
                            boolean allowCustomTeamworkSources, boolean allowCustomScriptHeaders,
                            boolean aiProfileCreateAllowed, boolean aiProfileEditAllowed,
                            boolean aiInternetAllowed,
                            boolean allowRuntimeDownloads, boolean allowModelDownloads,
                            boolean allowUserModels, boolean updatesEnabled, String updateFeedUrl,
                            LoadIntoEditorMode loadIntoSnippetEditor,
                            PolicyRule.LoggingRule logging,
                            PolicyRule.SessionJournalRule sessionJournal,
                            Long snippetAnalysisMaxStoredContentBytes,
                            TerminalPolicy terminal,
                            List<ServerRestriction> serverRestrictions,
                            Set<ManagedSetting> managedSettings,
                            List<PolicyFile.ScriptHeader> scriptHeaders,
                            List<PolicyFile.AiProfileDef> aiProfiles,
                            List<PolicyFile.RuntimeModel> runtimeModels,
                            List<PolicyFile.TeamworkSourceDef> teamworkSources,
                            SftpPolicy sftp,
                            WebhookHostAllowlist webhookHostAllowlist) {
        this.fromPolicyFile = fromPolicyFile;
        this.lockdown = lockdown;
        this.organization = organization;
        this.features = features;
        this.agentExecution = agentExecution;
        this.requireMasterPassword = requireMasterPassword;
        this.enforceHostKeyCheck = enforceHostKeyCheck;
        this.clipboardMode = clipboardMode;
        this.allowTelemetry = allowTelemetry;
        this.allowTerminalRecording = allowTerminalRecording;
        this.allowPortForwarding = allowPortForwarding;
        this.allowCustomTeamworkSources = allowCustomTeamworkSources;
        this.allowCustomScriptHeaders = allowCustomScriptHeaders;
        this.aiProfileCreateAllowed = aiProfileCreateAllowed;
        this.aiProfileEditAllowed = aiProfileEditAllowed;
        this.aiInternetAllowed = aiInternetAllowed;
        this.allowRuntimeDownloads = allowRuntimeDownloads;
        this.allowModelDownloads = allowModelDownloads;
        this.allowUserModels = allowUserModels;
        this.updatesEnabled = updatesEnabled;
        this.updateFeedUrl = updateFeedUrl;
        this.loadIntoSnippetEditor = loadIntoSnippetEditor;
        this.logging = logging;
        this.sessionJournal = sessionJournal;
        this.snippetAnalysisMaxStoredContentBytes = snippetAnalysisMaxStoredContentBytes;
        this.terminal = terminal;
        this.serverRestrictions = List.copyOf(serverRestrictions);
        this.managedSettings = managedSettings;
        this.scriptHeaders = List.copyOf(scriptHeaders);
        this.aiProfiles = List.copyOf(aiProfiles);
        this.runtimeModels = List.copyOf(runtimeModels);
        this.teamworkSources = List.copyOf(teamworkSources);
        this.sftp = sftp;
        this.webhookHostAllowlist = webhookHostAllowlist;
    }

    /** No policy present: everything allowed. */
    public static EffectivePolicy unrestricted() {
        return UNRESTRICTED;
    }

    /**
     * The fail-safe applied when a policy file exists but cannot be loaded: every managed feature
     * denied, no server may be connected to. Never grants more than a valid file could.
     */
    public static EffectivePolicy lockdown() {
        EnumMap<PolicyFeature, PolicyDecision> denied = new EnumMap<>(PolicyFeature.class);
        for (PolicyFeature feature : PolicyFeature.values()) {
            denied.put(feature, PolicyDecision.DENY);
        }
        return new EffectivePolicy(true, true, null, denied, AgentExecutionMode.READ_ONLY,
            true, true, ClipboardMode.INTERNAL, false, false, false, false, false, false, false, false, false, false,
            false, false, null, LoadIntoEditorMode.DENY, EMPTY_LOGGING, EMPTY_SESSION_JOURNAL, 0L,
            TerminalPolicy.LOCKDOWN, List.of(), EnumSet.allOf(ManagedSetting.class),
            List.of(), List.of(), List.of(), List.of(), SftpPolicy.LOCKDOWN, WebhookHostAllowlist.NONE);
    }

    /** Resolves the policy file for {@code identity}. */
    public static EffectivePolicy resolve(PolicyFile file, PolicyIdentity identity) {
        if (file == null) {
            return unrestricted();
        }
        List<PolicyRule> userTier = new ArrayList<>();
        List<PolicyRule> groupTier = new ArrayList<>();
        List<PolicyRule> allTier = new ArrayList<>();
        for (PolicyRule rule : file.rules()) {
            switch (tierOf(rule, file, identity)) {
                case USER -> userTier.add(rule);
                case GROUP -> groupTier.add(rule);
                case ALL -> allTier.add(rule);
                case NONE -> { }
            }
        }
        Resolver resolver = new Resolver(userTier, groupTier, allTier);

        EnumSet<ManagedSetting> managed = EnumSet.noneOf(ManagedSetting.class);
        EnumMap<PolicyFeature, PolicyDecision> features = new EnumMap<>(PolicyFeature.class);
        for (PolicyFeature feature : PolicyFeature.values()) {
            PolicyDecision decision = resolver.resolve(
                rule -> rule.features().get(feature), PolicyDecision::mostRestrictive);
            if (decision != null) {
                features.put(feature, decision);
                managed.add(switch (feature) {
                    case AI, AI_AGENT, AI_CHAT, AI_SWARM, AI_PLANNING -> ManagedSetting.AI_FEATURES;
                    case TEAMWORK -> ManagedSetting.TEAMWORK;
                    case PLUGINS -> ManagedSetting.PLUGINS;
                    case SESSION_JOURNAL -> ManagedSetting.SESSION_JOURNAL;
                    case CONTROL_API -> ManagedSetting.CONTROL_API;
                    case TERMINAL_TRIGGERS -> ManagedSetting.TERMINAL_TRIGGERS;
                    case MULTI_EXEC -> ManagedSetting.MULTI_EXEC;
                    case FILE_TRANSFER -> ManagedSetting.FILE_TRANSFER;
                    case SFTP_SUDO_EDIT -> ManagedSetting.SFTP_SUDO_EDIT;
                    case MCP_SERVER -> ManagedSetting.MCP_SERVER;
                    case JOB_WEBHOOKS -> ManagedSetting.JOB_WEBHOOKS;
                });
            }
        }

        AgentExecutionMode agentExecution = resolver.resolve(
            PolicyRule::agentExecution, AgentExecutionMode::mostRestrictive);
        if (agentExecution != null) {
            managed.add(ManagedSetting.AGENT_EXECUTION);
            if (agentExecution != AgentExecutionMode.ALLOW) {
                managed.add(ManagedSetting.AGENT_CONFIRM_MUTATING);
            }
        }

        Boolean requireMasterPassword = resolver.resolveRequire(PolicyRule::requireMasterPassword);
        Boolean enforceHostKeyCheck = resolver.resolveRequire(PolicyRule::enforceHostKeyCheck);
        ClipboardMode clipboardMode = resolver.resolve(
            PolicyRule::clipboardMode, ClipboardMode::mostRestrictive);
        Boolean allowTelemetry = resolver.resolveAllow(PolicyRule::allowTelemetry);
        Boolean allowTerminalRecording = resolver.resolveAllow(PolicyRule::allowTerminalRecording);
        Boolean allowPortForwarding = resolver.resolveAllow(PolicyRule::allowPortForwarding);
        Boolean allowCustomTeamworkSources = resolver.resolveAllow(PolicyRule::allowCustomTeamworkSources);
        Boolean allowCustomScriptHeaders = resolver.resolveAllow(PolicyRule::allowCustomScriptHeaders);
        Boolean aiProfileAllowCreate = resolver.resolveAllow(PolicyRule::aiProfileAllowCreate);
        Boolean aiProfileAllowEdit = resolver.resolveAllow(PolicyRule::aiProfileAllowEdit);
        Boolean aiProfileAllowInternet = resolver.resolveAllow(PolicyRule::aiProfileAllowInternet);
        Boolean allowRuntimeDownloads = resolver.resolveAllow(PolicyRule::allowRuntimeDownloads);
        Boolean allowModelDownloads = resolver.resolveAllow(PolicyRule::allowModelDownloads);
        Boolean allowUserModels = resolver.resolveAllow(PolicyRule::allowUserModels);
        Boolean updatesEnabled = resolver.resolveAllow(PolicyRule::updatesEnabled);
        // The feed URL is not a restriction, so "most restrictive" has no meaning — take the value
        // from the winning tier deterministically (lexicographically smallest on same-tier conflict).
        String updateFeedUrl = resolver.resolve(PolicyRule::updateFeedUrl,
            (a, b) -> a.compareTo(b) <= 0 ? a : b);
        LoadIntoEditorMode loadIntoEditor = resolver.resolve(
            PolicyRule::loadIntoSnippetEditor, LoadIntoEditorMode::mostRestrictive);

        markManaged(managed, ManagedSetting.MASTER_PASSWORD, requireMasterPassword);
        markManaged(managed, ManagedSetting.HOST_KEY_CHECK, enforceHostKeyCheck);
        if (clipboardMode != null) {
            managed.add(ManagedSetting.CLIPBOARD);
        }
        markManaged(managed, ManagedSetting.TELEMETRY, allowTelemetry);
        markManaged(managed, ManagedSetting.TERMINAL_RECORDING, allowTerminalRecording);
        markManaged(managed, ManagedSetting.PORT_FORWARDING, allowPortForwarding);
        markManaged(managed, ManagedSetting.TEAMWORK, allowCustomTeamworkSources);
        markManaged(managed, ManagedSetting.SCRIPT_HEADERS, allowCustomScriptHeaders);
        markManaged(managed, ManagedSetting.AI_PROFILES, aiProfileAllowCreate);
        markManaged(managed, ManagedSetting.AI_PROFILES, aiProfileAllowEdit);
        markManaged(managed, ManagedSetting.AI_INTERNET, aiProfileAllowInternet);
        markManaged(managed, ManagedSetting.AI_RUNTIME, allowRuntimeDownloads);
        markManaged(managed, ManagedSetting.AI_RUNTIME, allowModelDownloads);
        markManaged(managed, ManagedSetting.AI_RUNTIME, allowUserModels);
        markManaged(managed, ManagedSetting.UPDATES, updatesEnabled);
        // The stored script text of an analysis is an upper bound: the smaller value is the more
        // restrictive one, so 0 (store nothing) beats every positive cap on a same-tier conflict.
        Long analysisMaxStoredContentBytes = resolver.resolve(
            PolicyRule::snippetAnalysisMaxStoredContentBytes, Math::min);
        if (analysisMaxStoredContentBytes != null) {
            managed.add(ManagedSetting.SNIPPET_ANALYSIS_CONTENT);
        }
        if (updateFeedUrl != null) {
            managed.add(ManagedSetting.UPDATES);
        }
        if (loadIntoEditor != null) {
            managed.add(ManagedSetting.LOAD_INTO_SNIPPET_EDITOR);
        }

        de.kortty.paste.PasteWarningMode pasteWarningFloor = resolver.resolve(
            PolicyRule::pasteWarningFloor, de.kortty.paste.PasteWarningMode::mostRestrictive);
        if (pasteWarningFloor != null) {
            managed.add(ManagedSetting.PASTE_WARNING);
        }
        Boolean allowOsc52ClipboardWrite = resolver.resolveAllow(PolicyRule::allowOsc52ClipboardWrite);
        markManaged(managed, ManagedSetting.CLIPBOARD, allowOsc52ClipboardWrite);
        de.kortty.model.SessionRestoreMode sessionRestoreMode = resolver.resolve(
            PolicyRule::sessionRestoreMode, de.kortty.model.SessionRestoreMode::leastAutomatic);
        if (sessionRestoreMode != null) {
            managed.add(ManagedSetting.SESSION_RESTORE);
        }
        // Off wins: two rules of the same tier that disagree keep terminal output off the disk.
        Boolean sessionRestoreOutput = resolver.resolveAllow(PolicyRule::sessionRestoreOutput);
        markManaged(managed, ManagedSetting.SESSION_RESTORE_OUTPUT, sessionRestoreOutput);
        TerminalPolicy terminal = new TerminalPolicy(pasteWarningFloor,
            orDefault(allowOsc52ClipboardWrite, true), sessionRestoreMode, sessionRestoreOutput);

        List<ServerRestriction> serverRestrictions = resolver.resolveServerRestrictions();
        if (!serverRestrictions.isEmpty()) {
            managed.add(ManagedSetting.SERVER_ACCESS);
        }

        PolicyRule.LoggingRule logging = resolveLogging(resolver);
        if (!logging.isEmpty()) {
            managed.add(ManagedSetting.LOGGING);
        }

        PolicyRule.SessionJournalRule sessionJournal = resolveSessionJournal(resolver);
        if (!sessionJournal.isEmpty()) {
            managed.add(ManagedSetting.SESSION_JOURNAL);
        }

        // A cap: the smaller number is the more restrictive one (the loader rejects values outside 1..8).
        Integer sftpMaxParallel = resolver.resolve(
            rule -> rule.sftp() != null ? rule.sftp().maxParallelTransfers() : null, Math::min);
        de.kortty.model.SftpConflictDefault sftpConflictDefault = resolver.resolve(
            rule -> rule.sftp() != null ? rule.sftp().conflictDefault() : null,
            de.kortty.model.SftpConflictDefault::mostRestrictive);
        SftpPolicy sftp = new SftpPolicy(sftpMaxParallel, sftpConflictDefault);
        if (sftpMaxParallel != null || sftpConflictDefault != null) {
            managed.add(ManagedSetting.SFTP_TRANSFERS);
        }

        // Every list of the winning tier applies; an empty list (any host) adds no limit of its own.
        WebhookHostAllowlist webhookHosts = resolver.resolve(
            rule -> rule.webhookHostAllowlist() != null ? WebhookHostAllowlist.of(rule.webhookHostAllowlist()) : null,
            WebhookHostAllowlist::and);
        if (webhookHosts != null) {
            managed.add(ManagedSetting.WEBHOOK_HOST_ALLOWLIST);
        }

        return new EffectivePolicy(true, false, file.organization(), features,
            orDefault(agentExecution, AgentExecutionMode.ALLOW),
            orDefault(requireMasterPassword, false), orDefault(enforceHostKeyCheck, false),
            orDefault(clipboardMode, ClipboardMode.SYSTEM),
            orDefault(allowTelemetry, true), orDefault(allowTerminalRecording, true),
            orDefault(allowPortForwarding, true),
            orDefault(allowCustomTeamworkSources, true), orDefault(allowCustomScriptHeaders, true),
            orDefault(aiProfileAllowCreate, true), orDefault(aiProfileAllowEdit, true),
            orDefault(aiProfileAllowInternet, true),
            orDefault(allowRuntimeDownloads, true), orDefault(allowModelDownloads, true),
            orDefault(allowUserModels, true), orDefault(updatesEnabled, true), updateFeedUrl,
            orDefault(loadIntoEditor, LoadIntoEditorMode.ALLOW), logging, sessionJournal,
            analysisMaxStoredContentBytes, terminal, serverRestrictions, managed,
            file.scriptHeaders(), file.aiProfiles(), file.runtimeModels(), file.teamworkSources(), sftp,
            orDefault(webhookHosts, WebhookHostAllowlist.NONE));
    }

    // ---- accessors -------------------------------------------------------------------------

    /** True when a policy file was present (valid or lockdown). */
    public boolean fromPolicyFile() {
        return fromPolicyFile;
    }

    /** True for the malformed-file fallback. */
    public boolean isLockdown() {
        return lockdown;
    }

    public Optional<String> organization() {
        return Optional.ofNullable(organization);
    }

    public boolean isManaged(ManagedSetting setting) {
        return managedSettings.contains(setting);
    }

    public boolean aiAllowed() {
        return decision(PolicyFeature.AI) != PolicyDecision.DENY;
    }

    public boolean aiAgentAllowed() {
        return aiAllowed() && decision(PolicyFeature.AI_AGENT) != PolicyDecision.DENY;
    }

    public boolean aiChatAllowed() {
        return aiAllowed() && decision(PolicyFeature.AI_CHAT) != PolicyDecision.DENY;
    }

    public boolean aiSwarmAllowed() {
        return aiAllowed() && decision(PolicyFeature.AI_SWARM) != PolicyDecision.DENY;
    }

    public boolean aiPlanningAllowed() {
        return aiAllowed() && decision(PolicyFeature.AI_PLANNING) != PolicyDecision.DENY;
    }

    public boolean teamworkAllowed() {
        return decision(PolicyFeature.TEAMWORK) != PolicyDecision.DENY;
    }

    /**
     * Whether the local control API may run at all.
     *
     * <p>Not chained through any other feature: the API is about local automation, not AI. This is
     * only the policy leg — the user's own default-off setting still has to be on, which is what
     * {@code ControlApiGate.shouldRun} combines.
     *
     * <p>Note the documented consequence of the managed-settings model: an admin who writes
     * {@code control-api = "allow"} also locks the checkbox in that position, because a policy file
     * that mentions a setting takes it over. Because this is the one default-off setting of the
     * group, locking it means {@code PolicyClamp} has to switch it <em>on</em> — every other managed
     * control is already at the value its policy chose.
     */
    public boolean controlApiAllowed() {
        return decision(PolicyFeature.CONTROL_API) != PolicyDecision.DENY;
    }

    /**
     * Whether highlight rules may act when their pattern appears in terminal output (a desktop
     * notification, running a snippet). Only the policy leg: the user's own switch ({@code GlobalSettings.terminalTriggersEnabled},
     * on by default) has to be on as well. Highlighting itself is never affected. A policy that mentions
     * the key locks the switch in the position it chose ({@link PolicyClamp}).
     */
    public boolean terminalTriggersAllowed() {
        return decision(PolicyFeature.TERMINAL_TRIGGERS) != PolicyDecision.DENY;
    }

    /**
     * Whether panes may take part in multi-exec or a tab's broadcast mode: typing into several terminals
     * at once. Denied, the include items and the broadcast switches are locked and no pane can join;
     * leaving and Stop Multi-exec keep working, so nothing can get stuck on.
     */
    public boolean multiExecAllowed() {
        return decision(PolicyFeature.MULTI_EXEC) != PolicyDecision.DENY;
    }

    /**
     * Whether files may be copied between this computer and a server (D6): the SFTP manager's
     * uploads, downloads, drops and drag-out, and the JobScheduler's SFTP upload, download and sync
     * actions and its rsync action. Allowed unless the policy denies {@code file-transfer}. Browsing
     * and remote-only operations are never gated by it. Callers ask {@link FileTransferGate}, which adds the reason.
     */
    public boolean fileTransferAllowed() {
        return decision(PolicyFeature.FILE_TRANSFER) != PolicyDecision.DENY;
    }

    /**
     * Whether the SFTP manager may edit server files as root (D16): allowed unless the policy denies
     * {@code sftp-sudo-edit}, and only while {@code file-transfer} is allowed (the edit needs a local
     * copy) and {@code load-into-snippet-editor} is {@code allow} (a root edit always writes back).
     */
    public boolean sudoEditAllowed() {
        return decision(PolicyFeature.SFTP_SUDO_EDIT) != PolicyDecision.DENY
            && fileTransferAllowed()
            && (loadIntoSnippetEditor == null || loadIntoSnippetEditor == LoadIntoEditorMode.ALLOW);
    }

    /**
     * Whether korTTY may serve MCP clients ({@code kortty-cli mcp}): allowed unless the policy denies
     * {@code mcp-server}, and only while {@code control-api} is allowed, because the MCP server is a
     * facade on the control API. Only the policy leg: the user's own default-off switch has to be on as
     * well. Unlike {@code control-api}, {@code allow} never switches the user's setting on.
     */
    public boolean mcpServerAllowed() {
        return decision(PolicyFeature.MCP_SERVER) != PolicyDecision.DENY && controlApiAllowed();
    }

    /**
     * Whether JobScheduler runs and test messages may be sent to webhooks (Slack, Teams, generic
     * JSON): allowed unless the policy denies {@code job-webhooks}. Checked on every send.
     */
    public boolean jobWebhooksAllowed() {
        return decision(PolicyFeature.JOB_WEBHOOKS) != PolicyDecision.DENY;
    }

    /**
     * The hosts webhooks may be sent to ({@code [rule.job-scheduler] webhook-host-allowlist});
     * {@link WebhookHostAllowlist#NONE} when the policy sets none.
     */
    public WebhookHostAllowlist webhookHostAllowlist() {
        return webhookHostAllowlist;
    }

    /**
     * Whether a webhook may be sent to {@code host}: the feature is allowed and the host equals an
     * allowlist entry or lies below one (label boundaries; any host when no list is set).
     */
    public boolean webhookHostAllowed(String host) {
        return jobWebhooksAllowed() && webhookHostAllowlist.allows(host);
    }

    /** The {@code [rule.sftp]} limits: parallel transfers cap and the forced conflict default. */
    public SftpPolicy sftp() {
        return sftp;
    }

    /** The admin cap on parallel SFTP transfers, or null when the policy sets none. */
    public Integer sftpMaxParallelTransfers() {
        return sftp.maxParallelTransfers();
    }

    /** The conflict default the policy sets and locks, or null when it leaves it to the user. */
    public de.kortty.model.SftpConflictDefault sftpConflictDefault() {
        return sftp.conflictDefault();
    }

    /** Session journals are NOT chained through {@link #aiAllowed()}: capture works without AI. */
    public boolean sessionJournalAllowed() {
        return decision(PolicyFeature.SESSION_JOURNAL) != PolicyDecision.DENY;
    }

    /** The AI part of the journal needs both the journal feature and AI itself. */
    public boolean sessionJournalAiSummariesAllowed() {
        return sessionJournalAllowed() && aiAllowed();
    }

    /**
     * On-demand AI over journal content: the viewer's Q&amp;A panel and the manager's
     * cross-journal AI search. Needs the journal feature and AI itself, and an admin can turn
     * just this capability off ({@code ai-ask = false}) while keeping summaries.
     */
    public boolean sessionJournalAiAskAllowed() {
        return sessionJournalAiSummariesAllowed() && !Boolean.FALSE.equals(sessionJournal.aiAsk());
    }

    /**
     * False when the admin keeps web addresses on journal pages plain text
     * ({@code clickable-links = false}); otherwise the user's setting decides.
     */
    public boolean sessionJournalClickableLinksAllowed() {
        return !Boolean.FALSE.equals(sessionJournal.clickableLinks());
    }

    /** True when the admin mandates a journal for every connection (users cannot stop it). */
    public boolean sessionJournalEnforced() {
        return sessionJournalAllowed() && Boolean.TRUE.equals(sessionJournal.enforced());
    }

    public boolean sessionJournalRenameAllowed() {
        return !Boolean.FALSE.equals(sessionJournal.allowRename());
    }

    public boolean sessionJournalDeleteAllowed() {
        return !Boolean.FALSE.equals(sessionJournal.allowDelete());
    }

    /**
     * The admin cap on rotated capture-log parts per journal, or null when no policy sets one.
     * The effective limit is the minimum of this and the connection's configured value.
     */
    public Integer sessionJournalMaxLogParts() {
        return sessionJournal.maxLogParts();
    }

    /**
     * Session journals per JobScheduler / AI Swarm run. Needs the journal feature itself; an admin
     * can turn just the automation journals off ({@code automation-allowed = false}).
     */
    public boolean automationJournalAllowed() {
        return sessionJournalAllowed() && !Boolean.FALSE.equals(sessionJournal.automationAllowed());
    }

    /** Admin cap on how long an automation journal is kept, in days; null = no cap. */
    public Integer automationJournalMaxRetentionDays() {
        return sessionJournal.automationMaxRetentionDays();
    }

    /** Admin cap on the disk space of one automation source's journals, in MB; null = no cap. */
    public Integer automationJournalMaxStorageMb() {
        return sessionJournal.automationMaxStorageMb();
    }

    /** Admin cap on the number of kept runs per automation source; null = no cap. */
    public Integer automationJournalMaxJournals() {
        return sessionJournal.automationMaxJournals();
    }

    /** The raw {@code [rule.session-journal]} mandates (fields null when not set). */
    public PolicyRule.SessionJournalRule sessionJournal() {
        return sessionJournal;
    }

    /**
     * Search-and-replace rules the organisation applies to every journal automatically. Empty
     * when the feature is denied outright — no journal is written, so nothing needs rewriting.
     */
    public List<de.kortty.model.SessionJournalReplacement> sessionJournalReplacements() {
        return sessionJournalAllowed() ? sessionJournal.replacements() : List.of();
    }

    public boolean pluginsAllowed() {
        return decision(PolicyFeature.PLUGINS) != PolicyDecision.DENY;
    }

    public AgentExecutionMode agentExecution() {
        return agentExecution;
    }

    public boolean requireMasterPassword() {
        return requireMasterPassword;
    }

    public boolean enforceHostKeyCheck() {
        return enforceHostKeyCheck;
    }

    public ClipboardMode clipboardMode() {
        return clipboardMode;
    }

    public boolean telemetryAllowed() {
        return allowTelemetry;
    }

    public boolean terminalRecordingAllowed() {
        return allowTerminalRecording;
    }

    /**
     * False forbids every SSH tunnel configured on a connection: local, remote and dynamic port
     * forwarding. Enforced where tunnels are opened ({@code SshTunnelManager.attach}), so no UI
     * path can start one; a jump server's internal hop is not a user tunnel and is unaffected.
     */
    public boolean portForwardingAllowed() {
        return allowPortForwarding;
    }

    public boolean customTeamworkSourcesAllowed() {
        return allowCustomTeamworkSources;
    }

    public boolean customScriptHeadersAllowed() {
        return allowCustomScriptHeaders;
    }

    public boolean aiProfileCreateAllowed() {
        return aiProfileCreateAllowed;
    }

    public boolean aiProfileEditAllowed() {
        return aiProfileEditAllowed;
    }

    /**
     * False forbids every AI internet-access mode. Enforced in three places, because one is not
     * enough: {@link PolicyClamp} resets each profile's stored mode, the AI Manager and Settings
     * lock the dropdown, and {@code AiServiceFactory} refuses to build an internet-enabled service
     * — so a hand-edited {@code global-settings.xml} cannot re-enable web access either.
     */
    public boolean aiInternetAllowed() {
        return aiInternetAllowed;
    }

    public boolean runtimeDownloadsAllowed() {
        return allowRuntimeDownloads;
    }

    public boolean modelDownloadsAllowed() {
        return allowModelDownloads;
    }

    public boolean userModelsAllowed() {
        return allowUserModels;
    }

    public boolean updatesEnabled() {
        return updatesEnabled;
    }

    public Optional<String> updateFeedUrl() {
        return Optional.ofNullable(updateFeedUrl);
    }

    public LoadIntoEditorMode loadIntoSnippetEditor() {
        return loadIntoSnippetEditor;
    }

    /** The terminal dimensions of the policy: paste warning floor, OSC 52, session restore and its output. */
    public TerminalPolicy terminal() {
        return terminal;
    }

    /**
     * The least a paste warning may ask, or null when the policy leaves it to the user. Applied with
     * {@link de.kortty.paste.PasteWarningMode#mostRestrictive} on top of whatever Settings, the
     * connection or a teamwork connection chose, so no level below it can apply.
     */
    public de.kortty.paste.PasteWarningMode pasteWarningFloor() {
        return terminal.pasteWarningFloor();
    }

    /**
     * False when the policy forbids programs in a terminal to put text on the clipboard with OSC 52,
     * whatever Settings → Terminal says. True leaves the choice (off by default) to the user.
     */
    public boolean osc52ClipboardWriteAllowed() {
        return terminal.osc52ClipboardWriteAllowed();
    }

    /** The startup session restore mode the policy sets and locks, or null when it leaves it to the user. */
    public de.kortty.model.SessionRestoreMode sessionRestoreMode() {
        return terminal.sessionRestoreMode();
    }

    /**
     * Whether the output of each terminal pane is restored with the session, as the policy sets and
     * locks it, or null when it leaves the switch to the user. Lockdown forces it off, since it writes
     * terminal output to disk.
     */
    public Boolean sessionRestoreOutput() {
        return terminal.sessionRestoreOutput();
    }

    /**
     * The admin's upper bound in bytes of UTF-8 for the script text stored with each Full-code
     * analysis, or {@code null} when no policy sets one; {@code 0} forbids storing script text. The
     * effective limit is {@code min(user setting, this)}, see
     * {@link de.kortty.core.SnippetAnalysisContentLimit#compute}.
     */
    public Long snippetAnalysisMaxStoredContentBytes() {
        return snippetAnalysisMaxStoredContentBytes;
    }

    /** The resolved admin log configuration; all-null fields when logging is not managed. */
    public PolicyRule.LoggingRule logging() {
        return logging;
    }

    /**
     * Whether connecting to {@code host:port} is permitted. In lockdown nothing is; without server
     * restrictions everything is; otherwise the connection must pass every applicable restriction.
     */
    public boolean isServerAllowed(String host, int port) {
        if (lockdown) {
            return false;
        }
        for (ServerRestriction restriction : serverRestrictions) {
            if (!restriction.permits(host, port)) {
                return false;
            }
        }
        return true;
    }

    public List<PolicyFile.ScriptHeader> scriptHeaders() {
        return scriptHeaders;
    }

    public List<PolicyFile.AiProfileDef> aiProfiles() {
        return aiProfiles;
    }

    public List<PolicyFile.RuntimeModel> runtimeModels() {
        return runtimeModels;
    }

    public List<PolicyFile.TeamworkSourceDef> teamworkSources() {
        return teamworkSources;
    }

    /**
     * The terminal dimensions of a policy, grouped so that a new one changes this record rather than
     * every positional constructor call of {@link EffectivePolicy}.
     *
     * @param pasteWarningFloor          the least a paste warning may ask; null = left to the user
     * @param osc52ClipboardWriteAllowed false forbids OSC 52 clipboard writes
     * @param sessionRestoreMode         the forced startup session restore mode; null = left to the user
     * @param sessionRestoreOutput       the forced "restore the output of each pane" switch; null = left
     *                                   to the user
     */
    public record TerminalPolicy(de.kortty.paste.PasteWarningMode pasteWarningFloor,
                                 boolean osc52ClipboardWriteAllowed,
                                 de.kortty.model.SessionRestoreMode sessionRestoreMode,
                                 Boolean sessionRestoreOutput) {

        /** Nothing set: the user decides everything. */
        static final TerminalPolicy NONE = new TerminalPolicy(null, true, null, null);

        /**
         * The fail-safe: every paste with a line break asks, no OSC 52 writes, nothing reopens by itself
         * and no terminal output is written to disk.
         */
        static final TerminalPolicy LOCKDOWN = new TerminalPolicy(
            de.kortty.paste.PasteWarningMode.ALWAYS, false, de.kortty.model.SessionRestoreMode.OFF, false);
    }

    /**
     * The {@code [rule.sftp]} dimensions of a policy.
     *
     * @param maxParallelTransfers the most files copied at once; null = up to the user (1..8)
     * @param conflictDefault      the forced answer when a target exists; null = up to the user
     */
    public record SftpPolicy(Integer maxParallelTransfers, de.kortty.model.SftpConflictDefault conflictDefault) {

        /** Nothing set: the user decides. */
        static final SftpPolicy NONE = new SftpPolicy(null, null);

        /** The fail-safe: one transfer at a time, and always ask. (File transfer itself is denied.) */
        static final SftpPolicy LOCKDOWN = new SftpPolicy(1, de.kortty.model.SftpConflictDefault.ASK);

        /** {@code requested} capped by {@link #maxParallelTransfers()}, when one is set. */
        public int capParallel(int requested) {
            return maxParallelTransfers == null ? requested : Math.min(requested, maxParallelTransfers);
        }
    }

    // ---- resolution internals --------------------------------------------------------------

    private PolicyDecision decision(PolicyFeature feature) {
        return features.get(feature);
    }

    private static <T> T orDefault(T value, T fallback) {
        return value != null ? value : fallback;
    }

    private static void markManaged(Set<ManagedSetting> managed, ManagedSetting setting, Boolean resolved) {
        if (resolved != null) {
            managed.add(setting);
        }
    }

    /**
     * Per-field logging resolution. Direction of "restrictive" per field: shorter retention and
     * tighter rotation caps win (a cap beats "unlimited" 0), compression on wins, JSON wins over
     * text (deterministic), directory picks the lexicographically smallest on a same-tier tie.
     */
    private static PolicyRule.LoggingRule resolveLogging(Resolver resolver) {
        String directory = resolver.resolve(
            rule -> rule.logging() != null ? rule.logging().directory() : null,
            (a, b) -> a.compareTo(b) <= 0 ? a : b);
        Integer retentionDays = resolver.resolve(
            rule -> rule.logging() != null ? rule.logging().retentionDays() : null,
            EffectivePolicy::tighterCap);
        Boolean compress = resolver.resolve(
            rule -> rule.logging() != null ? rule.logging().compress() : null,
            (a, b) -> a || b);
        LogFormat format = resolver.resolve(
            rule -> rule.logging() != null ? rule.logging().format() : null,
            (a, b) -> a.ordinal() >= b.ordinal() ? a : b);
        Integer rotationMaxFiles = resolver.resolve(
            rule -> rule.logging() != null ? rule.logging().rotationMaxFiles() : null,
            EffectivePolicy::tighterCap);
        Integer rotationTotalSizeMb = resolver.resolve(
            rule -> rule.logging() != null ? rule.logging().rotationTotalSizeMb() : null,
            EffectivePolicy::tighterCap);
        return new PolicyRule.LoggingRule(
            directory, retentionDays, compress, format, rotationMaxFiles, rotationTotalSizeMb);
    }

    /**
     * Per-field session-journal resolution. Direction of "restrictive" per field: enforced true
     * wins, allow-rename/allow-delete false wins, ai-title true wins, the AI line cap uses the
     * tighter cap (0 = context fill counts as "unlimited"), and free-form values (format, path,
     * template) pick the lexicographically smallest on a same-tier tie, like the update feed URL.
     */
    private static PolicyRule.SessionJournalRule resolveSessionJournal(Resolver resolver) {
        Boolean enforced = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().enforced() : null,
            (a, b) -> a || b);
        String logFormat = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().logFormat() : null,
            (a, b) -> a.compareTo(b) <= 0 ? a : b);
        Integer aiMaxLines = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().aiMaxLines() : null,
            EffectivePolicy::tighterCap);
        String storagePath = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().storagePath() : null,
            (a, b) -> a.compareTo(b) <= 0 ? a : b);
        Boolean allowRename = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().allowRename() : null,
            (a, b) -> a && b);
        Boolean allowDelete = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().allowDelete() : null,
            (a, b) -> a && b);
        String nameTemplate = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().nameTemplate() : null,
            (a, b) -> a.compareTo(b) <= 0 ? a : b);
        Boolean aiTitle = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().aiTitle() : null,
            (a, b) -> a || b);
        // Deliberately && (unlike aiTitle's ||): screenshots leaving the machine is the risk, so
        // when same-tier rules conflict the analysis stays off.
        Boolean aiScreenshotAnalysis = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().aiScreenshotAnalysis() : null,
            (a, b) -> a && b);
        // Journal text leaving the machine is the risk, so a same-tier conflict resolves to off.
        Boolean aiAsk = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().aiAsk() : null,
            (a, b) -> a && b);
        // The lower part cap is the more restrictive one; unlike aiMaxLines there is no
        // 0-means-unlimited sentinel (the loader rejects 0), so a plain min resolves it.
        Integer maxLogParts = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().maxLogParts() : null,
            Math::min);
        Boolean automationAllowed = resolver.resolveAllow(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().automationAllowed() : null);
        // Caps: the lower value is the more restrictive one (the loader rejects 0).
        Integer automationMaxRetentionDays = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().automationMaxRetentionDays() : null,
            Math::min);
        Integer automationMaxStorageMb = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().automationMaxStorageMb() : null,
            Math::min);
        Integer automationMaxJournals = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().automationMaxJournals() : null,
            Math::min);
        // A link is a way out of the journal page, so a same-tier conflict resolves to off.
        Boolean clickableLinks = resolver.resolve(
            rule -> rule.sessionJournal() != null ? rule.sessionJournal().clickableLinks() : null,
            (a, b) -> a && b);
        return new PolicyRule.SessionJournalRule(
            enforced, logFormat, aiMaxLines, storagePath, allowRename, allowDelete, nameTemplate,
            aiTitle, aiScreenshotAnalysis, aiAsk, maxLogParts,
            resolveSessionJournalReplacements(resolver), automationAllowed, automationMaxRetentionDays,
            automationMaxStorageMb, automationMaxJournals, clickableLinks);
    }

    /**
     * The union of every tier's replacement rules, deduplicated, in file order.
     *
     * <p>Deliberately not the usual "highest tier that says anything wins": these rules remove
     * secrets, so a user-tier rule adding one must not silence the organisation-wide list. More
     * redaction is the more restrictive outcome, and that is what a policy resolves to.</p>
     */
    private static List<de.kortty.model.SessionJournalReplacement> resolveSessionJournalReplacements(
            Resolver resolver) {
        List<de.kortty.model.SessionJournalReplacement> merged = new ArrayList<>();
        for (List<PolicyRule> tier : List.of(resolver.userTier(), resolver.groupTier(), resolver.allTier())) {
            for (PolicyRule rule : tier) {
                if (rule.sessionJournal() == null) {
                    continue;
                }
                for (de.kortty.model.SessionJournalReplacement replacement : rule.sessionJournal().replacements()) {
                    if (!merged.contains(replacement)) {
                        merged.add(replacement);
                    }
                }
            }
        }
        return List.copyOf(merged);
    }

    /** Combines caps where 0 means "unlimited": any cap beats 0, otherwise the smaller cap wins. */
    private static Integer tighterCap(Integer a, Integer b) {
        if (a == 0) {
            return b;
        }
        if (b == 0) {
            return a;
        }
        return Math.min(a, b);
    }

    private enum Tier { USER, GROUP, ALL, NONE }

    private static Tier tierOf(PolicyRule rule, PolicyFile file, PolicyIdentity identity) {
        if (rule.appliesToAll()) {
            return Tier.ALL;
        }
        String user = identity.userName();
        if (rule.users().contains(user)) {
            return Tier.USER;
        }
        for (String group : rule.groups()) {
            String normalized = group.toLowerCase(Locale.ROOT);
            Set<String> tomlMembers = file.groups().get(normalized);
            if (tomlMembers != null && tomlMembers.contains(user)) {
                return Tier.GROUP;
            }
            if (identity.osGroups().contains(normalized)) {
                return Tier.GROUP;
            }
        }
        return Tier.NONE;
    }

    /** Resolves one setting: highest tier that sets it wins; same-tier conflicts combine. */
    private record Resolver(List<PolicyRule> userTier, List<PolicyRule> groupTier, List<PolicyRule> allTier) {

        <T> T resolve(Function<PolicyRule, T> getter, BinaryOperator<T> combiner) {
            for (List<PolicyRule> tier : List.of(userTier, groupTier, allTier)) {
                T combined = null;
                for (PolicyRule rule : tier) {
                    T value = getter.apply(rule);
                    if (value != null) {
                        combined = combined == null ? value : combiner.apply(combined, value);
                    }
                }
                if (combined != null) {
                    return combined;
                }
            }
            return null;
        }

        /** For allow-flags: false is the restrictive value. */
        Boolean resolveAllow(Function<PolicyRule, Boolean> getter) {
            return resolve(getter, (a, b) -> a && b);
        }

        /** For require/enforce-flags: true is the restrictive value. */
        Boolean resolveRequire(Function<PolicyRule, Boolean> getter) {
            return resolve(getter, (a, b) -> a || b);
        }

        /** All server restrictions of the highest tier that defines any (intersection semantics). */
        List<ServerRestriction> resolveServerRestrictions() {
            for (List<PolicyRule> tier : List.of(userTier, groupTier, allTier)) {
                List<ServerRestriction> restrictions = tier.stream()
                    .map(PolicyRule::servers)
                    .filter(java.util.Objects::nonNull)
                    .toList();
                if (!restrictions.isEmpty()) {
                    return restrictions;
                }
            }
            return List.of();
        }
    }
}
