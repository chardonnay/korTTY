package de.kortty.policy;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

class PolicyLoaderTest {

    private static final String FULL_EXAMPLE = """
        [meta]
        schema-version = 1
        organization = "ACME Corp"

        [groups]
        devs = ["Alice", "bob"]
        ops = ["carol"]

        [[rule]]
        name = "company-baseline"
          [rule.servers]
          mode = "deny"
          hosts = ["*.prod.acme.com", "10.99.0.0/16", "vault.acme.com:22", "192.168.10.42", "10.20.0.100-10.20.0.199"]
          [rule.features]
          ai = "allow"
          ai-agent = "deny"
          ai-chat = "allow"
          ai-swarm = "deny"
          ai-planning = "deny"
          teamwork = "deny"
          plugins = "deny"
          ai-agent-execution = "read-only"
          [rule.security]
          require-master-password = true
          enforce-host-key-check = true
          allow-telemetry = false
          allow-terminal-recording = false
          clipboard-mode = "internal"
          [rule.teamwork]
          allow-custom-sources = false
          [rule.snippets]
          allow-custom-script-headers = false
          [rule.ai-profiles]
          allow-create = false
          allow-edit = false
          [rule.ai-runtime]
          allow-runtime-downloads = false
          allow-model-downloads = false
          allow-user-models = false
          [rule.updates]
          enabled = false
          [rule.terminal]
          load-into-snippet-editor = "read-only"

        [[rule]]
        name = "ops-exception"
        groups = ["ops", "ACME\\\\Operations"]
        users = ["Eve"]
          [rule.features]
          ai-agent = "allow"
          ai-agent-execution = "confirm"
          [rule.updates]
          feed-url = "https://intranet.acme.com/kortty/latest.json"

        [[script-header]]
        name = "ACME Bash Header"
        content = '''
        #!/usr/bin/env bash
        set -euo pipefail
        '''

        [[ai-profile]]
        id = "policy-acme-llm"
        name = "ACME internal LLM"
        provider = "openai-compatible"
        endpoint = "https://llm.acme.internal/v1"
        model = "acme-70b"

        [[ai-runtime.model]]
        name = "acme-llama-q4"
        runtime = "llama"
        source = "https://models.acme.internal/llama-q4.gguf"

        [[teamwork-source]]
        name = "ACME shared connections"
        type = "git"
        url = "https://git.acme.internal/it/kortty-connections.git"
        """;

    private Path tempDir;

    private Path write(String content) throws IOException {
        if (tempDir == null) {
            tempDir = Files.createTempDirectory("kortty-policy-test");
        }
        Path file = Files.createTempFile(tempDir, "policy", ".toml");
        Files.writeString(file, content);
        return file;
    }

    @AfterClass
    void cleanup() throws IOException {
        if (tempDir != null) {
            try (var paths = Files.walk(tempDir)) {
                paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    @Test
    void parsesTheFullExample() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write(FULL_EXAMPLE));

        assertThat(result.errors()).isEmpty();
        assertThat(result.isValid()).isTrue();
        PolicyFile file = result.file();
        assertThat(file.schemaVersion()).isEqualTo(1);
        assertThat(file.organization()).isEqualTo("ACME Corp");
        assertThat(file.groups()).containsExactly(
            "devs", Set.of("alice", "bob"), "ops", Set.of("carol"));
        assertThat(file.rules()).hasSize(2);

        PolicyRule baseline = file.rules().get(0);
        assertThat(baseline.appliesToAll()).isTrue();
        assertThat(baseline.servers().mode()).isEqualTo(ServerRestriction.Mode.DENY);
        assertThat(baseline.servers().patterns()).hasSize(5);
        assertThat(baseline.features().get(PolicyFeature.AI_AGENT)).isEqualTo(PolicyDecision.DENY);
        assertThat(baseline.agentExecution()).isEqualTo(AgentExecutionMode.READ_ONLY);
        assertThat(baseline.requireMasterPassword()).isTrue();
        assertThat(baseline.enforceHostKeyCheck()).isTrue();
        assertThat(baseline.allowTelemetry()).isFalse();
        assertThat(baseline.allowTerminalRecording()).isFalse();
        assertThat(baseline.clipboardMode()).isEqualTo(ClipboardMode.INTERNAL);
        assertThat(baseline.allowCustomTeamworkSources()).isFalse();
        assertThat(baseline.allowCustomScriptHeaders()).isFalse();
        assertThat(baseline.aiProfileAllowCreate()).isFalse();
        assertThat(baseline.aiProfileAllowEdit()).isFalse();
        assertThat(baseline.allowRuntimeDownloads()).isFalse();
        assertThat(baseline.updatesEnabled()).isFalse();
        assertThat(baseline.loadIntoSnippetEditor()).isEqualTo(LoadIntoEditorMode.READ_ONLY);

        PolicyRule exception = file.rules().get(1);
        assertThat(exception.users()).containsExactly("eve");
        assertThat(exception.groups()).containsExactly("ops", "acme\\operations");
        assertThat(exception.agentExecution()).isEqualTo(AgentExecutionMode.CONFIRM);
        assertThat(exception.updateFeedUrl()).isEqualTo("https://intranet.acme.com/kortty/latest.json");
        assertThat(exception.updatesEnabled()).isNull();

        assertThat(file.scriptHeaders()).hasSize(1);
        assertThat(file.scriptHeaders().get(0).content()).contains("#!/usr/bin/env bash");
        assertThat(file.aiProfiles()).hasSize(1);
        assertThat(file.aiProfiles().get(0).id()).isEqualTo("policy-acme-llm");
        assertThat(file.runtimeModels()).hasSize(1);
        assertThat(file.teamworkSources()).hasSize(1);
    }

    @Test
    void reportsSyntaxErrorsWithPosition() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("[meta\nschema-version = 1\n"));
        assertThat(result.isValid()).isFalse();
        assertThat(result.file()).isNull();
        // tomlj error messages carry line/column information.
        assertThat(result.errors().get(0)).contains("line 1");
    }

    @Test
    void missingMetaOrSchemaVersionIsAnError() throws IOException {
        assertThat(PolicyLoader.load(write("[[rule]]\n")).errors())
            .contains("missing required [meta] table");
        assertThat(PolicyLoader.load(write("[meta]\norganization = \"x\"\n")).errors())
            .contains("[meta] schema-version is required");
    }

    @Test
    void unsupportedSchemaVersionIsAnError() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("[meta]\nschema-version = 2\n"));
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0)).contains("schema-version 2 is not supported");
    }

    @Test
    void unknownKeysAreWarningsNotErrors() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1
            futuristic-key = "x"

            [[rule]]
            [rule.features]
            hologram = "deny"
            """));
        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).hasSize(2);
        assertThat(result.warnings().get(0)).contains("futuristic-key");
        assertThat(result.warnings().get(1)).contains("hologram");
    }

    @Test
    void oneInvalidValueRejectsTheWholeFile() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.features]
            ai = "maybe"

            [[rule]]
            [rule.features]
            teamwork = "deny"
            """));
        assertThat(result.isValid()).isFalse();
        assertThat(result.file()).isNull();
        assertThat(result.errors().get(0)).contains("ai must be \"allow\" or \"deny\"");
    }

    @Test
    void invalidServerPatternIsAnError() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.servers]
            mode = "deny"
            hosts = ["10.0.0.0/99"]
            """));
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0)).contains("CIDR prefix out of range");
    }

    @Test
    void serverModeAndHostsAreValidated() throws IOException {
        assertThat(PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.servers]
            mode = "blocklist"
            hosts = ["a.acme.com"]
            """)).errors().get(0)).contains("mode must be \"allow\" or \"deny\"");

        assertThat(PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.servers]
            mode = "deny"
            hosts = []
            """)).errors().get(0)).contains("hosts must be a non-empty array");
    }

    @Test
    void aiProfileIdPrefixAndDuplicatesAreEnforced() throws IOException {
        PolicyLoadResult badPrefix = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[ai-profile]]
            id = "acme-llm"
            name = "x"
            provider = "anthropic"
            """));
        assertThat(badPrefix.errors().get(0)).contains("id must start with \"policy-\"");

        PolicyLoadResult duplicate = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[ai-profile]]
            id = "policy-a"
            name = "x"
            provider = "anthropic"

            [[ai-profile]]
            id = "policy-a"
            name = "y"
            provider = "anthropic"
            """));
        assertThat(duplicate.errors().get(0)).contains("duplicate id");
    }

    @Test
    void unknownProviderAndRuntimeAndTeamworkTypeAreErrors() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[ai-profile]]
            id = "policy-a"
            name = "x"
            provider = "skynet"

            [[ai-runtime.model]]
            name = "m"
            runtime = "gpt4all"
            source = "/models/m.gguf"

            [[teamwork-source]]
            name = "t"
            type = "ftp"
            url = "ftp://x"
            """));
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors()).hasSize(3);
        assertThat(result.errors().get(0)).contains("unknown provider");
        assertThat(result.errors().get(1)).contains("runtime must be one of");
        assertThat(result.errors().get(2)).contains("type must be one of");
    }

    @Test
    void encryptedApiKeyEnvelopeIsValidated() throws IOException {
        PolicyLoadResult invalid = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[ai-profile]]
            id = "policy-a"
            name = "x"
            provider = "anthropic"
            api-key-encrypted = "sk-plaintext-key"
            """));
        assertThat(invalid.errors().get(0)).contains("api-key-encrypted must be a kortty-enc:v1:");

        PolicyLoadResult valid = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[ai-profile]]
            id = "policy-a"
            name = "x"
            provider = "anthropic"
            api-key-encrypted = "%s"
            """.formatted(PolicyValueCipher.encrypt("sk-test"))));
        assertThat(valid.isValid()).isTrue();
        assertThat(valid.file().aiProfiles().get(0).apiKeyEncrypted()).startsWith("kortty-enc:v1:");
    }

    @Test
    void invalidClipboardModeIsAnError() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.security]
            clipboard-mode = "airgapped"
            """));
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0)).contains("clipboard-mode must be \"system\" or \"internal\"");
    }

    @Test
    void invalidFeedUrlIsAnError() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.updates]
            feed-url = "file:///tmp/x.json"
            """));
        assertThat(result.errors().get(0)).contains("feed-url must be an http(s) URL");
    }

    @Test
    void missingFileBecomesAnError() {
        PolicyLoadResult result = PolicyLoader.load(Path.of("/nonexistent/kortty-policy.toml"));
        assertThat(result.isValid()).isFalse();
        assertThat(result.errors().get(0)).contains("cannot read policy file");
    }

    @Test
    void loaderResultFeedsTheResolver() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write(FULL_EXAMPLE));
        PolicyFile file = result.file();

        EffectivePolicy alice = EffectivePolicy.resolve(file, TestIdentities.of("alice"));
        assertThat(alice.aiAgentAllowed()).isFalse();
        assertThat(alice.isServerAllowed("web.prod.acme.com", 22)).isFalse();
        assertThat(alice.isServerAllowed("web.dev.acme.com", 22)).isTrue();
        assertThat(alice.isServerAllowed("10.99.3.4", 22)).isFalse();
        assertThat(alice.isServerAllowed("10.20.0.150", 22)).isFalse();
        assertThat(alice.isServerAllowed("vault.acme.com", 22)).isFalse();
        assertThat(alice.isServerAllowed("vault.acme.com", 2222)).isTrue();

        EffectivePolicy carol = EffectivePolicy.resolve(file, TestIdentities.of("carol"));
        assertThat(carol.aiAgentAllowed()).isTrue();
        assertThat(carol.agentExecution()).isEqualTo(AgentExecutionMode.CONFIRM);
        assertThat(carol.updateFeedUrl()).hasValue("https://intranet.acme.com/kortty/latest.json");

        EffectivePolicy adUser = EffectivePolicy.resolve(file,
            TestIdentities.of("zoe", "acme\\operations"));
        assertThat(adUser.aiAgentAllowed()).isTrue();
    }

    private static final class TestIdentities {
        static PolicyIdentity of(String user, String... osGroups) {
            return new PolicyIdentity() {
                @Override
                public String userName() {
                    return user;
                }

                @Override
                public java.util.Set<String> osGroups() {
                    return Set.of(osGroups);
                }
            };
        }
    }
}
