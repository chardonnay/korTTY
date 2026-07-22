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

    /** No policy file: everything allowed, nothing managed. */
    private static final EffectivePolicy UNRESTRICTED = new EffectivePolicy(false, false, null,
        new EnumMap<>(PolicyFeature.class), AgentExecutionMode.ALLOW, false, false, true, true,
        true, true, true, true, true, true, true, true, null, LoadIntoEditorMode.ALLOW,
        List.of(), EnumSet.noneOf(ManagedSetting.class), List.of(), List.of(), List.of(), List.of());

    private final boolean fromPolicyFile;
    private final boolean lockdown;
    private final String organization;
    private final Map<PolicyFeature, PolicyDecision> features;
    private final AgentExecutionMode agentExecution;
    private final boolean requireMasterPassword;
    private final boolean enforceHostKeyCheck;
    private final boolean allowTelemetry;
    private final boolean allowTerminalRecording;
    private final boolean allowCustomTeamworkSources;
    private final boolean allowCustomScriptHeaders;
    private final boolean aiProfileCreateAllowed;
    private final boolean aiProfileEditAllowed;
    private final boolean allowRuntimeDownloads;
    private final boolean allowModelDownloads;
    private final boolean allowUserModels;
    private final boolean updatesEnabled;
    private final String updateFeedUrl;
    private final LoadIntoEditorMode loadIntoSnippetEditor;
    private final List<ServerRestriction> serverRestrictions;
    private final Set<ManagedSetting> managedSettings;
    private final List<PolicyFile.ScriptHeader> scriptHeaders;
    private final List<PolicyFile.AiProfileDef> aiProfiles;
    private final List<PolicyFile.RuntimeModel> runtimeModels;
    private final List<PolicyFile.TeamworkSourceDef> teamworkSources;

    private EffectivePolicy(boolean fromPolicyFile, boolean lockdown, String organization,
                            Map<PolicyFeature, PolicyDecision> features, AgentExecutionMode agentExecution,
                            boolean requireMasterPassword, boolean enforceHostKeyCheck,
                            boolean allowTelemetry, boolean allowTerminalRecording,
                            boolean allowCustomTeamworkSources, boolean allowCustomScriptHeaders,
                            boolean aiProfileCreateAllowed, boolean aiProfileEditAllowed,
                            boolean allowRuntimeDownloads, boolean allowModelDownloads,
                            boolean allowUserModels, boolean updatesEnabled, String updateFeedUrl,
                            LoadIntoEditorMode loadIntoSnippetEditor,
                            List<ServerRestriction> serverRestrictions,
                            Set<ManagedSetting> managedSettings,
                            List<PolicyFile.ScriptHeader> scriptHeaders,
                            List<PolicyFile.AiProfileDef> aiProfiles,
                            List<PolicyFile.RuntimeModel> runtimeModels,
                            List<PolicyFile.TeamworkSourceDef> teamworkSources) {
        this.fromPolicyFile = fromPolicyFile;
        this.lockdown = lockdown;
        this.organization = organization;
        this.features = features;
        this.agentExecution = agentExecution;
        this.requireMasterPassword = requireMasterPassword;
        this.enforceHostKeyCheck = enforceHostKeyCheck;
        this.allowTelemetry = allowTelemetry;
        this.allowTerminalRecording = allowTerminalRecording;
        this.allowCustomTeamworkSources = allowCustomTeamworkSources;
        this.allowCustomScriptHeaders = allowCustomScriptHeaders;
        this.aiProfileCreateAllowed = aiProfileCreateAllowed;
        this.aiProfileEditAllowed = aiProfileEditAllowed;
        this.allowRuntimeDownloads = allowRuntimeDownloads;
        this.allowModelDownloads = allowModelDownloads;
        this.allowUserModels = allowUserModels;
        this.updatesEnabled = updatesEnabled;
        this.updateFeedUrl = updateFeedUrl;
        this.loadIntoSnippetEditor = loadIntoSnippetEditor;
        this.serverRestrictions = List.copyOf(serverRestrictions);
        this.managedSettings = managedSettings;
        this.scriptHeaders = List.copyOf(scriptHeaders);
        this.aiProfiles = List.copyOf(aiProfiles);
        this.runtimeModels = List.copyOf(runtimeModels);
        this.teamworkSources = List.copyOf(teamworkSources);
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
            true, true, false, false, false, false, false, false, false, false, false,
            false, null, LoadIntoEditorMode.DENY, List.of(), EnumSet.allOf(ManagedSetting.class),
            List.of(), List.of(), List.of(), List.of());
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
        Boolean allowTelemetry = resolver.resolveAllow(PolicyRule::allowTelemetry);
        Boolean allowTerminalRecording = resolver.resolveAllow(PolicyRule::allowTerminalRecording);
        Boolean allowCustomTeamworkSources = resolver.resolveAllow(PolicyRule::allowCustomTeamworkSources);
        Boolean allowCustomScriptHeaders = resolver.resolveAllow(PolicyRule::allowCustomScriptHeaders);
        Boolean aiProfileAllowCreate = resolver.resolveAllow(PolicyRule::aiProfileAllowCreate);
        Boolean aiProfileAllowEdit = resolver.resolveAllow(PolicyRule::aiProfileAllowEdit);
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
        markManaged(managed, ManagedSetting.TELEMETRY, allowTelemetry);
        markManaged(managed, ManagedSetting.TERMINAL_RECORDING, allowTerminalRecording);
        markManaged(managed, ManagedSetting.TEAMWORK, allowCustomTeamworkSources);
        markManaged(managed, ManagedSetting.SCRIPT_HEADERS, allowCustomScriptHeaders);
        markManaged(managed, ManagedSetting.AI_PROFILES, aiProfileAllowCreate);
        markManaged(managed, ManagedSetting.AI_PROFILES, aiProfileAllowEdit);
        markManaged(managed, ManagedSetting.AI_RUNTIME, allowRuntimeDownloads);
        markManaged(managed, ManagedSetting.AI_RUNTIME, allowModelDownloads);
        markManaged(managed, ManagedSetting.AI_RUNTIME, allowUserModels);
        markManaged(managed, ManagedSetting.UPDATES, updatesEnabled);
        if (updateFeedUrl != null) {
            managed.add(ManagedSetting.UPDATES);
        }
        if (loadIntoEditor != null) {
            managed.add(ManagedSetting.LOAD_INTO_SNIPPET_EDITOR);
        }

        List<ServerRestriction> serverRestrictions = resolver.resolveServerRestrictions();
        if (!serverRestrictions.isEmpty()) {
            managed.add(ManagedSetting.SERVER_ACCESS);
        }

        return new EffectivePolicy(true, false, file.organization(), features,
            orDefault(agentExecution, AgentExecutionMode.ALLOW),
            orDefault(requireMasterPassword, false), orDefault(enforceHostKeyCheck, false),
            orDefault(allowTelemetry, true), orDefault(allowTerminalRecording, true),
            orDefault(allowCustomTeamworkSources, true), orDefault(allowCustomScriptHeaders, true),
            orDefault(aiProfileAllowCreate, true), orDefault(aiProfileAllowEdit, true),
            orDefault(allowRuntimeDownloads, true), orDefault(allowModelDownloads, true),
            orDefault(allowUserModels, true), orDefault(updatesEnabled, true), updateFeedUrl,
            orDefault(loadIntoEditor, LoadIntoEditorMode.ALLOW), serverRestrictions, managed,
            file.scriptHeaders(), file.aiProfiles(), file.runtimeModels(), file.teamworkSources());
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

    public boolean telemetryAllowed() {
        return allowTelemetry;
    }

    public boolean terminalRecordingAllowed() {
        return allowTerminalRecording;
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
