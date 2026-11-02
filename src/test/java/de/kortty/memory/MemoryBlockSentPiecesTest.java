package de.kortty.memory;

import de.kortty.core.AiTokenCounter;
import de.kortty.memory.MemoryBlock.Origin;
import de.kortty.memory.MemoryBlock.SentPart;
import de.kortty.memory.MemoryBlock.SentPiece;
import de.kortty.memory.MemoryBlockBuilder.ExternalSource;
import de.kortty.memory.MemoryBlockBuilder.Input;
import de.kortty.memory.MemoryBlockBuilder.OrganizationRule;
import de.kortty.memory.external.ExternalMemoryTool;
import de.kortty.model.AiTokenizerType;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The pieces a block reports: the text each part sends, its tokens, and whether an external file was shortened. */
class MemoryBlockSentPiecesTest {

    private static final int BUDGET = 1500;

    private static String entry(String id, String created, String body) {
        return MemoryFormat.newEntry(id, body, LocalDate.parse(created), "chat");
    }

    private static MemoryBlock build(List<OrganizationRule> organization, String global, String connection,
                                     List<ExternalSource> external, int budget) {
        return MemoryBlockBuilder.build(new Input(organization, global, connection, external), budget,
            AiTokenizerType.ESTIMATE);
    }

    @Test
    void reportsEveryPartWithItsTextAndTokensInRenderingOrder() {
        String global = "Global one.\n\n" + entry("g001", "2026-11-02", "- Global rule.");
        MemoryBlock block = build(
            List.of(new OrganizationRule("Keys", "No secrets in notes.")),
            global,
            "Host note.",
            List.of(new ExternalSource("Claude Code: CLAUDE.md", "Use tabs.", ExternalMemoryTool.CLAUDE_CODE)),
            BUDGET);

        List<SentPiece> pieces = block.sentPieces();

        assertThat(pieces).hasSize(5);
        assertThat(pieces.get(0).origin()).isEqualTo(Origin.ORGANIZATION);
        assertThat(pieces.get(0).text()).isEqualTo("### Keys\nNo secrets in notes.");
        assertThat(pieces.get(1).origin()).isEqualTo(Origin.GLOBAL);
        assertThat(pieces.get(1).text()).isEqualTo("Global one.");
        assertThat(pieces.get(1).entryId()).isNull();
        assertThat(pieces.get(2).origin()).isEqualTo(Origin.GLOBAL);
        assertThat(pieces.get(2).entryId()).isEqualTo("g001");
        assertThat(pieces.get(2).text()).isEqualTo("- Global rule.");
        assertThat(pieces.get(3).origin()).isEqualTo(Origin.CONNECTION);
        assertThat(pieces.get(3).text()).isEqualTo("Host note.");
        assertThat(pieces.get(4).origin()).isEqualTo(Origin.EXTERNAL);
        assertThat(pieces.get(4).label()).isEqualTo("Claude Code: CLAUDE.md");
        assertThat(pieces.get(4).text()).isEqualTo("Use tabs.");
        assertThat(pieces.get(4).truncated()).isFalse();
        for (SentPiece piece : pieces) {
            assertThat(piece.tokens()).isEqualTo(AiTokenCounter.countTextTokens(piece.text(), AiTokenizerType.ESTIMATE));
        }
    }

    @Test
    void anExternalFileTheReaderShortenedIsReportedAsTruncated() {
        MemoryBlock block = build(List.of(), null, null,
            List.of(new ExternalSource("Codex: AGENTS.md", "Short.", ExternalMemoryTool.CODEX, true)), BUDGET);

        assertThat(block.sentPieces()).hasSize(1);
        assertThat(block.sentPieces().get(0).truncated()).isTrue();
        assertThat(block.sentPieces().get(0).label()).isEqualTo("Codex: AGENTS.md");
    }

    @Test
    void anExternalFileWhoseParagraphsDidNotFitIsReportedAsTruncatedWithTheKeptText() {
        String tooLong = "word ".repeat(3000).strip();
        MemoryBlock block = build(List.of(), null, null,
            List.of(new ExternalSource("Notes: notes.md", "Short rule.\n\n" + tooLong, ExternalMemoryTool.OTHER)),
            BUDGET);

        SentPiece file = block.sentPieces().get(0);
        assertThat(file.origin()).isEqualTo(Origin.EXTERNAL);
        assertThat(file.truncated()).isTrue();
        assertThat(file.text()).isEqualTo("Short rule.");
        assertThat(block.omittedCount()).isGreaterThan(0);
    }

    @Test
    void aBlockWithoutPartsReportsNone() {
        assertThat(MemoryBlock.empty().sentPieces()).isEmpty();
        assertThat(build(List.of(), null, null, List.of(), BUDGET).sentPieces()).isEmpty();
    }

    @Test
    void theMaskedCopyMasksEveryTextAndKeepsTheParts() {
        String secret = "Key-4711-Secret";
        MemoryBlock block = new MemoryBlock("<kortty_user_memory>\n" + secret + "\n</kortty_user_memory>", 50,
            List.of("c001"), List.of(new SentPart(true, 0, "c001")), 2,
            List.of(new SentPiece(Origin.CONNECTION, 0, "c001", null, secret, 9, false)));

        MemoryBlock masked = block.masked(text -> text.replace(secret, "<masked>"), AiTokenizerType.ESTIMATE);

        assertThat(masked.text()).doesNotContain(secret);
        assertThat(masked.sentPieces().get(0).text()).isEqualTo("<masked>");
        assertThat(masked.sentPieces().get(0).tokens())
            .isEqualTo(AiTokenCounter.countTextTokens("<masked>", AiTokenizerType.ESTIMATE));
        assertThat(masked.sentPieces().get(0).entryId()).isEqualTo("c001");
        assertThat(masked.sentParts()).containsExactly(new SentPart(true, 0, "c001"));
        assertThat(masked.includedEntryIds()).containsExactly("c001");
        assertThat(masked.omittedCount()).isEqualTo(2);
        assertThat(masked.tokens()).isEqualTo(AiTokenCounter.countTextTokens(masked.text(), AiTokenizerType.ESTIMATE));
    }

    @Test
    void anExternalFileWithoutAnExplicitFlagIsNotTruncated() {
        assertThat(new ExternalSource("CLAUDE.md", "Text.").truncated()).isFalse();
        assertThat(new ExternalSource("CLAUDE.md", "Text.", ExternalMemoryTool.OTHER).truncated()).isFalse();
    }
}
