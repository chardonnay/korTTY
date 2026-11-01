package de.kortty.core;

import de.kortty.memory.MemoryBlock;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The sent context of one agent run, built up turn by turn: the memory the run carries, and every skill that any turn
 * sent, with the number of turns that sent it. A skill counts once per turn, however often the turn resent it.
 *
 * <p>The run's turns record from its worker threads and the activity view reads from the UI thread, so the methods are
 * synchronized.</p>
 */
public final class AiSentRunLedger {

    private final Map<String, AiSentContext.Skill> skills = new LinkedHashMap<>();
    private MemoryBlock memory = MemoryBlock.empty();

    /**
     * Adds one turn. A turn with memory replaces the run's memory, which every turn carries unchanged; each skill of the
     * turn adds one to its turn count.
     */
    public synchronized void recordTurn(AiSentContext turn) {
        if (turn == null) {
            return;
        }
        if (!turn.memory().isEmpty()) {
            memory = turn.memory();
        }
        Set<String> seenInTurn = new HashSet<>();
        for (AiSentContext.Skill skill : turn.skills()) {
            String key = skill.id().isBlank() ? skill.name() : skill.id();
            if (!seenInTurn.add(key)) {
                continue;
            }
            AiSentContext.Skill previous = skills.get(key);
            int turnsBefore = previous == null ? 0 : previous.turns();
            skills.put(key, new AiSentContext.Skill(skill.id(), skill.name(), skill.automatic(), skill.tokens(),
                turnsBefore + 1, skill.learnedRules()));
        }
    }

    /** The run's sent context so far; empty before any turn sent anything. */
    public synchronized AiSentContext snapshot() {
        return new AiSentContext(memory, List.copyOf(skills.values()));
    }
}
