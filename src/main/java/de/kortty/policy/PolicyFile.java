package de.kortty.policy;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The complete parsed and validated content of {@code kortty-policy.toml}. Immutable and free of
 * JavaFX/JAXB so it can be built and asserted in plain unit tests.
 *
 * @param schemaVersion   the declared {@code [meta] schema-version}
 * @param organization    optional organization name for "managed by your organization" hints
 * @param groups          TOML-defined groups: lowercased group name → lowercased member user names
 * @param rules           all {@code [[rule]]} tables in file order (order is not significant)
 * @param scriptHeaders   admin-provided immutable script headers
 * @param aiProfiles      admin-provided AI profile definitions
 * @param runtimeModels   admin-provisioned local AI models
 * @param teamworkSources admin-provided teamwork source definitions
 */
public record PolicyFile(
    int schemaVersion,
    String organization,
    Map<String, Set<String>> groups,
    List<PolicyRule> rules,
    List<ScriptHeader> scriptHeaders,
    List<AiProfileDef> aiProfiles,
    List<RuntimeModel> runtimeModels,
    List<TeamworkSourceDef> teamworkSources) {

    /** The only schema version this build understands. */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** Required id prefix for policy-provided AI profiles. */
    public static final String AI_PROFILE_ID_PREFIX = "policy-";

    public PolicyFile {
        groups = Map.copyOf(groups);
        rules = List.copyOf(rules);
        scriptHeaders = List.copyOf(scriptHeaders);
        aiProfiles = List.copyOf(aiProfiles);
        runtimeModels = List.copyOf(runtimeModels);
        teamworkSources = List.copyOf(teamworkSources);
    }

    /** An immutable admin-provided script header ({@code [[script-header]]}). */
    public record ScriptHeader(String name, String content) {
    }

    /**
     * An admin-preconfigured AI profile ({@code [[ai-profile]]}). The API key, when present, stays
     * in its {@code kortty-enc:v1:} envelope here; it is decrypted only at injection time and only
     * in memory.
     */
    public record AiProfileDef(
        String id, String name, String provider, String endpoint, String model, String apiKeyEncrypted) {
    }

    /** An admin-provisioned local AI model ({@code [[ai-runtime.model]]}). */
    public record RuntimeModel(String name, String runtime, String source) {
    }

    /** An admin-provided teamwork source ({@code [[teamwork-source]]}). */
    public record TeamworkSourceDef(String name, String type, String url) {
    }
}
