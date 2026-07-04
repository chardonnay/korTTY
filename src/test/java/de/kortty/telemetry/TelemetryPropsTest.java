package de.kortty.telemetry;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

class TelemetryPropsTest {

    @Test
    void blankModelNormalizesToAuto() {
        assertThat(TelemetryProps.normalizeModelName(null)).isEqualTo("auto");
        assertThat(TelemetryProps.normalizeModelName("")).isEqualTo("auto");
        assertThat(TelemetryProps.normalizeModelName("   ")).isEqualTo("auto");
    }

    @Test
    void recognizesKnownModelFamiliesIncludingVendorQualifiedIds() {
        // Includes ids korTTY itself suggests in AiCloudModelCatalog — a naive
        // prefix-only match ("gpt-", "llama-", ...) would misclassify several of these.
        assertThat(TelemetryProps.normalizeModelName("gpt-4o")).isEqualTo("openai");
        assertThat(TelemetryProps.normalizeModelName("gpt-4o-mini")).isEqualTo("openai");
        assertThat(TelemetryProps.normalizeModelName("o4-mini")).isEqualTo("openai");
        assertThat(TelemetryProps.normalizeModelName("openai/gpt-oss-20b")).isEqualTo("openai");
        assertThat(TelemetryProps.normalizeModelName("claude-3-5-sonnet-latest")).isEqualTo("anthropic");
        assertThat(TelemetryProps.normalizeModelName("gemini-2.0-flash")).isEqualTo("google");
        assertThat(TelemetryProps.normalizeModelName("mistral-large-latest")).isEqualTo("mistral");
        assertThat(TelemetryProps.normalizeModelName("mixtral-8x7b")).isEqualTo("mistral");
        assertThat(TelemetryProps.normalizeModelName("deepseek-chat")).isEqualTo("deepseek");
        assertThat(TelemetryProps.normalizeModelName("meta-llama/Llama-3.1-70b-instruct")).isEqualTo("meta");
        assertThat(TelemetryProps.normalizeModelName("minimax-abab6.5s")).isEqualTo("minimax");
        assertThat(TelemetryProps.normalizeModelName("MMX-Text-Chat")).isEqualTo("minimax");
        assertThat(TelemetryProps.normalizeModelName("grok-2-latest")).isEqualTo("xai");
        assertThat(TelemetryProps.normalizeModelName("command-r-plus")).isEqualTo("cohere");
    }

    @Test
    void unknownOrCustomModelNamesNeverLeakAsFreeText() {
        assertThat(TelemetryProps.normalizeModelName("acme-internal-secret-model-v3")).isEqualTo("other");
        assertThat(TelemetryProps.normalizeModelName("my-self-hosted-endpoint")).isEqualTo("other");
    }

    @Test
    void aiProfilePropsNeverIncludeRawModelName() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setModel("acme-internal-secret-model-v3");

        Map<String, Object> props = TelemetryProps.aiProfileProps(profile);

        assertThat(props.get("model")).isEqualTo("other");
        assertThat(props.get("mode")).isEqualTo("http_api");
        assertThat(props.get("cli_provider")).isEqualTo("none");
    }
}
