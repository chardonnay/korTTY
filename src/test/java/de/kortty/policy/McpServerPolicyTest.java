package de.kortty.policy;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.GlobalSettings;
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
 * The {@code mcp-server} feature (AI-21, D14): allowed unless denied and only together with
 * {@code control-api}; a denial forces both MCP switches off, while {@code allow} never switches
 * them on.
 */
class McpServerPolicyTest {

    private static final PolicyIdentity USER = new PolicyIdentity() {
        @Override
        public String userName() {
            return "u";
        }

        @Override
        public Set<String> osGroups() {
            return Set.of();
        }
    };

    private Path dir;

    @BeforeMethod
    void createDir() throws IOException {
        dir = Files.createTempDirectory("kt-mcp-policy");
    }

    @AfterMethod(alwaysRun = true)
    void cleanup() throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static EffectivePolicy resolve(Map<PolicyFeature, PolicyDecision> features) {
        return EffectivePolicy.resolve(new PolicyFile(1, "ACME", Map.of(),
            List.of(PolicyRule.builder().features(features).build()),
            List.of(), List.of(), List.of(), List.of()), USER);
    }

    private static GlobalSettings bothOn() {
        GlobalSettings settings = new GlobalSettings();
        settings.setControlApiEnabled(true);
        settings.setMcpServerEnabled(true);
        settings.setMcpWriteToolsEnabled(true);
        return settings;
    }

    @Test
    void theTomlKeyIsMcpServer() {
        assertThat(PolicyFeature.MCP_SERVER.tomlKey()).isEqualTo("mcp-server");
        assertThat(PolicyFeature.fromTomlKey(" MCP-Server ")).isEqualTo(PolicyFeature.MCP_SERVER);
    }

    @Test
    void theLoaderReadsTheFeatureAndDenyTakesItAway() throws IOException {
        Path file = dir.resolve("kortty-policy.toml");
        Files.writeString(file, """
            [meta]
            schema-version = 1
            organization = "ACME"

            [[rule]]
              [rule.features]
              mcp-server = "deny"
            """, StandardCharsets.UTF_8);
        PolicyLoadResult result = PolicyLoader.load(file);
        assertWithMessage(String.valueOf(result.errors())).that(result.isValid()).isTrue();
        assertThat(result.warnings()).isEmpty();
        EffectivePolicy policy = EffectivePolicy.resolve(result.file(), USER);
        assertThat(policy.mcpServerAllowed()).isFalse();
        assertThat(policy.isManaged(ManagedSetting.MCP_SERVER)).isTrue();
        assertWithMessage("only the MCP facade goes; the control API itself stays")
            .that(policy.controlApiAllowed()).isTrue();
    }

    @Test
    void allowedWithoutAPolicyAndNeededTogetherWithTheControlApi() {
        assertThat(EffectivePolicy.unrestricted().mcpServerAllowed()).isTrue();
        assertThat(resolve(Map.of(PolicyFeature.MCP_SERVER, PolicyDecision.ALLOW)).mcpServerAllowed())
            .isTrue();
        assertThat(resolve(Map.of(PolicyFeature.MCP_SERVER, PolicyDecision.ALLOW,
            PolicyFeature.CONTROL_API, PolicyDecision.DENY)).mcpServerAllowed()).isFalse();
        assertThat(EffectivePolicy.lockdown().mcpServerAllowed()).isFalse();
    }

    @Test
    void aDenialForcesBothSwitchesOff() {
        GlobalSettings settings = bothOn();
        new PolicyClamp(resolve(Map.of(PolicyFeature.MCP_SERVER, PolicyDecision.DENY))).apply(settings);
        assertThat(settings.isMcpServerEnabled()).isFalse();
        assertThat(settings.isMcpWriteToolsEnabled()).isFalse();
        assertThat(settings.isControlApiEnabled()).isTrue();

        GlobalSettings viaControlApi = bothOn();
        new PolicyClamp(resolve(Map.of(PolicyFeature.CONTROL_API, PolicyDecision.DENY))).apply(viaControlApi);
        assertThat(viaControlApi.isMcpServerEnabled()).isFalse();
        assertThat(viaControlApi.isMcpWriteToolsEnabled()).isFalse();
    }

    @Test
    void anAllowLeavesTheUsersChoiceAlone() {
        GlobalSettings off = new GlobalSettings();
        new PolicyClamp(resolve(Map.of(PolicyFeature.MCP_SERVER, PolicyDecision.ALLOW))).apply(off);
        assertWithMessage("an MCP client is usually driven by a cloud model: allow never opts a user in")
            .that(off.isMcpServerEnabled()).isFalse();
        assertThat(off.isMcpWriteToolsEnabled()).isFalse();

        GlobalSettings on = bothOn();
        new PolicyClamp(resolve(Map.of(PolicyFeature.MCP_SERVER, PolicyDecision.ALLOW))).apply(on);
        assertThat(on.isMcpServerEnabled()).isTrue();
        assertThat(on.isMcpWriteToolsEnabled()).isTrue();
    }
}
