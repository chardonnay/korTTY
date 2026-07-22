package de.kortty.policy;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class PolicyRuntimeProvisionerTest {

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
}
