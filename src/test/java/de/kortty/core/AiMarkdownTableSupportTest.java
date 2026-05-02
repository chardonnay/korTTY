package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class AiMarkdownTableSupportTest {

    @Test
    void buildRenderedTableStripsInlineMarkdownAndSerializesClipboardFormats() {
        AiMarkdownTableSupport.RenderedMarkdownTable table = AiMarkdownTableSupport.buildRenderedTable(List.of(
            List.of("**Name**", "**Status**"),
            List.of("`api`", "ok"),
            List.of("worker", "**warn**")));

        assertThat(table.header()).isEqualTo(List.of("Name", "Status"));
        assertThat(AiMarkdownTableSupport.toTsv(table)).isEqualTo("Name\tStatus\napi\tok\nworker\twarn");
        assertThat(AiMarkdownTableSupport.toColumnText(table, 1)).isEqualTo("Status\nok\nwarn");
        assertThat(AiMarkdownTableSupport.toCellText(table, 1, 1)).isEqualTo("warn");
    }
}
