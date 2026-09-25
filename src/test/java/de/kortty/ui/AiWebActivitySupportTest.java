package de.kortty.ui;

import de.kortty.core.AiWebToolCall;
import de.kortty.model.SavedAiWebToolCall;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class AiWebActivitySupportTest {

    private static final List<AiWebToolCall> CALLS = List.of(
        new AiWebToolCall(AiWebToolCall.Kind.SEARCH, "web_search", "kortty release", true, null,
            List.of(
                new AiWebToolCall.Source("korTTY Releases", "https://example.test/releases"),
                new AiWebToolCall.Source("", "https://example.test/blog")),
            0, false),
        new AiWebToolCall(AiWebToolCall.Kind.EXTRACT, "web_extract", "https://example.test/manual.pdf", true, null,
            List.of(new AiWebToolCall.Source("", "https://example.test/manual.pdf")), 12000, true),
        AiWebToolCall.failed(AiWebToolCall.Kind.SEARCH, "web_search", "broken", "HTTP 401"));

    @Test
    void toSavedKeepsEveryDisplayField() {
        List<SavedAiWebToolCall> saved = AiWebActivitySupport.toSaved(CALLS);

        assertThat(saved).hasSize(3);
        assertThat(saved.get(0).getKind()).isEqualTo("SEARCH");
        assertThat(saved.get(0).getSources()).hasSize(2);
        assertThat(saved.get(1).getContentChars()).isEqualTo(12000);
        assertThat(saved.get(1).isTruncated()).isTrue();
        assertThat(saved.get(2).isSuccess()).isFalse();
        assertThat(saved.get(2).getMessage()).isEqualTo("HTTP 401");
    }

    @Test
    void summaryCountsSearchesReadsAndFailuresWithoutPlaceholders() {
        String summary = AiWebActivitySupport.summary(AiWebActivitySupport.toSaved(CALLS));

        assertThat(summary).startsWith(I18n.get("ai.result.web.label"));
        assertThat(summary).contains(I18n.get("ai.result.web.count.search", "1"));
        assertThat(summary).contains(I18n.get("ai.result.web.count.extract", "1"));
        assertThat(summary).contains(I18n.get("ai.result.web.count.failed", "1"));
        assertThat(summary).doesNotContain("{");
    }

    @Test
    void detailListsQueriesSourcesAndErrorsInCallOrder() {
        String detail = AiWebActivitySupport.detail(AiWebActivitySupport.toSaved(CALLS));

        assertThat(detail).contains("kortty release");
        assertThat(detail).contains("  • korTTY Releases\n    https://example.test/releases");
        assertThat(detail).contains("  • https://example.test/blog");
        assertThat(detail).contains("12000");
        assertThat(detail).contains("HTTP 401");
        // A page read lists its URL once, not again as a source line.
        assertThat(detail.indexOf("https://example.test/manual.pdf"))
            .isEqualTo(detail.lastIndexOf("https://example.test/manual.pdf"));
        assertThat(detail.indexOf("kortty release")).isLessThan(detail.indexOf("manual.pdf"));
        assertThat(detail).doesNotContain("{");
    }
}
