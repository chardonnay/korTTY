package de.kortty.core;

import de.kortty.memory.MemoryBlock;
import de.kortty.memory.MemoryBlock.Origin;
import de.kortty.memory.MemoryBlock.SentPiece;
import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenizerType;

import java.util.List;

/**
 * What one AI request, or one agent run, sent besides the user's own text: the memory parts and the skills. The texts
 * are the masked copies the provider received, and the tokens are counted with the profile's tokenizer.
 *
 * <p>The context is built on the thread that sends the request, so no token is counted on the UI thread. An empty
 * context has nothing to show.</p>
 *
 * @param memory the memory block as it was sent, empty when none was sent
 * @param skills the skills that were sent, each once
 */
public record AiSentContext(MemoryBlock memory, List<Skill> skills) {

    private static final int FIRST_LINE_CHARS = 120;

    /**
     * A skill that was sent.
     *
     * @param id        the skill's id, or empty when it has none
     * @param name      the skill's name as the user sees it
     * @param automatic true when the relevance check selected the skill, false when it was pinned for the connection or
     *                  the request
     * @param tokens    the size of the skill's text as it was sent, in tokens
     * @param turns         how many requests of the run sent the skill; 1 for a single request
     * @param learnedRules  the learned rules of the skill as they were sent, one "- " line each; empty when none were sent
     */
    public record Skill(String id, String name, boolean automatic, int tokens, int turns, String learnedRules) {

        /** A skill without learned rules, as the sent-context view had it before the rules were listed. */
        public Skill(String id, String name, boolean automatic, int tokens, int turns) {
            this(id, name, automatic, tokens, turns, "");
        }

        public Skill {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            learnedRules = learnedRules == null ? "" : learnedRules;
        }
    }

    public AiSentContext {
        memory = memory == null ? MemoryBlock.empty() : memory;
        skills = skills == null ? List.of() : List.copyOf(skills);
    }

    public static AiSentContext empty() {
        return new AiSentContext(MemoryBlock.empty(), List.of());
    }

    /** True when nothing was sent: no memory and no skill. */
    public boolean isEmpty() {
        return memory.isEmpty() && skills.isEmpty();
    }

    /** The memory parts that are not external files: organisation rules, global and connection memory, in order. */
    public List<SentPiece> entries() {
        return memory.sentPieces().stream().filter(piece -> piece.origin() != Origin.EXTERNAL).toList();
    }

    /** The external reference files that were sent, in order. */
    public List<SentPiece> files() {
        return memory.sentPieces().stream().filter(piece -> piece.origin() == Origin.EXTERNAL).toList();
    }

    /** How many memory entries or paragraphs did not fit the budget. */
    public int omittedCount() {
        return memory.omittedCount();
    }

    /** The tokens sent: the memory block and every skill. */
    public int tokens() {
        int total = memory.tokens();
        for (Skill skill : skills) {
            total += skill.tokens();
        }
        return total;
    }

    /** The same context with another memory block; the agent adds the memory it masks for the run this way. */
    public AiSentContext withMemory(MemoryBlock block) {
        return new AiSentContext(block, skills);
    }

    /**
     * The first line of a memory text, without leading heading marks, shortened to the width of one row.
     */
    public static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        String line = text.strip().lines().findFirst().orElse("").strip().replaceFirst("^#+\\s*", "");
        return line.length() > FIRST_LINE_CHARS ? line.substring(0, FIRST_LINE_CHARS) + "…" : line;
    }

    /** The tokenizer the prompts of {@code profile} are counted with; the estimate when the profile names none. */
    public static AiTokenizerType tokenizerOf(AiProfile profile) {
        return profile != null && profile.getTokenizerType() != null
            ? profile.getTokenizerType()
            : AiTokenizerType.ESTIMATE;
    }
}
