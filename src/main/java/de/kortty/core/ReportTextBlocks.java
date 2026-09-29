package de.kortty.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits AI prose (a finding's detail, a recommendation, the summary) into the blocks a report
 * renders: paragraphs — separated by blank lines, keeping line breaks and indentation inside —,
 * fenced code blocks and Markdown tables. Built on {@link AiChatContentSupport}, which the chat
 * export uses for the same job.
 */
final class ReportTextBlocks {

    enum Type { PARAGRAPH, CODE, TABLE }

    /**
     * @param text     the paragraph text or the code (null for a table)
     * @param language the code fence's language hint ("" when absent)
     */
    record Block(Type type, String text, String language, List<List<String>> rows) {

        Block {
            text = text != null ? text : "";
            language = language != null ? language : "";
            rows = rows != null ? List.copyOf(rows) : List.of();
        }
    }

    private ReportTextBlocks() {
    }

    static List<Block> split(String text) {
        List<Block> blocks = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return blocks;
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        for (AiChatContentSupport.ContentSection section : AiChatContentSupport.splitContent(normalized)) {
            if (section.code()) {
                String code = section.content();
                if (code.endsWith("\n")) {
                    code = code.substring(0, code.length() - 1);
                }
                blocks.add(new Block(Type.CODE, code, section.language(), null));
                continue;
            }
            for (AiChatContentSupport.StructuredTextBlock block
                    : AiChatContentSupport.splitStructuredText(section.content())) {
                if (block.type() == AiChatContentSupport.StructuredTextBlock.Type.TABLE) {
                    blocks.add(new Block(Type.TABLE, null, null, block.tableRows()));
                } else {
                    addParagraphs(blocks, block.text());
                }
            }
        }
        return blocks;
    }

    /** One block per blank-line separated paragraph; trailing whitespace of each line is dropped. */
    private static void addParagraphs(List<Block> blocks, String text) {
        StringBuilder current = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.isBlank()) {
                flush(blocks, current);
                continue;
            }
            if (!current.isEmpty()) {
                current.append('\n');
            }
            current.append(line.stripTrailing());
        }
        flush(blocks, current);
    }

    private static void flush(List<Block> blocks, StringBuilder current) {
        if (!current.isEmpty()) {
            blocks.add(new Block(Type.PARAGRAPH, current.toString(), null, null));
            current.setLength(0);
        }
    }
}
