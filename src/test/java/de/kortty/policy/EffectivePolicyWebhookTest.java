package de.kortty.policy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The {@code job-webhooks} feature and {@code [rule.job-scheduler] webhook-host-allowlist} (AI-19):
 * webhooks are allowed unless denied, and an allowlist entry matches its host and the hosts below it
 * on label boundaries only.
 */
class EffectivePolicyWebhookTest {

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kt-webhook-policy");
    }

    @AfterMethod(alwaysRun = true)
    void cleanup() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static final PolicyIdentity USER = new PolicyIdentity() {
        @Override
        public String userName() {
            return "alice";
        }

        @Override
        public Set<String> osGroups() {
            return Set.of("ops");
        }
    };

    private static EffectivePolicy resolve(PolicyRule... rules) {
        return EffectivePolicy.resolve(new PolicyFile(1, "ACME", Map.of(), List.of(rules),
            List.of(), List.of(), List.of(), List.of()), USER);
    }

    private PolicyLoadResult load(String toml) throws IOException {
        Path file = dir.resolve("kortty-policy.toml");
        Files.writeString(file, "[meta]\nschema-version = 1\norganization = \"ACME\"\n\n" + toml, StandardCharsets.UTF_8);
        return PolicyLoader.load(file);
    }

    @Test
    void theTomlKeyIsJobWebhooks() {
        assertThat(PolicyFeature.JOB_WEBHOOKS.tomlKey()).isEqualTo("job-webhooks");
        assertThat(PolicyFeature.fromTomlKey(" Job-Webhooks ")).isEqualTo(PolicyFeature.JOB_WEBHOOKS);
    }

    @Test
    void defaultsAllowEveryHostAndManageNothing() {
        for (EffectivePolicy policy : List.of(EffectivePolicy.unrestricted(), resolve(PolicyRule.builder().build()))) {
            assertThat(policy.jobWebhooksAllowed()).isTrue();
            assertThat(policy.webhookHostAllowlist().restricts()).isFalse();
            assertThat(policy.webhookHostAllowlist().entries()).isEmpty();
            assertThat(policy.webhookHostAllowed("hooks.slack.com")).isTrue();
            assertThat(policy.webhookHostAllowed("anything.example")).isTrue();
            assertThat(policy.isManaged(ManagedSetting.JOB_WEBHOOKS)).isFalse();
            assertThat(policy.isManaged(ManagedSetting.WEBHOOK_HOST_ALLOWLIST)).isFalse();
        }
    }

    @Test
    void lockdownDeniesWebhooks() {
        EffectivePolicy lockdown = EffectivePolicy.lockdown();
        assertThat(lockdown.jobWebhooksAllowed()).isFalse();
        assertThat(lockdown.webhookHostAllowed("hooks.slack.com")).isFalse();
        assertThat(lockdown.isManaged(ManagedSetting.WEBHOOK_HOST_ALLOWLIST)).isTrue();
    }

    @Test
    void theLoaderReadsTheFeatureAndTheAllowlist() throws IOException {
        PolicyLoadResult result = load("""
            [[rule]]
              [rule.features]
              job-webhooks = "allow"
              [rule.job-scheduler]
              webhook-host-allowlist = ["Hooks.Slack.com", "*.webhook.office.com", ".example.org.", "hooks.slack.com"]
            """);

        assertWithMessage(String.valueOf(result.errors())).that(result.isValid()).isTrue();
        assertThat(result.warnings()).isEmpty();
        EffectivePolicy policy = EffectivePolicy.resolve(result.file(), USER);
        assertThat(policy.jobWebhooksAllowed()).isTrue();
        assertThat(policy.isManaged(ManagedSetting.JOB_WEBHOOKS)).isTrue();
        assertThat(policy.isManaged(ManagedSetting.WEBHOOK_HOST_ALLOWLIST)).isTrue();
        assertThat(policy.webhookHostAllowlist().entries())
            .containsExactly("hooks.slack.com", "webhook.office.com", "example.org").inOrder();
        assertThat(policy.webhookHostAllowed("hooks.slack.com")).isTrue();
        assertThat(policy.webhookHostAllowed("acme.webhook.office.com")).isTrue();
        assertThat(policy.webhookHostAllowed("example.org")).isTrue();
        assertThat(policy.webhookHostAllowed("example.com")).isFalse();
    }

    @Test
    void denyTakesWebhooksAway() throws IOException {
        PolicyLoadResult result = load("""
            [[rule]]
              [rule.features]
              job-webhooks = "deny"
            """);

        assertWithMessage(String.valueOf(result.errors())).that(result.isValid()).isTrue();
        EffectivePolicy policy = EffectivePolicy.resolve(result.file(), USER);
        assertThat(policy.jobWebhooksAllowed()).isFalse();
        assertThat(policy.webhookHostAllowed("hooks.slack.com")).isFalse();
        assertThat(policy.isManaged(ManagedSetting.JOB_WEBHOOKS)).isTrue();
    }

    @Test
    void anEmptyListAllowsAnyHostButIsManaged() throws IOException {
        PolicyLoadResult result = load("""
            [[rule]]
              [rule.job-scheduler]
              webhook-host-allowlist = []
            """);

        assertWithMessage(String.valueOf(result.errors())).that(result.isValid()).isTrue();
        EffectivePolicy policy = EffectivePolicy.resolve(result.file(), USER);
        assertThat(policy.webhookHostAllowed("anything.example")).isTrue();
        assertThat(policy.isManaged(ManagedSetting.WEBHOOK_HOST_ALLOWLIST)).isTrue();
    }

    @Test
    void entriesThatAreNotBareHostNamesAreErrors() throws IOException {
        for (String bad : List.of("https://hooks.slack.com", "hooks.slack.com/services", "hooks.slack.com:443",
                "user@hooks.slack.com", "hooks .slack.com", "*", "hooks.*.com", "a..b", ".")) {
            PolicyLoadResult result = load("""
                [[rule]]
                  [rule.job-scheduler]
                  webhook-host-allowlist = ["%s"]
                """.formatted(bad));
            assertWithMessage(bad).that(result.isValid()).isFalse();
            assertWithMessage(bad).that(String.join("\n", result.errors())).contains("webhook-host-allowlist");
        }
        PolicyLoadResult wrongType = load("""
            [[rule]]
              [rule.job-scheduler]
              webhook-host-allowlist = "hooks.slack.com"
            """);
        assertThat(wrongType.isValid()).isFalse();
    }

    @Test
    void unknownJobSchedulerKeysWarn() throws IOException {
        PolicyLoadResult result = load("""
            [[rule]]
              [rule.job-scheduler]
              webhook-hosts = ["hooks.slack.com"]
            """);

        assertThat(result.isValid()).isTrue();
        assertThat(String.join("\n", result.warnings())).contains("webhook-hosts");
    }

    @Test
    void matchingIsOnLabelBoundaries() {
        EffectivePolicy policy = resolve(PolicyRule.builder().webhookHostAllowlist(List.of("hooks.slack.com")).build());

        assertThat(policy.webhookHostAllowed("hooks.slack.com")).isTrue();
        assertThat(policy.webhookHostAllowed("HOOKS.SLACK.COM.")).isTrue();
        assertThat(policy.webhookHostAllowed("eu.hooks.slack.com")).isTrue();
        assertThat(policy.webhookHostAllowed("evilhooks.slack.com")).isFalse();
        assertThat(policy.webhookHostAllowed("hooks.slack.com.attacker.net")).isFalse();
        assertThat(policy.webhookHostAllowed("slack.com")).isFalse();
        assertThat(policy.webhookHostAllowed(null)).isFalse();
        assertThat(policy.webhookHostAllowed("")).isFalse();

        assertThat(WebhookHostAllowlist.matches("a.example.com", "example.com")).isTrue();
        assertThat(WebhookHostAllowlist.matches("aexample.com", "example.com")).isFalse();
        assertThat(WebhookHostAllowlist.matches("[::1]", "[::1]")).isTrue();
        assertThat(WebhookHostAllowlist.matches("example.com", "")).isFalse();
    }

    @Test
    void theMostSpecificTierWinsAndSameTierListsAllApply() {
        PolicyRule all = PolicyRule.builder().webhookHostAllowlist(List.of("hooks.slack.com")).build();
        PolicyRule ops = PolicyRule.builder().groups(Set.of("ops"))
            .webhookHostAllowlist(List.of("webhook.office.com")).build();
        EffectivePolicy groupWins = resolve(all, ops);
        assertThat(groupWins.webhookHostAllowed("acme.webhook.office.com")).isTrue();
        assertThat(groupWins.webhookHostAllowed("hooks.slack.com")).isFalse();

        PolicyRule relaxForAlice = PolicyRule.builder().users(Set.of("alice")).webhookHostAllowlist(List.of()).build();
        assertThat(resolve(all, ops, relaxForAlice).webhookHostAllowed("anything.example")).isTrue();

        PolicyRule broad = PolicyRule.builder().webhookHostAllowlist(List.of("slack.com")).build();
        PolicyRule narrow = PolicyRule.builder().webhookHostAllowlist(List.of("hooks.slack.com", "example.org")).build();
        EffectivePolicy both = resolve(broad, narrow);
        assertThat(both.webhookHostAllowed("hooks.slack.com")).isTrue();
        assertThat(both.webhookHostAllowed("files.slack.com")).isFalse();
        assertThat(both.webhookHostAllowed("example.org")).isFalse();
    }
}
