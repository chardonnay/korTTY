package de.kortty.core;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class TerminalAgentWebActivityTest {

    private static final AiWebToolCall SEARCH = new AiWebToolCall(
        AiWebToolCall.Kind.SEARCH, "web_search", "jenkins repo", true, null,
        List.of(new AiWebToolCall.Source("Jenkins", "https://example.test/jenkins")), 0, false);
    private static final AiWebToolCall READ = new AiWebToolCall(
        AiWebToolCall.Kind.EXTRACT, "web_extract", "https://example.test/jenkins", true, null,
        List.of(new AiWebToolCall.Source("", "https://example.test/jenkins")), 12000, true);
    private static final AiWebToolCall FAILED = AiWebToolCall.failed(
        AiWebToolCall.Kind.SEARCH, "web_search", "fedora", "Tavily search failed with HTTP 401");

    @Test
    void singleSearchSummaryNamesQueryAndResultCount() {
        assertThat(TerminalAgentService.webToolSummary(List.of(SEARCH)))
            .isEqualTo("Web search \"jenkins repo\" (1 results)");
    }

    @Test
    void mixedCallsAreCountedAndDetailedInCallOrder() {
        List<AiWebToolCall> calls = List.of(SEARCH, READ, FAILED);

        assertThat(TerminalAgentService.webToolSummary(calls))
            .isEqualTo("Used the internet: 1 search, 1 page read, 1 failed");
        String detail = TerminalAgentService.webToolDetail(calls);
        assertThat(detail).contains("- Web search \"jenkins repo\" — 1 results\n    Jenkins — https://example.test/jenkins");
        assertThat(detail).contains("- Read page https://example.test/jenkins — 12000 characters (truncated)");
        assertThat(detail).contains("- Web search \"fedora\" — failed: Tavily search failed with HTTP 401");
        assertThat(detail.indexOf("jenkins repo")).isLessThan(detail.indexOf("Read page"));
    }
}
