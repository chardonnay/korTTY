package de.kortty.core;

import de.kortty.memory.MemoryBlock;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The sent context of an agent run, built turn by turn: the run's memory and the union of its skills. */
class AiSentRunLedgerTest {

    private static AiSentContext.Skill skill(String id, String name, int tokens) {
        return new AiSentContext.Skill(id, name, true, tokens, 1);
    }

    private static AiSentContext turn(MemoryBlock memory, AiSentContext.Skill... skills) {
        return new AiSentContext(memory, List.of(skills));
    }

    @Test
    void aNewLedgerHasNothingToShow() {
        assertThat(new AiSentRunLedger().snapshot().isEmpty()).isTrue();
    }

    @Test
    void countsEachSkillOncePerTurnThatSentIt() {
        AiSentRunLedger ledger = new AiSentRunLedger();
        ledger.recordTurn(turn(MemoryBlock.empty(), skill("a", "Alpha", 10), skill("b", "Beta", 20)));
        ledger.recordTurn(turn(MemoryBlock.empty(), skill("b", "Beta", 20), skill("c", "Gamma", 5)));
        ledger.recordTurn(turn(MemoryBlock.empty(), skill("b", "Beta", 21), skill("b", "Beta", 21)));

        assertThat(ledger.snapshot().skills()).containsExactly(
            new AiSentContext.Skill("a", "Alpha", true, 10, 1),
            new AiSentContext.Skill("b", "Beta", true, 21, 3),
            new AiSentContext.Skill("c", "Gamma", true, 5, 1)).inOrder();
    }

    @Test
    void aSkillWithoutIdIsCountedByItsName() {
        AiSentRunLedger ledger = new AiSentRunLedger();
        ledger.recordTurn(turn(MemoryBlock.empty(), skill("", "Plain", 4)));
        ledger.recordTurn(turn(MemoryBlock.empty(), skill("", "Plain", 4)));

        assertThat(ledger.snapshot().skills()).containsExactly(new AiSentContext.Skill("", "Plain", true, 4, 2));
    }

    @Test
    void theRunMemoryIsKeptAndATurnWithoutMemoryDoesNotClearIt() {
        MemoryBlock memory = new MemoryBlock("<kortty_user_memory>\nA rule.\n</kortty_user_memory>", 9, List.of(), 0);
        AiSentRunLedger ledger = new AiSentRunLedger();

        ledger.recordTurn(turn(memory));
        ledger.recordTurn(turn(MemoryBlock.empty(), skill("a", "Alpha", 3)));

        assertThat(ledger.snapshot().memory()).isEqualTo(memory);
        assertThat(ledger.snapshot().skills()).hasSize(1);
    }

    @Test
    void aTurnWithNothingSentOrANullTurnChangesNothing() {
        AiSentRunLedger ledger = new AiSentRunLedger();

        ledger.recordTurn(null);
        ledger.recordTurn(AiSentContext.empty());

        assertThat(ledger.snapshot().isEmpty()).isTrue();
    }

    @Test
    void aSnapshotIsACopyThatLaterTurnsDoNotChange() {
        AiSentRunLedger ledger = new AiSentRunLedger();
        ledger.recordTurn(turn(MemoryBlock.empty(), skill("a", "Alpha", 3)));

        AiSentContext before = ledger.snapshot();
        ledger.recordTurn(turn(MemoryBlock.empty(), skill("a", "Alpha", 3)));

        assertThat(before.skills().get(0).turns()).isEqualTo(1);
        assertThat(ledger.snapshot().skills().get(0).turns()).isEqualTo(2);
    }
}
