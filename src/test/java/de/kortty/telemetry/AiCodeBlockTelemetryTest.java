package de.kortty.telemetry;

import org.testng.annotations.Test;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * {@code ai_code_block_action} carries two ids from fixed sets, never the block, the command or the pane.
 */
class AiCodeBlockTelemetryTest {

    private static final Set<String> ACTIONS = Set.of("insert", "run");
    private static final Set<String> OUTCOMES = Set.of("sent", "cancelled", "refused");

    @Test
    void theEventSendsOnlyTheActionAndTheOutcome() {
        for (AiCodeBlockTelemetry.Action action : AiCodeBlockTelemetry.Action.values()) {
            for (AiCodeBlockTelemetry.Outcome outcome : AiCodeBlockTelemetry.Outcome.values()) {
                Map<String, Object> props = AiCodeBlockTelemetry.codeBlockAction(action, outcome);
                assertThat(props.keySet()).containsExactly("action", "outcome").inOrder();
                assertThat(ACTIONS).contains(props.get("action"));
                assertThat(OUTCOMES).contains(props.get("outcome"));
                assertOnlyEnumIds(props);
            }
        }
        assertThat(AiCodeBlockTelemetry.codeBlockAction(AiCodeBlockTelemetry.Action.RUN,
            AiCodeBlockTelemetry.Outcome.CANCELLED)).containsExactly("action", "run", "outcome", "cancelled");
        assertThat(AiCodeBlockTelemetry.codeBlockAction(null, null))
            .containsExactly("action", "insert", "outcome", "refused");
    }

    @Test
    void theEventNameIsSnakeCase() {
        assertThat(TelemetryEvents.AI_CODE_BLOCK_ACTION).isEqualTo("ai_code_block_action");
    }

    @Test
    void trackingWithoutATelemetryServiceDoesNothing() {
        AiCodeBlockTelemetry.track(AiCodeBlockTelemetry.Action.INSERT, AiCodeBlockTelemetry.Outcome.SENT);
    }

    private static void assertOnlyEnumIds(Map<String, Object> props) {
        Pattern enumId = Pattern.compile("[a-z][a-z_]*");
        for (Map.Entry<String, Object> prop : props.entrySet()) {
            assertWithMessage(prop.getKey()).that(prop.getValue()).isInstanceOf(String.class);
            assertWithMessage(prop.getKey()).that(enumId.matcher((String) prop.getValue()).matches()).isTrue();
        }
    }
}
