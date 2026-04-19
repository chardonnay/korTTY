package de.kortty.core;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AiMarkdownTableSupportTest {

    @Test
    void buildRenderedTableStripsInlineMarkdownAndSerializesClipboardFormats() {
        AiMarkdownTableSupport.RenderedMarkdownTable table = AiMarkdownTableSupport.buildRenderedTable(List.of(
            List.of("**Name**", "**Status**"),
            List.of("`api`", "ok"),
            List.of("worker", "**warn**")));

        assertEquals(List.of("Name", "Status"), table.header());
        assertEquals("Name\tStatus\napi\tok\nworker\twarn", AiMarkdownTableSupport.toTsv(table));
        assertEquals("Status\nok\nwarn", AiMarkdownTableSupport.toColumnText(table, 1));
        assertEquals("warn", AiMarkdownTableSupport.toCellText(table, 1, 1));
    }
}
