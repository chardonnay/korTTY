package de.kortty.policy;

import org.testng.annotations.AfterClass;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class PolicyLoaderTest {

    @Test
    void terminalLogContextPolicyParsesAndRejectsUnknownMode() throws IOException {
        String toml = "[meta]\nschema-version = 1\n[[rule]]\nname = \"log-context\"\n"
            + "[rule.logging]\nterminal-include-username = false\nterminal-directory-mode = \"relative\"\n";
        var result = PolicyLoader.load(write(toml));
        assertThat(result.errors()).isEmpty();
        assertThat(result.file().rules().get(0).logging().terminalIncludeUsername()).isFalse();
        assertThat(result.file().rules().get(0).logging().terminalDirectoryMode())
            .isEqualTo(de.kortty.model.TerminalLogConfig.DirectoryMode.RELATIVE);
        assertThat(PolicyLoader.load(write(toml.replace("relative", "invented"))).errors()).isNotEmpty();
    }

    @Test
    void backupRuleParsesIntervalAndDirectory() throws Exception {
        String toml = "[meta]\nschema-version = 1\n[[rule]]\nname = \"backup\"\n"
            + "[rule.backup]\nauto-interval = \"daily\"\nauto-directory = \"~/Backups/korTTY\"\n";
        var result = PolicyLoader.load(write(toml));
        assertThat(result.errors()).isEmpty();
        PolicyRule.BackupRule backup = result.file().rules().get(0).backup();
        assertThat(backup.interval()).isEqualTo(de.kortty.model.GlobalSettings.AutoBackupInterval.DAILY);
        assertThat(backup.directory()).isEqualTo("~/Backups/korTTY");

        assertThat(PolicyLoader.load(write(toml.replace("daily", "hourly"))).errors()).isNotEmpty();
        assertThat(PolicyLoader.load(write(toml.replace("~/Backups/korTTY", "Backups/korTTY"))).errors()).isNotEmpty();
        var offOnly = PolicyLoader.load(write("[meta]\nschema-version = 1\n[[rule]]\n[rule.backup]\nauto-interval = \"off\"\n"));
        assertThat(offOnly.errors()).isEmpty();
        assertThat(offOnly.file().rules().get(0).backup().interval())
            .isEqualTo(de.kortty.model.GlobalSettings.AutoBackupInterval.OFF);
        assertThat(offOnly.file().rules().get(0).backup().directory()).isNull();
    }

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
          allow-internet = false
          [rule.ai-runtime]
          allow-runtime-downloads = false
          allow-model-downloads = false
          allow-user-models = false
          [rule.updates]
          enabled = false
          [rule.terminal]
          load-into-snippet-editor = "read-only"
          [rule.logging]
          directory = "/var/log/kortty"
          retention-days = 30
          compress = true
          format = "json"
          rotation-max-files = 14
          rotation-total-size-mb = 512

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
        assertThat(baseline.aiProfileAllowInternet()).isFalse();
        assertThat(baseline.allowRuntimeDownloads()).isFalse();
        assertThat(baseline.updatesEnabled()).isFalse();
        assertThat(baseline.loadIntoSnippetEditor()).isEqualTo(LoadIntoEditorMode.READ_ONLY);
        assertThat(baseline.logging().directory()).isEqualTo("/var/log/kortty");
        assertThat(baseline.logging().retentionDays()).isEqualTo(30);
        assertThat(baseline.logging().compress()).isTrue();
        assertThat(baseline.logging().format()).isEqualTo(LogFormat.JSON);
        assertThat(baseline.logging().rotationMaxFiles()).isEqualTo(14);
        assertThat(baseline.logging().rotationTotalSizeMb()).isEqualTo(512);

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
    void parsesTheTerminalTriggersFeature() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.features]
            terminal-triggers = "deny"
            """));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.file().rules().get(0).features().get(PolicyFeature.TERMINAL_TRIGGERS))
            .isEqualTo(PolicyDecision.DENY);

        PolicyLoadResult invalid = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.features]
            terminal-triggers = "notify-only"
            """));
        assertThat(invalid.isValid()).isFalse();
        assertThat(invalid.errors().get(0)).contains("terminal-triggers must be \"allow\" or \"deny\"");
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
    void invalidLoggingValuesAreErrors() throws IOException {
        assertThat(PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.logging]
            format = "xml"
            """)).errors().get(0)).contains("format must be \"text\" or \"json\"");

        assertThat(PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.logging]
            retention-days = -1
            """)).errors().get(0)).contains("retention-days must be a non-negative integer");
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

    @Test
    void parsesAutomaticSessionJournalReplacements() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            name = "redaction"
              [rule.session-journal]
              enforced = true
                [[rule.session-journal.replace]]
                pattern = "AKIA[0-9A-Z]{16}"
                replacement = "***AWS-KEY***"
                regex = true
                label = "AWS access keys"
                [[rule.session-journal.replace]]
                pattern = "internal.acme.corp"
                ignore-case = true
            """));
        assertThat(result.errors()).isEmpty();

        List<de.kortty.model.SessionJournalReplacement> replacements =
            result.file().rules().get(0).sessionJournal().replacements();
        assertThat(replacements).hasSize(2);
        assertThat(replacements.get(0).pattern()).isEqualTo("AKIA[0-9A-Z]{16}");
        assertThat(replacements.get(0).replacement()).isEqualTo("***AWS-KEY***");
        assertThat(replacements.get(0).regex()).isTrue();
        assertThat(replacements.get(0).label()).isEqualTo("AWS access keys");
        // Omitted keys fall back: literal, case-insensitive here, masked with ***.
        assertThat(replacements.get(1).regex()).isFalse();
        assertThat(replacements.get(1).ignoreCase()).isTrue();
        assertThat(replacements.get(1).replacement()).isEqualTo("***");
    }

    @Test
    void theShippedTemplatesSessionJournalSectionLoadsWhenUncommented() throws IOException {
        // The example file is documented as a ready-to-use template, so its commented block has
        // to be valid the moment an admin strips the leading "#" — including the nested
        // [[rule.session-journal.replace]] array, which is easy to get wrong in TOML.
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.session-journal]
              enforced = true
              log-format = "json"
              ai-max-lines = 100
              storage-path = "/srv/audit/kortty-journals"
              allow-rename = false
              allow-delete = false
              name-template = "{connection} {date} {time} ({user})"
              ai-title = true
              ai-screenshot-analysis = false
              max-log-parts = 40
                [[rule.session-journal.replace]]
                pattern = "AKIA[0-9A-Z]{16}"
                replacement = "***AWS-ACCESS-KEY***"
                regex = true
                label = "AWS access keys"
                [[rule.session-journal.replace]]
                pattern = "vpn.internal.acme.corp"
                replacement = "<internal-host>"
                ignore-case = true
            """));
        assertThat(result.errors()).isEmpty();
        PolicyRule.SessionJournalRule journal = result.file().rules().get(0).sessionJournal();
        assertThat(journal.enforced()).isTrue();
        assertThat(journal.aiScreenshotAnalysis()).isFalse();
        assertThat(journal.maxLogParts()).isEqualTo(40);
        assertThat(journal.replacements()).hasSize(2);
    }

    @Test
    void parsesTheSnippetAnalysisStoredContentCap() throws IOException {
        PolicyLoadResult capped = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.snippets]
              allow-custom-script-headers = false
              analysis-max-stored-content-bytes = 2_097_152
            """));
        assertThat(capped.errors()).isEmpty();
        PolicyRule rule = capped.file().rules().get(0);
        assertThat(rule.snippetAnalysisMaxStoredContentBytes()).isEqualTo(2_097_152L);
        assertThat(rule.allowCustomScriptHeaders()).isFalse();

        PolicyLoadResult forbidden = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.snippets]
              analysis-max-stored-content-bytes = 0
            """));
        assertThat(forbidden.errors()).isEmpty();
        assertThat(forbidden.file().rules().get(0).snippetAnalysisMaxStoredContentBytes()).isEqualTo(0L);
        assertThat(forbidden.file().rules().get(0).allowCustomScriptHeaders()).isNull();
    }

    @Test
    void snippetAnalysisStoredContentCapIsNullWhenUnset() throws IOException {
        PolicyLoadResult unset = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.snippets]
              allow-custom-script-headers = true
            """));
        assertThat(unset.errors()).isEmpty();
        assertThat(unset.file().rules().get(0).snippetAnalysisMaxStoredContentBytes()).isNull();
    }

    @Test
    void invalidSnippetAnalysisStoredContentCapsAreErrors() throws IOException {
        PolicyLoadResult negative = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.snippets]
              analysis-max-stored-content-bytes = -1
            """));
        assertThat(negative.errors()).hasSize(1);
        assertThat(negative.errors().get(0)).contains("analysis-max-stored-content-bytes");

        PolicyLoadResult text = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.snippets]
              analysis-max-stored-content-bytes = "5 MB"
            """));
        assertThat(text.errors()).hasSize(1);
        assertThat(text.errors().get(0)).contains("analysis-max-stored-content-bytes");
        assertThat(text.file()).isNull();
    }

    @Test
    void maxLogPartsIsNullWhenUnsetAndRejectsZero() throws IOException {
        PolicyLoadResult unset = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.session-journal]
              enforced = true
            """));
        assertThat(unset.errors()).isEmpty();
        assertThat(unset.file().rules().get(0).sessionJournal().maxLogParts()).isNull();

        PolicyLoadResult zero = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.session-journal]
              max-log-parts = 0
            """));
        assertThat(zero.errors()).hasSize(1);
        assertThat(zero.errors().get(0)).contains("max-log-parts must be at least 1");
    }

    @Test
    void parsesTheAutomationJournalMandatesAndRejectsZeroCaps() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.session-journal]
              automation-allowed = false
              automation-max-retention-days = 30
              automation-max-storage-mb = 500
              automation-max-journals = 20
            """));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        PolicyRule.SessionJournalRule rule = result.file().rules().get(0).sessionJournal();
        assertThat(rule.automationAllowed()).isFalse();
        assertThat(rule.automationMaxRetentionDays()).isEqualTo(30);
        assertThat(rule.automationMaxStorageMb()).isEqualTo(500);
        assertThat(rule.automationMaxJournals()).isEqualTo(20);

        PolicyLoadResult zero = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.session-journal]
              automation-max-retention-days = 0
            """));
        assertThat(zero.errors()).hasSize(1);
        assertThat(zero.errors().get(0)).contains("automation-max-retention-days must be at least 1");
    }

    @Test
    void parsesTheAiScreenshotAnalysisMandateInBothDirections() throws IOException {
        PolicyLoadResult forced = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.session-journal]
              ai-screenshot-analysis = true
            """));
        assertThat(forced.errors()).isEmpty();
        assertThat(forced.file().rules().get(0).sessionJournal().aiScreenshotAnalysis()).isTrue();

        PolicyLoadResult unset = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.session-journal]
              enforced = true
            """));
        assertThat(unset.errors()).isEmpty();
        assertThat(unset.file().rules().get(0).sessionJournal().aiScreenshotAnalysis()).isNull();
    }

    @Test
    void rejectsAnInvalidReplacementPattern() throws IOException {
        // A rule that silently does nothing is worse than a policy the admin has to fix:
        // they would believe the redaction is in place.
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
            name = "broken"
              [rule.session-journal]
                [[rule.session-journal.replace]]
                pattern = "AKIA[0-9A-Z"
                regex = true
            """));
        assertThat(result.errors()).isNotEmpty();
        assertThat(result.errors().get(0)).contains("not a valid regular expression");
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

    @Test
    void parsesTheClickableLinksMandate() throws IOException {
        PolicyLoadResult off = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.session-journal]
              clickable-links = false
            """));
        assertThat(off.errors()).isEmpty();
        assertThat(off.warnings()).isEmpty();
        assertThat(off.file().rules().get(0).sessionJournal().clickableLinks()).isFalse();
    }

    /** {@code path} as an absolute folder on this platform; on Windows an absolute path needs a drive. */
    private static String absolute(String path) {
        return File.separatorChar == '\\' ? "C:" + path : path;
    }

    private static final String AI_MEMORY_RULE = """
        [meta]
        schema-version = 1

        [[rule]]
          [rule.ai-memory]
          user-memory = "read-only"
          learning = "off"
          skill-creation = false
          external-files = "allow"
          external-file-roots = ["%s", "~/Notes"]
          max-tokens = 800
          triage = "never"
          statistics = false
        """;

    @Test
    void aiMemoryRuleParsesEveryKeyAndKeepsTheSchemaVersion() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write(AI_MEMORY_RULE.formatted(absolute("/srv/shared-notes"))));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.file().schemaVersion()).isEqualTo(PolicyFile.SUPPORTED_SCHEMA_VERSION);
        assertThat(PolicyFile.SUPPORTED_SCHEMA_VERSION).isEqualTo(1);
        PolicyRule.AiMemoryRule memory = result.file().rules().get(0).aiMemory();
        assertThat(memory.userMemory()).isEqualTo(UserMemoryMode.READ_ONLY);
        assertThat(memory.learning()).isEqualTo(de.kortty.model.AiMemoryLearningMode.OFF);
        assertThat(memory.skillCreation()).isFalse();
        assertThat(memory.externalFiles()).isTrue();
        assertThat(memory.externalFileRoots()).containsExactly(absolute("/srv/shared-notes"), "~/Notes").inOrder();
        assertThat(memory.maxTokens()).isEqualTo(800);
        assertThat(memory.triage()).isEqualTo(de.kortty.model.AiAgentTriageMode.NEVER);
        assertThat(memory.statistics()).isFalse();
    }

    @Test
    void aiMemoryRuleAcceptsTheOtherValuesAndLeavesUnsetKeysUnmanaged() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1
            [[rule]]
              [rule.ai-memory]
              user-memory = "disabled"
              learning = "propose"
              external-files = "deny"
              triage = "auto"
              max-tokens = 4000
            """));
        assertThat(result.errors()).isEmpty();
        PolicyRule.AiMemoryRule memory = result.file().rules().get(0).aiMemory();
        assertThat(memory.userMemory()).isEqualTo(UserMemoryMode.DISABLED);
        assertThat(memory.learning()).isEqualTo(de.kortty.model.AiMemoryLearningMode.PROPOSE);
        assertThat(memory.externalFiles()).isFalse();
        assertThat(memory.triage()).isEqualTo(de.kortty.model.AiAgentTriageMode.AUTO);
        assertThat(memory.maxTokens()).isEqualTo(4000);
        assertThat(memory.skillCreation()).isNull();
        assertThat(memory.statistics()).isNull();
        assertThat(memory.externalFileRoots()).isNull();

        PolicyLoadResult plain = PolicyLoader.load(write("[meta]\nschema-version = 1\n[[rule]]\nname = \"plain\"\n"));
        assertThat(plain.file().rules().get(0).aiMemory()).isNull();
    }

    @Test
    void everyAiMemoryTypeEnumAndRangeErrorRejectsTheFile() throws IOException {
        List<String> broken = List.of(
            "user-memory = \"invented\"",
            "learning = \"sometimes\"",
            "external-files = \"maybe\"",
            "triage = \"sometimes\"",
            "max-tokens = 299",
            "max-tokens = 4001",
            "max-tokens = \"1500\"",
            "skill-creation = \"yes\"",
            "statistics = 1",
            "external-file-roots = \"/srv/notes\"",
            "external-file-roots = [\"relative/notes\"]",
            "external-file-roots = [\"\"]");
        for (String line : broken) {
            PolicyLoadResult result = PolicyLoader.load(write("[meta]\nschema-version = 1\n[[rule]]\n"
                + "[rule.ai-memory]\n" + line + "\n"));
            assertWithMessage(line).that(result.errors()).isNotEmpty();
            assertWithMessage(line).that(result.isValid()).isFalse();
        }
    }

    @Test
    void unknownAiMemoryKeysAreWarningsAndTheFileStaysValid() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("[meta]\nschema-version = 1\n[[rule]]\n"
            + "[rule.ai-memory]\nfuture-key = true\nstatistics = true\n"));
        assertThat(result.isValid()).isTrue();
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("future-key");
    }

    @Test
    void aNewerAiMemoryKeyDoesNotLockDownAnOlderClientWhileItsKnownKeysStillApply() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("[meta]\nschema-version = 1\n[[rule]]\n"
            + "[rule.ai-memory]\nuser-memory = \"read-only\"\nnext-year-key = \"x\"\n"));
        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("next-year-key");
        PolicyRule.AiMemoryRule memory = result.file().rules().get(0).aiMemory();
        assertThat(memory.userMemory()).isEqualTo(UserMemoryMode.READ_ONLY);
    }

    @Test
    void aTypoInTheMemoryMandateIsAWarningThatNamesTheKeyAndSetsNoMandate() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("[meta]\nschema-version = 1\n[[rule]]\n"
            + "[rule.ai-memory]\nuser-memmory = \"disabled\"\n"));
        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("user-memmory");
        PolicyRule.AiMemoryRule memory = result.file().rules().get(0).aiMemory();
        assertThat(memory == null || memory.userMemory() == null).isTrue();
    }

    @Test
    void organizationMemoryHoldsAtMostTheBlockCountTheFileDocuments() throws IOException {
        StringBuilder toml = new StringBuilder("[meta]\nschema-version = 1\n");
        for (int i = 1; i <= PolicyFile.ORGANIZATION_MEMORY_MAX_BLOCKS + 1; i++) {
            toml.append(organizationBlock("Rule " + i, "Keep rule " + i + "."));
        }
        PolicyLoadResult result = PolicyLoader.load(write(toml.toString()));
        assertThat(result.file().organizationMemory()).hasSize(PolicyFile.ORGANIZATION_MEMORY_MAX_BLOCKS);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("#" + (PolicyFile.ORGANIZATION_MEMORY_MAX_BLOCKS + 1));
    }

    @Test
    void organizationMemoryHoldsAtMostTheTotalCharacterLimit() throws IOException {
        String content = "Keep logs short. ".repeat(400);
        PolicyLoadResult result = PolicyLoader.load(write("[meta]\nschema-version = 1\n"
            + organizationBlock("A", content) + organizationBlock("B", content) + organizationBlock("C", content)));
        assertThat(result.file().organizationMemory()).hasSize(2);
        assertThat(result.warnings()).hasSize(1);
        assertThat(result.warnings().get(0)).contains("#3");
    }

    private static String organizationBlock(String title, String content) {
        return "[[organization-memory]]\ntitle = \"" + title + "\"\ncontent = \"" + content + "\"\n";
    }

    @Test
    void aiMemoryFeatureIsADecisionLikeTheOtherAiFeatures() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("[meta]\nschema-version = 1\n[[rule]]\n"
            + "[rule.features]\nai-memory = \"deny\"\n"));
        assertThat(result.errors()).isEmpty();
        assertThat(result.file().rules().get(0).features().get(PolicyFeature.AI_MEMORY))
            .isEqualTo(PolicyDecision.DENY);
        assertThat(PolicyLoader.load(write("[meta]\nschema-version = 1\n[[rule]]\n"
            + "[rule.features]\nai-memory = \"maybe\"\n")).errors()).isNotEmpty();
    }

    @Test
    void organizationMemoryBlocksParseInFileOrder() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[organization-memory]]
            title = "Change rules"
            content = "Production changes need a ticket."

            [[organization-memory]]
            title = "Data rules"
            content = "No customer data in prompts."
            """));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        assertThat(result.file().organizationMemory()).containsExactly(
            new PolicyFile.OrganizationMemoryBlock("Change rules", "Production changes need a ticket."),
            new PolicyFile.OrganizationMemoryBlock("Data rules", "No customer data in prompts.")).inOrder();
    }

    @Test
    void organizationMemoryWithASecretOrTooLongContentIsDroppedAndTheRestOfThePolicyApplies() throws IOException {
        String tooLong = "Keep logs. ".repeat(800).substring(0, PolicyFile.ORGANIZATION_MEMORY_MAX_CHARS + 1);
        String exact = "Keep logs. ".repeat(800).substring(0, PolicyFile.ORGANIZATION_MEMORY_MAX_CHARS);
        String toml = "[meta]\nschema-version = 1\norganization = \"ACME\"\n\n"
            + "[[rule]]\n[rule.features]\nai-agent = \"deny\"\n\n"
            + "[[organization-memory]]\ntitle = \"Secret\"\ncontent = \"first line\\npassword=hunter2; # db\\nlast\"\n\n"
            + "[[organization-memory]]\ntitle = \"Long\"\ncontent = \"" + tooLong + "\"\n\n"
            + "[[organization-memory]]\ntitle = \"Exact\"\ncontent = \"" + exact + "\"\n\n"
            + "[[organization-memory]]\ntitle = \"Kept\"\ncontent = \"Keep the audit log on.\"\n";

        PolicyLoadResult result = PolicyLoader.load(write(toml));

        assertThat(result.errors()).isEmpty();
        assertThat(result.isValid()).isTrue();
        assertThat(result.warnings()).hasSize(2);
        assertThat(result.warnings().get(0)).contains("SECRET_ASSIGNMENT on line 2");
        assertThat(result.warnings().get(1)).contains("8001 characters");
        assertThat(String.join("\n", result.warnings())).doesNotContain("hunter2");
        assertThat(result.file().organizationMemory().stream().map(PolicyFile.OrganizationMemoryBlock::title).toList())
            .containsExactly("Exact", "Kept").inOrder();
        assertThat(result.file().rules().get(0).features().get(PolicyFeature.AI_AGENT)).isEqualTo(PolicyDecision.DENY);
        assertThat(result.file().organization()).isEqualTo("ACME");
    }

    @Test
    void organizationMemoryTitleIsCheckedLikeTheContent() throws IOException {
        String longTitle = "Title ".repeat(2_000).substring(0, PolicyFile.ORGANIZATION_MEMORY_MAX_CHARS + 1);
        String toml = "[meta]\nschema-version = 1\norganization = \"ACME\"\n\n"
            + "[[organization-memory]]\ntitle = \"password = \\\"Hunter2-Staging-2026\\\"\"\ncontent = \"Keep the audit log on.\"\n\n"
            + "[[organization-memory]]\ntitle = \"" + longTitle + "\"\ncontent = \"Keep logs.\"\n\n"
            + "[[organization-memory]]\ntitle = \"Kept\"\ncontent = \"Keep the audit log on.\"\n";

        PolicyLoadResult result = PolicyLoader.load(write(toml));

        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).hasSize(2);
        assertThat(result.warnings().get(0)).contains("title looks like a secret");
        assertThat(result.warnings().get(1)).contains("title has 8001 characters");
        assertThat(String.join("\n", result.warnings())).doesNotContain("Hunter2");
        assertThat(result.file().organizationMemory().stream().map(PolicyFile.OrganizationMemoryBlock::title).toList())
            .containsExactly("Kept");
    }

    @Test
    void organizationMemoryTypeErrorsRejectTheFile() throws IOException {
        assertThat(PolicyLoader.load(write("[meta]\nschema-version = 1\n[[organization-memory]]\n"
            + "title = 5\ncontent = \"Rule.\"\n")).errors()).isNotEmpty();
        assertThat(PolicyLoader.load(write("[meta]\nschema-version = 1\n[[organization-memory]]\n"
            + "title = \"Only a title\"\n")).errors()).isNotEmpty();
    }

    @Test
    void theShippedTemplatesAiMemorySectionLoadsWhenUncommented() throws IOException {
        // Same contract as the session-journal template test: the commented block must be valid once uncommented.
        PolicyLoadResult result = PolicyLoader.load(write("""
            [meta]
            schema-version = 1

            [[rule]]
              [rule.ai-memory]
              user-memory = "read-only"
              learning = "off"
              skill-creation = false
              external-files = "deny"
              external-file-roots = ["%s"]
              max-tokens = 1500
              triage = "auto"
              statistics = true

            [[organization-memory]]
            title = "ACME operating rules"
            content = \"\"\"
            - Production changes go through the change ticket system; never change a production host without one.
            - Never paste customer data into an AI prompt.
            \"\"\"
            """.formatted(absolute("/srv/shared-ai-notes"))));
        assertThat(result.errors()).isEmpty();
        assertThat(result.warnings()).isEmpty();
        PolicyRule.AiMemoryRule memory = result.file().rules().get(0).aiMemory();
        assertThat(memory.maxTokens()).isEqualTo(1500);
        assertThat(memory.externalFileRoots()).containsExactly(absolute("/srv/shared-ai-notes"));
        assertThat(result.file().organizationMemory()).hasSize(1);
    }

    @Test
    void anOrganisationTextWithAZeroWidthSpaceInsideAKeywordIsCheckedWithoutIt() throws IOException {
        PolicyLoadResult result = PolicyLoader.load(write("[meta]\nschema-version = 1\n"
            + organizationBlock("Login", "Pass​wort ist Sommer2026!")));
        assertThat(result.file().organizationMemory()).isEmpty();
        assertThat(String.join("\n", result.warnings())).contains("looks like a secret");
    }
}
