package de.kortty.core;

import de.kortty.memory.MemoryBlockBuilder;
import de.kortty.memory.MemoryFormat;

import java.util.List;

/**
 * Enforces Action → Skills → Memory → RAG → Model preset ordering for system prompts.
 *
 * <p>Each insertion goes in front of the first marker that belongs to a later part, so the final order
 * does not depend on which transport or decorator applies its part first: skills and memory are
 * inserted before the earliest of RAG, preset and memory, and RAG and preset are appended.</p>
 */
final class AiPromptPipeline {

    private static final String MEMORY = MemoryBlockBuilder.OPEN_TAG;
    private static final String RETRIEVED = "<retrieved_context>";
    private static final String PRESET = "<kortty_model_preset>";

    private AiPromptPipeline() {
    }

    static String appendAfterSkills(String systemPrompt, AiRequest request) {
        String result = normalize(systemPrompt);
        if (request != null && request.retrievedContext() != null && !request.retrievedContext().isBlank()) {
            result = append(result, request.retrievedContext().trim());
        }
        return AiPromptPresetSupport.append(result, request != null ? request.promptPreset() : null);
    }

    /**
     * Inserts the skill block. Its text is user-authored, so prompt markers inside it are escaped first: a
     * skill that quotes a marker can then neither suppress the memory block nor split it.
     */
    static String insertSkills(String systemPrompt, String skillBlock) {
        String base = normalize(systemPrompt);
        String skills = MemoryFormat.escapeMarkers(normalize(skillBlock));
        if (skills.isBlank()) {
            return base;
        }
        return insertBeforeLaterParts(base, skills);
    }

    /**
     * Inserts the memory block before RAG and preset. A prompt that already holds a memory block is
     * returned unchanged, so the block is sent exactly once however often the caller runs.
     */
    static String insertMemory(String systemPrompt, String memoryBlock) {
        String base = normalize(systemPrompt);
        String memory = normalize(memoryBlock);
        if (memory.isBlank() || base.contains(MEMORY)) {
            return base;
        }
        return insertBeforeLaterParts(base, memory);
    }

    /** The text without any memory block, so memory words never take part in skill selection. */
    static String withoutMemory(String text) {
        if (text == null || !text.contains(MEMORY)) {
            return text;
        }
        StringBuilder kept = new StringBuilder(text.length());
        String rest = text;
        int open = rest.indexOf(MEMORY);
        while (open >= 0) {
            kept.append(rest, 0, open);
            int close = rest.indexOf(MemoryBlockBuilder.CLOSE_TAG, open);
            if (close < 0) {
                return kept.toString();
            }
            rest = rest.substring(close + MemoryBlockBuilder.CLOSE_TAG.length());
            open = rest.indexOf(MEMORY);
        }
        return kept.append(rest).toString();
    }

    private static String insertBeforeLaterParts(String base, String block) {
        int boundary = suffixBoundary(base);
        if (boundary < 0) {
            return append(base, block);
        }
        String prefix = base.substring(0, boundary).stripTrailing();
        String suffix = base.substring(boundary).stripLeading();
        return append(append(prefix, block), suffix);
    }

    /** The earliest position of memory, RAG or preset, or -1 when the prompt has none of them. */
    private static int suffixBoundary(String value) {
        int boundary = -1;
        for (String marker : List.of(MEMORY, RETRIEVED, PRESET)) {
            int index = value.indexOf(marker);
            if (index >= 0 && (boundary < 0 || index < boundary)) {
                boundary = index;
            }
        }
        return boundary;
    }

    private static String append(String first, String second) {
        if (first.isBlank()) return second;
        if (second.isBlank()) return first;
        return first + "\n\n" + second;
    }

    private static String normalize(String value) {
        return value != null ? value.trim() : "";
    }
}
