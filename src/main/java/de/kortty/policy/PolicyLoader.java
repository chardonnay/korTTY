package de.kortty.policy;

import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlInvalidTypeException;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Parses and validates {@code kortty-policy.toml} into a {@link PolicyFile}. Pure (no JavaFX, no
 * app state) so every validation rule is unit-testable. Any error rejects the entire file — the
 * caller then applies {@link EffectivePolicy#lockdown()}; unknown keys are warnings only, so a
 * newer policy written for a later korTTY does not brick an older installation outright.
 */
public final class PolicyLoader {

    static final Set<String> KNOWN_PROVIDERS =
        Set.of("anthropic", "openai-compatible", "lm-studio", "embedded-llama", "embedded-mlx");
    static final Set<String> KNOWN_RUNTIMES = Set.of("llama", "mlx");
    static final Set<String> KNOWN_TEAMWORK_TYPES = Set.of("git", "shared-file");

    private static final Set<String> TOP_LEVEL_KEYS = Set.of(
        "meta", "groups", "rule", "script-header", "ai-profile", "ai-runtime", "teamwork-source");
    private static final Set<String> META_KEYS = Set.of("schema-version", "organization");
    private static final Set<String> RULE_KEYS = Set.of("name", "users", "groups", "servers",
        "features", "security", "teamwork", "snippets", "ai-profiles", "ai-runtime", "updates",
        "terminal", "logging", "session-journal");
    private static final Set<String> SERVERS_KEYS = Set.of("mode", "hosts");
    private static final Set<String> SECURITY_KEYS = Set.of("require-master-password",
        "enforce-host-key-check", "allow-telemetry", "allow-terminal-recording", "clipboard-mode");
    private static final Set<String> RULE_TEAMWORK_KEYS = Set.of("allow-custom-sources");
    private static final Set<String> SNIPPETS_KEYS = Set.of("allow-custom-script-headers");
    private static final Set<String> AI_PROFILES_KEYS = Set.of("allow-create", "allow-edit");
    private static final Set<String> RULE_AI_RUNTIME_KEYS =
        Set.of("allow-runtime-downloads", "allow-model-downloads", "allow-user-models");
    private static final Set<String> UPDATES_KEYS = Set.of("enabled", "feed-url");
    private static final Set<String> TERMINAL_KEYS = Set.of("load-into-snippet-editor");
    private static final Set<String> LOGGING_KEYS = Set.of("directory", "retention-days",
        "compress", "format", "rotation-max-files", "rotation-total-size-mb");
    private static final Set<String> SESSION_JOURNAL_KEYS = Set.of("enforced", "log-format",
        "ai-max-lines", "storage-path", "allow-rename", "allow-delete", "name-template", "ai-title");
    private static final Set<String> SESSION_JOURNAL_LOG_FORMATS = Set.of("xml", "json", "yaml");
    private static final Set<String> SCRIPT_HEADER_KEYS = Set.of("name", "content");
    private static final Set<String> AI_PROFILE_KEYS =
        Set.of("id", "name", "provider", "endpoint", "model", "api-key-encrypted");
    private static final Set<String> RUNTIME_MODEL_KEYS = Set.of("name", "runtime", "source");
    private static final Set<String> TEAMWORK_SOURCE_KEYS = Set.of("name", "type", "url");

    private final List<String> errors = new ArrayList<>();
    private final List<String> warnings = new ArrayList<>();

    private PolicyLoader() {
    }

    /** Loads and validates {@code path}. Never throws — I/O and parse failures become errors. */
    public static PolicyLoadResult load(Path path) {
        PolicyLoader loader = new PolicyLoader();
        PolicyFile file = loader.parse(path);
        return new PolicyLoadResult(loader.errors.isEmpty() ? file : null,
            loader.errors, loader.warnings, path);
    }

    private PolicyFile parse(Path path) {
        TomlParseResult toml;
        try {
            toml = Toml.parse(path);
        } catch (IOException e) {
            errors.add("cannot read policy file: " + e.getMessage());
            return null;
        }
        toml.errors().forEach(error -> errors.add(error.toString()));
        if (!errors.isEmpty()) {
            return null;
        }
        warnUnknownKeys(toml, TOP_LEVEL_KEYS, "");

        int schemaVersion = parseMeta(toml);
        String organization = null;
        TomlTable meta = getTable(toml, "meta", "");
        if (meta != null) {
            organization = getString(meta, "organization", "[meta]");
        }

        Map<String, Set<String>> groups = parseGroups(toml);
        List<PolicyRule> rules = parseRules(toml);
        List<PolicyFile.ScriptHeader> scriptHeaders = parseScriptHeaders(toml);
        List<PolicyFile.AiProfileDef> aiProfiles = parseAiProfiles(toml);
        List<PolicyFile.RuntimeModel> runtimeModels = parseRuntimeModels(toml);
        List<PolicyFile.TeamworkSourceDef> teamworkSources = parseTeamworkSources(toml);

        if (!errors.isEmpty()) {
            return null;
        }
        return new PolicyFile(schemaVersion, organization, groups, rules,
            scriptHeaders, aiProfiles, runtimeModels, teamworkSources);
    }

    private int parseMeta(TomlParseResult toml) {
        TomlTable meta = getTable(toml, "meta", "");
        if (meta == null) {
            errors.add("missing required [meta] table");
            return -1;
        }
        warnUnknownKeys(meta, META_KEYS, "[meta]");
        Long version = getLong(meta, "schema-version", "[meta]");
        if (version == null) {
            errors.add("[meta] schema-version is required");
            return -1;
        }
        if (version != PolicyFile.SUPPORTED_SCHEMA_VERSION) {
            errors.add("[meta] schema-version " + version + " is not supported by this korTTY (expected "
                + PolicyFile.SUPPORTED_SCHEMA_VERSION + ")");
            return -1;
        }
        return version.intValue();
    }

    private Map<String, Set<String>> parseGroups(TomlParseResult toml) {
        TomlTable table = getTable(toml, "groups", "");
        if (table == null) {
            return Map.of();
        }
        Map<String, Set<String>> groups = new HashMap<>();
        for (String key : table.keySet()) {
            List<String> members = getStringArray(table, key, "[groups]");
            if (members == null) {
                continue;
            }
            Set<String> normalized = new HashSet<>();
            members.forEach(member -> normalized.add(member.trim().toLowerCase(Locale.ROOT)));
            groups.put(key.trim().toLowerCase(Locale.ROOT), Set.copyOf(normalized));
        }
        return groups;
    }

    private List<PolicyRule> parseRules(TomlParseResult toml) {
        TomlArray ruleArray = getTableArray(toml, "rule", "");
        if (ruleArray == null) {
            return List.of();
        }
        List<PolicyRule> rules = new ArrayList<>();
        for (int i = 0; i < ruleArray.size(); i++) {
            TomlTable table = ruleArray.getTable(i);
            String context = "[[rule]] #" + (i + 1);
            warnUnknownKeys(table, RULE_KEYS, context);
            PolicyRule.Builder builder = PolicyRule.builder();
            builder.name(getString(table, "name", context));
            builder.users(lowercasedSet(getStringArray(table, "users", context)));
            builder.groups(lowercasedSet(getStringArray(table, "groups", context)));
            parseRuleServers(table, context, builder);
            parseRuleFeatures(table, context, builder);
            parseRuleSecurity(table, context, builder);
            parseRuleFlag(table, "teamwork", RULE_TEAMWORK_KEYS, context,
                (key, value) -> builder.allowCustomTeamworkSources(value));
            parseRuleFlag(table, "snippets", SNIPPETS_KEYS, context,
                (key, value) -> builder.allowCustomScriptHeaders(value));
            parseRuleAiProfiles(table, context, builder);
            parseRuleAiRuntime(table, context, builder);
            parseRuleUpdates(table, context, builder);
            parseRuleTerminal(table, context, builder);
            parseRuleLogging(table, context, builder);
            parseRuleSessionJournal(table, context, builder);
            rules.add(builder.build());
        }
        return rules;
    }

    private void parseRuleServers(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable servers = getTable(rule, "servers", context);
        if (servers == null) {
            return;
        }
        String serversContext = context + " [rule.servers]";
        warnUnknownKeys(servers, SERVERS_KEYS, serversContext);
        String mode = getString(servers, "mode", serversContext);
        ServerRestriction.Mode restrictionMode = switch (mode == null ? "" : mode.toLowerCase(Locale.ROOT)) {
            case "allow" -> ServerRestriction.Mode.ALLOW;
            case "deny" -> ServerRestriction.Mode.DENY;
            default -> {
                errors.add(serversContext + ": mode must be \"allow\" or \"deny\"");
                yield null;
            }
        };
        List<String> hosts = getStringArray(servers, "hosts", serversContext);
        if (hosts == null || hosts.isEmpty()) {
            errors.add(serversContext + ": hosts must be a non-empty array of patterns");
            return;
        }
        List<ServerMatcher> patterns = new ArrayList<>();
        boolean patternsValid = true;
        for (String host : hosts) {
            try {
                patterns.add(ServerMatcher.parse(host));
            } catch (IllegalArgumentException e) {
                errors.add(serversContext + ": " + e.getMessage());
                patternsValid = false;
            }
        }
        if (restrictionMode != null && patternsValid) {
            builder.servers(new ServerRestriction(restrictionMode, patterns));
        }
    }

    private void parseRuleFeatures(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable features = getTable(rule, "features", context);
        if (features == null) {
            return;
        }
        String featuresContext = context + " [rule.features]";
        Map<PolicyFeature, PolicyDecision> decisions = new EnumMap<>(PolicyFeature.class);
        for (String key : features.keySet()) {
            String value = getString(features, key, featuresContext);
            if ("ai-agent-execution".equals(key)) {
                AgentExecutionMode mode = AgentExecutionMode.fromToml(value);
                if (mode == null) {
                    errors.add(featuresContext
                        + ": ai-agent-execution must be \"allow\", \"confirm\" or \"read-only\"");
                } else {
                    builder.agentExecution(mode);
                }
                continue;
            }
            PolicyFeature feature = PolicyFeature.fromTomlKey(key);
            if (feature == null) {
                warnings.add(featuresContext + ": unknown feature \"" + key + "\" (ignored)");
                continue;
            }
            PolicyDecision decision = PolicyDecision.fromToml(value);
            if (decision == null) {
                errors.add(featuresContext + ": " + key + " must be \"allow\" or \"deny\"");
            } else {
                decisions.put(feature, decision);
            }
        }
        builder.features(decisions);
    }

    private void parseRuleSecurity(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable security = getTable(rule, "security", context);
        if (security == null) {
            return;
        }
        String securityContext = context + " [rule.security]";
        warnUnknownKeys(security, SECURITY_KEYS, securityContext);
        builder.requireMasterPassword(getBoolean(security, "require-master-password", securityContext));
        builder.enforceHostKeyCheck(getBoolean(security, "enforce-host-key-check", securityContext));
        builder.allowTelemetry(getBoolean(security, "allow-telemetry", securityContext));
        builder.allowTerminalRecording(getBoolean(security, "allow-terminal-recording", securityContext));
        String clipboardMode = getString(security, "clipboard-mode", securityContext);
        if (clipboardMode != null) {
            ClipboardMode mode = ClipboardMode.fromToml(clipboardMode);
            if (mode == null) {
                errors.add(securityContext + ": clipboard-mode must be \"system\" or \"internal\"");
            } else {
                builder.clipboardMode(mode);
            }
        }
    }

    private void parseRuleFlag(TomlTable rule, String tableKey, Set<String> knownKeys, String context,
                               java.util.function.BiConsumer<String, Boolean> sink) {
        TomlTable table = getTable(rule, tableKey, context);
        if (table == null) {
            return;
        }
        String tableContext = context + " [rule." + tableKey + "]";
        warnUnknownKeys(table, knownKeys, tableContext);
        for (String key : knownKeys) {
            Boolean value = getBoolean(table, key, tableContext);
            if (value != null) {
                sink.accept(key, value);
            }
        }
    }

    private void parseRuleAiProfiles(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable table = getTable(rule, "ai-profiles", context);
        if (table == null) {
            return;
        }
        String tableContext = context + " [rule.ai-profiles]";
        warnUnknownKeys(table, AI_PROFILES_KEYS, tableContext);
        builder.aiProfileAllowCreate(getBoolean(table, "allow-create", tableContext));
        builder.aiProfileAllowEdit(getBoolean(table, "allow-edit", tableContext));
    }

    private void parseRuleAiRuntime(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable table = getTable(rule, "ai-runtime", context);
        if (table == null) {
            return;
        }
        String tableContext = context + " [rule.ai-runtime]";
        warnUnknownKeys(table, RULE_AI_RUNTIME_KEYS, tableContext);
        builder.allowRuntimeDownloads(getBoolean(table, "allow-runtime-downloads", tableContext));
        builder.allowModelDownloads(getBoolean(table, "allow-model-downloads", tableContext));
        builder.allowUserModels(getBoolean(table, "allow-user-models", tableContext));
    }

    private void parseRuleUpdates(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable table = getTable(rule, "updates", context);
        if (table == null) {
            return;
        }
        String tableContext = context + " [rule.updates]";
        warnUnknownKeys(table, UPDATES_KEYS, tableContext);
        builder.updatesEnabled(getBoolean(table, "enabled", tableContext));
        String feedUrl = getString(table, "feed-url", tableContext);
        if (feedUrl != null) {
            if (!feedUrl.startsWith("https://") && !feedUrl.startsWith("http://")) {
                errors.add(tableContext + ": feed-url must be an http(s) URL");
            } else {
                builder.updateFeedUrl(feedUrl);
            }
        }
    }

    private void parseRuleTerminal(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable table = getTable(rule, "terminal", context);
        if (table == null) {
            return;
        }
        String tableContext = context + " [rule.terminal]";
        warnUnknownKeys(table, TERMINAL_KEYS, tableContext);
        String value = getString(table, "load-into-snippet-editor", tableContext);
        if (value != null) {
            LoadIntoEditorMode mode = LoadIntoEditorMode.fromToml(value);
            if (mode == null) {
                errors.add(tableContext
                    + ": load-into-snippet-editor must be \"allow\", \"read-only\" or \"deny\"");
            } else {
                builder.loadIntoSnippetEditor(mode);
            }
        }
    }

    private void parseRuleLogging(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable table = getTable(rule, "logging", context);
        if (table == null) {
            return;
        }
        String tableContext = context + " [rule.logging]";
        warnUnknownKeys(table, LOGGING_KEYS, tableContext);
        String directory = getString(table, "directory", tableContext);
        Integer retentionDays = getNonNegativeInt(table, "retention-days", tableContext);
        Boolean compress = getBoolean(table, "compress", tableContext);
        LogFormat format = null;
        String formatValue = getString(table, "format", tableContext);
        if (formatValue != null) {
            format = LogFormat.fromToml(formatValue);
            if (format == null) {
                errors.add(tableContext + ": format must be \"text\" or \"json\"");
            }
        }
        Integer rotationMaxFiles = getNonNegativeInt(table, "rotation-max-files", tableContext);
        Integer rotationTotalSizeMb = getNonNegativeInt(table, "rotation-total-size-mb", tableContext);
        PolicyRule.LoggingRule logging = new PolicyRule.LoggingRule(
            directory, retentionDays, compress, format, rotationMaxFiles, rotationTotalSizeMb);
        if (!logging.isEmpty()) {
            builder.logging(logging);
        }
    }

    private void parseRuleSessionJournal(TomlTable rule, String context, PolicyRule.Builder builder) {
        TomlTable table = getTable(rule, "session-journal", context);
        if (table == null) {
            return;
        }
        String tableContext = context + " [rule.session-journal]";
        warnUnknownKeys(table, SESSION_JOURNAL_KEYS, tableContext);
        Boolean enforced = getBoolean(table, "enforced", tableContext);
        String logFormat = getString(table, "log-format", tableContext);
        if (logFormat != null) {
            logFormat = logFormat.trim().toLowerCase(java.util.Locale.ROOT);
            if (!SESSION_JOURNAL_LOG_FORMATS.contains(logFormat)) {
                errors.add(tableContext + ": log-format must be \"xml\", \"json\" or \"yaml\"");
                logFormat = null;
            }
        }
        Integer aiMaxLines = getNonNegativeInt(table, "ai-max-lines", tableContext);
        String storagePath = getString(table, "storage-path", tableContext);
        Boolean allowRename = getBoolean(table, "allow-rename", tableContext);
        Boolean allowDelete = getBoolean(table, "allow-delete", tableContext);
        String nameTemplate = getString(table, "name-template", tableContext);
        Boolean aiTitle = getBoolean(table, "ai-title", tableContext);
        PolicyRule.SessionJournalRule sessionJournal = new PolicyRule.SessionJournalRule(
            enforced, logFormat, aiMaxLines, storagePath, allowRename, allowDelete, nameTemplate, aiTitle);
        if (!sessionJournal.isEmpty()) {
            builder.sessionJournal(sessionJournal);
        }
    }

    private Integer getNonNegativeInt(TomlTable table, String key, String context) {
        Long value = getLong(table, key, context);
        if (value == null) {
            return null;
        }
        if (value < 0 || value > Integer.MAX_VALUE) {
            errors.add(prefix(context) + key + " must be a non-negative integer");
            return null;
        }
        return value.intValue();
    }

    private List<PolicyFile.ScriptHeader> parseScriptHeaders(TomlParseResult toml) {
        TomlArray array = getTableArray(toml, "script-header", "");
        if (array == null) {
            return List.of();
        }
        List<PolicyFile.ScriptHeader> headers = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            TomlTable table = array.getTable(i);
            String context = "[[script-header]] #" + (i + 1);
            warnUnknownKeys(table, SCRIPT_HEADER_KEYS, context);
            String name = requireString(table, "name", context);
            String content = requireString(table, "content", context);
            if (name != null && content != null) {
                headers.add(new PolicyFile.ScriptHeader(name, content));
            }
        }
        return headers;
    }

    private List<PolicyFile.AiProfileDef> parseAiProfiles(TomlParseResult toml) {
        TomlArray array = getTableArray(toml, "ai-profile", "");
        if (array == null) {
            return List.of();
        }
        List<PolicyFile.AiProfileDef> profiles = new ArrayList<>();
        Set<String> seenIds = new HashSet<>();
        for (int i = 0; i < array.size(); i++) {
            TomlTable table = array.getTable(i);
            String context = "[[ai-profile]] #" + (i + 1);
            warnUnknownKeys(table, AI_PROFILE_KEYS, context);
            String id = requireString(table, "id", context);
            String name = requireString(table, "name", context);
            String provider = requireString(table, "provider", context);
            String endpoint = getString(table, "endpoint", context);
            String model = getString(table, "model", context);
            String apiKeyEncrypted = getString(table, "api-key-encrypted", context);
            if (id != null && !id.startsWith(PolicyFile.AI_PROFILE_ID_PREFIX)) {
                errors.add(context + ": id must start with \"" + PolicyFile.AI_PROFILE_ID_PREFIX + "\"");
            }
            if (id != null && !seenIds.add(id)) {
                errors.add(context + ": duplicate id \"" + id + "\"");
            }
            if (provider != null && !KNOWN_PROVIDERS.contains(provider.toLowerCase(Locale.ROOT))) {
                errors.add(context + ": unknown provider \"" + provider + "\" (known: "
                    + String.join(", ", KNOWN_PROVIDERS.stream().sorted().toList()) + ")");
            }
            if (apiKeyEncrypted != null && !PolicyValueCipher.isEncryptedValue(apiKeyEncrypted)) {
                errors.add(context + ": api-key-encrypted must be a " + PolicyValueCipher.PREFIX
                    + " value (create it with: korTTY --encrypt-policy-value)");
            }
            if (id != null && name != null && provider != null) {
                profiles.add(new PolicyFile.AiProfileDef(id, name,
                    provider.toLowerCase(Locale.ROOT), endpoint, model, apiKeyEncrypted));
            }
        }
        return profiles;
    }

    private List<PolicyFile.RuntimeModel> parseRuntimeModels(TomlParseResult toml) {
        TomlTable aiRuntime = getTable(toml, "ai-runtime", "");
        if (aiRuntime == null) {
            return List.of();
        }
        warnUnknownKeys(aiRuntime, Set.of("model"), "[ai-runtime]");
        TomlArray array = getTableArray(aiRuntime, "model", "[ai-runtime]");
        if (array == null) {
            return List.of();
        }
        List<PolicyFile.RuntimeModel> models = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            TomlTable table = array.getTable(i);
            String context = "[[ai-runtime.model]] #" + (i + 1);
            warnUnknownKeys(table, RUNTIME_MODEL_KEYS, context);
            String name = requireString(table, "name", context);
            String runtime = requireString(table, "runtime", context);
            String source = requireString(table, "source", context);
            if (runtime != null && !KNOWN_RUNTIMES.contains(runtime.toLowerCase(Locale.ROOT))) {
                errors.add(context + ": runtime must be one of "
                    + String.join(", ", KNOWN_RUNTIMES.stream().sorted().toList()));
            }
            if (name != null && runtime != null && source != null) {
                models.add(new PolicyFile.RuntimeModel(name, runtime.toLowerCase(Locale.ROOT), source));
            }
        }
        return models;
    }

    private List<PolicyFile.TeamworkSourceDef> parseTeamworkSources(TomlParseResult toml) {
        TomlArray array = getTableArray(toml, "teamwork-source", "");
        if (array == null) {
            return List.of();
        }
        List<PolicyFile.TeamworkSourceDef> sources = new ArrayList<>();
        for (int i = 0; i < array.size(); i++) {
            TomlTable table = array.getTable(i);
            String context = "[[teamwork-source]] #" + (i + 1);
            warnUnknownKeys(table, TEAMWORK_SOURCE_KEYS, context);
            String name = requireString(table, "name", context);
            String type = requireString(table, "type", context);
            String url = requireString(table, "url", context);
            if (type != null && !KNOWN_TEAMWORK_TYPES.contains(type.toLowerCase(Locale.ROOT))) {
                errors.add(context + ": type must be one of "
                    + String.join(", ", KNOWN_TEAMWORK_TYPES.stream().sorted().toList()));
            }
            if (name != null && type != null && url != null) {
                sources.add(new PolicyFile.TeamworkSourceDef(name, type.toLowerCase(Locale.ROOT), url));
            }
        }
        return sources;
    }

    // ---- typed getters that convert tomlj type mismatches into admin-readable errors ----------

    private TomlTable getTable(TomlTable parent, String key, String context) {
        try {
            return parent.getTable(key);
        } catch (TomlInvalidTypeException e) {
            errors.add(prefix(context) + key + " must be a table");
            return null;
        }
    }

    private TomlArray getTableArray(TomlTable parent, String key, String context) {
        try {
            TomlArray array = parent.getArray(key);
            if (array == null) {
                return null;
            }
            for (int i = 0; i < array.size(); i++) {
                if (!(array.get(i) instanceof TomlTable)) {
                    errors.add(prefix(context) + key + " must be an array of tables ([[" + key + "]])");
                    return null;
                }
            }
            return array;
        } catch (TomlInvalidTypeException e) {
            errors.add(prefix(context) + key + " must be an array of tables ([[" + key + "]])");
            return null;
        }
    }

    private String getString(TomlTable table, String key, String context) {
        try {
            String value = table.getString(key);
            return value == null || value.isBlank() ? null : value.trim();
        } catch (TomlInvalidTypeException e) {
            errors.add(prefix(context) + key + " must be a string");
            return null;
        }
    }

    private String requireString(TomlTable table, String key, String context) {
        String value = getString(table, key, context);
        if (value == null && !table.contains(key)) {
            errors.add(prefix(context) + key + " is required");
        } else if (value == null) {
            errors.add(prefix(context) + key + " must be a non-empty string");
        }
        return value;
    }

    private Boolean getBoolean(TomlTable table, String key, String context) {
        try {
            return table.getBoolean(key);
        } catch (TomlInvalidTypeException e) {
            errors.add(prefix(context) + key + " must be true or false");
            return null;
        }
    }

    private Long getLong(TomlTable table, String key, String context) {
        try {
            return table.getLong(key);
        } catch (TomlInvalidTypeException e) {
            errors.add(prefix(context) + key + " must be an integer");
            return null;
        }
    }

    private List<String> getStringArray(TomlTable table, String key, String context) {
        try {
            TomlArray array = table.getArray(key);
            if (array == null) {
                return null;
            }
            List<String> values = new ArrayList<>();
            for (int i = 0; i < array.size(); i++) {
                Object element = array.get(i);
                if (!(element instanceof String s) || s.isBlank()) {
                    errors.add(prefix(context) + key + " must be an array of non-empty strings");
                    return null;
                }
                values.add(s.trim());
            }
            return values;
        } catch (TomlInvalidTypeException e) {
            errors.add(prefix(context) + key + " must be an array of strings");
            return null;
        }
    }

    private void warnUnknownKeys(TomlTable table, Set<String> known, String context) {
        for (String key : table.keySet()) {
            if (!known.contains(key)) {
                warnings.add(prefix(context) + "unknown key \"" + key + "\" (ignored)");
            }
        }
    }

    private static Set<String> lowercasedSet(List<String> values) {
        if (values == null) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        values.forEach(value -> normalized.add(value.toLowerCase(Locale.ROOT)));
        return normalized;
    }

    private static String prefix(String context) {
        return context.isEmpty() ? "" : context + ": ";
    }
}
