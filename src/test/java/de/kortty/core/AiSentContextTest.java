package de.kortty.core;

import de.kortty.memory.MemoryBlock;
import de.kortty.memory.MemoryBlock.Origin;
import de.kortty.memory.MemoryBlock.SentPiece;
import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenizerType;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The "what was sent" summary: counts, tokens, the empty case and the text of a row. */
class AiSentContextTest {

    private static SentPiece piece(Origin origin, String text, String entryId, String label) {
        return new SentPiece(origin, 0, entryId, label, text, 4, false);
    }

    private static MemoryBlock memory(List<SentPiece> pieces, int tokens, int omitted) {
        return new MemoryBlock("<kortty_user_memory>\n</kortty_user_memory>", tokens, List.of(), List.of(), omitted,
            pieces);
    }

    @Test
    void countsTheEntriesTheFilesAndTheSkillsAndSumsTheTokens() {
        MemoryBlock block = memory(List.of(
            piece(Origin.ORGANIZATION, "### Keys\nNo keys in notes.", null, null),
            piece(Origin.GLOBAL, "Use tabs.", null, null),
            piece(Origin.CONNECTION, "- Perl: use state.", "c001", null),
            piece(Origin.EXTERNAL, "Reference text.", null, "Claude Code: CLAUDE.md")), 40, 0);
        AiSentContext sent = new AiSentContext(block, List.of(
            new AiSentContext.Skill("s1", "Shell Style", true, 30, 1),
            new AiSentContext.Skill("s2", "Pinned Style", false, 20, 1)));

        assertThat(sent.entries()).hasSize(3);
        assertThat(sent.files()).hasSize(1);
        assertThat(sent.skills()).hasSize(2);
        assertThat(sent.tokens()).isEqualTo(40 + 30 + 20);
    }

    @Test
    void entriesKeepTheirOrderAndFilesAreTheExternalPartsOnly() {
        MemoryBlock block = memory(List.of(
            piece(Origin.GLOBAL, "Global one.", null, null),
            piece(Origin.EXTERNAL, "File.", null, "Codex: AGENTS.md"),
            piece(Origin.CONNECTION, "Host note.", null, null)), 20, 0);

        AiSentContext sent = new AiSentContext(block, List.of());

        assertThat(sent.entries().stream().map(SentPiece::text).toList())
            .containsExactly("Global one.", "Host note.").inOrder();
        assertThat(sent.files().stream().map(SentPiece::label).toList()).containsExactly("Codex: AGENTS.md");
    }

    @Test
    void anEmptyContextHasNothingToShow() {
        assertThat(AiSentContext.empty().isEmpty()).isTrue();
        assertThat(new AiSentContext(MemoryBlock.empty(), List.of()).isEmpty()).isTrue();
        assertThat(new AiSentContext(null, null).isEmpty()).isTrue();
    }

    @Test
    void aSkillOrAMemoryBlockAloneIsSomethingToShow() {
        assertThat(new AiSentContext(MemoryBlock.empty(),
            List.of(new AiSentContext.Skill("s", "Skill", true, 3, 1))).isEmpty()).isFalse();
        assertThat(new AiSentContext(memory(List.of(), 9, 0), List.of()).isEmpty()).isFalse();
    }

    @Test
    void theOmittedCountComesFromTheBlock() {
        AiSentContext sent = new AiSentContext(memory(List.of(piece(Origin.GLOBAL, "Kept.", null, null)), 9, 3),
            List.of());

        assertThat(sent.omittedCount()).isEqualTo(3);
    }

    @Test
    void withMemoryKeepsTheSkills() {
        AiSentContext.Skill skill = new AiSentContext.Skill("s", "Skill", false, 6, 2);
        AiSentContext sent = new AiSentContext(MemoryBlock.empty(), List.of(skill))
            .withMemory(memory(List.of(piece(Origin.ORGANIZATION, "Rule.", null, null)), 7, 0));

        assertThat(sent.skills()).containsExactly(skill);
        assertThat(sent.entries()).hasSize(1);
        assertThat(sent.tokens()).isEqualTo(7 + 6);
    }

    @Test
    void theFirstLineOfARowDropsHeadingMarksAndTheRestOfTheText() {
        assertThat(AiSentContext.firstLine("### Keys\nNo keys in notes.")).isEqualTo("Keys");
        assertThat(AiSentContext.firstLine("  Use tabs.  \n\nMore text.")).isEqualTo("Use tabs.");
        assertThat(AiSentContext.firstLine(null)).isEmpty();
        assertThat(AiSentContext.firstLine("")).isEmpty();
    }

    @Test
    void aLongFirstLineIsShortenedToTheWidthOfARow() {
        String line = AiSentContext.firstLine("x".repeat(200));

        assertThat(line).hasLength(121);
        assertThat(line).endsWith("…");
    }

    @Test
    void theTokenizerOfAProfileIsTheEstimateWhenItNamesNone() {
        assertThat(AiSentContext.tokenizerOf(null)).isEqualTo(AiTokenizerType.ESTIMATE);
        AiProfile profile = new AiProfile();
        profile.setTokenizerType(AiTokenizerType.CL100K_BASE);
        assertThat(AiSentContext.tokenizerOf(profile)).isEqualTo(AiTokenizerType.CL100K_BASE);
    }

    @Test
    void aSkillWithoutIdOrNameGetsEmptyTextNotNull() {
        AiSentContext.Skill skill = new AiSentContext.Skill(null, null, true, 1, 1);

        assertThat(skill.id()).isEmpty();
        assertThat(skill.name()).isEmpty();
    }
}
