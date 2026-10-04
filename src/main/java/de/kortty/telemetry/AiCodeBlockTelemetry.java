package de.kortty.telemetry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The props of {@value TelemetryEvents#AI_CODE_BLOCK_ACTION}: an Insert into terminal or Run in terminal button
 * of an AI chat code block was used, and how it ended.
 *
 * <p>Anti-PII contract: both values come from a fixed set of ids. Never the block, its language, the command,
 * the pane, the tab, the host or the refusal reason. FX-free, so the prop types are unit-tested without the
 * toolkit.
 */
public final class AiCodeBlockTelemetry {

    /** Which button was used. */
    public enum Action {
        /** Insert into terminal: typed at the prompt, without Enter. */
        INSERT,
        /** Run in terminal: one command line, after a confirmation. */
        RUN;

        /** The id sent: the lowercase name. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** How it ended. */
    public enum Outcome {
        /** The text was handed to the pane (an Insert may still be declined in the paste confirmation). */
        SENT,
        /** The user cancelled the Run confirmation. */
        CANCELLED,
        /** korTTY refused: the policy, the block or the pane's state did not allow it. */
        REFUSED;

        /** The id sent: the lowercase name. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private AiCodeBlockTelemetry() {
    }

    /** {@code ai_code_block_action}: {@code action} is {@code insert} or {@code run}, {@code outcome} how it ended. */
    public static Map<String, Object> codeBlockAction(Action action, Outcome outcome) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("action", (action != null ? action : Action.INSERT).id());
        props.put("outcome", (outcome != null ? outcome : Outcome.REFUSED).id());
        return props;
    }

    /** Sends {@code ai_code_block_action} when telemetry is on; never throws. */
    public static void track(Action action, Outcome outcome) {
        try {
            Telemetry.track(TelemetryEvents.AI_CODE_BLOCK_ACTION, codeBlockAction(action, outcome));
        } catch (RuntimeException e) {
            // telemetry must never break the action
        }
    }
}
