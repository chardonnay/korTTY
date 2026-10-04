package de.kortty.ui;

import de.kortty.codingagent.CodingAgentState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Decides which panes broadcast mode leaves out, whatever is typed: the keys typed in one pane are
 * not mirrored into a pane that
 *
 * <ul>
 *   <li>is sending a paste line by line, so no key lands between two pasted lines;</li>
 *   <li>an AI agent run drives, which types its own commands into it;</li>
 *   <li>shows a coding agent that waits for a decision ({@link CodingAgentState#BLOCKED}), where a
 *       mirrored {@code y} and Enter would approve whatever the agent asks for.</li>
 * </ul>
 *
 * <p>The pane the user types in is not affected: its own keys always reach it. Pure, FX-free.
 */
final class MirrorTargetGuard {

    private MirrorTargetGuard() {
    }

    /** Whether broadcast mode leaves a pane in this state out. */
    static boolean holds(boolean pacingPaste, boolean agentRun, @Nullable CodingAgentState codingAgent) {
        return pacingPaste || agentRun || codingAgent == CodingAgentState.BLOCKED;
    }

    /**
     * The guard for split panes: true for a pane broadcast mode may type into. The questions are
     * asked in the order of the parameters, and only until one of them holds the pane.
     *
     * @param codingAgent the state of the coding agent a pane shows, null when it shows none
     */
    static <W> @NotNull Predicate<W> accepting(@NotNull Predicate<? super W> pacingPaste,
                                               @NotNull Predicate<? super W> agentRun,
                                               @NotNull Function<? super W, CodingAgentState> codingAgent) {
        Objects.requireNonNull(pacingPaste, "pacingPaste");
        Objects.requireNonNull(agentRun, "agentRun");
        Objects.requireNonNull(codingAgent, "codingAgent");
        return pane -> !(pacingPaste.test(pane)
            || agentRun.test(pane)
            || holds(false, false, codingAgent.apply(pane)));
    }
}
