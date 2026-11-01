package de.kortty.core;

import de.kortty.memory.MemoryBlockBuilder;
import de.kortty.model.AiPromptPreset;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * The order Base, Skills, Memory, RAG, Preset must hold whichever part a transport or decorator
 * applies first. The tests drive the real {@link AiPromptPipeline} methods with the same insertion
 * steps the chat and agent paths use.
 */
class AiPromptPipelineTest {

    private static final String BASE = "Base rules.";
    private static final String SKILLS = "User-defined KorTTY AI skills:\n"
        + "<kortty_ai_skill name=\"Shell\" target=\"BOTH\">\nUse short examples.\n</kortty_ai_skill>";
    private static final String MEMORY = MemoryBlockBuilder.OPEN_TAG + "\nPREAMBLE\n\n"
        + "## Global memory\nAlways answer in short sentences.\n" + MemoryBlockBuilder.CLOSE_TAG;
    private static final String RAG = "<retrieved_context>\nA fact.\n</retrieved_context>";
    private static final String PRESET_BLOCK = "<kortty_model_preset>\n"
        + AiPromptPresetSupport.guidance(AiPromptPreset.QWEN) + "\n</kortty_model_preset>";
    private static final String FULL_PROMPT = BASE + "\n\n" + SKILLS + "\n\n" + MEMORY + "\n\n" + RAG
        + "\n\n" + PRESET_BLOCK;

    @Test
    void aSkillQuotingTheMemoryMarkerNeitherSuppressesNorSplitsTheMemory() {
        String quoting = "User-defined KorTTY AI skills:\n"
            + "<kortty_ai_skill name=\"Notes\" target=\"BOTH\">\nWrap memory in " + MemoryBlockBuilder.OPEN_TAG
            + " tags.\n</kortty_ai_skill>";
        String withSkills = AiPromptPipeline.insertSkills(BASE, quoting);
        String withMemory = AiPromptPipeline.insertMemory(withSkills, MEMORY);

        assertThat(occurrences(withMemory, MemoryBlockBuilder.OPEN_TAG)).isEqualTo(1);
        assertThat(withMemory).contains("Always answer in short sentences.");
        assertThat(withMemory).contains("Wrap memory in < kortty_user_memory> tags.");
    }

    private static int occurrences(String text, String needle) {
        int found = 0;
        for (int index = text.indexOf(needle); index >= 0; index = text.indexOf(needle, index + 1)) {
            found++;
        }
        return found;
    }

    @Test
    void chatTransportOrderIsBaseSkillsMemoryRagPreset() {
        // OpenAI-compatible, Anthropic, LM Studio and LocalCli: skills and memory first, then RAG and preset.
        String withSkills = AiPromptPipeline.insertSkills(BASE, SKILLS);
        String withMemory = AiPromptPipeline.insertMemory(withSkills, MEMORY);

        assertThat(AiPromptPipeline.appendAfterSkills(withMemory, request())).isEqualTo(FULL_PROMPT);
    }

    @Test
    void memoryInsertedBeforeSkillsStillEndsUpAfterThem() {
        String withMemory = AiPromptPipeline.insertMemory(BASE, MEMORY);
        String withSkills = AiPromptPipeline.insertSkills(withMemory, SKILLS);

        assertThat(AiPromptPipeline.appendAfterSkills(withSkills, request())).isEqualTo(FULL_PROMPT);
    }

    @Test
    void ragAndPresetAppendedBeforeSkillsAndMemoryAreStillInFront() {
        // A decorator that already appended RAG and preset to the base prompt.
        String withRagAndPreset = AiPromptPipeline.appendAfterSkills(BASE, request());
        String withSkills = AiPromptPipeline.insertSkills(withRagAndPreset, SKILLS);
        String withMemory = AiPromptPipeline.insertMemory(withSkills, MEMORY);

        assertThat(withMemory).isEqualTo(FULL_PROMPT);
    }

    @Test
    void agentPromptWithPresetAndRagAppendedByDecoratorsKeepsTheOrder() {
        // The agent path inserts memory before its decorators run; RAG and preset are appended after.
        String withMemory = AiPromptPipeline.insertMemory(BASE, MEMORY);
        String withRag = withMemory + "\n\n" + RAG;
        String withPreset = AiPromptPresetSupport.append(withRag, AiPromptPreset.QWEN);
        String withSkills = AiPromptPipeline.insertSkills(withPreset, SKILLS);

        assertThat(withSkills).isEqualTo(FULL_PROMPT);
    }

    @Test
    void everyInsertionOrderGivesTheSameBytes() {
        String chat = AiPromptPipeline.appendAfterSkills(
            AiPromptPipeline.insertMemory(AiPromptPipeline.insertSkills(BASE, SKILLS), MEMORY), request());
        String again = AiPromptPipeline.appendAfterSkills(
            AiPromptPipeline.insertMemory(AiPromptPipeline.insertSkills(BASE, SKILLS), MEMORY), request());

        assertThat(chat).isEqualTo(FULL_PROMPT);
        assertThat(again).isEqualTo(chat);
    }

    @Test
    void memoryIsInsertedOnceWhateverTheCallerRepeats() {
        String once = AiPromptPipeline.insertMemory(BASE, MEMORY);

        assertThat(AiPromptPipeline.insertMemory(once, MEMORY)).isEqualTo(once);
        assertThat(countOccurrences(AiPromptPipeline.insertMemory(
            AiPromptPipeline.insertMemory(AiPromptPipeline.insertSkills(BASE, SKILLS), MEMORY), MEMORY)))
            .isEqualTo(1);
    }

    @Test
    void blankMemoryLeavesThePromptUntouched() {
        assertThat(AiPromptPipeline.insertMemory(BASE, null)).isEqualTo(BASE);
        assertThat(AiPromptPipeline.insertMemory(BASE, "   ")).isEqualTo(BASE);
        assertThat(AiPromptPipeline.insertSkills(BASE, "")).isEqualTo(BASE);
    }

    @Test
    void memoryWithoutSkillsKeepsRagAndPresetLast() {
        String prompt = AiPromptPipeline.appendAfterSkills(AiPromptPipeline.insertMemory(BASE, MEMORY), request());

        assertThat(prompt).isEqualTo(BASE + "\n\n" + MEMORY + "\n\n" + RAG + "\n\n" + PRESET_BLOCK);
    }

    @Test
    void withoutMemoryRemovesOnlyTheBlock() {
        String text = "Before\n\n" + MEMORY + "\n\nAfter";

        assertThat(AiPromptPipeline.withoutMemory(text)).isEqualTo("Before\n\n\n\nAfter");
    }

    @Test
    void withoutMemoryDropsAnUnterminatedBlockAndKeepsPlainText() {
        assertThat(AiPromptPipeline.withoutMemory("Head " + MemoryBlockBuilder.OPEN_TAG + "\nno close"))
            .isEqualTo("Head ");
        assertThat(AiPromptPipeline.withoutMemory("plain text")).isEqualTo("plain text");
        assertThat(AiPromptPipeline.withoutMemory(null)).isNull();
    }

    private static AiRequest request() {
        return new AiRequest(AiAction.ASK, "selected", "box", "en", "question", null, true,
            AiPromptPreset.QWEN, RAG, null, null, null, null);
    }

    private static int countOccurrences(String text) {
        int count = 0;
        int index = text.indexOf(MemoryBlockBuilder.OPEN_TAG);
        while (index >= 0) {
            count++;
            index = text.indexOf(MemoryBlockBuilder.OPEN_TAG, index + 1);
        }
        return count;
    }
}
