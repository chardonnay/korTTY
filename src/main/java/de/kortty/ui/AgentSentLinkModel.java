package de.kortty.ui;

import de.kortty.core.AiSentContext;
import org.jetbrains.annotations.Nullable;

/**
 * What the "Mitgesendet" link of a KI-Agent run shows in the designs that have no activity list: the context the run's
 * latest turn sent. A turn replaces the earlier copy, a turn that sent nothing changes nothing, and a new run clears it.
 * The context stays after the run ends. FX thread only.
 */
final class AgentSentLinkModel {

    private @Nullable AiSentContext context;

    /** Makes {@code sent} the context to show; {@code null} or an empty context keeps the earlier one. */
    void publish(@Nullable AiSentContext sent) {
        if (sent != null && !sent.isEmpty()) {
            context = sent;
        }
    }

    /** Forgets the context of the earlier run, when a new run starts. */
    void reset() {
        context = null;
    }

    /** The context to show, or {@code null} when the run has sent nothing. */
    @Nullable AiSentContext context() {
        return context;
    }
}
