package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiReasoningDiscoveryServiceTest {

    @Test
    void discoverReturnsOnlyAcceptedReasoningEfforts() throws Exception {
        List<AiReasoningEffort> options = AiReasoningDiscoveryService.discover(effort -> {
            if (effort == AiReasoningEffort.MINIMAL) {
                throw new IllegalStateException("unsupported");
            }
            return effort == AiReasoningEffort.DISABLED
                || effort == AiReasoningEffort.LOW
                || effort == AiReasoningEffort.HIGH;
        });

        assertThat(options).containsExactly(
            AiReasoningEffort.DISABLED,
            AiReasoningEffort.LOW,
            AiReasoningEffort.HIGH).inOrder();
    }

    @Test
    void discoverRejectsProfileWhenBaseConnectionTestFails() {
        try {
            AiReasoningDiscoveryService.discover(effort -> false);
        } catch (Exception ex) {
            assertThat(ex).hasMessageThat().contains("connection test failed");
            return;
        }
        throw new AssertionError("Expected reasoning discovery to require a successful base connection test.");
    }

    @Test
    void exactLmStudioMetadataIsLimitedToHttpProfilesWithResolvableModelSelection() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setModelSelectionMode(AiModelSelectionMode.AUTO);
        assertThat(AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile)).isTrue();

        profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        assertThat(AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile)).isFalse();

        profile.setConnectionMode(AiConnectionMode.EMBEDDED_LLAMA_CPP);
        assertThat(AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile)).isFalse();

        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
        assertThat(AiReasoningDiscoveryService.usesExactLmStudioMetadata(profile)).isFalse();
    }

    @Test
    void automaticMetadataLookupNeedsAnHttpProfileWithAChatEndpoint() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel("qwen/qwen3.8-27b");
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isTrue();

        // A LAN LM Studio is just as readable as a loopback one.
        profile.setApiUrl("http://192.168.178.100:1234/v1");
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isTrue();

        // Nothing to ask: no URL, a non-chat path, or a mode whose model cannot be identified.
        profile.setApiUrl(null);
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isFalse();
        profile.setApiUrl("http://127.0.0.1:1234/v1/embeddings");
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isFalse();
        profile.setApiUrl("http://127.0.0.1:1234/v1/chat/completions");
        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(profile)).isFalse();

        assertThat(AiReasoningDiscoveryService.canDiscoverFromMetadata(null)).isFalse();
    }

    @Test
    void automaticMetadataLookupReturnsNothingForAProfileItCannotRead() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        profile.setCliProviderId("codex-cli");

        assertThat(AiReasoningDiscoveryService.discoverFromMetadata(profile, null)).isEmpty();
    }
}
