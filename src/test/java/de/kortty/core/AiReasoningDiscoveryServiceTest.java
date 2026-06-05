package de.kortty.core;

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
}
