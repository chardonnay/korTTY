package de.kortty.policy;

import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.mlx.MlxModelRegistry;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

class PolicyRuntimeProvisionerTest {

    private Path configDir;

    @BeforeMethod
    void createConfigDir() throws IOException {
        configDir = Files.createTempDirectory("kortty-provisioner-test");
    }

    @AfterMethod
    void cleanup() throws IOException {
        try (var paths = Files.walk(configDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private Path llmDir() {
        return configDir.resolve("llm");
    }

    @Test
    void slugLowercasesAndReplacesUnsafeCharacters() {
        assertThat(PolicyRuntimeProvisioner.slug("ACME Llama Q4")).isEqualTo("acme-llama-q4");
        assertThat(PolicyRuntimeProvisioner.slug("model/with:weird*chars"))
            .isEqualTo("model-with-weird-chars");
        // Already-safe characters are preserved.
        assertThat(PolicyRuntimeProvisioner.slug("llama-3.3_70b")).isEqualTo("llama-3.3_70b");
    }

    @Test
    void slugTrimsLeadingAndTrailingSeparators() {
        assertThat(PolicyRuntimeProvisioner.slug("  spaced  ")).isEqualTo("spaced");
        assertThat(PolicyRuntimeProvisioner.slug("***")).isEqualTo("model");
        assertThat(PolicyRuntimeProvisioner.slug("")).isEqualTo("model");
    }

    @Test
    void provisionedModelIdsCarryThePolicyPrefix() {
        // The prefix is what AiServiceFactory keys on for allow-user-models = false.
        assertThat(PolicyRuntimeProvisioner.POLICY_MODEL_ID_PREFIX).isEqualTo("policy-");
        String id = PolicyRuntimeProvisioner.POLICY_MODEL_ID_PREFIX
            + PolicyRuntimeProvisioner.slug("ACME Llama Q4");
        assertThat(id).isEqualTo("policy-acme-llama-q4");
    }

    @Test
    void mlxModelWithUrlSourceIsRejectedWithoutDownloadOrRegistration() throws Exception {
        PolicyRuntimeProvisioner provisioner = new PolicyRuntimeProvisioner(configDir);
        // MLX only supports local safetensors directories — a URL must be refused up front,
        // never downloaded, and never registered.
        provisioner.provision(new PolicyFile.RuntimeModel(
            "ACME MLX", "mlx", "https://models.acme.internal/mlx-model"));
        assertThat(MlxModelRegistry.inDirectory(llmDir()).list()).isEmpty();
    }

    @Test
    void mlxModelWithNonDirectorySourceIsRejected() throws Exception {
        PolicyRuntimeProvisioner provisioner = new PolicyRuntimeProvisioner(configDir);
        provisioner.provision(new PolicyFile.RuntimeModel(
            "ACME MLX", "mlx", configDir.resolve("does-not-exist").toString()));
        assertThat(MlxModelRegistry.inDirectory(llmDir()).list()).isEmpty();
    }

    @Test
    void llamaModelWithMissingLocalFileIsNotRegistered() throws Exception {
        PolicyRuntimeProvisioner provisioner = new PolicyRuntimeProvisioner(configDir);
        provisioner.provision(new PolicyFile.RuntimeModel(
            "ACME Llama", "llama", configDir.resolve("missing.gguf").toString()));
        assertThat(LlamaModelRegistry.inDirectory(llmDir()).list()).isEmpty();
    }
}
