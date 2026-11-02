package de.kortty.memory;

import de.kortty.memory.MemoryBlock.SentPart;
import de.kortty.memory.MemoryBlockBuilder.Input;
import de.kortty.memory.MemoryBlockBuilder.Paragraph;
import de.kortty.model.AiTokenizerType;
import org.testng.annotations.Test;

import java.time.LocalDate;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The sent parts a block reports: which user paragraphs and entries it carries, in both files. */
class MemoryBlockSentPartsTest {

    private static String entry(String id, String created, String body) {
        return MemoryFormat.newEntry(id, body, LocalDate.parse(created), "chat");
    }

    @Test
    void reportsUserParagraphsAndEntriesOfEachFile() {
        String global = "Global one.\n\nGlobal two.\n\n" + entry("g001", "2026-11-02", "- Global rule.");
        String connection = "Host note.\n\n" + entry("c001", "2026-11-02", "- Host rule.");

        MemoryBlock block = MemoryBlockBuilder.build(new Input(List.of(), global, connection, List.of()), 1500,
            AiTokenizerType.ESTIMATE);

        assertThat(block.sentParts()).containsExactly(
            new SentPart(true, 0, null),
            new SentPart(true, 1, "c001"),
            new SentPart(false, 0, null),
            new SentPart(false, 1, null),
            new SentPart(false, 2, "g001")).inOrder();
        assertThat(block.includedEntryIds()).containsExactly("c001", "g001").inOrder();
    }

    @Test
    void anEmptyBlockReportsNoParts() {
        assertThat(MemoryBlock.empty().sentParts()).isEmpty();
        assertThat(new MemoryBlock("x", 1, List.of(), 0).sentParts()).isEmpty();
    }

    @Test
    void theBuilderTextIsUnchangedByTheReportedParts() {
        String global = "Use tabs.\n\n" + entry("g001", "2026-11-02", "- Perl: use state.");
        MemoryBlock block = MemoryBlockBuilder.build(new Input(List.of(), global, null, List.of()), 1500,
            AiTokenizerType.ESTIMATE);

        assertThat(block.text()).startsWith("<kortty_user_memory>");
        assertThat(block.text()).contains("Use tabs.");
        assertThat(block.text()).contains("- Perl: use state.");
        assertThat(block.text()).endsWith("</kortty_user_memory>");
    }

    @Test
    void documentParagraphsNumberTheParagraphsTheWayTheBlockDoes() {
        String text = "A.\n\nB.\n\n" + entry("k001", "2026-11-02", "- C.") + "\n" + entry("k002", "2026-11-03", "");
        List<Paragraph> paragraphs = MemoryBlockBuilder.documentParagraphs(text);

        assertThat(paragraphs.stream().map(Paragraph::position)).containsExactly(0, 1, 2).inOrder();
        assertThat(paragraphs.get(2).korttyEntry()).isTrue();
        assertThat(paragraphs.get(2).source()).isEqualTo("chat");
        assertThat(paragraphs.get(2).created()).isEqualTo("2026-11-02");
    }
}
